package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.transfer.AbstractTableDataImporter;
import edu.zsc.ai.domain.service.db.transfer.ColumnValueConverter;
import edu.zsc.ai.domain.service.db.transfer.CreateTableDdlGenerator;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.domain.service.db.transfer.InferColumnTypeSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Imports rows from a CSV file. The first CSV line is a header row whose names
 * must match table columns; all inserts run in one transaction.
 */
@Component
public class CsvTableDataImporter extends AbstractTableDataImporter {

    /** Leading bytes inspected when auto-detecting the CSV charset. */
    private static final int ENCODE_DETECT_SAMPLE_SIZE = 8192;

    public CsvTableDataImporter(ConnectionService connectionService,
                                ImportPrepareSupport prepareSupport,
                                ColumnValueConverter valueConverter,
                                ImportBatchSupport batchSupport,
                                InferColumnTypeSupport inferSupport,
                                CreateTableDdlGenerator ddlGenerator) {
        super(connectionService, prepareSupport, valueConverter, batchSupport, inferSupport, ddlGenerator);
    }

    @Override
    public String fileType() {
        return "CSV";
    }

    @Override
    protected PeekResult peekHeadersAndSample(PushbackInputStream pushback, String encoding) throws IOException {
        Charset charset = resolveCharset(pushback, encoding);
        ByteArrayOutputStream consumed = new ByteArrayOutputStream();
        // Capture parser read-ahead as well as complete sampled records. The
        // original stream stays open so prepare() can replay this prefix and
        // then continue streaming the rest of a large file.
        InputStream recording = new FilterInputStream(pushback) {
            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value >= 0) consumed.write(value);
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, length);
                if (count > 0) consumed.write(bytes, offset, count);
                return count;
            }

            @Override
            public void close() { /* The import owns the original stream. */ }
        };
        BufferedReader reader = new BufferedReader(new InputStreamReader(recording, charset));
        reader.mark(1);
        if (reader.read() != '\uFEFF') reader.reset();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            List<String[]> samples = new ArrayList<>();
            Iterator<CSVRecord> records = parser.iterator();
            while (samples.size() < InferColumnTypeSupport.SAMPLE_ROW_LIMIT && records.hasNext()) {
                CSVRecord record = records.next();
                String[] row = new String[headers.size()];
                for (int i = 0; i < headers.size(); i++) {
                    row[i] = record.isSet(i) ? record.get(i) : null;
                }
                samples.add(row);
            }
            return new PeekResult(headers, samples, consumed.toByteArray());
        } catch (RuntimeException ex) {
            throw new IOException("Failed to peek CSV header: " + ex.getMessage(), ex);
        }
    }

    /** Resolves an explicit non-AUTO charset request, or {@code null} to auto-detect. */
    private Charset explicitCharset(String requested) {
        if (requested != null && !requested.isBlank() && !"AUTO".equalsIgnoreCase(requested.trim())) {
            return Charset.forName(requested.trim().toUpperCase(Locale.ROOT));
        }
        return null;
    }

    /**
     * Sniffs the charset from a byte sample: BOM wins (UTF-8/UTF-16LE/UTF-16BE),
     * otherwise strict UTF-8 validation decides between UTF-8 and GBK.
     */
    private Charset sniffCharset(byte[] sample, int length) {
        if (length >= 3 && (sample[0] & 0xFF) == 0xEF && (sample[1] & 0xFF) == 0xBB
                && (sample[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (length >= 2 && (sample[0] & 0xFF) == 0xFF && (sample[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (length >= 2 && (sample[0] & 0xFF) == 0xFE && (sample[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return looksLikeValidUtf8(sample, length) ? StandardCharsets.UTF_8 : Charset.forName("GBK");
    }

    @Override
    protected ImportSource prepare(InputStream inputStream, String encoding,
                                   ImportPrepareSupport.ResolvedTableColumns resolved,
                                   String tableName) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(inputStream, ENCODE_DETECT_SAMPLE_SIZE);
        Charset charset = resolveCharset(pushback, encoding);
        BufferedReader reader = new BufferedReader(new InputStreamReader(pushback, charset));
        reader.mark(1);
        int firstChar = reader.read();
        if (firstChar != '\uFEFF') {
            reader.reset();
        }

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        CSVParser parser = csvFormat.parse(reader);
        try {
            List<String> csvHeaders = parser.getHeaderNames();
            if (csvHeaders.isEmpty()) {
                throw new ImportFailureException(1, "CSV file is empty or missing a header row");
            }

            List<ColumnMetadata> targetColumns =
                    prepareSupport.matchColumns(csvHeaders, resolved, tableName, "CSV");

            int numColumns = targetColumns.size();
            Iterator<CSVRecord> iterator = parser.iterator();

            return new ImportSource() {
                @Override
                public List<ColumnMetadata> targetColumns() {
                    return targetColumns;
                }

                @Override
                public boolean hasNextRow() {
                    return iterator.hasNext();
                }

                @Override
                public ImportRow nextRow() {
                    CSVRecord record = iterator.next();
                    int fileRowNumber = (int) record.getRecordNumber() + 1;
                    String[] values = new String[numColumns];
                    for (int i = 0; i < numColumns; i++) {
                        values[i] = record.isSet(i) ? record.get(i) : null;
                    }
                    return new ImportRow(values, fileRowNumber);
                }

                @Override
                public void close() throws IOException {
                    parser.close();
                }
            };
        } catch (RuntimeException e) {
            parser.close();
            throw e;
        }
    }

    // --- Charset detection ---

    /**
     * Resolves the charset of the uploaded CSV. An explicit request encoding
     * (UTF-8/GBK) wins; "AUTO" (default) sniffs the byte order mark first and
     * falls back to strict UTF-8 validation on a leading sample: valid UTF-8
     * keeps UTF-8, otherwise the file is decoded as GBK.
     */
    private Charset resolveCharset(PushbackInputStream inputStream, String requested) throws IOException {
        Charset explicit = explicitCharset(requested);
        if (explicit != null) {
            return explicit;
        }
        byte[] sample = new byte[ENCODE_DETECT_SAMPLE_SIZE];
        int sampleLength = inputStream.readNBytes(sample, 0, sample.length);
        if (sampleLength <= 0) {
            return StandardCharsets.UTF_8;
        }
        inputStream.unread(sample, 0, sampleLength);
        return sniffCharset(sample, sampleLength);
    }

    /**
     * Strict UTF-8 structure check on a leading sample; a multi-byte sequence
     * cut off at the end of the sample is tolerated.
     */
    private boolean looksLikeValidUtf8(byte[] data, int length) {
        int i = 0;
        while (i < length) {
            int lead = data[i] & 0xFF;
            int sequenceLength;
            if (lead < 0x80) {
                sequenceLength = 1;
            } else if ((lead & 0xE0) == 0xC0) {
                sequenceLength = 2;
            } else if ((lead & 0xF0) == 0xE0) {
                sequenceLength = 3;
            } else if ((lead & 0xF8) == 0xF0) {
                sequenceLength = 4;
            } else {
                return false;
            }
            if (i + sequenceLength > length) {
                return true;
            }
            for (int j = 1; j < sequenceLength; j++) {
                if ((data[i + j] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += sequenceLength;
        }
        return true;
    }
}

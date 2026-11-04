package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
import edu.zsc.ai.domain.service.db.transfer.TableDataImporter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports rows from a SQL file. The file may contain INSERT statements and SET
 * session settings (e.g. "SET NAMES utf8mb4;"); comments are skipped and other
 * statements are rejected. All statements run in one transaction.
 */
@Component
@RequiredArgsConstructor
public class SqlTableDataImporter implements TableDataImporter {

    private static final Logger log = LoggerFactory.getLogger(SqlTableDataImporter.class);

    private final ConnectionService connectionService;
    private final ImportBatchSupport batchSupport;

    @Override
    public String fileType() {
        return "SQL";
    }

    @Override
    public ImportTableDataResponse importData(DbContext db, String tableName,
                                              InputStream inputStream, String encoding) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();

            String script = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (!script.isEmpty() && script.charAt(0) == '\uFEFF') {
                script = script.substring(1);
            }
            List<String> statements = splitSqlStatements(script);
            if (statements.isEmpty()) {
                return ImportBatchSupport.failureResponse(1, "SQL file contains no INSERT statements", 0);
            }

            int[] counters = new int[2]; // [0] = statements executed, [1] = rows affected by INSERTs
            try {
                batchSupport.runInTransaction(connection, () -> {
                    try (Statement statement = connection.createStatement()) {
                        for (String sql : statements) {
                            counters[0]++;
                            String lower = sql.toLowerCase(Locale.ROOT);
                            // Whitelist: INSERT loads data, SET changes session settings,
                            // CREATE TABLE lets SQL users bootstrap a new missing table.
                            // UPDATE/DELETE/DROP/ALTER and other statements stay rejected.
                            if (!isAllowedSqlStatement(lower)) {
                                throw new ImportFailureException(counters[0],
                                        "Only INSERT, SET and CREATE TABLE statements are supported, "
                                                + "but statement " + counters[0] + " starts with '"
                                                + firstSqlWord(sql) + "'");
                            }
                            try {
                                int affected = statement.executeUpdate(sql);
                                if (isInsertStatement(lower)) {
                                    counters[1] += affected;
                                }
                            } catch (SQLException ex) {
                                throw new ImportFailureException(counters[0],
                                        "Database rejected statement " + counters[0]
                                                + ": " + ex.getMessage());
                            }
                        }
                    }
                    return counters[0];
                });
            } catch (ImportFailureException failure) {
                log.warn("SQL import failed at statement {}: connectionId={}, tableName={}",
                        failure.fileRow(), db.connectionId(), tableName);
                return ImportBatchSupport.failureResponse(failure.fileRow(), failure.getMessage(),
                        counters[0]);
            }

            log.info("SQL import completed: connectionId={}, tableName={}, statements={}, affectedRows={}",
                    db.connectionId(), tableName, counters[0], counters[1]);
            return ImportTableDataResponse.builder()
                    .success(true)
                    .totalRows(counters[0])
                    .insertedRows(counters[1])
                    .build();
        } catch (SQLException | IOException ex) {
            throw new RuntimeException("Import SQL failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Splits a SQL script into statements on semicolons that sit outside quotes and
     * comments. Line comments (-- and #), block comments, string literals with
     * backslash and doubled-quote escapes, and quoted identifiers are handled.
     */
    private List<String> splitSqlStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(script.charAt(i + 2)))) {
                // Line comment: skip up to (not including) the line break.
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '#') {
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                // Block comment: replace with a space to keep tokens separated.
                current.append(' ');
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                // Quoted string or identifier: copy verbatim honoring escapes.
                current.append(c);
                i++;
                while (i < n) {
                    char ch = script.charAt(i);
                    current.append(ch);
                    i++;
                    if (ch == '\\' && i < n) {
                        current.append(script.charAt(i));
                        i++;
                        continue;
                    }
                    if (ch == c) {
                        if (i + 1 < n && script.charAt(i + 1) == c) {
                            current.append(c);
                            i++;
                            continue;
                        }
                        break;
                    }
                }
            } else if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private String firstSqlWord(String sql) {
        String trimmed = sql.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        String word = trimmed.substring(0, end);
        return word.length() > 20 ? word.substring(0, 20) + "..." : word;
    }

    /**
     * Whitelists a statement by its leading keyword: INSERT, SET, or CREATE
     * TABLE. Keywords are matched as whole words (a word boundary must follow),
     * so prefixes like "inserted", "setup" or "create tableXYZ" are rejected
     * instead of slipping through as a loose prefix match.
     */
    private static boolean isAllowedSqlStatement(String lower) {
        return isInsertStatement(lower)
                || startsWithKeyword(lower, "set")
                || startsWithKeyword(lower, "create table");
    }

    private static boolean isInsertStatement(String lower) {
        return startsWithKeyword(lower, "insert");
    }

    /** True when {@code lower} starts with {@code keyword} followed by a word boundary. */
    private static boolean startsWithKeyword(String lower, String keyword) {
        if (!lower.startsWith(keyword)) {
            return false;
        }
        int after = keyword.length();
        return after >= lower.length()
                || !(Character.isLetterOrDigit(lower.charAt(after)) || lower.charAt(after) == '_');
    }
}

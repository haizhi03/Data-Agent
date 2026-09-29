package edu.zsc.ai.domain.service.db.transfer;

/**
 * Shared constants for table data export and import.
 */
public final class TransferConstants {

    private TransferConstants() {
    }

    /** Rows fetched per database round trip during exports and import preparation. */
    public static final int EXPORT_PAGE_SIZE = 5000;

    /** Number of rows accumulated before one JDBC batch is sent during imports. */
    public static final int IMPORT_BATCH_SIZE = 1000;

    /** HSSF (.xls) supports 65536 rows in total; row 0 is the header row. */
    public static final int XLS_MAX_DATA_ROWS = 65535;
}

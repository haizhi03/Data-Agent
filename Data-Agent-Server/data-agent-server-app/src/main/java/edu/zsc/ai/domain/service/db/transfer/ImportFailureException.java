package edu.zsc.ai.domain.service.db.transfer;

/** Carries the 1-based file row number that caused an import failure. */
public class ImportFailureException extends RuntimeException {

    private final int fileRow;

    public ImportFailureException(int fileRow, String message) {
        super(message);
        this.fileRow = fileRow;
    }

    public int fileRow() {
        return fileRow;
    }
}

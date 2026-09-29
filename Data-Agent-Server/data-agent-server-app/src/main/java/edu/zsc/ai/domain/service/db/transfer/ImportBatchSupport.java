package edu.zsc.ai.domain.service.db.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * JDBC batch execution and transaction cleanup shared by every importer.
 */
@Component
public class ImportBatchSupport {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchSupport.class);

    public void executeImportBatch(PreparedStatement statement, int batchFirstRow) {
        try {
            statement.executeBatch();
        } catch (SQLException ex) {
            throw new ImportFailureException(batchFirstRow,
                    "Database rejected the batch: " + ex.getMessage());
        }
    }

    public void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            log.warn("Rollback after CSV import failure failed: {}", rollbackEx.getMessage());
        }
    }

    public void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ex) {
            log.warn("Failed to restore autoCommit: {}", ex.getMessage());
        }
    }
}

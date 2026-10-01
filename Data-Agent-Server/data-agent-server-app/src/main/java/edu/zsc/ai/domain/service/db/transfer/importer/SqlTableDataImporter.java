package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
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

/**
 * Imports rows from a SQL file. The file may contain INSERT statements and SET
 * session settings (e.g. "SET NAMES utf8mb4;"); comments are skipped and other
 * statements are rejected. All statements run in one transaction.
 */
@Component
@RequiredArgsConstructor
public class SqlTableDataImporter {

    private static final Logger log = LoggerFactory.getLogger(SqlTableDataImporter.class);

    private final ConnectionService connectionService;
    private final ImportBatchSupport batchSupport;

    public ImportTableDataResponse importData(DbContext db, String tableName,
                                              InputStream inputStream) {
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
                throw new BusinessException("SQL file contains no INSERT statements");
            }

            boolean originalAutoCommit = connection.getAutoCommit();
            int totalStatements = 0;
            int totalRows = 0;
            int insertedRows = 0;
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : statements) {
                        totalStatements++;
                        String lower = sql.toLowerCase();
                        // Whitelist: INSERT loads data; SET only changes session settings
                        // (e.g. "SET NAMES utf8mb4;" written by this app's own SQL export).
                        // UPDATE/DELETE/DROP and other statements stay rejected.
                        if (!lower.startsWith("insert") && !lower.startsWith("set")) {
                            throw new ImportFailureException(totalStatements,
                                    "Only INSERT and SET statements are supported, but statement "
                                            + totalStatements + " starts with '"
                                            + firstSqlWord(sql) + "'");
                        }
                        try {
                            int affected = statement.executeUpdate(sql);
                            if (lower.startsWith("insert")) {
                                totalRows++;
                                insertedRows += affected;
                            }
                        } catch (SQLException ex) {
                            throw new ImportFailureException(totalStatements,
                                    "Database rejected statement " + totalStatements
                                            + ": " + ex.getMessage());
                        }
                    }
                }
                connection.commit();
            } catch (ImportFailureException failure) {
                batchSupport.safeRollback(connection);
                log.warn("SQL import failed at statement {}: connectionId={}, tableName={}",
                        failure.fileRow(), db.connectionId(), tableName);
                return ImportTableDataResponse.builder()
                        .success(false)
                        .totalRows(totalStatements)
                        .insertedRows(0)
                        .failedAtRow(failure.fileRow())
                        .errorMessage(failure.getMessage())
                        .build();
            } catch (Exception unexpected) {
                // Any error outside the normal ImportFailureException path must roll
                // back too; otherwise restoring autoCommit in finally could commit
                // partial rows on some JDBC drivers.
                batchSupport.safeRollback(connection);
                if (unexpected instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException("Import failed; transaction rolled back: "
                        + unexpected.getMessage(), unexpected);
            } finally {
                batchSupport.restoreAutoCommit(connection, originalAutoCommit);
            }

            log.info("SQL import completed: connectionId={}, tableName={}, statements={}, affectedRows={}",
                    db.connectionId(), tableName, totalStatements, insertedRows);
            return ImportTableDataResponse.builder()
                    .success(true)
                    .totalRows(totalStatements)
                    .insertedRows(insertedRows)
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
}

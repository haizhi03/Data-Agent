package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.connection.ConnectionConfig;

import java.sql.*;

public interface ConnectionManager {

    Connection connect(ConnectionConfig config);

    boolean testConnection(ConnectionConfig config);

    void closeConnection(Connection connection);

    default DatabaseMetaData getMetaData(Connection connection) {
        try {
            return connection.getMetaData();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get database metadata: " + e.getMessage(), e);
        }
    }

    default String getDatabaseProductVersion(Connection connection) {
        try {
            DatabaseMetaData metaData = getMetaData(connection);
            return metaData.getDatabaseProductVersion();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get database product version: " + e.getMessage(), e);
        }
    }

    default String getDbmsInfo(Connection connection) {
        try {
            DatabaseMetaData metaData = getMetaData(connection);
            return String.format("%s (ver. %s)",
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get database info: " + e.getMessage(), e);
        }
    }

    default String getDriverInfo(Connection connection) {
        try {
            DatabaseMetaData metaData = getMetaData(connection);
            return String.format("%s (ver. %s, JDBC%d.%d)",
                    metaData.getDriverName(),
                    metaData.getDriverVersion(),
                    metaData.getJDBCMajorVersion(),
                    metaData.getJDBCMinorVersion());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get driver info: " + e.getMessage(), e);
        }
    }

    /**
     * Restore the session baseline before a connection is returned to the pool.
     * Default is a no-op (Hikari already resets autoCommit/readOnly/isolation/catalog
     * for the values it tracks). Plugins override to reset dialect-specific session
     * state such as the current schema, and to clear warnings.
     *
     * @param connection the physical connection being returned
     * @param catalog    catalog the pool slot is scoped to (may be null)
     * @param schema     schema the pool slot is scoped to (may be null)
     * @throws SQLException if the reset fails; callers should drop the physical connection
     */
    default void resetSessionState(Connection connection, String catalog, String schema) throws SQLException {
    }
}

package edu.zsc.ai.plugin.dm.fixture;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-run lifecycle manager for DM live test objects.
 *
 * <p>Each run gets a unique object name prefix {@code <namespace><timestamp>_<random>}
 * (default namespace {@code M1T_}, overridable per agent); only objects created through
 * {@link #track(String, String)} (or dropped via the stale sweeper restricted to the
 * same namespace) are ever touched.
 * Shared/business tables are never modified. {@link #close()} drops tracked
 * objects in reverse creation order; failures are logged with table name,
 * sqlState and errorCode (never credentials).
 */
public final class DmLiveTestRun implements AutoCloseable {

    /** Namespace reserved for M1 live-test objects. Business tables must never use it. */
    public static final String PREFIX_NAMESPACE = "M1T_";

    private final String namespace;
    private final String prefix;
    private final String schema;
    private final Connection connection;
    private final Deque<CreatedObject> created = new ArrayDeque<>();

    private record CreatedObject(String objectType, String name) {
    }

    private DmLiveTestRun(Connection connection, String schema, String namespace, String prefix) {
        this.connection = connection;
        this.schema = schema;
        this.namespace = namespace;
        this.prefix = prefix;
    }

    /**
     * Start a new run against {@code schema} with a fresh unique prefix in the
     * default {@link #PREFIX_NAMESPACE} namespace.
     */
    public static DmLiveTestRun start(Connection connection, String schema) {
        return start(connection, schema, PREFIX_NAMESPACE);
    }

    /**
     * Start a new run against {@code schema} with a fresh unique prefix inside a
     * caller-owned namespace (e.g. {@code "M1E_"}). The namespace scopes both the
     * generated object names and {@link #sweepStaleRunObjects()}; it must be a
     * dedicated test-only prefix, never shared with business objects.
     */
    public static DmLiveTestRun start(Connection connection, String schema, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)).toUpperCase(Locale.ROOT);
        return new DmLiveTestRun(connection, schema, namespace, namespace + timestamp + "_" + random);
    }

    public String prefix() {
        return prefix;
    }

    public String namespace() {
        return namespace;
    }

    public String schema() {
        return schema;
    }

    /**
     * Full object name for this run, e.g. {@code run.name("T")} → {@code M1T_20260927123000_1A2B_T}.
     */
    public String name(String suffix) {
        return prefix + "_" + suffix;
    }

    /**
     * Record a created object so {@link #close()} drops it. Call right after a
     * successful CREATE; pass the DM object type (TABLE, VIEW, PROCEDURE,
     * FUNCTION, TRIGGER, SEQUENCE).
     */
    public void track(String objectType, String name) {
        created.push(new CreatedObject(objectType.toUpperCase(Locale.ROOT), name));
    }

    /**
     * Execute SQL, swallowing errors with a diagnostic line (setup/cleanup helper).
     */
    public void execQuietly(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            System.out.println("[fixture] " + sql + " -> sqlState=" + e.getSQLState()
                    + " errorCode=" + e.getErrorCode() + " message=" + e.getMessage());
        }
    }

    /**
     * Drop objects created by earlier crashed runs in this schema. Only names
     * inside this run's namespace are eligible; anything else is ignored.
     */
    public void sweepStaleRunObjects() {
        List<CreatedObject> stale = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT OBJECT_TYPE, OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = '"
                             + schema.replace("'", "''") + "' AND OBJECT_NAME LIKE '"
                             + namespace.replace("_", "\\_") + "%' ESCAPE '\\'")) {
            while (rs.next()) {
                stale.add(new CreatedObject(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            System.out.println("[fixture] stale sweep query failed: sqlState=" + e.getSQLState()
                    + " errorCode=" + e.getErrorCode());
            return;
        }
        // triggers must go before their tables
        stale.sort((a, b) -> Boolean.compare(!"TRIGGER".equals(a.objectType), !"TRIGGER".equals(b.objectType)));
        for (CreatedObject obj : stale) {
            execQuietly(dropStatement(obj));
        }
    }

    /**
     * Drop all objects created by this run, in reverse creation order.
     */
    @Override
    public void close() {
        while (!created.isEmpty()) {
            execQuietly(dropStatement(created.pop()));
        }
    }

    private String dropStatement(CreatedObject obj) {
        String quoted = "\"" + obj.name.replace("\"", "\"\"") + "\"";
        return switch (obj.objectType) {
            case "TABLE" -> "DROP TABLE \"" + schema + "\"." + quoted;
            case "VIEW" -> "DROP VIEW \"" + schema + "\"." + quoted;
            case "PROCEDURE" -> "DROP PROCEDURE \"" + schema + "\"." + quoted;
            case "FUNCTION" -> "DROP FUNCTION \"" + schema + "\"." + quoted;
            case "TRIGGER" -> "DROP TRIGGER \"" + schema + "\"." + quoted;
            case "SEQUENCE" -> "DROP SEQUENCE \"" + schema + "\"." + quoted;
            default -> throw new IllegalArgumentException("unsupported tracked object type: " + obj.objectType);
        };
    }
}

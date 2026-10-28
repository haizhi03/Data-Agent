package edu.zsc.ai.local;

import java.time.Instant;
import java.util.Map;

/** Durable public configuration; never place plaintext credentials in this record. */
record LocalConnectionRecord(long id, long version, String name, String dbType, String host, int port,
        String database, String username, String driverJarPath, int timeout, Map<String, String> properties,
        String credentialRef, Instant createdAt, Instant updatedAt) {
    LocalConnectionRecord { properties = properties == null ? null : Map.copyOf(properties); }

    PublicView publicView() {
        return new PublicView(id, version, name, dbType, host, port, database, username, driverJarPath, timeout,
                properties, credentialRef != null, createdAt, updatedAt);
    }

    record PublicView(long id, long version, String name, String dbType, String host, int port, String database,
            String username, String driverJarPath, int timeout, Map<String, String> properties,
            boolean credentialConfigured, Instant createdAt, Instant updatedAt) { }
}

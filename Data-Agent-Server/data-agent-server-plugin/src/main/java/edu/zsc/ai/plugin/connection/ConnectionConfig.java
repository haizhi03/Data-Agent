package edu.zsc.ai.plugin.connection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionConfig {
    
    private String host;
    
    private Integer port;
    
    private String database;

    private String schema;

    private String username;
    
    private String password;
    
    private Map<String, String> properties;
    
    private String driverJarPath;
    
    private Integer timeout = 30;

    /**
     * Optional socket-level network timeout (milliseconds) applied via
     * {@code Connection.setNetworkTimeout} after connect; null leaves the driver default.
     * Complements query timeouts by failing blocked socket reads on a hung server (M1-09).
     */
    private Integer networkTimeoutMs;
    
    public void addProperty(String key, String value) {
        if (properties == null) {
            properties = new HashMap<>();
        }
        properties.put(key, value);
    }
}


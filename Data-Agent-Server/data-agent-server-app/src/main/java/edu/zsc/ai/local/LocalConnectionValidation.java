package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.dto.request.db.ConnectionCreateRequest;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import java.nio.file.*;
import java.util.*;

final class LocalConnectionValidation {
    // Unknown vendor options are private by default. Only these known non-secret options enter the public JSON.
    private static final Set<String> PUBLIC_PROPERTIES = Set.of("usessl", "sslmode", "useunicode", "characterencoding",
            "servertimezone", "connecttimeout", "sockettimeout", "allowpublickeyretrieval", "zerodatetimebehavior",
            "remarks", "remarksreporting", "useinformationschema");

    static boolean publicProperty(String key) { return key != null && PUBLIC_PROPERTIES.contains(key.toLowerCase(Locale.ROOT)); }

    static void validate(ConnectionCreateRequest input, boolean checkDriver) {
        if (input == null || blank(input.getName()) || input.getName().length() > 100 || blank(input.getDbType())
                || blank(input.getHost()) || input.getHost().length() > 255 || input.getHost().matches(".*[\\s/@?;=].*")
                || input.getPort() == null || input.getPort() < 1 || input.getPort() > 65535
                || input.getTimeout() == null || input.getTimeout() < 1 || input.getTimeout() > 300
                || blank(input.getDriverJarPath()) || !validText(input.getName()) || !validText(input.getHost())
                || !validText(input.getUsername()) || !validText(input.getDatabase())) throw LocalRequestException.invalid();
        try {
            if (DefaultPluginManager.getInstance().getConnectionManagerByDbType(input.getDbType()).isEmpty()) throw LocalRequestException.invalid();
            Path driver = Path.of(input.getDriverJarPath());
            if (!driver.isAbsolute() || (checkDriver && !Files.isRegularFile(driver))) throw LocalRequestException.invalid();
        } catch (IllegalArgumentException e) { throw LocalRequestException.invalid(); }
        validateProperties(input.getProperties());
        String password = input.getPassword();
        if (password != null && (password.length() > 16384 || password.matches("[＊*•●]+") || !validText(password))) {
            throw LocalRequestException.invalid();
        }
    }

    static void validateProperties(Map<String, String> properties) {
        if (properties == null || properties.size() > 256) throw LocalRequestException.invalid();
        properties.forEach((key, value) -> {
            if (blank(key) || key.length() > 128 || !validText(key) || value == null || value.length() > 16384
                    || !validText(value)) throw LocalRequestException.invalid();
        });
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean validText(String value) { return value == null || value.chars().noneMatch(c -> c < 32 || c == 127); }
}

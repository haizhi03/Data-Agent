package edu.zsc.ai.domain.service.db.compare.diff;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 比较选项，由接口入参下发，比较器不读全局配置。 */
public record CompareOptions(
        Set<String> includeTables,
        Set<String> excludeTables,
        boolean ignoreColumnOrder,
        boolean ignoreComments
) {

    public static CompareOptions defaults() {
        return new CompareOptions(Set.of(), Set.of(), false, false);
    }

    /** includeTables 为空表示全部；excludeTables 优先于 includeTables。 */
    public boolean includes(String tableName) {
        String normalized = tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
        if (!excludeTables.isEmpty() && excludeTables.contains(normalized)) {
            return false;
        }
        return includeTables.isEmpty() || includeTables.contains(normalized);
    }

    public static Set<String> normalizeNames(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}

package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 比较器注册表：Spring 收集全部策略，按对象类型分发，调用方不写 switch。 */
@Component
public class SchemaComparatorRegistry {

    private final Map<DiffObjectType, SchemaObjectComparator> comparators;

    public SchemaComparatorRegistry(List<SchemaObjectComparator> comparatorList) {
        Map<DiffObjectType, SchemaObjectComparator> map = new EnumMap<>(DiffObjectType.class);
        for (SchemaObjectComparator comparator : comparatorList) {
            map.put(comparator.objectType(), comparator);
        }
        this.comparators = map;
    }

    /** 按枚举声明顺序返回全部比较器，保证结果顺序稳定。 */
    public List<SchemaObjectComparator> ordered() {
        return List.copyOf(comparators.values());
    }
}

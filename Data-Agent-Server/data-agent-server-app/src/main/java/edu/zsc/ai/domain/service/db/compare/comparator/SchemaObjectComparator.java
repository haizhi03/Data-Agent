package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.diff.ObjectDiff;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;

import java.util.List;

/** 一类对象比较策略；新增对象类型只需实现本接口，注册表自动收集。 */
public interface SchemaObjectComparator {

    DiffObjectType objectType();

    List<ObjectDiff> compare(TableSnapshot source, TableSnapshot target, CompareContext context);
}

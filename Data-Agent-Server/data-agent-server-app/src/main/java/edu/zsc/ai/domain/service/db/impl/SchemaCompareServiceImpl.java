package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.SchemaCompareRequest;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SchemaCompareService;
import edu.zsc.ai.domain.service.db.compare.comparator.CompareContext;
import edu.zsc.ai.domain.service.db.compare.comparator.SchemaComparatorRegistry;
import edu.zsc.ai.domain.service.db.compare.comparator.SchemaObjectComparator;
import edu.zsc.ai.domain.service.db.compare.dialect.DialectRules;
import edu.zsc.ai.domain.service.db.compare.dialect.DialectRulesRegistry;
import edu.zsc.ai.domain.service.db.compare.diff.CompareOptions;
import edu.zsc.ai.domain.service.db.compare.diff.CompareSummary;
import edu.zsc.ai.domain.service.db.compare.diff.DiffStatus;
import edu.zsc.ai.domain.service.db.compare.diff.ObjectDiff;
import edu.zsc.ai.domain.service.db.compare.diff.SchemaCompareResult;
import edu.zsc.ai.domain.service.db.compare.diff.TableDiff;
import edu.zsc.ai.domain.service.db.compare.model.SchemaSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import edu.zsc.ai.domain.service.db.compare.snapshot.SchemaSnapshotReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** 结构比较门面：读快照、配对表、按注册表分发比较器、汇总状态。 */
@Service
@RequiredArgsConstructor
public class SchemaCompareServiceImpl implements SchemaCompareService {

    private final SchemaSnapshotReader snapshotReader;
    private final SchemaComparatorRegistry comparatorRegistry;
    private final DialectRulesRegistry dialectRulesRegistry;
    private final ConnectionService connectionService;

    @Override
    public SchemaCompareResult compare(SchemaCompareRequest request) {
        Long sourceConnectionId = request.getSourceConnectionId() != null
                ? request.getSourceConnectionId() : request.getConnectionId();
        Long targetConnectionId = request.getTargetConnectionId() != null
                ? request.getTargetConnectionId() : request.getConnectionId();
        if (sourceConnectionId == null || targetConnectionId == null) {
            throw new BusinessException("请选择源连接与目标连接");
        }

        DbContext source = toContext(sourceConnectionId, request.getSourceCatalog(), request.getSourceSchema());
        DbContext target = toContext(targetConnectionId, request.getTargetCatalog(), request.getTargetSchema());
        if (source.equals(target)) {
            throw new BusinessException("源与目标不能是同一个库或模式");
        }

        connectionService.openConnection(source);
        connectionService.openConnection(target);
        // 跨连接比较时两侧可能是不同类型（如 MySQL vs 达梦），此时退回通用归一化规则。
        String sourceDbType = ActiveConnectionRegistry.getOwnedConnection(source).dbType();
        String targetDbType = ActiveConnectionRegistry.getOwnedConnection(target).dbType();
        DialectRules rules = dialectRulesRegistry.resolve(
                Objects.equals(sourceDbType, targetDbType) ? sourceDbType : "generic");
        CompareContext context = new CompareContext(rules, request.isIgnoreColumnOrder(), request.isIgnoreComments());
        CompareOptions options = new CompareOptions(
                CompareOptions.normalizeNames(toSet(request.getIncludeTables())),
                CompareOptions.normalizeNames(toSet(request.getExcludeTables())),
                request.isIgnoreColumnOrder(),
                request.isIgnoreComments()
        );

        SchemaSnapshot sourceSnapshot = snapshotReader.read(source, options);
        SchemaSnapshot targetSnapshot = snapshotReader.read(target, options);

        Map<String, TableSnapshot> sourceTables = indexByName(sourceSnapshot.tables(), rules);
        Map<String, TableSnapshot> targetTables = indexByName(targetSnapshot.tables(), rules);

        Set<String> tableKeys = new LinkedHashSet<>(sourceTables.keySet());
        tableKeys.addAll(targetTables.keySet());

        List<TableDiff> tableDiffs = new ArrayList<>();
        for (String key : tableKeys) {
            TableSnapshot sourceTable = sourceTables.get(key);
            TableSnapshot targetTable = targetTables.get(key);
            if (targetTable == null) {
                tableDiffs.add(new TableDiff(sourceTable.name(), DiffStatus.ONLY_SOURCE, List.of(),
                        sourceTable.auxiliaryError()));
            } else if (sourceTable == null) {
                tableDiffs.add(new TableDiff(targetTable.name(), DiffStatus.ONLY_TARGET, List.of(),
                        targetTable.auxiliaryError()));
            } else {
                tableDiffs.add(compareTable(sourceTable, targetTable, context));
            }
        }

        return new SchemaCompareResult(summarize(tableDiffs), tableDiffs);
    }

    private TableDiff compareTable(TableSnapshot source, TableSnapshot target, CompareContext context) {
        if (!source.columnsReadable() || !target.columnsReadable()) {
            String error = Stream.of(
                            source.columnError() == null ? null : "source " + source.columnError(),
                            target.columnError() == null ? null : "target " + target.columnError())
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("元数据读取失败");
            return new TableDiff(source.name(), DiffStatus.READ_ERROR, List.of(), error);
        }

        List<ObjectDiff> objects = new ArrayList<>();
        for (SchemaObjectComparator comparator : comparatorRegistry.ordered()) {
            objects.addAll(comparator.compare(source, target, context));
        }

        boolean different = objects.stream().anyMatch(object -> object.status() != DiffStatus.SAME);
        String message = Stream.of(source.auxiliaryError(), target.auxiliaryError())
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "; " + right)
                .orElse(null);
        return new TableDiff(source.name(), different ? DiffStatus.DIFFERENT : DiffStatus.SAME, objects, message);
    }

    private static CompareSummary summarize(List<TableDiff> tables) {
        int onlySource = 0;
        int onlyTarget = 0;
        int different = 0;
        int same = 0;
        int readError = 0;
        for (TableDiff table : tables) {
            switch (table.status()) {
                case ONLY_SOURCE -> onlySource++;
                case ONLY_TARGET -> onlyTarget++;
                case DIFFERENT -> different++;
                case SAME -> same++;
                case READ_ERROR -> readError++;
            }
        }
        return new CompareSummary(onlySource, onlyTarget, different, same, readError);
    }

    private static Map<String, TableSnapshot> indexByName(List<TableSnapshot> tables, DialectRules rules) {
        Map<String, TableSnapshot> map = new LinkedHashMap<>();
        for (TableSnapshot table : tables) {
            map.putIfAbsent(rules.normalizeIdentifier(table.name()), table);
        }
        return map;
    }

    private static DbContext toContext(Long connectionId, String catalog, String schema) {
        return new DbContext(connectionId, blankToNull(catalog), blankToNull(schema));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Set<String> toSet(List<String> values) {
        return values == null ? new HashSet<>() : new HashSet<>(values);
    }
}

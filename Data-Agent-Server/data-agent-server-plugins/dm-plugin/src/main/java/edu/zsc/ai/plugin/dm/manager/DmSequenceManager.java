package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.SequenceManager;
import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.SequenceMetadata;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** DM sequence discovery and DDL, without reading NEXTVAL or changing state. */
public final class DmSequenceManager implements SequenceManager {

    private final DmObjectQuerySupport support;

    public DmSequenceManager(DmObjectQuerySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<SequenceMetadata> getSequences(Connection connection, String catalog, String schema) {
        if (connection == null) {
            return List.of();
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return List.of();
        }
        List<SequenceMetadata> result = new ArrayList<>();
        for (Map<String, Object> row : support.query(connection, DmObjectSql.SQL_LIST_SEQUENCES, effectiveSchema)) {
            result.add(new SequenceMetadata(
                    DmObjectQuerySupport.stringValue(row.get("SEQUENCE_NAME")),
                    decimal(row.get("MIN_VALUE")), decimal(row.get("MAX_VALUE")),
                    decimal(row.get("INCREMENT_BY")),
                    "Y".equalsIgnoreCase(DmObjectQuerySupport.stringValue(row.get("CYCLE_FLAG"))),
                    integer(row.get("CACHE_SIZE"))));
        }
        return result;
    }

    @Override
    public String getSequenceDdl(Connection connection, String catalog, String schema, String sequenceName) {
        return support.getObjectDdl(connection, DmObjectSql.OBJECT_TYPE_SEQUENCE,
                support.resolveSchema(connection, schema), sequenceName);
    }

    @Override
    public void deleteSequence(Connection connection, String catalog, String schema, String sequenceName) {
        if (connection == null || StringUtils.isBlank(sequenceName)) {
            throw new IllegalArgumentException("Connection and sequence name must not be null or empty");
        }
        String fullName = DmObjectQuerySupport.buildFullIdentifier(
                support.resolveSchema(connection, schema), sequenceName);
        support.executeDdl(connection, String.format(DmObjectSql.SQL_DROP_SEQUENCE, fullName),
                "Failed to delete sequence");
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}

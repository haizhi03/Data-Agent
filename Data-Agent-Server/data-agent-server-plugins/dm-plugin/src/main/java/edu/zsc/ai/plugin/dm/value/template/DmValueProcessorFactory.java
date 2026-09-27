package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.dm.value.DmDataTypeEnum;
import edu.zsc.ai.plugin.value.DefaultValueProcessor;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory for creating type-specific value processors for DM (DaMeng).
 *
 * <p>Uses enum-based type mapping and shared processor instances, mirroring the
 * MySQL factory. Only types that genuinely need DM-specific handling are
 * registered; everything else falls back to {@link DefaultValueProcessor}.
 *
 * @author hhz
 */
public class DmValueProcessorFactory {

    /**
     * Shared processor instances (one instance per processor type)
     */
    private static final DmDecimalProcessor DECIMAL_PROCESSOR = new DmDecimalProcessor();
    private static final DmDateTimeProcessor DATETIME_PROCESSOR = new DmDateTimeProcessor();
    private static final DmTimestampProcessor TIMESTAMP_PROCESSOR = new DmTimestampProcessor();
    private static final DmTimestampWithTimeZoneProcessor TIMESTAMP_TZ_PROCESSOR = new DmTimestampWithTimeZoneProcessor();
    private static final DmBlobProcessor BLOB_PROCESSOR = new DmBlobProcessor();
    private static final DmClobProcessor CLOB_PROCESSOR = new DmClobProcessor();
    private static final DmBitProcessor BIT_PROCESSOR = new DmBitProcessor();
    private static final DmBinaryProcessor BINARY_PROCESSOR = new DmBinaryProcessor();

    /**
     * Enum-based processor cache for fast lookup
     */
    private static final Map<DmDataTypeEnum, DefaultValueProcessor> PROCESSOR_MAP = new EnumMap<>(DmDataTypeEnum.class);

    static {
        // NUMBER/DECIMAL family: precision-safe BigDecimal handling
        PROCESSOR_MAP.put(DmDataTypeEnum.NUMBER, DECIMAL_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.NUMERIC, DECIMAL_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.DECIMAL, DECIMAL_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.DEC, DECIMAL_PROCESSOR);

        // Date/time family. DM DATE is date-only in native mode (the server
        // truncates any time component at write time, live-verified); DM DATETIME
        // carries time and is handled exactly like TIMESTAMP.
        PROCESSOR_MAP.put(DmDataTypeEnum.DATE, DATETIME_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.DATETIME, TIMESTAMP_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.TIMESTAMP, TIMESTAMP_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.TIMESTAMP_WITH_TIME_ZONE, TIMESTAMP_TZ_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.TIMESTAMP_WITH_LOCAL_TIME_ZONE, TIMESTAMP_TZ_PROCESSOR);

        // Large object family (stream-safe extraction)
        PROCESSOR_MAP.put(DmDataTypeEnum.BLOB, BLOB_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.BFILE, BLOB_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.IMAGE, BLOB_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.CLOB, CLOB_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.TEXT, CLOB_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.LONGVARCHAR, CLOB_PROCESSOR);

        // Boolean/bit
        PROCESSOR_MAP.put(DmDataTypeEnum.BIT, BIT_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.BOOLEAN, BIT_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.BOOL, BIT_PROCESSOR);

        // Binary family
        PROCESSOR_MAP.put(DmDataTypeEnum.BINARY, BINARY_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.VARBINARY, BINARY_PROCESSOR);
        PROCESSOR_MAP.put(DmDataTypeEnum.LONGVARBINARY, BINARY_PROCESSOR);
    }

    private DmValueProcessorFactory() {
    }

    /**
     * Get a type-specific processor for the given DM type name.
     *
     * @param columnTypeName the DM column type name (e.g. "NUMBER", "VARCHAR2",
     *                       "TIMESTAMP WITH TIME ZONE")
     * @return the processor, or null if no specific processor is registered
     */
    public static DefaultValueProcessor getValueProcessor(String columnTypeName) {
        if (columnTypeName == null || columnTypeName.isEmpty()) {
            return null;
        }

        DmDataTypeEnum dataType = DmDataTypeEnum.fromTypeName(columnTypeName);
        return dataType != null ? PROCESSOR_MAP.get(dataType) : null;
    }
}

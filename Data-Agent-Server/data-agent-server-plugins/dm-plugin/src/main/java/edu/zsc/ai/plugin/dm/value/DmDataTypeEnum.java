package edu.zsc.ai.plugin.dm.value;

import java.sql.Types;

/**
 * DM (DaMeng) data type enumeration.
 *
 * <p>Type names follow what the DM JDBC driver reports via
 * {@link java.sql.ResultSetMetaData#getColumnTypeName(int)}. Names containing
 * spaces (e.g. "TIMESTAMP WITH TIME ZONE") are normalized by
 * {@link #fromTypeName(String)}: upper-cased, trimmed, spaces replaced with '_'.
 *
 * @author hhz
 */
public enum DmDataTypeEnum {
    // Character types
    CHAR(Types.CHAR),
    CHARACTER(Types.CHAR),
    VARCHAR(Types.VARCHAR),
    VARCHAR2(Types.VARCHAR),
    TEXT(Types.LONGVARCHAR),
    LONGVARCHAR(Types.LONGVARCHAR),
    CLOB(Types.CLOB),

    // Numeric types
    NUMBER(Types.DECIMAL),
    NUMERIC(Types.DECIMAL),
    DECIMAL(Types.DECIMAL),
    DEC(Types.DECIMAL),
    INTEGER(Types.INTEGER),
    INT(Types.INTEGER),
    BIGINT(Types.BIGINT),
    TINYINT(Types.SMALLINT),
    SMALLINT(Types.SMALLINT),
    FLOAT(Types.FLOAT),
    DOUBLE(Types.DOUBLE),
    DOUBLE_PRECISION(Types.DOUBLE),
    REAL(Types.REAL),

    // Date/time types
    DATE(Types.TIMESTAMP),          // date-only under COMPATIBLE_MODE=0 (server truncates time at write)
    DATETIME(Types.TIMESTAMP),
    TIMESTAMP(Types.TIMESTAMP),
    TIME(Types.TIME),
    TIMESTAMP_WITH_TIME_ZONE(Types.TIMESTAMP_WITH_TIMEZONE),
    TIMESTAMP_WITH_LOCAL_TIME_ZONE(Types.TIMESTAMP_WITH_TIMEZONE),

    // Binary types
    BINARY(Types.BINARY),
    VARBINARY(Types.VARBINARY),
    LONGVARBINARY(Types.LONGVARBINARY),
    IMAGE(Types.LONGVARBINARY),
    BLOB(Types.BLOB),
    BFILE(Types.BLOB),

    // Boolean/bit types
    BIT(Types.BIT),
    BOOLEAN(Types.BIT),
    BOOL(Types.BIT);

    private final int sqlType;

    DmDataTypeEnum(int sqlType) {
        this.sqlType = sqlType;
    }

    public int getSqlType() {
        return sqlType;
    }

    public static DmDataTypeEnum fromTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }
        // Strip precision/scale suffixes reported by the driver, e.g. "DECIMAL(10,2)"
        // or "TIMESTAMP(6) WITH TIME ZONE", before matching enum constants.
        String normalized = typeName.toUpperCase().trim()
                .replaceAll("\\([^)]*\\)", "")
                .trim()
                .replace(' ', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int toSqlType(String dmDataType) {
        if (dmDataType == null) {
            return Types.OTHER;
        }
        DmDataTypeEnum type = fromTypeName(dmDataType);
        return type == null ? Types.OTHER : type.getSqlType();
    }
}

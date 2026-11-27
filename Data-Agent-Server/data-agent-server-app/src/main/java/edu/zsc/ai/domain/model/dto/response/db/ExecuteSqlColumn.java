package edu.zsc.ai.domain.model.dto.response.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteSqlColumn {
    private String name;
    private String label;
    private String typeName;
    private Integer jdbcType;
    private Integer precision;
    private Integer scale;
    private Boolean nullable;
    private String tableName;
    /** Catalog (MySQL database) the column originates from. */
    private String catalogName;
    /** Schema (DM owner) the column originates from. */
    private String schemaName;
    /** True when the column is part of the source table's primary key. */
    private Boolean primaryKey;
    /** 1-based position of the column inside the primary key. */
    private Integer primaryKeyOrdinal;
    /** Column comment reported by the database, when available. */
    private String comment;
}

package edu.zsc.ai.domain.model.dto.response.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend-resolved write target for one result set: whether the rows can be
 * edited in place and which table / primary key a row modification maps to.
 * The client must not re-derive these rules on its own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteSqlWriteTarget {

    /** True when every loaded row can be matched to at most one table row. */
    private boolean editable;

    /** Source object kind, e.g. TABLE or VIEW; null when it could not be resolved. */
    private String objectType;

    private String catalog;

    private String schema;

    private String table;

    /** Primary key columns, ordered by their position inside the key. */
    private List<String> primaryKeyColumns;

    /**
     * Columns the client must use to locate a row for UPDATE / DELETE, resolved
     * here so it never picks a matching rule on its own: the primary key when the
     * object has one, every selected column otherwise. Null when not editable.
     */
    private List<String> matchColumns;

    /** Why the result set is read-only; null when editable. */
    private String reason;
}

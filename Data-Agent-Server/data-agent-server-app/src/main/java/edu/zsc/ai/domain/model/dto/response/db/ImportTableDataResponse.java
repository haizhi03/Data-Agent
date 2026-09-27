package edu.zsc.ai.domain.model.dto.response.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of importing data from an uploaded file into one table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTableDataResponse {

    /** True only when the whole import succeeded and the transaction committed. */
    private boolean success;

    /** Number of data rows found in the source file (excluding the header row). */
    private int totalRows;

    /** Number of rows inserted into the table. */
    private int insertedRows;

    /** One-based row number in the file of the first failed row, null when all succeeded. */
    private Integer failedAtRow;

    private String errorMessage;
}

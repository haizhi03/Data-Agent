package edu.zsc.ai.domain.model.dto.request.db;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 结构比较入参；源与目标可以是同一连接下的两个库/模式，也可以是两个不同的连接。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaCompareRequest {

    /** 兼容旧调用：未单独指定源/目标连接时，两侧都用这个连接。 */
    private Long connectionId;

    /** 源端连接；为空时回退到 connectionId。 */
    private Long sourceConnectionId;

    /** 目标端连接；为空时回退到 connectionId。 */
    private Long targetConnectionId;

    private String sourceCatalog;

    private String sourceSchema;

    private String targetCatalog;

    private String targetSchema;

    /** 为空表示比较全部表。 */
    @Builder.Default
    private List<String> includeTables = new ArrayList<>();

    @Builder.Default
    private List<String> excludeTables = new ArrayList<>();

    /** 忽略列顺序差异。 */
    private boolean ignoreColumnOrder;

    /** 忽略表注释与列注释差异。 */
    private boolean ignoreComments;
}

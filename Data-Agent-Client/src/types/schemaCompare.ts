/** 结构比较的五种状态，与后端 DiffStatus 一致。 */
export type SchemaDiffStatus = 'ONLY_SOURCE' | 'ONLY_TARGET' | 'DIFFERENT' | 'SAME' | 'READ_ERROR';

/** 参与比较的对象类型，与后端 DiffObjectType 一致。 */
export type SchemaDiffObjectType =
  | 'TABLE'
  | 'COLUMN'
  | 'PRIMARY_KEY'
  | 'INDEX'
  | 'FOREIGN_KEY'
  | 'UNIQUE'
  | 'CHECK'
  | 'COMMENT';

export interface SchemaAttributeDiff {
  name: string;
  sourceValue: string | null;
  targetValue: string | null;
}

export interface SchemaObjectDiff {
  objectType: SchemaDiffObjectType;
  objectKey: string;
  status: SchemaDiffStatus;
  attributes: SchemaAttributeDiff[];
}

export interface SchemaTableDiff {
  tableName: string;
  status: SchemaDiffStatus;
  objects: SchemaObjectDiff[];
  message: string | null;
}

export interface SchemaCompareSummary {
  onlySource: number;
  onlyTarget: number;
  different: number;
  same: number;
  readError: number;
}

export interface SchemaCompareResult {
  summary: SchemaCompareSummary;
  tables: SchemaTableDiff[];
}

export interface SchemaCompareParams {
  sourceConnectionId: number;
  targetConnectionId: number;
  sourceCatalog?: string | null;
  sourceSchema?: string | null;
  targetCatalog?: string | null;
  targetSchema?: string | null;
  includeTables?: string[];
  excludeTables?: string[];
  ignoreColumnOrder?: boolean;
  ignoreComments?: boolean;
}

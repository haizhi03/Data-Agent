/** Database exploration endpoints */
export const ApiPaths = {
  COLUMNS: '/columns',
  DATABASES: '/databases',
  TABLES: '/tables',
  TABLES_ER_DIAGRAM: '/tables/er-diagram',
  TABLES_DDL: '/tables/ddl',
  TABLES_RENAME: '/tables/rename',
  TABLE_DATA: '/tables/data',
  TABLE_ROWS: '/tables/rows',
  TABLE_ROWS_BATCH: '/tables/rows/batch',
  TABLE_IMPORT: '/tables/import',
  TABLE_EXPORT: '/tables/export',
  VIEWS: '/views',
  VIEWS_DDL: '/views/ddl',
  VIEW_DATA: '/views/data',
  FUNCTIONS: '/functions',
  FUNCTIONS_DDL: '/functions/ddl',
  PROCEDURES: '/procedures',
  PROCEDURES_DDL: '/procedures/ddl',
  TRIGGERS: '/triggers',
  TRIGGERS_DDL: '/triggers/ddl',
  SEQUENCES: '/sequences',
  SEQUENCES_DDL: '/sequences/ddl',
  CONSTRAINTS: '/constraints',
  CONSTRAINTS_DDL: '/constraints/ddl',
  INDEXES: '/indexes',
  PRIMARY_KEYS: '/primary-keys',
} as const;

/** Connection management endpoints */
export const ConnectionPaths = {
  LIST: '/connections',
  CREATE: '/connections',
  EDIT: '/connections/:id',
  DELETE: '/connections/:id',
  TEST: '/connections/test',
} as const;

/** SQL execution endpoints */
export const SqlPaths = {
  EXECUTE: '/db/sql/execute',
  EXECUTE_SCRIPT: '/db/sql/execute-script',
  CANCEL: '/db/sql/cancel',
  ANALYZE: '/db/sql/analyze',
  COMPLETE: '/db/sql/complete',
  COMPLETION_DICTIONARY: '/db/sql/completion-dictionary',
  COMPLETION_INVALIDATE: '/db/sql/completion-invalidate',
  HOVER: '/db/sql/hover',
  SIGNATURE: '/db/sql/signature',
} as const;

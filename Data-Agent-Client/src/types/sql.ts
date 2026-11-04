/**
 * SQL execution params provided by frontend callers.
 * sqlExecutionService maps databaseName/schemaName to the backend's catalog/schema fields.
 */
export interface ExecuteSqlParams {
  connectionId: number;
  databaseName?: string | null;
  schemaName?: string | null;
  sql: string;
  timeoutMs?: number;
  maxRows?: number;
  executionId?: string;
}

export type SqlBatchMode = 'ATOMIC' | 'STEPWISE';

export interface ExecuteSqlScriptParams extends ExecuteSqlParams {
  mode: SqlBatchMode;
}

export type SqlStatementState = 'NOT_EXECUTED' | 'EXECUTED' | 'COMMITTED' | 'ROLLED_BACK'
  | 'FAILED' | 'TIMED_OUT' | 'CANCELLED' | 'UNKNOWN';
export type SqlTransactionOutcome = 'NONE' | 'COMMITTED' | 'ROLLED_BACK' | 'UNKNOWN';
export type SqlBatchState = 'SUCCESS' | 'FAILED' | 'ROLLED_BACK' | 'PARTIAL' | 'UNKNOWN';

export interface SqlSyntaxError {
  line: number;
  column: number;
  message: string;
}

export interface SqlStatementAnalysis {
  sql: string;
  type: string;
  objectType: string | null;
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
  startOffset: number;
  endOffset: number;
  tables: string[];
  columns: string[];
  aliases: Record<string, string>;
  readOnly: boolean;
  executableSql: string;
}

export interface SqlScriptAnalysis {
  statements: SqlStatementAnalysis[];
  errors: SqlSyntaxError[];
}

/** SQL completion, returned from backend /api/db/sql/complete */
export type SqlCompletionKind = 'KEYWORD' | 'FUNCTION' | 'PROCEDURE' | 'SCHEMA' | 'TABLE' | 'VIEW' | 'COLUMN' | 'ALIAS' | 'SNIPPET' | 'ALL_COLUMNS' | 'JOIN' | 'VARIABLE' | 'PACKAGE' | 'SYNONYM';

export interface SqlCompletionItem {
  label: string;
  kind: SqlCompletionKind;
  insertText: string;
  detail?: string | null;
  replaceStart: number;
  replaceEnd: number;
  source?: string | null;
  filterText: string;
  rank: number;
  documentation?: string | null;
  snippet: boolean;
}

export interface SqlCompletionResponse {
  items: SqlCompletionItem[];
  incomplete: boolean;
  environment?: { databaseVersion: string | null; settings: Record<string, string> } | null;
}

export interface SqlCompletionParams {
  connectionId: number;
  databaseName?: string | null;
  schemaName?: string | null;
  sql: string;
  cursorOffset: number;
}

/** Hover card, returned from backend /api/db/sql/hover. Blank markdown means no hover. */
export interface SqlHoverResponse {
  markdown?: string | null;
  rangeStart: number;
  rangeEnd: number;
}

export interface SqlSignatureParameter {
  label: string;
}

export interface SqlSignatureItem {
  label: string;
  parameters: SqlSignatureParameter[];
}

/** Signature help, returned from backend /api/db/sql/signature */
export interface SqlSignatureHelpResponse {
  signatures: SqlSignatureItem[];
  activeSignature: number;
  activeParameter: number;
}

/**
 * SQL Execution Response
 * Returned from backend /api/db/sql/execute
 */
export interface ExecuteSqlResponse {
  success: boolean;
  errorMessage?: string | null;
  executionTimeMs: number;
  originalSql?: string | null;
  executedSql?: string | null;
  databaseName?: string | null;
  schemaName?: string | null;
  query: boolean;  // true = SELECT-like, false = DML (INSERT/UPDATE/DELETE)
  headers?: string[] | null;
  rows?: (unknown[])[];
  affectedRows: number;

  // New structured fields (DataGrip-style)
  type?: ExecuteSqlResponseType | null;
  resultSet?: ExecuteSqlResultSet | null;
  executionInfo?: ExecuteSqlExecutionInfo | null;
  messages?: ExecuteSqlMessage[] | null;
  results?: ExecuteSqlSubResult[] | null;
  statementState?: SqlStatementState | null;
  transactionOutcome?: SqlTransactionOutcome | null;
  batchState?: SqlBatchState | null;
}

export type ExecuteSqlResponseType = 'QUERY' | 'UPDATE' | 'DDL' | 'ERROR';

export interface ExecuteSqlExecutionInfo {
  executionId?: string | null;
  startTime?: number | null;
  endTime?: number | null;
  durationMs?: number | null;
  executionMs?: number | null;
  fetchingMs?: number | null;
  affectedRows?: number | null;
  fetchRows?: number | null;
  truncated?: boolean | null;
  limitApplied?: boolean | null;
}

export interface ExecuteSqlColumn {
  name?: string | null;
  label?: string | null;
  typeName?: string | null;
  jdbcType?: number | null;
  precision?: number | null;
  scale?: number | null;
  nullable?: boolean | null;
  tableName?: string | null;
}

export interface ExecuteSqlResultSet {
  columns?: ExecuteSqlColumn[] | null;
  rows?: (unknown[])[];
  fetchRows?: number | null;
  truncated?: boolean | null;
}

export type ExecuteSqlMessageLevel = 'INFO' | 'WARN' | 'ERROR';

export interface ExecuteSqlMessage {
  level?: ExecuteSqlMessageLevel | null;
  code?: string | null;
  sqlState?: string | null;
  message?: string | null;
  detail?: string | null;
}

export interface ExecuteSqlSubResult {
  type?: ExecuteSqlResponseType | null;
  resultSet?: ExecuteSqlResultSet | null;
  headers?: string[] | null;
  rows?: (unknown[])[];
  affectedRows?: number | null;
  messages?: ExecuteSqlMessage[] | null;
}

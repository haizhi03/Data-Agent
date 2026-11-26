import type { ChatContext, ChatExecutionReference } from '../types/chat';
import type { ExecuteSqlResponse } from '../types/sql';
import { extractResultSet } from '../components/results/statementStatus';

export interface SqlExecutionSource {
  documentId?: string;
  documentName?: string;
  documentVersion?: number;
}

/** Copy a bounded excerpt at completion so later grid edits cannot alter the reference. */
export function snapshotSqlExecution(
  executionId: string, statementIndex: number, sql: string, target: ChatContext,
  source: SqlExecutionSource | undefined, response: ExecuteSqlResponse,
): ChatExecutionReference {
  const primary = response.resultSet ?? response.results?.find(result => result.type === 'QUERY')?.resultSet;
  let truncated = !!(primary?.truncated || response.executionInfo?.truncated);
  const clip = (value: string, limit: number) => {
    truncated ||= value.length > limit;
    return value.slice(0, limit);
  };
  const resultSet = extractResultSet(response);
  truncated ||= resultSet.rows.length > 5 || resultSet.headers.length > 8 || resultSet.rows.some(row => row.length > 8);
  const headers = resultSet.headers.slice(0, 8).map(header => clip(header, 64));
  const rows = resultSet.rows.slice(0, 5).map(row => row.slice(0, 8).map(value =>
    value == null ? null : clip(typeof value === 'object' ? JSON.stringify(value) : String(value), 128)));
  const error = response.errorMessage || response.messages?.filter(message => message.level === 'ERROR').map(message => message.message || '').join('\n');
  const sqlState = response.messages?.find(message => message.sqlState)?.sqlState;
  const referenceSql = clip(response.executedSql || response.originalSql || sql, 8000);
  const result: ChatExecutionReference['result'] = {
    success: response.success,
    statementState: response.statementState ?? 'UNKNOWN',
    transactionOutcome: response.transactionOutcome ?? 'UNKNOWN',
    batchState: response.batchState ?? undefined,
    errorMessage: error ? clip(error, 2000) : undefined,
    sqlState: sqlState ? clip(sqlState, 16) : undefined,
    headers, rows, returnedRows: resultSet.rows.length,
    affectedRows: response.executionInfo?.affectedRows ?? response.affectedRows ?? null,
  };
  return { executionId: response.executionInfo?.executionId || executionId, statementIndex,
    sql: referenceSql, target: { ...target }, ...source, truncated, result };
}

import type { ExecuteSqlResponse } from '../../types/sql';

/** Tailwind class for the status dot of a single statement result. */
export function statementStatusColor(result: ExecuteSqlResponse | null | undefined): string {
  if (!result) return 'bg-gray-500';
  if (result.statementState === 'UNKNOWN' || result.batchState === 'UNKNOWN') return 'bg-amber-500';
  if (result.batchState === 'PARTIAL') return 'bg-amber-500';
  if (result.statementState === 'ROLLED_BACK' || result.batchState === 'ROLLED_BACK') return 'bg-red-500';
  if (result.statementState === 'NOT_EXECUTED') return 'bg-gray-500';
  if (result.success) return 'bg-green-500';
  return 'bg-red-500';
}

/** Human-readable one-line status for a single statement result. */
export function statementStatusLabel(result: ExecuteSqlResponse | null | undefined): string {
  if (!result) return 'Ready';
  if (result.statementState === 'UNKNOWN' || result.batchState === 'UNKNOWN') return 'Outcome unknown';
  if (result.batchState === 'PARTIAL') return 'Partially applied';
  if (result.statementState === 'ROLLED_BACK') return 'Rolled back';
  if (result.statementState === 'NOT_EXECUTED') return 'Not executed';
  if (!result.success) return result.statementState || 'Error';
  const duration = result.executionInfo?.durationMs ?? result.executionTimeMs;
  const fetched = result.executionInfo?.fetchRows ?? result.resultSet?.rows?.length ?? result.rows?.length ?? 0;
  const affected = result.executionInfo?.affectedRows ?? result.affectedRows;
  if (result.query || result.type === 'QUERY') return `${fetched} rows · ${duration}ms`;
  return `${affected} affected · ${duration}ms`;
}

/** First line of the statement's SQL, for compact list rendering. */
export function statementFirstLine(result: ExecuteSqlResponse | null | undefined): string {
  const sql = result?.originalSql || result?.executedSql || '';
  const newline = sql.search(/\r?\n/);
  return newline === -1 ? sql : sql.slice(0, newline);
}

/** Headers + rows of a statement's primary result set (its QUERY result, if any). */
export function extractResultSet(result: ExecuteSqlResponse | null | undefined): {
  headers: string[];
  rows: unknown[][];
} {
  const primary = result?.resultSet
    ?? result?.results?.find((r) => r.type === 'QUERY')?.resultSet;
  const headers = primary?.columns?.map((c) => c.label || c.name || '').filter(Boolean)
    ?? result?.headers ?? [];
  const rows = primary?.rows ?? result?.rows ?? [];
  return { headers, rows };
}

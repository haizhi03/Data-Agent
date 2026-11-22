/**
 * Export query results in the formats offered by the result panel dropdown.
 * Reuses the shared browser download helper.
 */
import { EXPORT_FILE_EXTENSIONS } from '../constants/fileTypes';
import type { ExecuteSqlColumn } from '../types/sql';
import { downloadFile } from './download';
import { quoteIdent, toSqlLiteral, type SqlDialectProfile } from './sqlGenerator';

export type ResultExportFormat = 'CSV' | 'XLSX' | 'JSON' | 'SQL';

/** Formats generated in the browser; XLSX goes through the shared server export pipeline. */
export type ClientResultExportFormat = 'CSV' | 'JSON' | 'SQL';

export const RESULT_EXPORT_FORMATS: ResultExportFormat[] = ['CSV', 'XLSX', 'JSON', 'SQL'];

/**
 * Escape a cell value for CSV: wrap in double quotes if contains comma, newline, or quote.
 */
function escapeCsvCell(value: unknown): string {
  const s = value == null ? '' : String(value);
  const needsQuotes = /[",\r\n]/.test(s);
  if (!needsQuotes) return s;
  return `"${s.replace(/"/g, '""')}"`;
}

/**
 * Build CSV string from headers and rows.
 * Uses UTF-8 BOM so Excel opens it with correct encoding.
 */
export function buildCsvFromResult(
  headers: string[],
  rows: unknown[][],
): string {
  const headerLine = headers.map(escapeCsvCell).join(',');
  const dataLines = rows.map((row) =>
    row.map((cell) => escapeCsvCell(cell)).join(','),
  );
  const lines = [headerLine, ...dataLines];
  const csv = lines.join('\r\n');
  const BOM = '\uFEFF';
  return BOM + csv;
}

function uniqueColumnKeys(columns: ExecuteSqlColumn[]): string[] {
  const used = new Map<string, number>();
  return columns.map((column, index) => {
    const base = column.label || column.name || `column_${index + 1}`;
    const seen = used.get(base) ?? 0;
    used.set(base, seen + 1);
    return seen === 0 ? base : `${base}_${seen + 1}`;
  });
}

function buildJsonFromResult(columns: ExecuteSqlColumn[], rows: unknown[][]): string {
  const keys = uniqueColumnKeys(columns);
  const data = rows.map((row) => {
    const record: Record<string, unknown> = {};
    keys.forEach((key, index) => {
      record[key] = row[index] ?? null;
    });
    return record;
  });
  return JSON.stringify(
    data,
    (_key, value) => (typeof value === 'bigint' ? value.toString() : value),
    2,
  );
}

interface ResultTableIdentity {
  tableName: string;
  catalogName?: string | null;
  schemaName?: string | null;
}

/** Resolve the single source table of a result set, or null when columns span tables. */
export function resolveResultTableIdentity(columns: ExecuteSqlColumn[]): ResultTableIdentity | null {
  if (columns.length === 0) return null;
  const first = columns[0];
  if (!first.tableName) return null;
  const sameTable = columns.every(
    (column) =>
      column.tableName === first.tableName &&
      (column.catalogName ?? null) === (first.catalogName ?? null) &&
      (column.schemaName ?? null) === (first.schemaName ?? null),
  );
  if (!sameTable) return null;
  return {
    tableName: first.tableName,
    catalogName: first.catalogName,
    schemaName: first.schemaName,
  };
}

function qualifiedTableName(table: ResultTableIdentity, profile: SqlDialectProfile): string {
  // MySQL qualifies with the database (catalog); other dialects use the schema.
  const container = profile.quote === '`' ? table.catalogName : table.schemaName;
  return [container, table.tableName]
    .filter((part): part is string => typeof part === 'string' && part.length > 0)
    .map((part) => quoteIdent(part, profile))
    .join('.');
}

function buildSqlInsertFromResult(
  columns: ExecuteSqlColumn[],
  rows: unknown[][],
  profile: SqlDialectProfile,
): string {
  const table = resolveResultTableIdentity(columns);
  if (!table) {
    throw new Error('SQL export requires a result set from a single table');
  }
  const columnList = columns
    .map((column) => quoteIdent(column.name || column.label || '', profile))
    .join(', ');
  const tableName = qualifiedTableName(table, profile);
  return rows
    .map(
      (row) =>
        `INSERT INTO ${tableName} (${columnList}) VALUES (${row
          .map((value) => toSqlLiteral(value, profile))
          .join(', ')});`,
    )
    .join('\n');
}

function timestampSuffix(): string {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

interface DownloadResultOptions {
  columns: ExecuteSqlColumn[];
  rows: unknown[][];
  format: ClientResultExportFormat;
  /** Dialect used by the SQL export (quoting and literals). */
  profile: SqlDialectProfile;
  baseName?: string;
}

/** Build the requested format and trigger a browser download. */
export function downloadResultSet({
  columns,
  rows,
  format,
  profile,
  baseName,
}: DownloadResultOptions): void {
  const headers = columns.map((column, index) => column.label || column.name || `Column ${index + 1}`);
  const table = resolveResultTableIdentity(columns);
  const name = baseName || table?.tableName || 'result';
  const extension = EXPORT_FILE_EXTENSIONS[format] ?? format.toLowerCase();

  let content: string;
  let mimeType: string;
  if (format === 'JSON') {
    content = buildJsonFromResult(columns, rows);
    mimeType = 'application/json;charset=utf-8';
  } else if (format === 'SQL') {
    content = buildSqlInsertFromResult(columns, rows, profile);
    mimeType = 'text/plain;charset=utf-8';
  } else {
    content = buildCsvFromResult(headers, rows);
    mimeType = 'text/csv;charset=utf-8';
  }

  downloadFile(new Blob([content], { type: mimeType }), `${name}-${timestampSuffix()}.${extension}`);
}

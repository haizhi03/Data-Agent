import { escapeStringLiteral, type SqlDialectProfile } from './dialect';

/**
 * Convert a raw cell value into a SQL literal.
 *
 * Mirrors the server-side {@code SqlTableDataExporter.toSqlLiteral} so that
 * generated SQL and the file-export SQL share the same rules:
 *   - null        -> NULL
 *   - number      -> raw representation
 *   - boolean     -> 1 / 0 (works across MySQL and DM)
 *   - binary      -> X'..' hex literal
 *   - Date        -> ISO-8601 string, quoted
 *   - string/other -> quoted + escaped string
 */
export function toSqlLiteral(value: unknown, profile: SqlDialectProfile): string {
  if (value === null || value === undefined) {
    return 'NULL';
  }

  if (typeof value === 'number') {
    return Number.isFinite(value) ? String(value) : 'NULL';
  }

  if (typeof value === 'boolean') {
    return value ? '1' : '0';
  }

  if (value instanceof Date) {
    return `'${escapeStringLiteral(value.toISOString(), profile)}'`;
  }

  const bytes = toBytes(value);
  if (bytes) {
    let hex = "X'";
    for (const b of bytes) {
      hex += b.toString(16).toUpperCase().padStart(2, '0');
    }
    return `${hex}'`;
  }

  return `'${escapeStringLiteral(String(value), profile)}'`;
}

function toBytes(value: unknown): Uint8Array | null {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  // JSON-decoded byte[] may arrive as an array of numbers or {data:[...]}.
  if (Array.isArray(value) && value.every((item) => typeof item === 'number' && Number.isInteger(item) && item >= 0 && item <= 255)) {
    return Uint8Array.from(value as number[]);
  }
  return null;
}

/** Build a column assignment/WHERE clause from a value (handles IS NULL). */
export function toWhereCondition(
  column: string,
  operator: '=' | '<>',
  value: unknown,
  profile: SqlDialectProfile,
): string {
  if (value === null || value === undefined) {
    return `${column} ${operator === '<>' ? 'IS NOT NULL' : 'IS NULL'}`;
  }
  return `${column} ${operator} ${toSqlLiteral(value, profile)}`;
}

import type { IdentifierQuoteStyle } from '../../types/dbType';

/**
 * Dialect-aware rules for rendering generated SQL.
 *
 * Kept in one place so the SQL generator (data grid) and the object-tree
 * "SQL Scripts" menu produce identical quoting/literal/transaction syntax.
 *
 * The identifier quote style mirrors the server-side
 * {@code DbTypeSqlTemplateRegistry} ({@code backtick} / {@code double_quote} /
 * {@code none}) and JDBC {@code getIdentifierQuoteString()}.
 */

export type SqlQuoteChar = '`' | '"' | '';

export interface SqlDialectProfile {
  /** Character used to quote identifiers ('' = no quoting). */
  quote: SqlQuoteChar;
  /**
   * Whether a backslash is an escape character inside string literals.
   * MySQL: yes (so {@code \} becomes {@code \\}). Standard SQL / Oracle / DM: no.
   */
  backslashEscapes: boolean;
  /** Wrap generated statements in a transaction. */
  transactionWrap: (body: string) => string;
}

function quoteIdentifier(name: string, quote: SqlQuoteChar): string {
  if (!quote) return name;
  const escaped = name.split(quote).join(quote + quote);
  return `${quote}${escaped}${quote}`;
}

/** Escape a string literal body (already inside single quotes). */
export function escapeStringLiteral(value: string, profile: SqlDialectProfile): string {
  let result = value.replace(/'/g, "''");
  if (profile.backslashEscapes) {
    result = result.replace(/\\/g, '\\\\');
  }
  return result.replace(/\0/g, '');
}

/** Quote a single identifier using the profile's quote character. */
export function quoteIdent(name: string, profile: SqlDialectProfile): string {
  return quoteIdentifier(name, profile.quote);
}

/** Build a qualified name from catalog/schema/object, quoting each part. */
export function qualifyName(
  parts: { catalog?: string | null; schema?: string | null; name: string },
  profile: SqlDialectProfile,
): string {
  return [parts.catalog, parts.schema, parts.name]
    .filter((part): part is string => typeof part === 'string' && part.length > 0)
    .map((part) => quoteIdent(part, profile))
    .join('.');
}

const MYSQL_PROFILE: SqlDialectProfile = {
  quote: '`',
  backslashEscapes: true,
  transactionWrap: (body) => `START TRANSACTION;\n${body}\nCOMMIT;`,
};

const STANDARD_PROFILE: SqlDialectProfile = {
  quote: '"',
  backslashEscapes: false,
  // BEGIN is ambiguous in Oracle/DM (it also opens a PL/SQL block); DML there
  // runs in an implicit transaction and only needs a trailing COMMIT.
  transactionWrap: (body) => `${body}\nCOMMIT;`,
};

const POSTGRES_PROFILE: SqlDialectProfile = {
  quote: '"',
  backslashEscapes: false,
  transactionWrap: (body) => `BEGIN;\n${body}\nCOMMIT;`,
};

/**
 * Resolve the dialect profile for a backend dbType code.
 *
 * @param dbType   backend db type code (e.g. "mysql", "dm", "postgresql")
 * @param quoteStyle optional per-dbType identifier quote style from
 *                   {@code DbTypeOption.identifierQuoteStyle}; when provided it
 *                   takes precedence over the dbType default.
 */
export function resolveProfile(dbType?: string | null, quoteStyle?: IdentifierQuoteStyle): SqlDialectProfile {
  const normalized = (dbType ?? '').trim().toLowerCase();
  const base =
    normalized.includes('postgres')
      ? POSTGRES_PROFILE
      : normalized === 'mysql' || normalized === 'mariadb'
        ? MYSQL_PROFILE
        : STANDARD_PROFILE;

  let quote = base.quote;
  if (quoteStyle === 'backtick') quote = '`';
  else if (quoteStyle === 'double_quote') quote = '"';
  else if (quoteStyle === 'none') quote = '';

  return { ...base, quote };
}

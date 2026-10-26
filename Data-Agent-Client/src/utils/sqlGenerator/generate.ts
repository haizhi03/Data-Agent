import { qualifyName, quoteIdent, type SqlDialectProfile } from './dialect';
import { toSqlLiteral, toWhereCondition } from './literals';

/**
 * DataGrip-style SQL generator: turn selected grid rows into INSERT / UPDATE /
 * DELETE / SELECT statements, dialect-aware.
 *
 * The generator is pure — it receives column names and typed row values and
 * returns SQL text. Callers supply the primary-key columns (for UPDATE/DELETE
 * WHERE clauses) and any options; the preview can therefore re-render
 * instantly as options change.
 */

export type SqlStatementType = 'INSERT' | 'UPDATE' | 'DELETE' | 'SELECT';

export interface SqlGenerateOptions {
  /** Prefix the table with schema/catalog. */
  qualifyWithSchema: boolean;
  /** INSERT: emit `INSERT INTO t VALUES (...)` without the column list. */
  omitColumnList: boolean;
  /** INSERT: batch multiple rows into `VALUES (...),(...)`. */
  batchMultiValues: boolean;
  /** Wrap the statements in a transaction. */
  wrapInTransaction: boolean;
}

export const DEFAULT_SQL_GENERATE_OPTIONS: SqlGenerateOptions = {
  qualifyWithSchema: true,
  omitColumnList: false,
  batchMultiValues: true,
  wrapInTransaction: false,
};

export interface SqlGenerateRequest {
  statementType: SqlStatementType;
  table: { catalog?: string | null; schema?: string | null; name: string };
  /** Table columns, in display order. */
  columns: string[];
  /** Selected rows (typed values, one entry per column). */
  rows: unknown[][];
  /** Primary-key columns used as UPDATE/DELETE WHERE keys; empty -> all columns. */
  pkColumns?: string[];
  options: SqlGenerateOptions;
}

export interface GeneratedSql {
  sql: string;
  /** Unwrapped SQL for the server-managed transaction used by the console. */
  consoleSql: string;
  statementCount: number;
  /** Columns used in the WHERE clause (reported so the UI can surface fallback). */
  whereColumns: string[];
}

export function generateSql(req: SqlGenerateRequest, profile: SqlDialectProfile): GeneratedSql {
  if (req.rows.length === 0) {
    return { sql: '', consoleSql: '', statementCount: 0, whereColumns: [] };
  }

  const tableName = req.options.qualifyWithSchema
    ? qualifyName(req.table, profile)
    : quoteIdent(req.table.name, profile);

  const statements: string[] = [];
  let whereColumns: string[] = [];

  switch (req.statementType) {
    case 'INSERT':
      statements.push(...generateInsert(req, tableName, profile));
      break;
    case 'UPDATE': {
      const result = generateUpdate(req, tableName, profile);
      statements.push(...result.sql);
      whereColumns = result.whereColumns;
      break;
    }
    case 'DELETE': {
      const result = generateDelete(req, tableName, profile);
      statements.push(...result.sql);
      whereColumns = result.whereColumns;
      break;
    }
    case 'SELECT':
      statements.push(generateSelect(req, tableName, profile));
      break;
  }

  const consoleSql = statements.join('\n');
  let body = consoleSql;
  if (req.options.wrapInTransaction && statements.length > 0) {
    body = profile.transactionWrap(body);
  }

  return { sql: body, consoleSql, statementCount: statements.length, whereColumns };
}

export interface SqlTemplateRequest {
  statementType: SqlStatementType;
  table: { catalog?: string | null; schema?: string | null; name: string };
  /** Table columns, in display order. */
  columns: string[];
  /** Primary-key columns used as UPDATE/DELETE WHERE keys; empty -> all columns. */
  pkColumns?: string[];
  /** Defaults to true: prefix the table with catalog/schema. */
  qualifyWithSchema?: boolean;
}

/**
 * Column-only statement template for callers without selected rows (explorer
 * tree right-click). Values are emitted as NULL placeholders so the result
 * stays syntactically valid.
 */
export function generateSqlTemplate(req: SqlTemplateRequest, profile: SqlDialectProfile): string {
  const tableName = req.qualifyWithSchema === false
    ? quoteIdent(req.table.name, profile)
    : qualifyName(req.table, profile);
  const pkColumns = (req.pkColumns ?? []).filter((column) => req.columns.includes(column));
  const whereColumns = pkColumns.length > 0 ? pkColumns : req.columns;

  switch (req.statementType) {
    case 'SELECT':
      return `SELECT ${req.columns.map((column) => quoteIdent(column, profile)).join(', ')}\nFROM ${tableName};`;
    case 'INSERT': {
      const values = req.columns.map(() => 'NULL').join(', ');
      return `INSERT INTO ${tableName} (${req.columns.map((column) => quoteIdent(column, profile)).join(', ')})\nVALUES (${values});`;
    }
    case 'UPDATE': {
      const setColumns = req.columns.filter((column) => !whereColumns.includes(column));
      const assignments = (setColumns.length > 0 ? setColumns : req.columns)
        .map((column) => `  ${quoteIdent(column, profile)} = NULL`)
        .join(',\n');
      const where = whereColumns
        .map((column) => `  ${quoteIdent(column, profile)} = NULL`)
        .join(' AND\n');
      return `UPDATE ${tableName}\nSET\n${assignments}\nWHERE\n${where};`;
    }
    case 'DELETE': {
      const where = whereColumns
        .map((column) => `  ${quoteIdent(column, profile)} = NULL`)
        .join(' AND\n');
      return `DELETE FROM ${tableName}\nWHERE\n${where};`;
    }
  }
}

function generateInsert(req: SqlGenerateRequest, tableName: string, profile: SqlDialectProfile): string[] {
  const columnList = req.columns.map((c) => quoteIdent(c, profile)).join(', ');
  const values = req.rows.map((row) => {
    const literals = req.columns.map((_, index) => {
      const value = index < row.length ? row[index] : null;
      return toSqlLiteral(value, profile);
    });
    return `(${literals.join(', ')})`;
  });

  const prefix = req.options.omitColumnList
    ? `INSERT INTO ${tableName}`
    : `INSERT INTO ${tableName} (${columnList})`;

  if (req.options.batchMultiValues && values.length > 1) {
    return [`${prefix} VALUES\n  ${values.join(',\n  ')};`];
  }
  return values.map((value) => `${prefix} VALUES ${value};`);
}

function resolveWhereColumns(req: SqlGenerateRequest): string[] {
  const pk = req.pkColumns ?? [];
  const available = pk.filter((column) => req.columns.includes(column));
  return available.length > 0 ? available : req.columns;
}

function generateUpdate(
  req: SqlGenerateRequest,
  tableName: string,
  profile: SqlDialectProfile,
): { sql: string[]; whereColumns: string[] } {
  const whereColumns = resolveWhereColumns(req);

  return {
    whereColumns,
    sql: req.rows.map((row) => {
      const nonKeyColumns = req.columns.filter((column) => !whereColumns.includes(column));
      // All-column WHERE fallback still needs a SET clause. A key-only table
      // also gets a valid assignment instead of an empty SET.
      const setColumns = nonKeyColumns.length > 0 ? nonKeyColumns : req.columns;
      const assignments = setColumns
        .map((column) => {
          const value = row[req.columns.indexOf(column)];
          return `  ${quoteIdent(column, profile)} = ${toSqlLiteral(value, profile)}`;
        })
        .join(',\n');
      const where = whereColumns
        .map((column) => {
          const value = row[req.columns.indexOf(column)];
          return `  ${toWhereCondition(quoteIdent(column, profile), '=', value, profile)}`;
        })
        .join(' AND\n');

      return `UPDATE ${tableName}\nSET\n${assignments}\nWHERE\n${where};`;
    }),
  };
}

function generateDelete(
  req: SqlGenerateRequest,
  tableName: string,
  profile: SqlDialectProfile,
): { sql: string[]; whereColumns: string[] } {
  const whereColumns = resolveWhereColumns(req);

  return {
    whereColumns,
    sql: req.rows.map((row) => {
      const where = whereColumns
        .map((column) => {
          const value = row[req.columns.indexOf(column)];
          return `  ${toWhereCondition(quoteIdent(column, profile), '=', value, profile)}`;
        })
        .join(' AND\n');
      return `DELETE FROM ${tableName}\nWHERE\n${where};`;
    }),
  };
}

function generateSelect(req: SqlGenerateRequest, tableName: string, profile: SqlDialectProfile): string {
  const columnList = req.columns.map((c) => quoteIdent(c, profile)).join(', ');
  const conditions = req.rows.map((row) => {
    const parts = req.columns.map((column, index) => {
      const value = index < row.length ? row[index] : null;
      return toWhereCondition(quoteIdent(column, profile), '=', value, profile);
    });
    return parts.join(' AND ');
  });

  if (conditions.length === 0) {
    return `SELECT ${columnList} FROM ${tableName};`;
  }
  const where = conditions.map((c) => `  (${c})`).join('\n   OR\n');
  return `SELECT ${columnList}\nFROM ${tableName}\nWHERE\n${where};`;
}

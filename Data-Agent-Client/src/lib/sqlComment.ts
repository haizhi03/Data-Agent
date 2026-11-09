/** SQL helpers for the table-comment fragments the create-table dialog emits. */

export function isMysqlDbType(dbType?: string): boolean {
  return dbType?.toLowerCase().includes('mysql') ?? false;
}

export function isDmDbType(dbType?: string): boolean {
  const normalized = dbType?.toLowerCase() ?? '';
  return normalized === 'dm' || normalized.includes('dameng');
}

/** Quote an identifier the same way the create-table preview does. */
export function quoteCreateTableIdentifier(name: string, dbType?: string): string {
  if (!name.trim()) return name;
  return isMysqlDbType(dbType) ? `\`${name}\`` : `"${name.replace(/"/g, '""')}"`;
}

/** Escape a string literal for the target dialect. */
export function escapeSqlStringLiteral(value: string, dbType?: string): string {
  if (isMysqlDbType(dbType)) {
    return value.replace(/\\/g, '\\\\').replace(/'/g, "''");
  }
  return value.replace(/'/g, "''");
}

/** Inline MySQL table comment appended after the closing parenthesis. */
export function buildMysqlTableCommentClause(comment: string, dbType?: string): string {
  if (!isMysqlDbType(dbType) || !comment.trim()) return '';
  return ` COMMENT='${escapeSqlStringLiteral(comment.trim(), dbType)}'`;
}

/** Separate DM statement that sets the comment after CREATE TABLE. */
export function buildDmCommentOnTableStatement(
  schema: string | undefined,
  tableName: string,
  comment: string,
  dbType?: string
): string {
  const schemaName = schema?.trim();
  const fullName = schemaName
    ? `${quoteCreateTableIdentifier(schemaName, dbType)}.${quoteCreateTableIdentifier(tableName.trim(), dbType)}`
    : quoteCreateTableIdentifier(tableName.trim(), dbType);
  return `COMMENT ON TABLE ${fullName} IS '${escapeSqlStringLiteral(comment.trim(), dbType)}'`;
}

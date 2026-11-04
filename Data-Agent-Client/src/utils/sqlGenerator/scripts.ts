import { qualifyName, quoteIdent, type SqlDialectProfile } from './dialect';

/**
 * DataGrip-style "SQL Scripts" templates for a database object (table/view).
 * Unlike the data-grid generator these take no row values — they emit a
 * statement skeleton the user can edit in the console.
 */

export type ObjectScriptKind = 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'DROP';

export interface ObjectScriptRequest {
  kind: ObjectScriptKind;
  table: { catalog?: string | null; schema?: string | null; name: string };
  /** Column names; required for INSERT/UPDATE, ignored otherwise. */
  columns?: string[];
  /** DROP target: table vs view. */
  objectType?: 'table' | 'view';
}

export function generateObjectScript(
  req: ObjectScriptRequest,
  profile: SqlDialectProfile,
): string {
  const qualified = qualifyName(req.table, profile);
  const columns = req.columns ?? [];

  switch (req.kind) {
    case 'SELECT':
      return `SELECT * FROM ${qualified};`;
    case 'DROP': {
      const keyword = req.objectType === 'view' ? 'VIEW' : 'TABLE';
      return `DROP ${keyword} ${qualified};`;
    }
    case 'INSERT': {
      if (columns.length === 0) {
        return `INSERT INTO ${qualified} VALUES ();`;
      }
      const columnList = columns.map((c) => quoteIdent(c, profile)).join(', ');
      const placeholders = columns.map(() => '?').join(', ');
      return `INSERT INTO ${qualified} (${columnList}) VALUES (${placeholders});`;
    }
    case 'UPDATE': {
      const assignments = columns
        .map((c) => `  ${quoteIdent(c, profile)} = ?`)
        .join(',\n');
      return `UPDATE ${qualified}\nSET\n${assignments}\nWHERE\n  /* TODO: add condition */;`;
    }
    case 'DELETE':
      return `DELETE FROM ${qualified}\nWHERE\n  /* TODO: add condition */;`;
  }
}

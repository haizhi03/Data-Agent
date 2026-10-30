import type { DbConnection } from './connection';
import type { ExplorerNodeType, FolderName } from '../constants/explorer';

export interface ExplorerNode {
  id: string;
  name: string;
  type: ExplorerNodeType;
  connectionId?: string;
  dbConnection?: DbConnection;
  children?: ExplorerNode[];
  catalog?: string;
  schema?: string;
  /** Only set when type === 'folder' */
  folderName?: FolderName;
  /** On a group node: stable id of the table group in the group store. Also set on a table node to mark its resolved group. */
  groupId?: string;
  /** On a group node: 'manual' or 'rule'. Also set on a table node to mark the kind of group it resolved into. */
  groupKind?: 'manual' | 'rule';
  /** Parent table/view name when type is column/index or folder columns/indexes */
  tableName?: string;
  /** Routine signature for function/procedure nodes; or column type display for column nodes, e.g. "int unsigned (auto increment)" */
  signature?: string;
  /** Whether the column is part of primary key (for key icon on column nodes) */
  isPrimaryKey?: boolean;
  /** Raw object name for API calls when display name differs, e.g. trigger name without " (on tableName)" */
  objectName?: string;
}

/** Target of a right-clicked DB/schema/folder node for DDL generation. */
export interface SqlScriptTarget {
  connectionId: number;
  connectionName: string;
  databaseName?: string | null;
  schemaName?: string | null;
  dbType?: string;
  /** Human-readable "@host/db/schema" path shown in the dialog. */
  targetPath: string;
  /** Object scope for the DDL generator: whole schema, or a specific folder's objects. */
  scope?: 'all' | 'tables' | 'views' | 'routines';
}

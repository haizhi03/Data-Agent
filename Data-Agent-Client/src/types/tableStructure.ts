export type StructureDefaultKind = 'NONE' | 'NULL' | 'VALUE' | 'EXPRESSION';

export interface StructureColumn {
  name: string;
  originalName: string | null;
  typeName: string;
  columnType: string;
  length: number | null;
  scale: number | null;
  nullable: boolean;
  autoIncrement: boolean;
  unsigned: boolean;
  defaultKind: StructureDefaultKind;
  defaultValue: string | null;
  onUpdate: string | null;
  comment: string;
  charset: string | null;
  collation: string | null;
  generationExpression: string | null;
  generationStored: boolean;
}

export interface StructurePrimaryKey {
  name: string | null;
  columns: string[];
}

export interface StructureIndex {
  name: string;
  originalName: string | null;
  unique: boolean;
  type: string | null;
  columns: string[];
  prefixLengths: Array<number | null> | null;
}

export interface StructureForeignKey {
  name: string;
  originalName: string | null;
  columns: string[];
  referencedSchema: string | null;
  referencedTable: string;
  referencedColumns: string[];
  onUpdate: string | null;
  onDelete: string | null;
}

export interface StructureCheck {
  name: string;
  originalName: string | null;
  expression: string;
}

export interface TableStructure {
  name: string;
  comment: string | null;
  engine: string | null;
  charset: string | null;
  collation: string | null;
  columns: StructureColumn[];
  primaryKey: StructurePrimaryKey | null;
  indexes: StructureIndex[];
  foreignKeys: StructureForeignKey[];
  checks: StructureCheck[];
}

export interface StructureChange {
  objectType: string;
  action: string;
  name: string;
  destructive: boolean;
  detail: string | null;
}

export interface StructurePreview {
  statements: string[];
  changes: StructureChange[];
  warnings: string[];
}

export interface ApplyTableStructureResponse {
  structure: TableStructure;
  statements: string[];
  changes: StructureChange[];
  failedIndex: number;
  errorMessage: string | null;
}

export interface ColumnDraft extends StructureColumn {
  clientId: string;
}

export interface IndexDraft extends StructureIndex {
  clientId: string;
  columnText: string;
  expression: boolean;
}

export interface ForeignKeyDraft extends StructureForeignKey {
  clientId: string;
  columnsText: string;
  referencedColumnsText: string;
}

export interface CheckDraft extends StructureCheck {
  clientId: string;
}

export interface TableDraft {
  name: string;
  comment: string;
  engine: string;
  charset: string;
  collation: string;
  columns: ColumnDraft[];
  primaryKey: StructurePrimaryKey | null;
  indexes: IndexDraft[];
  foreignKeys: ForeignKeyDraft[];
  checks: CheckDraft[];
}

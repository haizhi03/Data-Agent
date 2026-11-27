import type { CellClassParams, ColDef, ValueFormatterParams } from 'ag-grid-community';
import type { TableRowValuePayload } from '../../services/tableData.service';
import type { ExecuteSqlColumn, ExecuteSqlResultSet } from '../../types/sql';
import { STORAGE_KEYS } from '../../constants/storageKeys';
import { buildRowNumberColumn, getGridFieldKey } from '../../utils/agGrid';
import { toRowMatchValue } from '../workspace/tableDataTabShared';
import { ResultColumnHeader, type ResultColumnHeaderMeta } from './ResultColumnHeader';

export const RESULT_CELL_MAX_CHARS = 240;

/** Persisted per-column display preferences for the result grid. */
export interface ResultColumnPreference {
  visible?: boolean;
  pinned?: 'left' | 'right' | null;
  width?: number;
}

export type ResultColumnPreferences = Record<string, ResultColumnPreference>;

/** Stable key for one result shape, independent of the connection. */
export function buildResultColumnSignature(columns: ExecuteSqlColumn[]): string {
  return columns.map((column) => column.name || column.label || '').join('\u0001');
}

export function readResultColumnPreferences(signature: string): ResultColumnPreferences {
  if (!signature) return {};
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.RESULT_COLUMN_PREFS);
    const stored = raw ? (JSON.parse(raw) as Record<string, ResultColumnPreferences>) : {};
    const prefs = stored?.[signature];
    return prefs && typeof prefs === 'object' ? prefs : {};
  } catch {
    return {};
  }
}

export function writeResultColumnPreferences(
  signature: string,
  prefs: ResultColumnPreferences,
): void {
  if (!signature) return;
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.RESULT_COLUMN_PREFS);
    const stored = raw ? (JSON.parse(raw) as Record<string, ResultColumnPreferences>) : {};
    stored[signature] = prefs;
    localStorage.setItem(STORAGE_KEYS.RESULT_COLUMN_PREFS, JSON.stringify(stored));
  } catch {
    // Storage may be unavailable (private mode / quota); prefs are best-effort.
  }
}

export interface ResultGridRow {
  __rowId: string;
  __rowNumber: number;
  __rowIndex?: number;
  /** Original (unmodified) row values, used to build WHERE / revert comparisons. */
  __sourceRow?: unknown[];
  [key: string]: unknown;
}

/** Columns with display fallbacks, so a missing name/label never breaks the grid. */
export function resolveResultColumns(
  resultSet?: ExecuteSqlResultSet | null,
  fallbackHeaders?: string[] | null,
): ExecuteSqlColumn[] {
  if (resultSet?.columns?.length) {
    return resultSet.columns.map((column, index) => ({
      ...column,
      name: column.name || column.label || `Column ${index + 1}`,
      label: column.label || column.name || `Column ${index + 1}`,
    }));
  }
  return (fallbackHeaders ?? []).map((header) => ({ name: header, label: header }));
}

export function buildResultGridRows(rows: unknown[][] | null | undefined): ResultGridRow[] {
  return (rows ?? []).map((row, rowIndex) => {
    const mapped: ResultGridRow = {
      __rowId: `result-row-${rowIndex}`,
      __rowNumber: rowIndex + 1,
      __rowIndex: rowIndex,
      __sourceRow: row,
    };
    row.forEach((value, colIndex) => {
      mapped[getGridFieldKey(colIndex)] = value;
    });
    return mapped;
  });
}

/** Render strings without losing the NULL / empty-string / long-text distinction. */
export function stringifyResultValue(value: unknown): string {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

export function formatResultCell(value: unknown, maxChars = RESULT_CELL_MAX_CHARS): string {
  if (value === null || value === undefined) return 'NULL';
  if (value === '') return "''";
  const text = stringifyResultValue(value);
  return text.length > maxChars ? `${text.slice(0, maxChars)}…` : text;
}

const TYPE_WITH_LENGTH = /^(?:var)?char2?$|^binary$|^varbinary$|^decimal$|^numeric$|^number$|^bit$/;

export type ResultTypeFamily =
  | 'numeric'
  | 'string'
  | 'temporal'
  | 'boolean'
  | 'binary'
  | 'json'
  | 'other';

const NUMERIC_TYPES = new Set([
  'tinyint', 'smallint', 'mediumint', 'int', 'integer', 'bigint', 'int2', 'int4', 'int8',
  'decimal', 'dec', 'numeric', 'number', 'float', 'double', 'real', 'bit', 'money',
  'smallmoney', 'serial',
]);
const STRING_TYPES = new Set([
  'char', 'varchar', 'varchar2', 'nchar', 'nvarchar', 'nvarchar2', 'character', 'character varying',
  'text', 'tinytext', 'mediumtext', 'longtext', 'clob', 'nclob', 'enum', 'set', 'string', 'citext',
]);
const TEMPORAL_TYPES = new Set([
  'date', 'datetime', 'datetime2', 'smalldatetime', 'timestamp', 'timestamptz', 'time',
  'timetz', 'year', 'interval',
]);
const BOOLEAN_TYPES = new Set(['boolean', 'bool']);
const BINARY_TYPES = new Set([
  'binary', 'varbinary', 'blob', 'tinyblob', 'mediumblob', 'longblob', 'bytea', 'image',
  'raw', 'long raw',
]);
const JSON_TYPES = new Set(['json', 'jsonb']);

/** Group a database type into a colour family for the header type label. */
export function getResultTypeFamily(typeName?: string | null): ResultTypeFamily {
  const normalized = (typeName ?? '')
    .toLowerCase()
    .replace(/\(.*$/, '')
    .replace(/\s+(unsigned|signed)$/, '')
    .trim();
  if (!normalized) return 'other';
  if (NUMERIC_TYPES.has(normalized)) return 'numeric';
  if (STRING_TYPES.has(normalized)) return 'string';
  if (TEMPORAL_TYPES.has(normalized)) return 'temporal';
  if (BOOLEAN_TYPES.has(normalized)) return 'boolean';
  if (BINARY_TYPES.has(normalized)) return 'binary';
  if (JSON_TYPES.has(normalized)) return 'json';
  return 'other';
}

/** Compact type label: "varchar(60)", "decimal(10,2)", "datetime". */
export function formatResultColumnType(column: ExecuteSqlColumn): string {
  const typeName = (column.typeName ?? '').toLowerCase();
  if (!typeName) return '';
  if (typeName.includes('(')) return typeName;
  const precision = column.precision ?? 0;
  if (precision > 0 && TYPE_WITH_LENGTH.test(typeName)) {
    const scale = column.scale ?? 0;
    return scale > 0 ? `${typeName}(${precision},${scale})` : `${typeName}(${precision})`;
  }
  return typeName;
}

export interface ResultColumnDefOptions {
  /** Allow editing data cells (requires an editable result set). */
  editable?: boolean;
  /** Staged edits as rowIndex::columnIndex keys. */
  dirtyCellKeys?: Set<string>;
}

export function buildResultColumnDefs(
  columns: ExecuteSqlColumn[],
  options: ResultColumnDefOptions = {},
): ColDef<ResultGridRow>[] {
  const { editable = false, dirtyCellKeys } = options;
  const dataColumns = columns.map<ColDef<ResultGridRow>>((column, colIndex) => {
    const meta: ResultColumnHeaderMeta = {
      label: column.label || column.name || `Column ${colIndex + 1}`,
      typeLabel: formatResultColumnType(column),
      typeFamily: getResultTypeFamily(column.typeName),
      primaryKey: !!column.primaryKey,
      tableLabel: column.tableName
        ? [column.catalogName, column.schemaName, column.tableName].filter(Boolean).join('.')
        : null,
      comment: column.comment?.trim() || null,
    };
    const tooltipParts = [
      meta.tableLabel ? `${meta.tableLabel}.${meta.label}` : meta.label,
      meta.typeLabel,
      meta.comment,
    ].filter(Boolean);
    return {
      colId: `result-col-${colIndex}`,
      field: getGridFieldKey(colIndex),
      headerName: meta.label,
      headerComponent: ResultColumnHeader,
      headerComponentParams: meta,
      headerTooltip: tooltipParts.join(' · '),
      sortable: true,
      resizable: true,
      filter: true,
      editable,
      minWidth: 90,
      width: 150,
      suppressHeaderMenuButton: false,
      cellClassRules: {
        'result-grid__cell--null': (params: CellClassParams<ResultGridRow>) =>
          params.value === null || params.value === undefined,
        'result-grid__cell--empty': (params: CellClassParams<ResultGridRow>) => params.value === '',
        'result-grid__cell--number': (params: CellClassParams<ResultGridRow>) =>
          typeof params.value === 'number' || typeof params.value === 'bigint',
        'workspace-ag-grid__cell-dirty': (params: CellClassParams<ResultGridRow>) => {
          const rowIndex = params.data?.__rowIndex;
          return rowIndex != null && !!dirtyCellKeys?.has(`${rowIndex}::${colIndex}`);
        },
      },
      valueFormatter: (params: ValueFormatterParams<ResultGridRow>) => formatResultCell(params.value),
      tooltipValueGetter: (params) => {
        if (params.value === null || params.value === undefined) return 'NULL';
        if (params.value === '') return "''";
        return stringifyResultValue(params.value);
      },
      cellClass: 'result-grid__cell',
    };
  });

  return [buildRowNumberColumn<ResultGridRow>({ totalRowCount: 0, headerName: '#' }), ...dataColumns];
}

/** Query result sets carried by a single execution: one per SELECT-like sub-result. */
export function collectQueryResultSets(
  result: { resultSet?: ExecuteSqlResultSet | null; results?: { resultSet?: ExecuteSqlResultSet | null }[] | null } | null | undefined,
): ExecuteSqlResultSet[] {
  const subSets = (result?.results ?? [])
    .map((sub) => sub.resultSet)
    .filter((set): set is ExecuteSqlResultSet => !!set);
  if (subSets.length > 0) {
    return subSets;
  }
  return result?.resultSet ? [result.resultSet] : [];
}

/** Normalize a column name for case-insensitive matching. */
export const normalizeResultColumnKey = (value?: string | null) =>
  (value ?? '').trim().toUpperCase();

/** Map result columns to indexes by physical name (falling back to label). */
export function buildResultColumnIndexMap(columns: ExecuteSqlColumn[]): Map<string, number> {
  const map = new Map<string, number>();
  columns.forEach((column, index) => {
    const key = normalizeResultColumnKey(column.name || column.label);
    if (key && !map.has(key)) {
      map.set(key, index);
    }
  });
  return map;
}

/** WHERE pairs for a result row, built from the backend-resolved match columns. */
export function buildResultMatchValues(
  columns: ExecuteSqlColumn[],
  sourceRow: unknown[],
  matchColumns: string[],
): TableRowValuePayload[] {
  const columnIndexByKey = buildResultColumnIndexMap(columns);
  return matchColumns
    .map((columnName) => {
      const columnIndex = columnIndexByKey.get(normalizeResultColumnKey(columnName));
      if (columnIndex == null) {
        return null;
      }
      return { columnName, value: toRowMatchValue(sourceRow[columnIndex]) };
    })
    .filter((entry): entry is TableRowValuePayload => entry != null);
}

/** Stable identity for a staged UPDATE row, matching the table data editor. */
export function buildResultMatchKey(matchValues: TableRowValuePayload[]): string {
  return matchValues
    .map((entry) => {
      const value = entry.value == null ? '<null>' : String(entry.value);
      return `${entry.columnName}=${value}`;
    })
    .sort()
    .join('|');
}

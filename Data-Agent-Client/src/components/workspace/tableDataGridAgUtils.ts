import type { ColDef, ValueFormatterParams } from 'ag-grid-community';
import type { TableDataResponse } from '../../services/tableData.service';
import { buildRowNumberColumn, getGridFieldKey } from '../../utils/agGrid';

export const TABLE_DATA_GRID_TRANSPOSE_FIELD_COL_ID = '__fieldName';

const TRANSPOSE_FIELD_MIN_WIDTH = 150;
const TRANSPOSE_FIELD_MAX_WIDTH = 260;

export interface TableDataGridAgRow {
  __rowId: string;
  __rowIndex?: number;
  __rowNumber: number;
  __sourceRow?: unknown[];
  __fieldName?: string;
  /** When present, this row is a staged INSERT; value is its pending op localId. */
  __pendingInsert?: string;
  [key: string]: unknown;
}

export function buildTableDataGridRows(
  data: TableDataResponse | null,
  startRow: number,
): TableDataGridAgRow[] {
  if (!data) {
    return [];
  }

  return data.rows.map((row, rowIndex) => {
    const mapped: TableDataGridAgRow = {
      __rowId: `row-${rowIndex}`,
      __rowIndex: rowIndex,
      __rowNumber: startRow + rowIndex,
      __sourceRow: row,
    };

    data.headers.forEach((_, colIndex) => {
      mapped[getGridFieldKey(colIndex)] = row[colIndex];
    });

    return mapped;
  });
}

export function buildTableDataGridColumnDefs(
  headers: string[],
  formatCellValue: (value: unknown) => string,
  totalRowCount: number,
  editable: boolean = false,
  findCellClassRules?: ColDef<TableDataGridAgRow>['cellClassRules'],
  pendingDeleteRowIndexes?: Set<number>,
): ColDef<TableDataGridAgRow>[] {
  const dataColumns = headers.map<ColDef<TableDataGridAgRow>>((header, colIndex) => ({
    colId: header,
    field: getGridFieldKey(colIndex),
    headerName: header,
    sortable: true,
    resizable: true,
    minWidth: 120,
    flex: 1,
    // Rows staged for deletion cannot be edited; staged INSERT rows remain editable.
    editable: editable
      ? (params) => {
          const rowIndex = params.node.data?.__rowIndex;
          if (rowIndex != null && rowIndex >= 0 && pendingDeleteRowIndexes?.has(rowIndex)) {
            return false;
          }
          return true;
        }
      : false,
    valueFormatter: (params: ValueFormatterParams<TableDataGridAgRow>) =>
      formatCellValue(params.value),
    comparator: () => 0,
    cellClass: 'theme-text-primary',
    headerClass: 'theme-text-secondary',
    ...(findCellClassRules ? { cellClassRules: findCellClassRules } : {}),
  }));

  return [
    buildRowNumberColumn<TableDataGridAgRow>({
      totalRowCount,
      // Staged INSERT rows use negative numbers; show '*' instead.
      valueFormatter: (params) => {
        const value = params.value;
        return typeof value === 'number' && value < 0 ? '*' : value;
      },
    }),
    ...dataColumns,
  ];
}

export function buildTransposeTableDataGridRows(
  data: TableDataResponse | null,
): TableDataGridAgRow[] {
  if (!data || data.rows.length === 0 || data.headers.length === 0) {
    return [];
  }

  return data.headers.map((header, colIndex) => {
    const mapped: TableDataGridAgRow = {
      __rowId: `field-${colIndex}-${header}`,
      __rowNumber: colIndex + 1,
      __fieldName: header,
    };

    data.rows.forEach((row, rowIndex) => {
      mapped[getGridFieldKey(rowIndex)] = row[colIndex];
    });

    return mapped;
  });
}

export function buildTransposeTableDataGridColumnDefs(
  data: TableDataResponse | null,
  startRow: number,
  fieldColumnLabel: string,
  formatCellValue: (value: unknown) => string,
): ColDef<TableDataGridAgRow>[] {
  if (!data) {
    return [];
  }

  const longestHeaderLength = data.headers.reduce(
    (maxLength, header) => Math.max(maxLength, header.length),
    fieldColumnLabel.length,
  );
  const fieldColumnWidth = Math.min(
    TRANSPOSE_FIELD_MAX_WIDTH,
    Math.max(TRANSPOSE_FIELD_MIN_WIDTH, longestHeaderLength * 9 + 40),
  );

  const fieldColumn: ColDef<TableDataGridAgRow> = {
    colId: TABLE_DATA_GRID_TRANSPOSE_FIELD_COL_ID,
    field: TABLE_DATA_GRID_TRANSPOSE_FIELD_COL_ID,
    headerName: fieldColumnLabel,
    width: fieldColumnWidth,
    minWidth: TRANSPOSE_FIELD_MIN_WIDTH,
    pinned: 'left',
    lockPosition: 'left',
    lockPinned: true,
    suppressMovable: true,
    sortable: false,
    resizable: true,
    suppressHeaderMenuButton: true,
    cellClass: 'workspace-ag-grid__transpose-field-cell',
    headerClass: 'workspace-ag-grid__transpose-field-header',
  };

  const valueColumns = data.rows.map<ColDef<TableDataGridAgRow>>((_, rowIndex) => ({
    colId: `transpose-row-${startRow + rowIndex}`,
    field: getGridFieldKey(rowIndex),
    headerName: `#${startRow + rowIndex}`,
    sortable: false,
    resizable: true,
    minWidth: 120,
    width: 140,
    suppressHeaderMenuButton: true,
    valueFormatter: (params: ValueFormatterParams<TableDataGridAgRow>) =>
      formatCellValue(params.value),
    cellClass: 'theme-text-primary',
    headerClass: 'theme-text-secondary',
  }));

  return [fieldColumn, ...valueColumns];
}

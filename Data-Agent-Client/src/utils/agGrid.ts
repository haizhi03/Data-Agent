import type { ColDef } from 'ag-grid-community';

/** Row-number column id shared by the table data grid and the result grid. */
export const AG_GRID_ROW_NUMBER_COL_ID = '__rowNumber';

const ROW_NUMBER_BASE_WIDTH = 30;
const ROW_NUMBER_DIGIT_WIDTH = 8;
const ROW_NUMBER_PADDING = 12;
const ROW_NUMBER_MIN_WIDTH = 40;

/** Field key that maps a raw row array index onto an AG Grid row object. */
export const getGridFieldKey = (index: number) => `__col_${index}`;

interface RowNumberColumnOptions<TData> {
  totalRowCount: number;
  headerName?: string;
  valueFormatter?: ColDef<TData>['valueFormatter'];
}

/** Pinned, non-sortable row-number column shared by every data grid. */
export function buildRowNumberColumn<TData>({
  totalRowCount,
  headerName = '',
  valueFormatter,
}: RowNumberColumnOptions<TData>): ColDef<TData> {
  const digits = Math.max(1, String(Math.max(totalRowCount, 1)).length);
  const width = Math.max(
    ROW_NUMBER_MIN_WIDTH,
    ROW_NUMBER_BASE_WIDTH + digits * ROW_NUMBER_DIGIT_WIDTH + ROW_NUMBER_PADDING,
  );
  return {
    colId: AG_GRID_ROW_NUMBER_COL_ID,
    field: AG_GRID_ROW_NUMBER_COL_ID as ColDef<TData>['field'],
    headerName,
    width,
    minWidth: width,
    maxWidth: width,
    pinned: 'left',
    lockPosition: 'left',
    lockPinned: true,
    suppressMovable: true,
    sortable: false,
    resizable: false,
    filter: false,
    suppressHeaderMenuButton: true,
    suppressHeaderFilterButton: true,
    ...(valueFormatter ? { valueFormatter } : {}),
    cellClass: 'workspace-ag-grid__row-number-cell',
    headerClass: 'workspace-ag-grid__row-number-header',
  };
}

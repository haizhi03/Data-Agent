import { useMemo, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react';
import { AgGridReact } from 'ag-grid-react';
import type { CellMouseDownEvent, CellValueChangedEvent, ColumnResizedEvent, FilterModel, GridApi } from 'ag-grid-community';
import { Copy, Filter, Rows3 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { useTheme } from '../../hooks/useTheme';
import { useToast } from '../../hooks/useToast';
import type { ExecuteSqlResultSet } from '../../types/sql';
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '../ui/ContextMenu';
import {
  buildResultColumnDefs,
  buildResultGridRows,
  resolveResultColumns,
  stringifyResultValue,
  type ResultGridRow,
} from './resultGridUtils';
import { getGridFieldKey } from '../../utils/agGrid';

interface ResultGridProps {
  resultSet: ExecuteSqlResultSet | null;
  fallbackHeaders?: string[] | null;
  fallbackRows?: unknown[][] | null;
  emptyMessage: string;
  /** In-place editing is enabled only for editable result sets. */
  editable?: boolean;
  /** Staged edits as rowIndex::columnIndex keys. */
  dirtyCellKeys?: Set<string>;
  /** Bump to rebuild grid rows from the original result data (discard edits). */
  resetVersion?: number;
  onCellValueChanged?: (event: CellValueChangedEvent<ResultGridRow>) => void;
  /** Exposes the grid API so callers can read the current sort/filter view. */
  onGridApiReady?: (api: GridApi<ResultGridRow>) => void;
  /** Reports a new filtered/sorted row model so callers can snapshot it. */
  onViewModelChanged?: (api: GridApi<ResultGridRow>) => void;
  /** Reports finished user column resizes so widths can be persisted. */
  onColumnResized?: (event: ColumnResizedEvent<ResultGridRow>) => void;
}

/** Right-clicked cell resolved from the grid's own mouse event. */
interface ResultMenuTarget {
  /** null when the click landed on the pinned row-number column. */
  colIndex: number | null;
  value: unknown;
  rowData: ResultGridRow | null;
}

export function ResultGrid({
  resultSet,
  fallbackHeaders,
  fallbackRows,
  emptyMessage,
  editable = false,
  dirtyCellKeys,
  resetVersion,
  onCellValueChanged,
  onGridApiReady,
  onViewModelChanged,
  onColumnResized,
}: ResultGridProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const { theme } = useTheme();
  const agThemeClass = theme === 'light' ? 'ag-theme-quartz' : 'ag-theme-quartz-dark';

  const columns = useMemo(
    () => resolveResultColumns(resultSet, fallbackHeaders),
    [resultSet, fallbackHeaders],
  );
  const rows = useMemo(() => resultSet?.rows ?? fallbackRows ?? [], [resultSet, fallbackRows]);
  // resetVersion intentionally forces a rebuild from the untouched source rows.
  const rowData = useMemo(() => buildResultGridRows(rows), [rows, resetVersion]);
  const columnDefs = useMemo(
    () => buildResultColumnDefs(columns, { editable, dirtyCellKeys }),
    [columns, editable, dirtyCellKeys],
  );

  const gridApiRef = useRef<GridApi<ResultGridRow> | null>(null);
  const [menuTarget, setMenuTarget] = useState<ResultMenuTarget | null>(null);

  // Copies keep the raw cell text so pasted values stay usable elsewhere.
  const copyToClipboard = (text: string) => {
    void navigator.clipboard.writeText(text).then(
      () => toast.success(t(I18N_KEYS.COMMON.RESULT_COPY_SUCCESS)),
      () => undefined,
    );
  };

  const copyRow = (data: ResultGridRow | null) => {
    if (!data) return;
    copyToClipboard(
      columns.map((_, index) => stringifyResultValue(data[getGridFieldKey(index)])).join('\t'),
    );
  };

  // AG Grid Community has no context-menu module, so the menu is built with the
  // shared Radix ContextMenu. The right-clicked cell is captured from the grid's
  // own mouse event to keep column/value resolution on the grid API.
  const handleCellMouseDown = (event: CellMouseDownEvent<ResultGridRow>) => {
    const mouseEvent = event.event as MouseEvent | null | undefined;
    if (mouseEvent?.button !== 2) return;
    const colId = event.column?.getColId() ?? '';
    const columnMatch = /^result-col-(\d+)$/.exec(colId);
    const target: ResultMenuTarget = {
      colIndex: columnMatch ? Number(columnMatch[1]) : null,
      value: columnMatch ? event.value : null,
      rowData: event.data ?? null,
    };
    setMenuTarget(target);
  };

  // Right-clicks outside data cells (headers, empty space) must not open a menu.
  // Capture phase stops Radix's trigger before it opens an empty menu.
  const handleGridContextMenuCapture = (event: ReactMouseEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest('.ag-cell')) return;
    // Keep the app-wide browser menu suppression (App.tsx) intact.
    event.preventDefault();
    event.stopPropagation();
    setMenuTarget(null);
  };

  const filterByValue = (colIndex: number, value: unknown) => {
    const api = gridApiRef.current;
    if (!api) return;
    const colId = `result-col-${colIndex}`;
    const model: FilterModel = value == null
      ? { filterType: 'text', type: 'blank' }
      : typeof value === 'number'
        ? { filterType: 'number', type: 'equals', filter: value }
        : { filterType: 'text', type: 'equals', filter: String(value) };
    api.setColumnFilterModel(colId, model);
    api.onFilterChanged();
  };

  const renderMenu = (target: ResultMenuTarget) => {
    const { colIndex, rowData, value } = target;
    return (
      <ContextMenuContent className="min-w-[190px]">
        {colIndex != null && (
          <ContextMenuItem onSelect={() => copyToClipboard(stringifyResultValue(value))}>
            <Copy className="w-3.5 h-3.5 mr-2" />
            {t(I18N_KEYS.COMMON.RESULT_COPY_CELL)}
          </ContextMenuItem>
        )}
        <ContextMenuItem onSelect={() => copyRow(rowData)}>
          <Rows3 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.COMMON.RESULT_COPY_ROW)}
        </ContextMenuItem>
        {colIndex != null && (
          <>
            <ContextMenuSeparator />
            <ContextMenuItem onSelect={() => filterByValue(colIndex, value)}>
              <Filter className="w-3.5 h-3.5 mr-2" />
              {t(I18N_KEYS.COMMON.RESULT_FILTER_BY_VALUE)}
            </ContextMenuItem>
          </>
        )}
      </ContextMenuContent>
    );
  };

  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>
        <div className="relative min-h-0 flex-1" onContextMenuCapture={handleGridContextMenuCapture}>
          <div className={`workspace-ag-grid result-grid ${agThemeClass} h-full min-h-0 w-full`}>
            <AgGridReact<ResultGridRow>
              rowData={rowData}
              columnDefs={columnDefs}
              rowModelType="clientSide"
              animateRows={false}
              suppressMultiSort
              suppressColumnMoveAnimation
              suppressNoRowsOverlay
              rowHeight={26}
              headerHeight={40}
              tooltipShowDelay={400}
              defaultColDef={{
                sortable: true,
                resizable: true,
                filter: true,
                minWidth: 90,
                width: 150,
              }}
              enableCellTextSelection
              ensureDomOrder
              onGridReady={(params) => {
                gridApiRef.current = params.api;
                onGridApiReady?.(params.api);
              }}
              onCellMouseDown={handleCellMouseDown}
              onCellValueChanged={onCellValueChanged}
              onModelUpdated={() => {
                const api = gridApiRef.current;
                if (api) onViewModelChanged?.(api);
              }}
              onColumnResized={onColumnResized}
              getRowId={(params) => params.data.__rowId}
            />
          </div>

          {rowData.length === 0 ? (
            <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
              <div className="workspace-ag-grid__state-card">{emptyMessage}</div>
            </div>
          ) : null}
        </div>
      </ContextMenuTrigger>

      {menuTarget ? renderMenu(menuTarget) : null}
    </ContextMenu>
  );
}

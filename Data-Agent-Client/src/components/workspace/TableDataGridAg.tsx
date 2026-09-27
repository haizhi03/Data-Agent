import { useEffect, useMemo, useRef } from 'react';
import type {
  CellClassParams,
  CellValueChangedEvent,
  GridApi,
  GridReadyEvent,
  RowClassParams,
  SelectionChangedEvent,
  SortChangedEvent,
} from 'ag-grid-community';
import { AgGridReact } from 'ag-grid-react';
import { useTranslation } from 'react-i18next';
import { useTheme } from '../../hooks/useTheme';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { TableDataResponse } from '../../services/tableData.service';
import {
  buildTableDataGridColumnDefs,
  buildTableDataGridRows,
  buildTransposeTableDataGridColumnDefs,
  buildTransposeTableDataGridRows,
  TABLE_DATA_GRID_ROW_NUMBER_COL_ID,
  type TableDataGridAgRow,
} from './tableDataGridAgUtils';
import type { FindMatch } from './useTableDataFind';
import type { SelectedTableRow } from './tableDataTabShared';

interface TableDataGridAgProps {
  data: TableDataResponse | null;
  loading: boolean;
  error: string | null;
  viewMode: 'grid' | 'transpose';
  orderByColumn: string;
  orderByDirection: 'asc' | 'desc';
  selectedRowIndex: number | null;
  startRow: number;
  editable?: boolean;
  matchedKeys: Set<string>;
  currentMatch: FindMatch | null;
  dirtyCellKeys: Set<string>;
  pendingDeleteRowIndexes: Set<number>;
  pendingInsertRows: TableDataGridAgRow[];
  onRowSelectionChange: (selection: SelectedTableRow | null) => void;
  onGridSortChange: (column: string | null, direction: 'asc' | 'desc' | null) => void;
  onCellValueChanged?: (event: CellValueChangedEvent<TableDataGridAgRow>) => void;
  formatCellValue: (value: unknown) => string;
}

export function TableDataGridAg({
  data,
  loading,
  error,
  viewMode,
  orderByColumn,
  orderByDirection,
  selectedRowIndex,
  startRow,
  editable = false,
  matchedKeys,
  currentMatch,
  dirtyCellKeys,
  pendingDeleteRowIndexes,
  pendingInsertRows,
  onRowSelectionChange,
  onGridSortChange,
  onCellValueChanged,
  formatCellValue,
}: TableDataGridAgProps) {
  const { t } = useTranslation();
  const { theme } = useTheme();
  const gridApiRef = useRef<GridApi<TableDataGridAgRow> | null>(null);
  const agThemeClass = theme === 'light' ? 'ag-theme-quartz' : 'ag-theme-quartz-dark';
  const syncingSelectionRef = useRef(false);
  const syncingSortRef = useRef(false);
  const isTransposeMode = viewMode === 'transpose';
  const isEditable = editable && !isTransposeMode;

  const baseRowData = useMemo(
    () => (isTransposeMode ? buildTransposeTableDataGridRows(data) : buildTableDataGridRows(data, startRow)),
    [data, isTransposeMode, startRow],
  );

  // Staged INSERT rows are appended after the loaded rows.
  const rowData = useMemo(
    () => (isTransposeMode ? baseRowData : [...baseRowData, ...pendingInsertRows]),
    [baseRowData, isTransposeMode, pendingInsertRows],
  );

  const hasRows = rowData.length > 0;

  const currentKey = currentMatch
    ? `${currentMatch.rowIndex}::${currentMatch.colKey}`
    : null;

  // 匹配单元格高亮规则：普通匹配黄色，当前项蓝色，已修改脏单元格蓝色标记
  const findCellClassRules = useMemo(
    () => ({
      'workspace-ag-grid__find-match': (params: CellClassParams<TableDataGridAgRow>) => {
        const node = params.node;
        if (!node?.data || node.data.__rowIndex == null || node.data.__rowIndex < 0) return false;
        const colId = params.colDef.colId ?? '';
        if (!colId || colId === TABLE_DATA_GRID_ROW_NUMBER_COL_ID) return false;
        const key = `${node.data.__rowIndex}::${colId}`;
        return matchedKeys.has(key) && key !== currentKey;
      },
      'workspace-ag-grid__find-current': (params: CellClassParams<TableDataGridAgRow>) => {
        const node = params.node;
        if (!node?.data || node.data.__rowIndex == null || node.data.__rowIndex < 0) return false;
        const colId = params.colDef.colId ?? '';
        if (!colId || colId === TABLE_DATA_GRID_ROW_NUMBER_COL_ID) return false;
        const key = `${node.data.__rowIndex}::${colId}`;
        return key === currentKey;
      },
      'workspace-ag-grid__cell-dirty': (params: CellClassParams<TableDataGridAgRow>) => {
        const node = params.node;
        if (!node?.data || node.data.__rowIndex == null || node.data.__rowIndex < 0) return false;
        const colId = params.colDef.colId ?? '';
        if (!colId || colId === TABLE_DATA_GRID_ROW_NUMBER_COL_ID) return false;
        const key = `${node.data.__rowIndex}::${colId}`;
        return dirtyCellKeys.has(key);
      },
    }),
    [matchedKeys, currentKey, dirtyCellKeys],
  );

  // 行 class 规则：暂存删除行灰显，暂存插入行高亮
  const rowClassRules = useMemo(
    () => ({
      'workspace-ag-grid__row-pending-delete': (params: RowClassParams<TableDataGridAgRow>) => {
        const rowIndex = params.data?.__rowIndex;
        return rowIndex != null && rowIndex >= 0 && pendingDeleteRowIndexes.has(rowIndex);
      },
      'workspace-ag-grid__row-pending-insert': (params: RowClassParams<TableDataGridAgRow>) =>
        params.data?.__pendingInsert != null,
    }),
    [pendingDeleteRowIndexes],
  );

  const columnDefs = useMemo(
    () =>
      isTransposeMode
        ? buildTransposeTableDataGridColumnDefs(
            data,
            startRow,
            t(I18N_KEYS.EXPLORER.TRANSPOSE_FIELD),
            formatCellValue,
          )
        : buildTableDataGridColumnDefs(
            data?.headers ?? [],
            formatCellValue,
            data?.totalCount ?? rowData.length,
            isEditable,
            findCellClassRules,
            pendingDeleteRowIndexes,
          ),
    [data, data?.headers, data?.totalCount, formatCellValue, findCellClassRules, isEditable, isTransposeMode, pendingDeleteRowIndexes, rowData.length, startRow, t],
  );

  useEffect(() => {
    const api = gridApiRef.current;
    if (!api || isTransposeMode) {
      return;
    }

    syncingSortRef.current = true;
    api.applyColumnState({
      defaultState: { sort: null },
      state: orderByColumn
        ? [{ colId: orderByColumn, sort: orderByDirection }]
        : [],
    });
    syncingSortRef.current = false;
  }, [columnDefs, isTransposeMode, orderByColumn, orderByDirection]);

  // 匹配集合或脏标记变化时刷新高亮，并把当前匹配滚动到视口中央
  useEffect(() => {
    const api = gridApiRef.current;
    if (!api) return;
    api.refreshCells({ force: true });
    if (!currentMatch || isTransposeMode) return;
    let targetRowIndex: number | null = null;
    api.forEachNode((node) => {
      if (node.data?.__rowIndex === currentMatch.rowIndex && node.rowIndex != null) {
        targetRowIndex = node.rowIndex;
      }
    });
    if (targetRowIndex != null) {
      api.ensureIndexVisible(targetRowIndex, 'middle');
      api.ensureColumnVisible(currentMatch.colKey, 'middle');
    }
  }, [matchedKeys, currentMatch, dirtyCellKeys, pendingDeleteRowIndexes, isTransposeMode]);

  useEffect(() => {
    const api = gridApiRef.current;
    if (!api) {
      return;
    }

    syncingSelectionRef.current = true;

    if (isTransposeMode) {
      api.deselectAll();
      syncingSelectionRef.current = false;
      return;
    }

    if (selectedRowIndex == null) {
      api.deselectAll();
      syncingSelectionRef.current = false;
      return;
    }

    let matched = false;
    api.forEachNode((node) => {
      const shouldSelect = node.data?.__rowIndex === selectedRowIndex;
      node.setSelected(shouldSelect);
      if (shouldSelect) {
        matched = true;
      }
    });

    if (!matched) {
      api.deselectAll();
    }

    syncingSelectionRef.current = false;
  }, [isTransposeMode, rowData, selectedRowIndex]);

  const handleGridReady = (event: GridReadyEvent<TableDataGridAgRow>) => {
    gridApiRef.current = event.api;

    if (isTransposeMode) {
      event.api.deselectAll();
      return;
    }

    syncingSortRef.current = true;
    event.api.applyColumnState({
      defaultState: { sort: null },
      state: orderByColumn
        ? [{ colId: orderByColumn, sort: orderByDirection }]
        : [],
    });
    syncingSortRef.current = false;

    if (selectedRowIndex != null) {
      syncingSelectionRef.current = true;
      event.api.forEachNode((node) => {
        node.setSelected(node.data?.__rowIndex === selectedRowIndex);
      });
      syncingSelectionRef.current = false;
    }
  };

  const handleSelectionChanged = (event: SelectionChangedEvent<TableDataGridAgRow>) => {
    if (syncingSelectionRef.current || isTransposeMode) {
      return;
    }

    const selectedNode = event.api.getSelectedNodes()[0];
    if (!selectedNode?.data) {
      onRowSelectionChange(null);
      return;
    }

    const selectedData = selectedNode.data;

    // Staged INSERT rows: expose with pendingLocalId so delete/update can target them.
    if (selectedData.__pendingInsert != null) {
      onRowSelectionChange({
        rowIndex: selectedData.__rowIndex ?? -1,
        row: selectedData.__sourceRow ?? [],
        pendingLocalId: selectedData.__pendingInsert,
      });
      return;
    }

    if (selectedData.__rowIndex == null || selectedData.__sourceRow == null) {
      onRowSelectionChange(null);
      return;
    }

    onRowSelectionChange({
      rowIndex: selectedData.__rowIndex,
      row: selectedData.__sourceRow,
    });
  };

  const handleSortChanged = (event: SortChangedEvent<TableDataGridAgRow>) => {
    if (syncingSortRef.current || isTransposeMode) {
      return;
    }

    const sortedColumn = event.api
      .getColumnState()
      .find((column) => column.colId !== TABLE_DATA_GRID_ROW_NUMBER_COL_ID && column.sort);

    onGridSortChange(
      sortedColumn?.colId ?? null,
      (sortedColumn?.sort as 'asc' | 'desc' | undefined) ?? null,
    );
  };

  return (
    <div className="flex-1 min-h-0 overflow-hidden border-t theme-border">
      <div className="relative h-full min-h-0">
        <div className={`workspace-ag-grid ${agThemeClass} h-full min-h-0 w-full`}>
          <AgGridReact<TableDataGridAgRow>
            rowData={rowData}
            columnDefs={columnDefs}
            rowModelType="clientSide"
            animateRows={false}
            suppressMultiSort
            suppressColumnMoveAnimation
            suppressCellFocus={isTransposeMode}
            rowHeight={34}
            headerHeight={36}
            rowSelection={
              isTransposeMode
                ? undefined
                : {
                    mode: 'singleRow',
                    enableClickSelection: true,
                    checkboxes: false,
                  }
            }
            suppressNoRowsOverlay
            defaultColDef={{
              sortable: !isTransposeMode,
              resizable: true,
              minWidth: 120,
              menuTabs: isTransposeMode ? [] : ['generalMenuTab', 'columnsMenuTab'],
            }}
            getRowId={(params) => params.data.__rowId}
            rowClassRules={rowClassRules}
            stopEditingWhenCellsLoseFocus
            onGridReady={handleGridReady}
            onSelectionChanged={handleSelectionChanged}
            onSortChanged={handleSortChanged}
            onCellValueChanged={isEditable ? onCellValueChanged : undefined}
          />
        </div>

        {loading ? (
          <div className="absolute inset-0 flex items-center justify-center bg-[color:var(--bg-main)]/86 px-4">
            <div className="workspace-ag-grid__state-card flex items-center gap-3">
              <div className="h-4 w-4 rounded-full border-2 border-primary border-t-transparent animate-spin" />
              <span>{t(I18N_KEYS.COMMON.LOADING)}...</span>
            </div>
          </div>
        ) : null}

        {!loading && !hasRows && !error && data ? (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <div className="workspace-ag-grid__state-card">
              {t(I18N_KEYS.EXPLORER.NO_DATA)}
            </div>
          </div>
        ) : null}

        {error ? (
          <div className="absolute inset-0 flex items-center justify-center bg-[color:var(--bg-main)]/90 px-4">
            <div className="workspace-ag-grid__state-card workspace-ag-grid__state-card--error">
              {error}
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}

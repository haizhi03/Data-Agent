import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CellValueChangedEvent } from 'ag-grid-community';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { useToast } from '../../hooks/useToast';
import { columnService, type ColumnMetadata } from '../../services/column.service';
import { primaryKeyService } from '../../services/primaryKey.service';
import {
  tableDataService,
  type BatchRowOperation,
  type TableDataResponse,
  type TableRowValuePayload,
} from '../../services/tableData.service';
import type { TableTabMetadata } from '../../types/tab';
import type { TableDataGridAgRow } from './tableDataGridAgUtils';
import type { LoadDataOverrides, SelectedTableRow } from './tableDataTabShared';
import { formatCellValue, toRowMatchValue } from './tableDataTabShared';
import { normalizeEditedCellInput, normalizeInsertInput } from './tableDataInputValues';

type PendingOpType = 'INSERT' | 'UPDATE' | 'DELETE';

interface PendingOp {
  localId: string;
  type: PendingOpType;
  /** Stable key of the row matched by matchValues. */
  matchKey?: string;
  /** INSERT column values. */
  values?: TableRowValuePayload[];
  /** UPDATE SET assignments. */
  setValues?: TableRowValuePayload[];
  /** WHERE matching values for UPDATE / DELETE. */
  matchValues?: TableRowValuePayload[];
}

interface UseTableDataRowActionsArgs {
  metadata: TableTabMetadata;
  isDm: boolean;
  connId: string;
  objectName: string;
  objectType: TableTabMetadata['objectType'];
  catalog: string;
  schema: string;
  isTable: boolean;
  isTransposeMode: boolean;
  data: TableDataResponse | null;
  currentPage: number;
  pageSize: number;
  loadData: (page?: number, overrides?: LoadDataOverrides) => Promise<void>;
}

type LocalSelectedRow = SelectedTableRow & { pendingLocalId?: string };

export function useTableDataRowActions({
  metadata,
  isDm,
  connId,
  objectName,
  objectType,
  catalog,
  schema,
  isTable,
  isTransposeMode,
  data,
  currentPage,
  loadData,
}: UseTableDataRowActionsArgs) {
  const { t } = useTranslation();
  const toast = useToast();

  // Insert form state
  const [isAddingRow, setIsAddingRow] = useState(false);
  const [newRowValues, setNewRowValues] = useState<Record<string, string>>({});
  const [columnMetadata, setColumnMetadata] = useState<ColumnMetadata[]>([]);
  const [loadingColumns, setLoadingColumns] = useState(false);
  const [insertError, setInsertError] = useState<string | null>(null);

  // Selection + pending operations
  const [selectedRow, setSelectedRow] = useState<LocalSelectedRow | null>(null);
  const [pendingOps, setPendingOps] = useState<PendingOp[]>([]);
  const [submittingEdits, setSubmittingEdits] = useState(false);

  // Batch force confirmation dialog state
  const [batchForceConfirmOpen, setBatchForceConfirmOpen] = useState(false);
  const [batchForceMessage, setBatchForceMessage] = useState('');

  const revertingCellRef = useRef(false);
  const primaryKeyColumnsRef = useRef<string[] | null>(null);
  const idCounterRef = useRef(0);
  const pendingOpsRef = useRef<PendingOp[]>([]);
  const currentPageRef = useRef(currentPage);

  useEffect(() => {
    pendingOpsRef.current = pendingOps;
  }, [pendingOps]);

  useEffect(() => {
    currentPageRef.current = currentPage;
  }, [currentPage]);

  const makeLocalId = useCallback(() => {
    idCounterRef.current += 1;
    return `op-${Date.now()}-${idCounterRef.current}`;
  }, []);

  // --- Primary keys -------------------------------------------------------

  const getPrimaryKeyColumns = useCallback(async (): Promise<string[]> => {
    const cached = primaryKeyColumnsRef.current;
    if (cached !== null) {
      return cached;
    }
    let pkColumns: string[];
    try {
      const primaryKeys = await primaryKeyService.listPrimaryKeys(
        connId,
        objectName,
        catalog || undefined,
        schema || undefined,
      );
      pkColumns =
        primaryKeys?.length && primaryKeys[0]?.columnNames?.length
          ? primaryKeys[0].columnNames
          : [];
    } catch {
      pkColumns = [];
    }
    primaryKeyColumnsRef.current = pkColumns;
    return pkColumns;
  }, [catalog, connId, objectName, schema]);

  // Build WHERE pairs for a row. Editing a primary key column itself still
  // targets the right row because callers pass the pre-edit source row.
  const buildMatchValues = useCallback(
    (sourceRow: unknown[], pkColumns: string[], headers: string[]): TableRowValuePayload[] => {
      const whereColumns = pkColumns.length > 0 ? pkColumns : headers;
      return whereColumns
        .map((column) => {
          const columnIndex = headers.indexOf(column);
          if (columnIndex === -1) {
            return null;
          }
          return { columnName: column, value: toRowMatchValue(sourceRow[columnIndex]) };
        })
        .filter((entry): entry is TableRowValuePayload => entry != null);
    },
    [],
  );

  const getMatchKey = useCallback((matchValues: TableRowValuePayload[]): string => {
    return matchValues
      .map((entry) => {
        const value = entry.value == null ? '<null>' : String(entry.value);
        return `${entry.columnName}=${value}`;
      })
      .sort()
      .join('|');
  }, []);

  // Locate a row on the currently loaded page by its match values.
  const findRowIndexByMatch = useCallback(
    (currentData: TableDataResponse, matchValues: TableRowValuePayload[]): number | null => {
      for (let rowIndex = 0; rowIndex < currentData.rows.length; rowIndex++) {
        const row = currentData.rows[rowIndex];
        let allMatch = true;
        for (const match of matchValues) {
          const columnIndex = currentData.headers.indexOf(match.columnName);
          if (columnIndex === -1) {
            allMatch = false;
            break;
          }
          if (toRowMatchValue(row[columnIndex]) !== toRowMatchValue(match.value)) {
            allMatch = false;
            break;
          }
        }
        if (allMatch) {
          return rowIndex;
        }
      }
      return null;
    },
    [],
  );

  // --- Derived visual state ----------------------------------------------

  // Dirty (edited) cells on the current page: rowIndex::colId
  const dirtyCellKeys = useMemo(() => {
    const set = new Set<string>();
    if (data) {
      pendingOps.forEach((op) => {
        if (op.type === 'UPDATE' && op.matchValues && op.setValues) {
          const rowIndex = findRowIndexByMatch(data, op.matchValues);
          if (rowIndex != null) {
            op.setValues.forEach((entry) => set.add(`${rowIndex}::${entry.columnName}`));
          }
        }
      });
    }
    return set;
  }, [data, findRowIndexByMatch, pendingOps]);

  // Rows staged for deletion on the current page.
  const pendingDeleteRowIndexes = useMemo(() => {
    const set = new Set<number>();
    if (data) {
      pendingOps.forEach((op) => {
        if (op.type === 'DELETE' && op.matchValues) {
          const rowIndex = findRowIndexByMatch(data, op.matchValues);
          if (rowIndex != null) {
            set.add(rowIndex);
          }
        }
      });
    }
    return set;
  }, [data, findRowIndexByMatch, pendingOps]);

  // Staged INSERT rows, rendered as temporary grid rows.
  const pendingInsertRows = useMemo<TableDataGridAgRow[]>(() => {
    if (!data) {
      return [];
    }
    return pendingOps
      .filter(
        (op): op is PendingOp & { type: 'INSERT'; values: TableRowValuePayload[] } =>
          op.type === 'INSERT' && !!op.values,
      )
      .map((op, index) => {
        const mapped: TableDataGridAgRow = {
          __rowId: `pending-insert-${op.localId}`,
          __rowIndex: -1000 - index,
          __rowNumber: -(index + 1),
          __pendingInsert: op.localId,
          __sourceRow: data.headers.map(
            (header) => op.values.find((entry) => entry.columnName === header)?.value ?? null,
          ),
        };
        data.headers.forEach((header, colIndex) => {
          mapped[`__col_${colIndex}`] =
            op.values.find((entry) => entry.columnName === header)?.value ?? null;
        });
        return mapped;
      });
  }, [data, pendingOps]);

  // --- Reset when switching table/view -----------------------------------

  const resetRowState = useCallback(() => {
    setSelectedRow(null);
    setIsAddingRow(false);
    setNewRowValues({});
    setInsertError(null);
    setPendingOps([]);
    primaryKeyColumnsRef.current = null;
  }, []);

  useEffect(() => {
    resetRowState();
  }, [catalog, objectName, objectType, resetRowState, schema]);

  useEffect(() => {
    if (isTransposeMode) {
      resetRowState();
    }
  }, [isTransposeMode, resetRowState]);

  // --- Selection ----------------------------------------------------------

  const handleGridSelectionChange = useCallback(
    (selection: LocalSelectedRow | null) => {
      if (isTransposeMode) {
        setSelectedRow(null);
        return;
      }
      setSelectedRow(selection);
    },
    [isTransposeMode],
  );

  // --- Insert -------------------------------------------------------------

  const handleAddRow = useCallback(() => {
    if (isTransposeMode) {
      toast.warning(t(I18N_KEYS.EXPLORER.TRANSPOSE_READONLY));
      return;
    }
    if (!isTable) {
      toast.warning(t(I18N_KEYS.EXPLORER.VIEW_READONLY));
      return;
    }

    setIsAddingRow(true);
    setNewRowValues({});
    setInsertError(null);
    setLoadingColumns(true);
    setSelectedRow(null);

    columnService
      .listColumns(connId, objectName, catalog || undefined, schema || undefined)
      .then((cols) => setColumnMetadata(cols || []))
      .catch(() => setColumnMetadata([]))
      .finally(() => setLoadingColumns(false));
  }, [catalog, connId, isTable, isTransposeMode, objectName, schema, t, toast]);

  const handleCancelAddRow = useCallback(() => {
    setIsAddingRow(false);
    setNewRowValues({});
    setInsertError(null);
  }, []);

  const handleNewRowValueChange = useCallback((columnName: string, value: string) => {
    setNewRowValues((prev) => ({ ...prev, [columnName]: value }));
    setInsertError(null);
  }, []);

  // Confirm the insert form: stage an INSERT op instead of writing immediately.
  const handleConfirmInsert = useCallback(async () => {
    const editableColumns = columnMetadata.filter((column) => !column.isAutoIncrement);
    if (editableColumns.length === 0) {
      setInsertError('No editable columns');
      return;
    }

    const values: TableRowValuePayload[] = [];
    for (const column of editableColumns) {
      const value = normalizeInsertInput(newRowValues[column.name], isDm);
      const nullable = column.nullable ?? true;

      if (value === null) {
        if (!nullable) {
          setInsertError(`Column ${column.name} is required`);
          return;
        }
        values.push({ columnName: column.name, value: null });
        continue;
      }
      values.push({ columnName: column.name, value });
    }

    setPendingOps((prev) => [...prev, { localId: makeLocalId(), type: 'INSERT', values }]);
    setIsAddingRow(false);
    setNewRowValues({});
    setInsertError(null);
  }, [columnMetadata, isDm, makeLocalId, newRowValues]);

  // --- Delete (staged) ----------------------------------------------------

  const handleDeleteRow = useCallback(() => {
    if (isTransposeMode) {
      toast.warning(t(I18N_KEYS.EXPLORER.TRANSPOSE_READONLY));
      return;
    }
    if (!isTable) {
      toast.warning(t(I18N_KEYS.EXPLORER.VIEW_READONLY));
      return;
    }
    if (!selectedRow) {
      toast.warning(t(I18N_KEYS.EXPLORER.SELECT_ROW_TO_DELETE));
      return;
    }

    // Deleting a staged INSERT row simply removes the staged op.
    if (selectedRow.pendingLocalId) {
      const pendingLocalId = selectedRow.pendingLocalId;
      setPendingOps((prev) => prev.filter((op) => op.localId !== pendingLocalId));
      setSelectedRow(null);
      return;
    }

    const currentData = data;
    if (!currentData) {
      return;
    }

    void (async () => {
      const pkColumns = await getPrimaryKeyColumns();
      const matchValues = buildMatchValues(selectedRow.row, pkColumns, currentData.headers);
      if (matchValues.length === 0) {
        toast.error('Cannot build DELETE: no matching columns');
        return;
      }
      const matchKey = getMatchKey(matchValues);

      setPendingOps((prev) => {
        const existingDelete = prev.find((op) => op.type === 'DELETE' && op.matchKey === matchKey);
        if (existingDelete) {
          // Toggle: cancel the staged deletion.
          return prev.filter((op) => op !== existingDelete);
        }
        // Staging a delete discards staged edits for the same row.
        return [
          ...prev.filter((op) => !(op.type === 'UPDATE' && op.matchKey === matchKey)),
          { localId: makeLocalId(), type: 'DELETE', matchKey, matchValues },
        ];
      });
    })();
  }, [
    buildMatchValues,
    data,
    getMatchKey,
    getPrimaryKeyColumns,
    isTable,
    isTransposeMode,
    makeLocalId,
    selectedRow,
    t,
    toast,
  ]);

  // --- Cell edit (staged UPDATE) -----------------------------------------

  const handleGridCellValueChanged = useCallback(
    (event: CellValueChangedEvent<TableDataGridAgRow>) => {
      if (revertingCellRef.current) {
        return;
      }
      if (!isTable || isTransposeMode || !data) {
        return;
      }

      const colId = event.column.getColId();
      if (data.headers.indexOf(colId) === -1) {
        return;
      }

      const oldValue = event.oldValue;
      const revert = () => {
        revertingCellRef.current = true;
        try {
          event.node.setDataValue(colId, oldValue);
        } finally {
          revertingCellRef.current = false;
        }
      };

      const newValue = normalizeEditedCellInput(event.newValue, oldValue, isDm);

      // AG Grid fires on !== comparisons; skip number 5 vs string "5" style non-changes.
      if (
        newValue === oldValue
        || (typeof oldValue === 'number' && formatCellValue(newValue) === formatCellValue(oldValue))
      ) {
        revert();
        return;
      }

      // Editing a staged INSERT row updates the INSERT values.
      const pendingInsertId = event.data?.__pendingInsert;
      if (pendingInsertId) {
        setPendingOps((prev) =>
          prev.map((op) => {
            if (op.localId !== pendingInsertId) {
              return op;
            }
            const values = (op.values ?? []).filter((entry) => entry.columnName !== colId);
            values.push({ columnName: colId, value: newValue });
            return { ...op, values };
          }),
        );
        return;
      }

      const sourceRow = event.data?.__sourceRow;
      if (!sourceRow) {
        revert();
        return;
      }

      const columnIndex = data.headers.indexOf(colId);
      const originalValue = sourceRow[columnIndex];
      const equalsOriginal =
        newValue === originalValue
        || (typeof originalValue === 'number'
          && formatCellValue(newValue) === formatCellValue(originalValue));

      void (async () => {
        const pkColumns = await getPrimaryKeyColumns();
        const matchValues = buildMatchValues(sourceRow, pkColumns, data.headers);
        if (matchValues.length === 0) {
          toast.error('Cannot build UPDATE: no matching columns');
          revert();
          return;
        }
        const matchKey = getMatchKey(matchValues);

        // Rows staged for deletion are not editable.
        const hasDelete = pendingOpsRef.current.some(
          (op) => op.type === 'DELETE' && op.matchKey === matchKey,
        );
        if (hasDelete) {
          revert();
          return;
        }

        if (equalsOriginal) {
          // Value returned to original: drop this column from the staged UPDATE,
          // removing the whole op when no edits remain.
          setPendingOps((prev) => {
            const existing = prev.find((op) => op.type === 'UPDATE' && op.matchKey === matchKey);
            if (!existing) {
              return prev;
            }
            const setValues = (existing.setValues ?? []).filter((entry) => entry.columnName !== colId);
            if (setValues.length === 0) {
              return prev.filter((op) => op !== existing);
            }
            return prev.map((op) => (op === existing ? { ...op, setValues } : op));
          });
          return;
        }

        setPendingOps((prev) => {
          const existing = prev.find((op) => op.type === 'UPDATE' && op.matchKey === matchKey);
          if (existing) {
            const setValues = (existing.setValues ?? []).filter((entry) => entry.columnName !== colId);
            setValues.push({ columnName: colId, value: newValue });
            return prev.map((op) => (op === existing ? { ...op, setValues } : op));
          }
          return [
            ...prev,
            {
              localId: makeLocalId(),
              type: 'UPDATE',
              matchKey,
              matchValues,
              setValues: [{ columnName: colId, value: newValue }],
            },
          ];
        });
      })();
    },
    [
      buildMatchValues,
      data,
      getMatchKey,
      getPrimaryKeyColumns,
      isDm,
      isTable,
      isTransposeMode,
      makeLocalId,
      toast,
    ],
  );

  // --- Submit / revert ----------------------------------------------------

  const submitPendingOps = useCallback(
    async (force: boolean): Promise<boolean> => {
      const ops = pendingOpsRef.current;
      if (ops.length === 0) {
        return true;
      }

      setSubmittingEdits(true);
      try {
        const operations: BatchRowOperation[] = ops.map((op) => {
          if (op.type === 'INSERT') {
            return { type: 'INSERT', values: op.values };
          }
          if (op.type === 'UPDATE') {
            return { type: 'UPDATE', setValues: op.setValues, matchValues: op.matchValues };
          }
          return { type: 'DELETE', matchValues: op.matchValues };
        });

        const result = await tableDataService.batchRows({
          connectionId: metadata.connectionId,
          tableName: objectName,
          catalog: catalog || undefined,
          schema: schema || undefined,
          operations,
          force,
        });

        if (result.success) {
          toast.success(t(I18N_KEYS.EXPLORER.BATCH_SUBMIT_SUCCESS, { count: result.total }));
          setPendingOps([]);
          await loadData(currentPageRef.current);
          return true;
        }

        if (result.requiresForce) {
          setBatchForceMessage(result.errorMessage ?? '');
          setBatchForceConfirmOpen(true);
          return false;
        }

        const failedIndex = result.failedAtIndex != null ? result.failedAtIndex + 1 : '?';
        toast.error(
          t(I18N_KEYS.EXPLORER.BATCH_SUBMIT_FAILED_ITEM, {
            index: failedIndex,
            message: result.errorMessage ?? '',
          }),
        );
        return false;
      } catch (err) {
        toast.error((err as Error).message);
        return false;
      } finally {
        setSubmittingEdits(false);
      }
    },
    [catalog, loadData, metadata.connectionId, objectName, schema, t, toast],
  );

  const closeBatchForceConfirm = useCallback(() => {
    setBatchForceConfirmOpen(false);
  }, []);

  const handleConfirmBatchForce = useCallback(async () => {
    setBatchForceConfirmOpen(false);
    await submitPendingOps(true);
  }, [submitPendingOps]);

  // Discard all staged operations and restore the grid from the database.
  const revertPendingOps = useCallback(async () => {
    setPendingOps([]);
    await loadData(currentPageRef.current);
  }, [loadData]);

  return {
    // Insert form
    isAddingRow,
    newRowValues,
    columnMetadata,
    loadingColumns,
    insertError,
    // Selection
    selectedRowIndex: selectedRow?.rowIndex ?? null,
    hasSelectedRow: selectedRow != null,
    // Pending state
    hasPendingOps: pendingOps.length > 0,
    pendingCount: pendingOps.length,
    submittingEdits,
    dirtyCellKeys,
    pendingDeleteRowIndexes,
    pendingInsertRows,
    // Batch force dialog
    batchForceConfirmOpen,
    batchForceMessage,
    closeBatchForceConfirm,
    handleConfirmBatchForce,
    // Handlers
    handleGridSelectionChange,
    handleGridCellValueChanged,
    handleAddRow,
    handleCancelAddRow,
    handleNewRowValueChange,
    handleConfirmInsert,
    handleDeleteRow,
    submitPendingOps,
    revertPendingOps,
  };
}

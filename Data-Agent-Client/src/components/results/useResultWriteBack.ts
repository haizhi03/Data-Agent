import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CellValueChangedEvent } from 'ag-grid-community';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { useToast } from '../../hooks/useToast';
import {
  tableDataService,
  type BatchRowOperation,
  type TableRowValuePayload,
} from '../../services/tableData.service';
import type { ExecuteSqlColumn, ExecuteSqlWriteTarget } from '../../types/sql';
import { normalizeEditedCellInput } from '../workspace/tableDataInputValues';
import { isSameCellValue } from '../workspace/tableDataTabShared';
import { useBatchRowOpsSubmit } from '../workspace/useBatchRowOpsSubmit';
import {
  buildResultColumnIndexMap,
  buildResultMatchKey,
  buildResultMatchValues,
  normalizeResultColumnKey,
  type ResultGridRow,
} from './resultGridUtils';

/** Staged cell edit for an editable result row (same payload shape as the table data editor). */
export interface ResultPendingUpdate {
  rowIndex: number;
  matchKey: string;
  matchValues: TableRowValuePayload[];
  setValues: TableRowValuePayload[];
}

interface UseResultWriteBackArgs {
  /** Identity of the displayed result page; staged edits and the preview reset with it. */
  pageKey: unknown;
  connectionId: number | null;
  writeTarget: ExecuteSqlWriteTarget | null;
  columns: ExecuteSqlColumn[];
  /** Displayed rows, including load-more pages and previously applied edits. */
  rows: unknown[][];
  isDm: boolean;
  /**
   * Applies committed values to the caller's latest rows. Passing an updater (not
   * a row snapshot) keeps a load-more result that landed during the commit.
   */
  onRowsApplied: (apply: (rows: unknown[][]) => unknown[][]) => void;
}

/** Staged operations, backend preview and commit flow for editable SQL results. */
export function useResultWriteBack({
  pageKey,
  connectionId,
  writeTarget,
  columns,
  rows,
  isDm,
  onRowsApplied,
}: UseResultWriteBackArgs) {
  const { t } = useTranslation();
  const toast = useToast();

  // Editability and the row-to-table mapping come from the backend: it also
  // resolves which columns identify a row (primary key, or all selected columns).
  // A backend that predates matchColumns only ships the primary key.
  const matchColumns = useMemo(
    () => writeTarget?.matchColumns ?? writeTarget?.primaryKeyColumns ?? [],
    [writeTarget],
  );
  // Without a matching rule every edit would be rejected and snap back, so such
  // a result stays read-only instead.
  const editable = writeTarget?.editable === true
    && connectionId != null
    && matchColumns.length > 0;
  const columnIndexByKey = useMemo(() => buildResultColumnIndexMap(columns), [columns]);

  const [pendingUpdates, setPendingUpdates] = useState<ResultPendingUpdate[]>([]);
  const [previewSqlOpen, setPreviewSqlOpen] = useState(false);
  const [previewStatements, setPreviewStatements] = useState<string[]>([]);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [gridResetVersion, setGridResetVersion] = useState(0);
  const pendingUpdatesRef = useRef<ResultPendingUpdate[]>([]);
  const previewPlanHashRef = useRef<string | null>(null);
  const revertingCellRef = useRef(false);
  // Page that owns the staged edits; a commit or preview that outlives it must
  // not touch the next page's state.
  const pageKeyRef = useRef(pageKey);
  useEffect(() => {
    pageKeyRef.current = pageKey;
  }, [pageKey]);
  /** Staged snapshot handed to the in-flight commit, applied on success. */
  const submittedRef = useRef<{ pageKey: unknown; updates: ResultPendingUpdate[] } | null>(null);
  /** Bumped to discard responses that belong to an older page or preview. */
  const previewRequestRef = useRef(0);

  // Staged edits belong to one result page.
  useEffect(() => {
    previewRequestRef.current += 1;
    setPendingUpdates([]);
    setPreviewSqlOpen(false);
    setPreviewStatements([]);
    setPreviewError(null);
    previewPlanHashRef.current = null;
  }, [pageKey]);

  useEffect(() => {
    // The preview hash is kept across edits on purpose: EXECUTE then rejects the
    // stale plan instead of silently running changes the user never previewed.
    pendingUpdatesRef.current = pendingUpdates;
  }, [pendingUpdates]);

  const pendingCellCount = useMemo(
    () => pendingUpdates.reduce((count, update) => count + update.setValues.length, 0),
    [pendingUpdates],
  );

  const dirtyCellKeys = useMemo(() => {
    const keys = new Set<string>();
    if (pendingUpdates.length === 0) {
      return keys;
    }
    pendingUpdates.forEach((update) => {
      update.setValues.forEach((entry) => {
        const columnIndex = columnIndexByKey.get(normalizeResultColumnKey(entry.columnName));
        if (columnIndex != null) {
          keys.add(`${update.rowIndex}::${columnIndex}`);
        }
      });
    });
    return keys;
  }, [pendingUpdates, columnIndexByKey]);

  // Applied values become the new baseline so later edits match the updated row.
  // The caller's rows are updated through an updater so a load-more response that
  // arrived while the commit was in flight is not overwritten.
  const applyUpdates = useCallback(
    (updates: ResultPendingUpdate[]) => (currentRows: unknown[][]) => {
      const nextRows = currentRows.slice();
      updates.forEach((update) => {
        const row = currentRows[update.rowIndex];
        if (!row) {
          return;
        }
        const nextRow = row.slice();
        update.setValues.forEach((entry) => {
          const columnIndex = columnIndexByKey.get(normalizeResultColumnKey(entry.columnName));
          if (columnIndex != null) {
            nextRow[columnIndex] = entry.value;
          }
        });
        nextRows[update.rowIndex] = nextRow;
      });
      return nextRows;
    },
    [columnIndexByKey],
  );

  const handleSubmitSuccess = useCallback(() => {
    const submitted = submittedRef.current;
    submittedRef.current = null;
    // The page changed while the request was running: its staged edits and
    // baseline are not ours to touch.
    if (!submitted || submitted.pageKey !== pageKeyRef.current) {
      return;
    }

    const applied = submitted.updates;
    if (applied.length > 0) {
      const apply = applyUpdates(applied);
      onRowsApplied(apply);
      // Edits staged while the request was in flight stay staged; values that now
      // equal the committed baseline are dropped so nothing is written twice.
      setPendingUpdates((prev) => pruneCommittedUpdates(prev, apply(rows), columnIndexByKey));
    } else {
      setPendingUpdates([]);
    }
    // The preview described the committed snapshot; a later edit needs a new one.
    previewPlanHashRef.current = null;
    setGridResetVersion((version) => version + 1);
  }, [applyUpdates, columnIndexByKey, onRowsApplied, rows]);

  const {
    submitting,
    forceConfirmOpen,
    forceMessage,
    submit,
    closeForceConfirm,
  } = useBatchRowOpsSubmit({
    connectionId,
    tableName: writeTarget?.table ?? '',
    catalog: writeTarget?.catalog ?? undefined,
    schema: writeTarget?.schema ?? undefined,
    onSuccess: handleSubmitSuccess,
  });

  // The preview SQL is produced by the backend, from the same builder EXECUTE uses.
  const handleOpenPreviewSql = useCallback(async () => {
    const updates = pendingUpdatesRef.current;
    if (updates.length === 0 || connectionId == null || !writeTarget?.table) {
      return;
    }
    const requestId = ++previewRequestRef.current;
    setPreviewSqlOpen(true);
    setPreviewLoading(true);
    setPreviewStatements([]);
    setPreviewError(null);
    try {
      const plan = await tableDataService.batchRows({
        connectionId,
        tableName: writeTarget.table,
        catalog: writeTarget.catalog ?? undefined,
        schema: writeTarget.schema ?? undefined,
        operations: updates.map(toOperation),
        mode: 'PLAN',
      });
      // A response for another page must not overwrite this page's preview.
      if (previewRequestRef.current !== requestId) {
        return;
      }
      if (!plan.success) {
        const message = plan.errorMessage ?? t(I18N_KEYS.COMMON.RESULT_PREVIEW_FAILED);
        setPreviewError(message);
        toast.error(message);
        return;
      }
      setPreviewStatements(plan.statements ?? []);
      previewPlanHashRef.current = plan.planHash ?? null;
    } catch (err) {
      if (previewRequestRef.current !== requestId) {
        return;
      }
      // Surface the backend message instead of the generic "Request failed with status code".
      const apiMessage = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      const message = apiMessage ?? (err as Error).message;
      setPreviewError(message);
      toast.error(message);
    } finally {
      if (previewRequestRef.current === requestId) {
        setPreviewLoading(false);
      }
    }
  }, [connectionId, t, toast, writeTarget]);

  const previewSql = useMemo(() => previewStatements.join('\n\n'), [previewStatements]);

  const handleSubmitEdits = useCallback(
    async (force = false): Promise<boolean> => {
      const updates = pendingUpdatesRef.current;
      if (updates.length === 0) {
        return true;
      }
      // Snapshot the exact operations being committed: edits made while the
      // request is in flight must stay staged instead of being applied locally.
      submittedRef.current = { pageKey: pageKeyRef.current, updates };
      const ok = await submit(updates.map(toOperation), force, previewPlanHashRef.current);
      if (!ok) {
        submittedRef.current = null;
      }
      return ok;
    },
    [submit],
  );

  const handleConfirmForce = useCallback(async () => {
    closeForceConfirm();
    await handleSubmitEdits(true);
  }, [closeForceConfirm, handleSubmitEdits]);

  const handleDiscardEdits = useCallback(() => {
    setPendingUpdates([]);
    setGridResetVersion((version) => version + 1);
  }, []);

  // Stage cell edits as UPDATE operations, reusing the table editor's payload
  // shape (setValues + primary-key matchValues) so Apply can use batchRows later.
  const stageCellEdit = useCallback(
    (event: CellValueChangedEvent<ResultGridRow>) => {
      if (revertingCellRef.current) {
        return;
      }
      if (!editable) {
        return;
      }

      const colId = event.column.getColId();
      const columnMatch = /^result-col-(\d+)$/.exec(colId);
      const rowIndex = event.data?.__rowIndex;
      const sourceRow = event.data?.__sourceRow;
      if (!columnMatch || rowIndex == null || !sourceRow) {
        return;
      }

      const columnIndex = Number(columnMatch[1]);
      const column = columns[columnIndex];
      const columnName = column?.name || column?.label;
      if (!columnName) {
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
      if (isSameCellValue(newValue, oldValue)) {
        revert();
        return;
      }

      const matchValues = buildResultMatchValues(columns, sourceRow, matchColumns);
      if (matchValues.length === 0) {
        revert();
        return;
      }
      const matchKey = buildResultMatchKey(matchValues);

      const originalValue = sourceRow[columnIndex];
      const equalsOriginal = isSameCellValue(newValue, originalValue);

      setPendingUpdates((prev) => {
        const existing = prev.find((update) => update.matchKey === matchKey);

        if (equalsOriginal) {
          // Value returned to the database value: drop this column from the staged UPDATE.
          if (!existing) {
            return prev;
          }
          const setValues = existing.setValues.filter((entry) => entry.columnName !== columnName);
          if (setValues.length === 0) {
            return prev.filter((update) => update !== existing);
          }
          return prev.map((update) => (update === existing ? { ...update, setValues } : update));
        }

        if (existing) {
          const setValues = existing.setValues.filter((entry) => entry.columnName !== columnName);
          setValues.push({ columnName, value: newValue });
          return prev.map((update) => (update === existing ? { ...update, setValues } : update));
        }

        return [
          ...prev,
          {
            rowIndex,
            matchKey,
            matchValues,
            setValues: [{ columnName, value: newValue }],
          },
        ];
      });
    },
    [columns, editable, isDm, matchColumns],
  );

  return {
    editable,
    pendingCellCount,
    dirtyCellKeys,
    gridResetVersion,
    previewSqlOpen,
    setPreviewSqlOpen,
    previewStatements,
    previewError,
    previewLoading,
    previewSql,
    submitting,
    forceConfirmOpen,
    forceMessage,
    closeForceConfirm,
    stageCellEdit,
    handleOpenPreviewSql,
    handleSubmitEdits,
    handleConfirmForce,
    handleDiscardEdits,
  };
}

function toOperation(update: ResultPendingUpdate): BatchRowOperation {
  return {
    type: 'UPDATE',
    setValues: update.setValues,
    matchValues: update.matchValues,
  };
}

/**
 * Drop staged values that now match the committed baseline, so edits made during
 * an in-flight commit are neither lost nor written twice.
 */
function pruneCommittedUpdates(
  updates: ResultPendingUpdate[],
  rows: unknown[][],
  columnIndexByKey: Map<string, number>,
): ResultPendingUpdate[] {
  const remaining: ResultPendingUpdate[] = [];
  updates.forEach((update) => {
    const row = rows[update.rowIndex];
    if (!row) {
      remaining.push(update);
      return;
    }
    const setValues = update.setValues.filter((entry) => {
      const columnIndex = columnIndexByKey.get(normalizeResultColumnKey(entry.columnName));
      return columnIndex == null || !isSameCellValue(row[columnIndex], entry.value);
    });
    if (setValues.length === 0) {
      return;
    }
    remaining.push(setValues.length === update.setValues.length ? update : { ...update, setValues });
  });
  return remaining;
}

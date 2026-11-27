import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ColumnResizedEvent, GridApi } from 'ag-grid-community';
import { Database, Download, Trash2, Maximize2, Minimize2, ChevronDown, ArrowUp, ArrowLeftToLine, ArrowRightToLine, FileCode2, Undo2, Loader2, AlertCircle, Copy, SlidersHorizontal, Sparkles } from 'lucide-react';
import { cn } from '../../lib/utils';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { EXPORT_FILE_EXTENSIONS } from '../../constants/fileTypes';
import type { ExecuteSqlMessage, ExecuteSqlResponse } from '../../types/sql';
import { SqlCodeBlock } from '../common/SqlCodeBlock';
import {
  downloadResultSet,
  resolveResultTableIdentity,
  RESULT_EXPORT_FORMATS,
  type ResultExportFormat,
  type ResultTableIdentity,
} from '../../utils/exportResult';
import { downloadFile } from '../../utils/download';
import { exportFileService } from '../../services/exportFile.service';
import { formatDmExecutionPlan } from '../../utils/dmExecutionPlan';
import { sqlExecutionService } from '../../services/sqlExecution.service';
import { aiService } from '../../services/ai.service';
import { useUiStore } from '../../store/uiStore';
import { useDbTypeStore } from '../../store/dbTypeStore';
import { resolveProfile } from '../../utils/sqlGenerator';
import { useToast } from '../../hooks/useToast';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';
import { Button } from '../ui/Button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import {
  Panel,
  Group as PanelGroup,
  Separator as PanelResizeHandle
} from 'react-resizable-panels';
import { classifySqlError, extractResultSet, statementStatusColor, statementStatusLabel } from './statementStatus';
import { StatementResultRow } from './StatementResultRow';
import { ResultGrid } from './ResultGrid';
import {
  buildResultColumnSignature,
  collectQueryResultSets,
  readResultColumnPreferences,
  resolveResultColumns,
  writeResultColumnPreferences,
  type ResultColumnPreference,
  type ResultColumnPreferences,
  type ResultGridRow,
} from './resultGridUtils';
import { useResultWriteBack } from './useResultWriteBack';
import { getGridFieldKey } from '../../utils/agGrid';

interface ResultsPanelProps {
  isVisible: boolean;
  onClose: () => void;
  executeResult?: ExecuteSqlResponse | null;
  scriptResults?: ExecuteSqlResponse[];
  cancelStatus?: string | null;
  isRunning?: boolean;
  children: React.ReactNode;
}

/** Rows loaded for one result page: "load more" output or committed cell edits. */
interface ResultPageRows {
  /** Page the rows were loaded for; rows never leak into another page. */
  pageKey: unknown;
  rows: unknown[][];
  truncated: boolean;
}

export function ResultsPanel({ isVisible, onClose, executeResult: singleResult, scriptResults, cancelStatus, isRunning = false, children }: ResultsPanelProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const [activeTab, setActiveTab] = useState<'result' | 'output'>('output');
  const [selectedStatement, setSelectedStatement] = useState(0);
  const [activeResultSetIndex, setActiveResultSetIndex] = useState(0);
  const [maximized, setMaximized] = useState(false);
  const executeResult = scriptResults?.[selectedStatement] ?? singleResult;

  useEffect(() => setSelectedStatement(0), [scriptResults]);
  useEffect(() => {
    setActiveResultSetIndex(0);
  }, [executeResult]);

  const scriptSummary = scriptResults && scriptResults.length > 1
    ? `${scriptResults.length} statements · ${scriptResults.filter(r => r.success).length} ok · ${scriptResults.filter(r => !r.success).length} failed`
    : null;

  const { headers: resultHeaders, rows: resultRows } = useMemo(
    () => extractResultSet(executeResult),
    [executeResult],
  );
  const resultSets = useMemo(() => collectQueryResultSets(executeResult), [executeResult]);
  const activeResultSet = resultSets[activeResultSetIndex] ?? null;
  const resultPageKey = useMemo(
    () => ({ executeResult, resultSetIndex: activeResultSetIndex }),
    [executeResult, activeResultSetIndex],
  );
  const [pageRowsState, setPageRowsState] = useState<ResultPageRows | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  // Rows belong to the page they were loaded for; stale entries are ignored so a
  // page switch never renders the previous page's rows with the new columns.
  const pageRows = pageRowsState?.pageKey === resultPageKey ? pageRowsState : null;
  const activeHeaders = useMemo(
    () => activeResultSet?.columns?.map((column) => column.label || column.name || '').filter(Boolean)
      ?? resultHeaders,
    [activeResultSet, resultHeaders],
  );
  const activeRows = pageRows?.rows ?? activeResultSet?.rows ?? resultRows;
  // Feed the grid rows loaded for this page ("load more" / committed edits) while
  // keeping the original columns.
  const gridResultSet = useMemo(() => {
    if (!activeResultSet || !pageRows) {
      return activeResultSet;
    }
    return {
      ...activeResultSet,
      rows: pageRows.rows,
      truncated: pageRows.truncated,
    };
  }, [activeResultSet, pageRows]);
  // A new result shape invalidates the snapshot; the grid recaptures it on the next model update.
  useEffect(() => {
    displayedRowsRef.current = null;
  }, [gridResultSet]);
  const sourceTruncated = activeResultSet?.truncated === true || executeResult?.executionInfo?.truncated === true;
  const resultTruncated = pageRows?.truncated ?? sourceTruncated;
  const loadMoreSql = executeResult?.executedSql || executeResult?.originalSql || '';
  const activeColumns = useMemo(
    () => resolveResultColumns(activeResultSet, activeHeaders),
    [activeResultSet, activeHeaders],
  );
  const activeConnectionId = executeResult?.connectionId ?? null;
  // Editability and the row-to-table mapping come from the backend.
  const resultWriteTarget = activeResultSet?.writeTarget ?? null;
  const resultReadonlyReason = resultWriteTarget?.editable === true && activeConnectionId != null
    ? undefined
    : activeConnectionId == null
      ? 'NO_CONNECTION'
      : resultWriteTarget?.reason ?? undefined;
  const resultGridApiRef = useRef<GridApi<ResultGridRow> | null>(null);
  // Last filtered/sorted rows seen in the grid, so export stays correct even
  // when the grid is unmounted (e.g. the output tab is active).
  const displayedRowsRef = useRef<unknown[][] | null>(null);
  const isDm = (executeResult?.dbType ?? '').toUpperCase() === 'DM';
  const supportedDbTypes = useDbTypeStore((state) => state.supportedDbTypes);
  const requestAiCompose = useUiStore((state) => state.requestAiCompose);

  // A load-more request belongs to the page that started it; drop the previous
  // page's rows so large results are not kept alive.
  useEffect(() => {
    setPageRowsState(null);
    setLoadingMore(false);
  }, [resultPageKey]);

  const handleResultRowsApplied = useCallback((apply: (rows: unknown[][]) => unknown[][]) => {
    setPageRowsState((prev) => {
      const samePage = prev?.pageKey === resultPageKey;
      const baseRows = samePage ? prev.rows : activeRows;
      return {
        pageKey: resultPageKey,
        rows: apply(baseRows),
        truncated: samePage ? prev.truncated : sourceTruncated,
      };
    });
  }, [activeRows, resultPageKey, sourceTruncated]);

  // Staging, previewing and committing result edits all live in the hook; this
  // component only renders that state.
  const {
    editable: resultEditable,
    pendingCellCount,
    dirtyCellKeys: dirtyResultCellKeys,
    gridResetVersion,
    previewSqlOpen,
    setPreviewSqlOpen,
    previewStatements,
    previewError,
    previewLoading,
    previewSql,
    submitting: submittingResultEdits,
    forceConfirmOpen: resultForceConfirmOpen,
    forceMessage: resultForceMessage,
    closeForceConfirm: closeResultForceConfirm,
    stageCellEdit: handleResultCellValueChanged,
    handleOpenPreviewSql,
    handleSubmitEdits: handleSubmitResultEdits,
    handleConfirmForce: handleConfirmResultForce,
    handleDiscardEdits: handleDiscardResultEdits,
  } = useResultWriteBack({
    pageKey: resultPageKey,
    connectionId: activeConnectionId,
    writeTarget: resultWriteTarget,
    columns: activeColumns,
    rows: activeRows,
    isDm,
    onRowsApplied: handleResultRowsApplied,
  });

  // Column display preferences (visibility / pinning / width) survive re-runs of
  // the same result shape; applied through the grid's own column state API.
  const columnSignature = useMemo(() => buildResultColumnSignature(activeColumns), [activeColumns]);
  const [columnPrefs, setColumnPrefs] = useState<ResultColumnPreferences>({});
  const columnPrefsRef = useRef<ResultColumnPreferences>({});

  const applyColumnPreferences = useCallback((prefs: ResultColumnPreferences) => {
    const api = resultGridApiRef.current;
    if (!api || api.isDestroyed()) return;
    const state = Object.entries(prefs).map(([colId, pref]) => ({
      colId,
      hide: pref.visible === false,
      pinned: pref.pinned ?? null,
      ...(pref.width != null ? { width: pref.width } : {}),
    }));
    if (state.length > 0) {
      api.applyColumnState({ state });
    }
  }, []);

  useEffect(() => {
    const prefs = readResultColumnPreferences(columnSignature);
    columnPrefsRef.current = prefs;
    setColumnPrefs(prefs);
    const api = resultGridApiRef.current;
    if (api && !api.isDestroyed()) {
      // A different result shape starts from the grid defaults.
      api.resetColumnState();
      applyColumnPreferences(prefs);
    }
  }, [applyColumnPreferences, columnSignature]);

  const updateColumnPreference = useCallback((colId: string, patch: ResultColumnPreference) => {
    const next: ResultColumnPreferences = {
      ...columnPrefsRef.current,
      [colId]: { ...columnPrefsRef.current[colId], ...patch },
    };
    columnPrefsRef.current = next;
    setColumnPrefs(next);
    writeResultColumnPreferences(columnSignature, next);
    const api = resultGridApiRef.current;
    if (api && !api.isDestroyed()) {
      api.applyColumnState({
        state: [{ colId, hide: next[colId].visible === false, pinned: next[colId].pinned ?? null }],
      });
    }
  }, [columnSignature]);

  const resetColumnPreferences = useCallback(() => {
    const next: ResultColumnPreferences = {};
    columnPrefsRef.current = next;
    setColumnPrefs(next);
    writeResultColumnPreferences(columnSignature, next);
    const api = resultGridApiRef.current;
    if (!api || api.isDestroyed()) return;
    api.applyColumnState({
      state: activeColumns.map((_, index) => ({
        colId: `result-col-${index}`,
        hide: false,
        pinned: null,
      })),
    });
  }, [activeColumns, columnSignature]);

  const handleResultColumnResized = useCallback((event: ColumnResizedEvent<ResultGridRow>) => {
    if (!event.finished || event.source !== 'uiColumnResized') return;
    const colId = event.column?.getColId();
    const width = event.column?.getActualWidth();
    if (!colId?.startsWith('result-col-') || !width) return;
    const next: ResultColumnPreferences = {
      ...columnPrefsRef.current,
      [colId]: { ...columnPrefsRef.current[colId], width: Math.round(width) },
    };
    columnPrefsRef.current = next;
    setColumnPrefs(next);
    writeResultColumnPreferences(columnSignature, next);
  }, [columnSignature]);

  const sqlProfile = useMemo(() => {
    const option = supportedDbTypes.find((item) => item.code === executeResult?.dbType);
    return resolveProfile(executeResult?.dbType, option?.identifierQuoteStyle);
  }, [executeResult?.dbType, supportedDbTypes]);

  const isDmExecutionPlan = /^\s*EXPLAIN\b/i.test(executeResult?.executedSql || executeResult?.originalSql || '')
    && activeHeaders.length === 1 && activeHeaders[0] === 'Execution Plan';

  // Determine if Results tab should be shown (SELECT query with data)
  const hasResultTab = !!(
    executeResult?.success &&
    (executeResult?.query || executeResult?.type === 'QUERY' || resultHeaders.length > 0)
  );

  // Can export when we have a result set (headers; rows may be empty)
  const canExport = hasResultTab && !!activeHeaders.length;
  // The backend already resolved the single source table, so the SQL export
  // reuses that verdict instead of re-deriving it from the column metadata.
  const resultExportTable = useMemo<ResultTableIdentity | null>(() => {
    if (resultWriteTarget?.table) {
      return {
        tableName: resultWriteTarget.table,
        catalogName: resultWriteTarget.catalog,
        schemaName: resultWriteTarget.schema,
      };
    }
    return resolveResultTableIdentity(activeColumns);
  }, [activeColumns, resultWriteTarget]);
  // SQL export needs every column to come from the same source table.
  const canExportSql = canExport && !!resultExportTable;

  // Export exactly what the grid shows: current sort order and active filters.
  // When rows are checked, only those rows are exported; otherwise every
  // filtered row is.
  // Export only the columns currently visible in the grid, in table order.
  const visibleColumnIndexes = useMemo(
    () => activeColumns
      .map((_, index) => index)
      .filter((index) => columnPrefs[`result-col-${index}`]?.visible !== false),
    [activeColumns, columnPrefs],
  );

  const captureDisplayedRows = useCallback((api: GridApi<ResultGridRow>) => {
    const rows: unknown[][] = [];
    api.forEachNodeAfterFilterAndSort((node) => {
      const data = node.data;
      if (!data) return;
      rows.push(visibleColumnIndexes.map((index) => data[getGridFieldKey(index)]));
    });
    displayedRowsRef.current = rows;
  }, [visibleColumnIndexes]);

  const collectDisplayedRows = (): unknown[][] => {
    const api = resultGridApiRef.current;
    if (!api || api.isDestroyed()) {
      return displayedRowsRef.current ?? activeRows ?? [];
    }
    captureDisplayedRows(api);
    return displayedRowsRef.current ?? activeRows ?? [];
  };

  const handleExport = async (format: ResultExportFormat) => {
    const exportColumns = visibleColumnIndexes.map((index) => activeColumns[index]);
    if (!canExport || exportColumns.length === 0) {
      toast.warning(t(I18N_KEYS.COMMON.EXPORT_NO_DATA));
      return;
    }
    const exportRows = collectDisplayedRows();
    if (exportRows.length === 0) {
      toast.warning(t(I18N_KEYS.COMMON.EXPORT_NO_DATA));
      return;
    }
    try {
      if (format === 'XLSX') {
        // Excel generation reuses the shared server-side export pipeline.
        const payload = await exportFileService.exportRows({
          format,
          headers: exportColumns.map((column, index) => column.label || column.name || `Column ${index + 1}`),
          rows: exportRows,
        });
        const file = await exportFileService.downloadFile(payload.fileId);
        downloadFile(file.blob, payload.filename);
      } else {
        downloadResultSet({
          columns: exportColumns,
          rows: exportRows,
          format,
          profile: sqlProfile,
          table: resultExportTable,
        });
      }
      toast.success(t(I18N_KEYS.COMMON.EXPORT_SUCCESS));
    } catch {
      toast.error(t(I18N_KEYS.COMMON.EXPORT_FAILED));
    }
  };

  // Truncated results: re-run the same SQL with a larger row cap and replace rows.
  const handleLoadMore = async () => {
    if (!loadMoreSql || activeConnectionId == null || loadingMore) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await sqlExecutionService.executeSql({
        connectionId: activeConnectionId,
        databaseName: executeResult?.databaseName ?? undefined,
        schemaName: executeResult?.schemaName ?? undefined,
        sql: loadMoreSql,
        maxRows: Math.max(activeRows.length * 2, activeRows.length + 10000),
      });
      if (!response.success) {
        toast.error(t(I18N_KEYS.COMMON.RESULT_LOAD_MORE_FAILED));
        return;
      }
      setPageRowsState({
        pageKey: resultPageKey,
        rows: response.resultSet?.rows ?? response.rows ?? [],
        truncated: response.resultSet?.truncated === true || response.executionInfo?.truncated === true,
      });
    } catch {
      toast.error(t(I18N_KEYS.COMMON.RESULT_LOAD_MORE_FAILED));
    } finally {
      setLoadingMore(false);
    }
  };

  // Auto-switch to Results tab when SELECT completes
  useEffect(() => {
    if (executeResult) {
      setActiveTab(hasResultTab ? 'result' : 'output');
    }
  }, [executeResult, hasResultTab]);

  const getStatusIndicatorColor = () => {
    if (isRunning) return 'bg-amber-500 animate-pulse';
    return statementStatusColor(executeResult);
  };

  const getStatusText = () => {
    if (isRunning) return t(I18N_KEYS.COMMON.RUNNING);
    return statementStatusLabel(executeResult);
  };

  const outputMessages: ExecuteSqlMessage[] =
    executeResult?.messages?.filter(m => m?.message) ??
    (executeResult?.errorMessage ? [{ level: 'ERROR', message: executeResult.errorMessage }] : []);

  // Structured error card data: first error message carries code / SQLSTATE / detail.
  const primaryErrorMessage = outputMessages.find((m) => m.level === 'ERROR') ?? outputMessages[0] ?? null;
  const errorText = primaryErrorMessage?.message || executeResult?.errorMessage || 'Unknown error';
  // JDBC reports 0 when the driver has no vendor error code.
  const rawErrorCode = primaryErrorMessage?.code ?? null;
  const errorCode = rawErrorCode && rawErrorCode !== '0' ? rawErrorCode : null;
  const errorSqlState = primaryErrorMessage?.sqlState ?? null;
  const errorDetail = primaryErrorMessage?.detail ?? null;
  const extraOutputMessages = outputMessages.filter((m) => m !== primaryErrorMessage);
  const errorCategory = classifySqlError(errorSqlState);
  const errorHint = (() => {
    switch (errorCategory) {
      case 'CONNECTION':
        return t(I18N_KEYS.COMMON.ERROR_HINT_CONNECTION);
      case 'AUTH':
        return t(I18N_KEYS.COMMON.ERROR_HINT_AUTH);
      case 'SYNTAX_OR_OBJECT':
        return t(I18N_KEYS.COMMON.ERROR_HINT_SYNTAX_OR_OBJECT);
      case 'CONSTRAINT':
        return t(I18N_KEYS.COMMON.ERROR_HINT_CONSTRAINT);
      case 'TRANSACTION':
        return t(I18N_KEYS.COMMON.ERROR_HINT_TRANSACTION);
      case 'TIMEOUT':
        return t(I18N_KEYS.COMMON.ERROR_HINT_TIMEOUT);
      case 'DATA':
        return t(I18N_KEYS.COMMON.ERROR_HINT_DATA);
      default:
        return null;
    }
  })();
  const buildErrorInfoText = () =>
    [
      errorText,
      errorSqlState ? `SQLSTATE: ${errorSqlState}` : '',
      errorCode ? `${t(I18N_KEYS.COMMON.ERROR_CODE)}: ${errorCode}` : '',
      errorDetail ?? '',
    ].filter(Boolean).join('\n');

  const copyErrorInfo = () => {
    void navigator.clipboard.writeText(buildErrorInfoText()).then(
      () => toast.success(t(I18N_KEYS.COMMON.RESULT_COPY_SUCCESS)),
      () => undefined,
    );
  };
  // Prefill the existing AI assistant with the failed statement and its error.
  const handleAiFix = async () => {
    try {
      const models = await aiService.getModels();
      if (models.length === 0) {
        toast.error(t(I18N_KEYS.COMMON.RESULT_AI_FIX_NO_MODEL));
        return;
      }
      requestAiCompose(
        t(I18N_KEYS.COMMON.RESULT_AI_FIX_PROMPT, {
          dbType: executeResult?.dbType ?? '-',
          database: executeResult?.databaseName ?? '-',
          schema: executeResult?.schemaName ?? '-',
          sql: executeResult?.originalSql || executeResult?.executedSql || '-',
          error: buildErrorInfoText(),
        }),
      );
    } catch (err) {
      toast.error((err as Error).message);
    }
  };

  const getMessageColor = (level?: string | null) => {
    if (level === 'ERROR') return 'text-red-400';
    if (level === 'WARN') return 'text-amber-400';
    return 'theme-text-secondary';
  };
  const formatTimestamp = (ts?: number | null) => {
    if (!ts) return '--';
    const d = new Date(ts);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  };

  const executionStartTime = executeResult?.executionInfo?.startTime ?? null;
  const durationMs = executeResult?.executionInfo?.durationMs ?? executeResult?.executionTimeMs ?? 0;
  const executionMs = executeResult?.executionInfo?.executionMs ?? null;
  const fetchingMs = executeResult?.executionInfo?.fetchingMs ?? null;
  const dbPrefix = executeResult?.databaseName
    ? `${executeResult.databaseName}${executeResult.schemaName ? `.${executeResult.schemaName}` : ''}`
    : '';
  const sqlText = executeResult?.originalSql || executeResult?.executedSql || '';
  const timestampPrefix = `[${formatTimestamp(executionStartTime)}] `;
  const sqlContextPrefix = dbPrefix ? `${dbPrefix}> ` : '';
  const sqlPrefixText = `${timestampPrefix}${sqlContextPrefix}`;
  const sqlLines = sqlText
    ? sqlText
        .split('\n')
        .map((line, index) => (index === 0 ? line.trimEnd() : line.trimStart()))
    : [];
  const fetchRows = executeResult?.executionInfo?.fetchRows ?? activeRows.length;
  const statusRowCount = hasResultTab ? (activeResultSet ? (activeRows?.length ?? 0) : resultRows.length) : 0;
  const statusColumnCount = hasResultTab ? activeHeaders.length : 0;
  const effectiveExecutionMs = executionMs ?? Math.max(0, durationMs - (fetchingMs ?? 0));

  const editabilityReasonText = useMemo(() => {
    if (resultEditable) return undefined;
    switch (resultReadonlyReason) {
      case 'DERIVED_COLUMN':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_DERIVED_COLUMN);
      case 'MULTIPLE_TABLES':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_MULTIPLE_TABLES);
      case 'NO_CONNECTION':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_NO_CONNECTION);
      case 'PRIMARY_KEY_UNAVAILABLE':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_PK_UNAVAILABLE);
      case 'NO_PRIMARY_KEY':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_NO_PK);
      case 'MISSING_PRIMARY_KEY':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_MISSING_PK, {
          columns: (resultWriteTarget?.primaryKeyColumns ?? []).join(', '),
        });
      case 'DUPLICATE_PRIMARY_KEY':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_DUPLICATE_PK);
      case 'NULL_PRIMARY_KEY':
        return t(I18N_KEYS.COMMON.RESULT_READONLY_NULL_PK);
      default:
        return undefined;
    }
  }, [resultEditable, resultReadonlyReason, resultWriteTarget, t]);

  const resultsContent = (
    <>
      {/* Toolbar */}
      <div className="workbench-header flex h-11 items-center px-3 shrink-0">
        {(scriptResults?.length ?? 0) > 1 && (
          <>
            <select
              aria-label="Script statement result"
              value={selectedStatement}
              onChange={event => setSelectedStatement(Number(event.target.value))}
              className="workbench-pill mr-2 text-[10px]"
            >
              {scriptResults?.map((result, index) => (
                <option key={index} value={index}>
                  {index + 1}. {result.statementState ?? (result.success ? 'EXECUTED' : 'FAILED')}
                </option>
              ))}
            </select>
            {scriptSummary && (
              <span className="mr-2 text-[10px] theme-text-secondary">{scriptSummary}</span>
            )}
          </>
        )}
        <div className="flex space-x-1 h-full">
          {hasResultTab && (
            <button
              onClick={() => setActiveTab('result')}
              className={cn(
                'workbench-chip flex h-7 items-center rounded-lg px-3 text-[11px] font-medium transition-all',
                activeTab === 'result'
                  ? 'workbench-chip--active theme-text-primary'
                  : 'theme-text-secondary hover:theme-text-primary'
              )}
            >
              {t(I18N_KEYS.COMMON.RESULT)}
            </button>
          )}
          <button
            onClick={() => setActiveTab('output')}
            className={cn(
              'workbench-chip flex h-7 items-center rounded-lg px-3 text-[11px] font-medium transition-all',
              (activeTab === 'output' || !hasResultTab)
                ? 'workbench-chip--active theme-text-primary'
                : 'theme-text-secondary hover:theme-text-primary'
            )}
          >
            {t(I18N_KEYS.COMMON.OUTPUT)}
          </button>
        </div>

        <div className="flex-1" />

        <div className="hidden items-center gap-2 rounded-full border border-[color:var(--workbench-chip-hover-border)] bg-[color:var(--workbench-chip-hover-bg)] px-2.5 py-1 text-[10px] theme-text-secondary shadow-sm md:flex">
          <span className={cn('h-2 w-2 rounded-full', getStatusIndicatorColor())} />
          <span>{getStatusText()}</span>
        </div>

        <div className="flex items-center gap-1 pl-2">
          {pendingCellCount > 0 && (
            <>
              <button
                onClick={handleDiscardResultEdits}
                disabled={submittingResultEdits}
                className="workbench-icon-button disabled:opacity-50"
                title={t(I18N_KEYS.COMMON.RESULT_PENDING_DISCARD)}
              >
                <Undo2 className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={() => void handleOpenPreviewSql()}
                disabled={submittingResultEdits}
                className="workbench-icon-button disabled:opacity-50"
                title={t(I18N_KEYS.COMMON.RESULT_PREVIEW_SQL)}
              >
                <FileCode2 className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={() => void handleSubmitResultEdits(false)}
                disabled={submittingResultEdits}
                className="flex h-6 items-center gap-1 rounded-md bg-green-600 px-2 text-[10px] font-medium text-white transition-colors hover:bg-green-500 disabled:cursor-not-allowed disabled:opacity-50"
                title={t(I18N_KEYS.COMMON.RESULT_PENDING_SUBMIT)}
              >
                {submittingResultEdits ? (
                  <Loader2 className="h-3 w-3 animate-spin" />
                ) : (
                  <ArrowUp className="h-3 w-3" />
                )}
                {pendingCellCount}
              </button>
            </>
          )}
          <button
            onClick={onClose}
            className="workbench-icon-button"
            title={t(I18N_KEYS.COMMON.CLOSE_PANEL)}
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                disabled={!hasResultTab || activeColumns.length === 0}
                className={cn(
                  'workbench-icon-button',
                  (!hasResultTab || activeColumns.length === 0) && 'cursor-not-allowed opacity-50',
                )}
                title={t(I18N_KEYS.COMMON.RESULT_COLUMN_SETTINGS)}
              >
                <SlidersHorizontal className="w-3.5 h-3.5" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="max-h-[320px] min-w-[260px] overflow-y-auto">
              {activeColumns.map((column, index) => {
                const colId = `result-col-${index}`;
                const pref = columnPrefs[colId];
                const visible = pref?.visible !== false;
                const pinned = pref?.pinned ?? null;
                const label = column.label || column.name || `Column ${index + 1}`;
                return (
                  <div key={colId} className="flex items-center gap-1.5 px-2 py-1 text-[11px]">
                    <input
                      type="checkbox"
                      checked={visible}
                      onChange={() => updateColumnPreference(colId, { visible: !visible })}
                      className="h-3 w-3 shrink-0"
                      aria-label={label}
                    />
                    <span className="min-w-0 flex-1 truncate theme-text-primary" title={label}>
                      {label}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        updateColumnPreference(colId, { pinned: pinned === 'left' ? null : 'left' })
                      }
                      className={cn(
                        'shrink-0 rounded p-0.5 transition-colors hover:bg-accent/40',
                        pinned === 'left' ? 'text-primary' : 'theme-text-secondary',
                      )}
                      title={
                        pinned === 'left'
                          ? t(I18N_KEYS.COMMON.RESULT_COLUMN_UNPIN)
                          : t(I18N_KEYS.COMMON.RESULT_COLUMN_PIN_LEFT)
                      }
                    >
                      <ArrowLeftToLine className="h-3 w-3" />
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        updateColumnPreference(colId, { pinned: pinned === 'right' ? null : 'right' })
                      }
                      className={cn(
                        'shrink-0 rounded p-0.5 transition-colors hover:bg-accent/40',
                        pinned === 'right' ? 'text-primary' : 'theme-text-secondary',
                      )}
                      title={
                        pinned === 'right'
                          ? t(I18N_KEYS.COMMON.RESULT_COLUMN_UNPIN)
                          : t(I18N_KEYS.COMMON.RESULT_COLUMN_PIN_RIGHT)
                      }
                    >
                      <ArrowRightToLine className="h-3 w-3" />
                    </button>
                  </div>
                );
              })}
              <DropdownMenuSeparator className="my-1" />
              <DropdownMenuItem onSelect={resetColumnPreferences} className="px-2 py-1.5 text-[11px]">
                {t(I18N_KEYS.COMMON.RESULT_COLUMN_RESET)}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                disabled={!canExport}
                className={cn(
                  'workbench-icon-button flex items-center gap-0.5',
                  canExport
                    ? 'hover:text-[color:var(--text-primary)]'
                    : 'cursor-not-allowed opacity-50'
                )}
                title={canExport ? t(I18N_KEYS.COMMON.EXPORT) : t(I18N_KEYS.COMMON.EXPORT_NO_DATA)}
              >
                <Download className="w-3.5 h-3.5" />
                <ChevronDown className="w-2.5 h-2.5 opacity-60" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-[130px]">
              {RESULT_EXPORT_FORMATS.map((format) => (
                <DropdownMenuItem
                  key={format}
                  disabled={format === 'SQL' && !canExportSql}
                  onClick={() => handleExport(format)}
                  title={format === 'SQL' && !canExportSql ? t(I18N_KEYS.COMMON.EXPORT_SQL_UNAVAILABLE) : undefined}
                  className="px-2 py-1.5 text-[11px]"
                >
                  <span>{format === 'XLSX' ? t(I18N_KEYS.COMMON.EXPORT_FORMAT_EXCEL) : format}</span>
                  <span className="ml-auto pl-3 text-[10px] opacity-50">
                    .{EXPORT_FILE_EXTENSIONS[format] ?? format.toLowerCase()}
                  </span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
          <button
            onClick={() => setMaximized((value) => !value)}
            className="workbench-icon-button"
            title={maximized ? t(I18N_KEYS.COMMON.RESTORE) : t(I18N_KEYS.COMMON.MAXIMIZE)}
          >
            {maximized ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {/* Content Area */}
      <div className="flex-1 overflow-hidden bg-transparent relative">
        {activeTab === 'result' && hasResultTab && executeResult ? (
          <div className="flex h-full min-h-0 flex-col">
            {resultSets.length > 1 && (
              <div className="flex shrink-0 items-center gap-1 border-b theme-border px-2 py-1">
                {resultSets.map((_, index) => (
                  <button
                    key={index}
                    onClick={() => setActiveResultSetIndex(index)}
                    className={cn(
                      'workbench-chip flex h-6 items-center rounded-md px-2 text-[10px] font-medium transition-all',
                      activeResultSetIndex === index
                        ? 'workbench-chip--active theme-text-primary'
                        : 'theme-text-secondary hover:theme-text-primary'
                    )}
                  >
                    {t(I18N_KEYS.COMMON.RESULT)} {index + 1}
                  </button>
                ))}
              </div>
            )}
            {resultTruncated && (
              <div className="flex shrink-0 items-center gap-2 border-b theme-border px-3 py-1 text-[11px] text-amber-500">
                <span className="truncate">{t(I18N_KEYS.COMMON.RESULT_TRUNCATED)}</span>
                {loadMoreSql && activeConnectionId != null && (
                  <button
                    type="button"
                    onClick={handleLoadMore}
                    disabled={loadingMore || pendingCellCount > 0}
                    title={pendingCellCount > 0 ? t(I18N_KEYS.COMMON.RESULT_LOAD_MORE_PENDING) : undefined}
                    className="workbench-chip ml-auto flex h-5 shrink-0 items-center rounded px-2 text-[10px] font-medium theme-text-primary transition-all hover:bg-accent/30 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {loadingMore && <Loader2 className="mr-1 h-3 w-3 animate-spin" />}
                    {t(I18N_KEYS.COMMON.RESULT_LOAD_MORE)}
                  </button>
                )}
              </div>
            )}
            {isDmExecutionPlan ? (
              <div className="flex-1 overflow-auto text-[11px] theme-text-primary">
                <div className="sticky top-0 px-3 py-1.5 font-medium theme-text-secondary border-b theme-border bg-[color:var(--bg-panel)]">
                  Execution Plan
                </div>
                {activeRows.map((row, index) => (
                  <pre key={index} className="px-3 py-2 font-mono leading-5 whitespace-pre-wrap break-words border-b theme-border">
                    {formatDmExecutionPlan(String(row[0] ?? ''))}
                  </pre>
                ))}
              </div>
            ) : activeResultSet || activeHeaders.length > 0 ? (
              <ResultGrid
                resultSet={gridResultSet}
                fallbackHeaders={activeHeaders}
                fallbackRows={activeRows}
                emptyMessage={t(I18N_KEYS.COMMON.NO_DATA)}
                editable={resultEditable}
                dirtyCellKeys={dirtyResultCellKeys}
                resetVersion={gridResetVersion}
                onCellValueChanged={handleResultCellValueChanged}
                onGridApiReady={(api) => {
                  resultGridApiRef.current = api;
                  applyColumnPreferences(columnPrefsRef.current);
                  captureDisplayedRows(api);
                }}
                onViewModelChanged={captureDisplayedRows}
                onColumnResized={handleResultColumnResized}
              />
            ) : (
              <div className="h-full w-full flex flex-col items-center justify-center text-xs theme-text-secondary opacity-50 space-y-2">
                <Database className="w-8 h-8 opacity-20" />
                <span>{t(I18N_KEYS.COMMON.READY_HINT)}</span>
              </div>
            )}
          </div>
        ) : (
          // Output Log
          <div className="h-full overflow-auto p-3 font-sans text-[12px] leading-5 whitespace-pre-wrap space-y-1">
            {isRunning ? (
              <div className="flex items-center space-x-2 text-amber-500">
                <span className="animate-pulse">●</span>
                <span>{cancelStatus || t(I18N_KEYS.COMMON.RUNNING)}</span>
              </div>
            ) : executeResult ? (
              <>
                {scriptResults && scriptResults.length > 0 && (
                  <div className="mb-2 space-y-1">
                    {scriptResults.map((result, index) => (
                      <StatementResultRow
                        key={index}
                        result={result}
                        index={index}
                        selected={index === selectedStatement}
                        onSelect={() => setSelectedStatement(index)}
                      />
                    ))}
                  </div>
                )}
                {executeResult.executionInfo?.truncated && (
                  <div className="text-amber-400">{t(I18N_KEYS.COMMON.RESULT_TRUNCATED)}</div>
                )}
                {executeResult.success && (
                  <div className="theme-text-secondary">
                    [{formatTimestamp(executionStartTime)}] {t(I18N_KEYS.COMMON.CONNECTED)}
                  </div>
                )}
                {sqlLines.length > 0 && (
                  <div className="space-y-0.5">
                    {sqlLines.map((line, index) => (
                      <div key={index} className="theme-text-secondary flex">
                        <span className={index === 0 ? 'theme-text-secondary' : 'theme-text-secondary opacity-0'} aria-hidden={index !== 0}>
                          {sqlPrefixText}
                        </span>
                        <SqlCodeBlock variant="inline" sql={line} />
                      </div>
                    ))}
                  </div>
                )}
                {executeResult.success && (
                  <div className="theme-text-secondary">
                    [{formatTimestamp(executionStartTime)}] {t(I18N_KEYS.COMMON.ROWS_RETRIEVED, { count: fetchRows })}
                    {' · '}
                    {t(I18N_KEYS.COMMON.EXECUTION_TIME_MS, { ms: durationMs })}
                    {` (${t(I18N_KEYS.COMMON.QUERY_TIMING_DETAIL, { execution: effectiveExecutionMs, fetching: fetchingMs ?? 0 })})`}
                  </div>
                )}
                {!executeResult.success && (
                  <div className="whitespace-normal rounded-md border border-red-500/40 bg-red-500/5 p-2.5">
                    <div className="flex items-start gap-2">
                      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-400" />
                      <div className="min-w-0 flex-1 space-y-1.5">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-red-400">
                            {t(I18N_KEYS.COMMON.RESULT_ERROR_TITLE)}
                          </span>
                          <span className="text-[10px] theme-text-secondary">
                            [{formatTimestamp(executionStartTime)}]
                          </span>
                          <button
                            type="button"
                            onClick={handleAiFix}
                            className="workbench-chip ml-auto flex h-5 shrink-0 items-center gap-1 rounded px-1.5 text-[10px] font-medium theme-text-primary transition-all hover:bg-accent/30"
                            title={t(I18N_KEYS.COMMON.RESULT_AI_FIX)}
                          >
                            <Sparkles className="h-3 w-3" />
                            {t(I18N_KEYS.COMMON.RESULT_AI_FIX)}
                          </button>
                          <button
                            type="button"
                            onClick={copyErrorInfo}
                            title={t(I18N_KEYS.COMMON.ERROR_COPY)}
                            className="workbench-icon-button"
                          >
                            <Copy className="h-3 w-3" />
                          </button>
                        </div>
                        <div className="select-text break-words font-mono text-[11px] text-red-300">
                          {errorText}
                        </div>
                        {(errorSqlState || errorCode) && (
                          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[10px] theme-text-secondary">
                            {errorSqlState && (
                              <span>
                                SQLSTATE: <span className="font-mono">{errorSqlState}</span>
                              </span>
                            )}
                            {errorCode && (
                              <span>
                                {t(I18N_KEYS.COMMON.ERROR_CODE)}:{' '}
                                <span className="font-mono">{errorCode}</span>
                              </span>
                            )}
                          </div>
                        )}
                        {errorHint && (
                          <div className="text-[11px] theme-text-secondary">{errorHint}</div>
                        )}
                        {errorDetail && (
                          <details className="text-[10px] theme-text-secondary">
                            <summary className="cursor-pointer select-none">
                              {t(I18N_KEYS.COMMON.ERROR_DETAIL)}
                            </summary>
                            <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono leading-4">
                              {errorDetail}
                            </pre>
                          </details>
                        )}
                        {extraOutputMessages.length > 0 && (
                          <div className="space-y-0.5 pt-1">
                            {extraOutputMessages.map((m, i) => (
                              <div key={i} className={cn('text-[10px]', getMessageColor(m.level))}>
                                {m.message}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="text-gray-500 opacity-50">-- Output Console --</div>
            )}
          </div>
        )}
      </div>

      {/* Status Bar */}
      <div className="workbench-status-bar flex h-7 items-center justify-between px-3 text-[10px] theme-text-secondary shrink-0">
        <div className="flex items-center space-x-2">
          <span className="flex items-center">
            <span className={cn('w-2 h-2 rounded-full mr-1.5', getStatusIndicatorColor())} />
            {getStatusText()}
          </span>
        </div>
        <div className="flex items-center space-x-2">
          {hasResultTab && (
            <>
              <span>{t(I18N_KEYS.COMMON.ROWS_RETRIEVED, { count: statusRowCount })}</span>
              <span className="opacity-50">|</span>
              <span>{t(I18N_KEYS.COMMON.COLS_COUNT, { count: statusColumnCount })}</span>
              <span className="opacity-50">|</span>
              <span>{t(I18N_KEYS.COMMON.EXECUTION_TIME_MS, { ms: durationMs })}</span>
              <span className="opacity-50">|</span>
            </>
          )}
          {hasResultTab && pendingCellCount > 0 && (
            <>
              <span className="text-amber-500">
                {t(I18N_KEYS.COMMON.RESULT_PENDING_COUNT, { count: pendingCellCount })}
              </span>
              <span className="opacity-50">|</span>
            </>
          )}
          {hasResultTab ? (
            <span title={editabilityReasonText}>
              {resultEditable
                ? t(I18N_KEYS.COMMON.RESULT_EDITABLE)
                : t(I18N_KEYS.COMMON.READONLY)}
            </span>
          ) : (
            <span>{t(I18N_KEYS.COMMON.READONLY)}</span>
          )}
        </div>
      </div>

      <Dialog
        open={resultForceConfirmOpen}
        onOpenChange={(open) => {
          if (!open) closeResultForceConfirm();
        }}
      >
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>{t(I18N_KEYS.EXPLORER.BATCH_FORCE_TITLE)}</DialogTitle>
            <DialogDescription>
              {resultForceMessage || t(I18N_KEYS.EXPLORER.BATCH_FORCE_PROMPT)}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={closeResultForceConfirm}>
              {t(I18N_KEYS.CONNECTIONS.CANCEL)}
            </Button>
            <Button variant="destructive" onClick={() => void handleConfirmResultForce()}>
              {t(I18N_KEYS.EXPLORER.BATCH_FORCE_CONTINUE)}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={previewSqlOpen} onOpenChange={setPreviewSqlOpen}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>{t(I18N_KEYS.COMMON.RESULT_PREVIEW_SQL_TITLE)}</DialogTitle>
            <DialogDescription>
              {t(I18N_KEYS.COMMON.RESULT_PENDING_COUNT, { count: pendingCellCount })}
            </DialogDescription>
          </DialogHeader>
          <div className="max-h-[60vh] overflow-auto">
            {previewLoading ? (
              <div className="p-4 text-xs theme-text-secondary">{t(I18N_KEYS.EXPLORER.LOADING)}</div>
            ) : previewError ? (
              <div className="p-4 text-xs text-red-500 whitespace-pre-wrap">{previewError}</div>
            ) : (
              <SqlCodeBlock sql={previewSql} />
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPreviewSqlOpen(false)}>
              {t(I18N_KEYS.COMMON.CLOSE)}
            </Button>
            <Button
              disabled={previewLoading || previewStatements.length === 0}
              onClick={() => {
                void navigator.clipboard.writeText(previewSql).then(
                  () => toast.success(t(I18N_KEYS.EXPLORER.SQL_GENERATOR_COPIED)),
                  () => undefined,
                );
              }}
            >
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_COPY)}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );

  if (!isVisible) {
    return (
      <PanelGroup orientation="vertical" className="h-full min-h-0">
        <Panel className="flex flex-col min-h-0 relative bg-transparent">
          {children}
        </Panel>
      </PanelGroup>
    );
  }

  if (maximized) {
    return (
      <div className="flex h-full min-h-0 flex-col border-t theme-border">
        {resultsContent}
      </div>
    );
  }

  return (
    <PanelGroup orientation="vertical" className="h-full min-h-0">
      <Panel className="flex flex-col min-h-0 relative bg-transparent">
        {children}
      </Panel>

      <PanelResizeHandle className="workbench-horizontal-resize-handle" />

      <Panel defaultSize="30%" minSize="15%" className="workbench-results-panel flex flex-col shrink-0 border-t theme-border relative">
        {resultsContent}
      </Panel>
    </PanelGroup>
  );
}

import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { TableTabMetadata } from '../../types/tab';
import { DdlViewerDialog } from '../explorer/DdlViewerDialog';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import { TABLE_DATA_PAGE_SIZE_OPTIONS } from './tableDataTabConstants';
import { TableDataFilterBar } from './TableDataFilterBar';
import { TableDataGridAg } from './TableDataGridAg';
import { TableDataInsertBar } from './TableDataInsertBar';
import { TableDataPagination } from './TableDataPagination';
import { TableDataToolbar } from './TableDataToolbar';
import { useTableDataFind } from './useTableDataFind';
import { useTableDataTabState } from './useTableDataTabState';

interface TableDataTabProps {
  tabId: string;
  metadata: TableTabMetadata;
}

export function TableDataTab({ tabId, metadata }: TableDataTabProps) {
  const { t } = useTranslation();
  const isDm = metadata.dbType?.toLowerCase() === 'dm';
  const {
    data,
    loading,
    error,
    currentPage,
    pageSize,
    whereClause,
    orderByColumn,
    orderByDirection,
    viewMode,
    orderControlsDisabled,
    txMode,
    setTxMode,
    isolationLevel,
    setIsolationLevel,
    ddlDialogOpen,
    setDdlDialogOpen,
    isAddingRow,
    newRowValues,
    columnMetadata,
    loadingColumns,
    insertError,
    selectedRowIndex,
    hasSelectedRow,
    hasPendingOps,
    pendingCount,
    submittingEdits,
    dirtyCellKeys,
    pendingDeleteRowIndexes,
    pendingInsertRows,
    batchForceConfirmOpen,
    batchForceMessage,
    closeBatchForceConfirm,
    handleConfirmBatchForce,
    databases,
    loadingDatabases,
    isTable,
    handleRun,
    handlePageSizeChange,
    handleWhereClauseChange,
    handleOrderByColumnChange,
    handleToggleOrderByDirection,
    handleViewModeChange,
    handleGridSortChange,
    handleGridSelectionChange,
    handleGridCellValueChanged,
    handleDatabaseChange,
    handleFirstPage,
    handlePrevPage,
    handleNextPage,
    handleLastPage,
    handleAddRow,
    handleCancelAddRow,
    handleNewRowValueChange,
    handleConfirmInsert,
    handleDeleteRow,
    submitPendingOps,
    revertPendingOps,
    formatCellValue,
    columns,
    startRow,
    endRow,
    displayDbLabel,
    displayName,
    loadDdl,
  } = useTableDataTabState({ tabId, metadata });

  const {
    findVisible,
    findDisabled,
    searchTerm,
    searchInputRef,
    matchCount,
    currentMatchIndex,
    matchedKeys,
    currentMatch,
    onToggleFind,
    onSearchTermChange,
    onFindNext,
    onFindPrevious,
    onCloseFind,
  } = useTableDataFind({ data, viewMode, formatCellValue });

  // Ctrl/Cmd + Enter submits all staged operations. Inputs, textareas and
  // content-editable elements (SQL editor, find box) keep their own shortcuts.
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key !== 'Enter') return;
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) {
        return;
      }
      event.preventDefault();
      void submitPendingOps(false);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [submitPendingOps]);

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <TableDataToolbar
        loading={loading}
        isTable={isTable}
        viewMode={viewMode}
        hasRowSelection={hasSelectedRow}
        hasPendingOps={hasPendingOps}
        pendingCount={pendingCount}
        submittingEdits={submittingEdits}
        txMode={txMode}
        isolationLevel={isolationLevel}
        displayDbLabel={displayDbLabel}
        connectionName={metadata.connectionName}
        databases={databases}
        loadingDatabases={loadingDatabases}
        onRun={handleRun}
        onRefresh={() => void revertPendingOps()}
        onSubmit={() => void submitPendingOps(false)}
        onTransactionModeChange={setTxMode}
        onIsolationLevelChange={setIsolationLevel}
        onAddRow={handleAddRow}
        onDeleteRow={handleDeleteRow}
        onOpenDdl={() => setDdlDialogOpen(true)}
        onDatabaseChange={handleDatabaseChange}
        onViewModeChange={handleViewModeChange}
        findVisible={findVisible}
        findDisabled={findDisabled}
        searchTerm={searchTerm}
        matchCount={matchCount}
        currentMatchIndex={currentMatchIndex}
        searchInputRef={searchInputRef}
        onToggleFind={onToggleFind}
        onSearchTermChange={onSearchTermChange}
        onFindNext={onFindNext}
        onFindPrevious={onFindPrevious}
        onCloseFind={onCloseFind}
      />

      {isDm && isTable && !isAddingRow && (
        <div className="px-3 py-1 text-[10px] theme-text-secondary border-b theme-border">
          {t(I18N_KEYS.EXPLORER.DM_CELL_EDIT_HINT)}
        </div>
      )}

      <TableDataFilterBar
        columns={columns}
        whereClause={whereClause}
        orderByColumn={orderByColumn}
        orderByDirection={orderByDirection}
        orderControlsDisabled={orderControlsDisabled}
        onWhereClauseChange={handleWhereClauseChange}
        onRun={handleRun}
        onOrderByColumnChange={handleOrderByColumnChange}
        onToggleOrderByDirection={handleToggleOrderByDirection}
      />

      <TableDataInsertBar
        isVisible={isAddingRow && isTable}
        headers={columns}
        columnMetadata={columnMetadata}
        loadingColumns={loadingColumns}
        insertSubmitting={false}
        insertError={insertError}
        newRowValues={newRowValues}
        isDm={isDm}
        onNewRowValueChange={handleNewRowValueChange}
        onConfirmInsert={() => void handleConfirmInsert()}
        onCancelInsert={handleCancelAddRow}
      />

      <DdlViewerDialog
        open={ddlDialogOpen}
        onOpenChange={setDdlDialogOpen}
        title={isTable ? t(I18N_KEYS.EXPLORER.TABLE_DDL) : t(I18N_KEYS.EXPLORER.VIEW_DDL_TITLE)}
        displayName={displayName}
        loadDdl={loadDdl}
      />

      <Dialog
        open={batchForceConfirmOpen}
        onOpenChange={(open) => {
          if (!open) closeBatchForceConfirm();
        }}
      >
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>{t(I18N_KEYS.EXPLORER.BATCH_FORCE_TITLE)}</DialogTitle>
            <DialogDescription>
              {batchForceMessage || t(I18N_KEYS.EXPLORER.BATCH_FORCE_PROMPT)}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={closeBatchForceConfirm}>
              {t(I18N_KEYS.CONNECTIONS.CANCEL)}
            </Button>
            <Button variant="destructive" onClick={() => void handleConfirmBatchForce()}>
              {t(I18N_KEYS.EXPLORER.BATCH_FORCE_CONTINUE)}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <TableDataGridAg
        data={data}
        loading={loading}
        error={error}
        viewMode={viewMode}
        orderByColumn={orderByColumn}
        orderByDirection={orderByDirection}
        selectedRowIndex={selectedRowIndex}
        startRow={startRow}
        editable={isTable && viewMode === 'grid'}
        matchedKeys={matchedKeys}
        currentMatch={currentMatch}
        dirtyCellKeys={dirtyCellKeys}
        pendingDeleteRowIndexes={pendingDeleteRowIndexes}
        pendingInsertRows={pendingInsertRows}
        onRowSelectionChange={handleGridSelectionChange}
        onGridSortChange={handleGridSortChange}
        onCellValueChanged={handleGridCellValueChanged}
        formatCellValue={formatCellValue}
      />

      {!loading && !error && data && data.totalCount > 0 && (
        <TableDataPagination
          currentPage={currentPage}
          pageSize={pageSize}
          totalCount={data.totalCount}
          totalPages={data.totalPages}
          startRow={startRow}
          endRow={endRow}
          pageSizeOptions={TABLE_DATA_PAGE_SIZE_OPTIONS}
          onPageSizeChange={handlePageSizeChange}
          onFirstPage={handleFirstPage}
          onPrevPage={handlePrevPage}
          onNextPage={handleNextPage}
          onLastPage={handleLastPage}
        />
      )}
    </div>
  );
}

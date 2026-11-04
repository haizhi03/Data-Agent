import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { FileUp, Loader2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from '../ui/Tooltip';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { IMPORT_FILE_TYPES } from '../../constants/fileTypes';
import { tableService } from '../../services/table.service';
import { useToast } from '../../hooks/useToast';
import { useBatchImport } from '../../hooks/useBatchImport';
import { formatImportResult } from '../../utils/importResult';

export interface BatchImportParams {
  connectionId: number;
  catalog?: string;
  schema?: string;
}

interface BatchImportDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  importParams: BatchImportParams | null;
  /** Called when at least one file was imported successfully. */
  onSuccess?: () => void;
}

interface PlannedFile {
  file: File;
  fileType: string;
  tableName: string;
  ready: boolean;
  reason?: string;
}

/** Sentinel value for the "create a new table" option in the target-table dropdown. */
const NEW_TABLE_OPTION = '__NEW_TABLE__';

export function BatchImportDialog({
  open,
  onOpenChange,
  importParams,
  onSuccess,
}: BatchImportDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [fileEncoding, setFileEncoding] = useState<string>('AUTO');
  const [targetTable, setTargetTable] = useState<string>('');
  const [tables, setTables] = useState<string[]>([]);
  const [tablesLoading, setTablesLoading] = useState(false);
  const [tablesLoadFailed, setTablesLoadFailed] = useState(false);

  const batchImport = useBatchImport();

  useEffect(() => {
    if (!open || !importParams) {
      return;
    }
    let cancelled = false;
    setFiles([]);
    setFileEncoding('AUTO');
    setTargetTable('');
    batchImport.reset();
    setTablesLoading(true);
    setTablesLoadFailed(false);
    tableService
      .listTables(String(importParams.connectionId), importParams.catalog, importParams.schema)
      .then((names) => {
        if (cancelled) return;
        setTables(names.filter(Boolean));
        setTablesLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setTables([]);
        setTablesLoadFailed(true);
        setTablesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, importParams, batchImport.reset]);

  // All ready files are imported into the single target table selected above.
  const isNewTable = targetTable === NEW_TABLE_OPTION;
  // SQL files declare their own table name inside CREATE TABLE; for other
  // formats the new table name is derived from the first file's name (without
  // extension) so the user does not have to type it.
  const allSqlFiles = files.length > 0 && files.every((file) => {
    const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
    return IMPORT_FILE_TYPES[ext] === 'SQL';
  });
  const tableNameFromFile = files[0]?.name.replace(/\.[^.]+$/, '') ?? '';
  const resolvedTableName = isNewTable
    ? allSqlFiles
      ? '' // SQL carries its own table name; nothing to forward.
      : tableNameFromFile
    : targetTable;
  const plannedFiles: PlannedFile[] = files.map((file) => {
    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    const fileType = IMPORT_FILE_TYPES[extension];
    if (!fileType) {
      return { file, fileType: '', tableName: resolvedTableName, ready: false, reason: t(I18N_KEYS.EXPLORER.IMPORT_FORMAT_UNSUPPORTED) };
    }
    const ready = isNewTable ? allSqlFiles || resolvedTableName !== '' : resolvedTableName !== '';
    return { file, fileType, tableName: resolvedTableName, ready };
  });
  const readyFiles = plannedFiles.filter((item) => item.ready);
  const hasCsv = plannedFiles.some((item) => item.fileType === 'CSV');

  const handleOpenChange = (next: boolean) => {
    // Closing the dialog while importing stops the remaining files.
    if (!next && batchImport.importing) {
      batchImport.stop();
    }
    onOpenChange(next);
  };

  const handleFilesChange = (event: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(event.target.files ?? []);
    if (picked.length > 0) {
      setFiles((previous) => {
        const seen = new Set(previous.map((file) => `${file.name}:${file.size}`));
        return [...previous, ...picked.filter((file) => !seen.has(`${file.name}:${file.size}`))];
      });
    }
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleRemoveFile = (target: File) => {
    setFiles((previous) => previous.filter((file) => file !== target));
  };

  const handleBatchImport = async () => {
    if (!importParams || batchImport.importing || readyFiles.length === 0) {
      return;
    }
    const plans = readyFiles.map((item) => ({
      file: item.file,
      fileType: item.fileType,
      tableName: item.tableName,
    }));
    const outcome = await batchImport.start(plans, {
      connectionId: importParams.connectionId,
      catalog: importParams.catalog,
      schema: importParams.schema,
      encoding: fileEncoding,
      createTableIfMissing: isNewTable,
    });
    if (!outcome.cancelled) {
      const success = outcome.results.filter((r) => r.success).length;
      const failed = outcome.results.length - success;
      if (success > 0) {
        // Refresh the explorer tree even on partial success — a new table may have
        // been auto-created even when some later files failed.
        onSuccess?.();
      }
      if (failed === 0 && success > 0) {
        toast.success(t(I18N_KEYS.EXPLORER.IMPORT_BATCH_DONE, { success, failed: 0 }));
      }
      // Failures are already listed inside the dialog; no toast needed.
    }
  };

  const { importing, progress, results } = batchImport;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <FileUp className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.IMPORT_WIZARD)}
          </DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <p className="text-sm text-blue-600 dark:text-blue-400">
            {t(I18N_KEYS.EXPLORER.IMPORT_WIZARD_HINT)}
          </p>
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.IMPORT_TARGET_TABLE)}
            </label>
            <select
              value={targetTable}
              onChange={(e) => setTargetTable(e.target.value)}
              disabled={tablesLoading || importing}
              className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            >
              <option value="">
                {tablesLoading ? t(I18N_KEYS.COMMON.LOADING) : t(I18N_KEYS.EXPLORER.IMPORT_SELECT_TARGET_TABLE)}
              </option>
              <option value={NEW_TABLE_OPTION}>➕ 添加新表</option>
              {tables.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium theme-text-primary">
                {t(I18N_KEYS.EXPLORER.IMPORT_SOURCE_FILE)}
              </label>
              <Button
                type="button"
                variant="outline"
                className="h-7 px-2 text-xs"
                onClick={() => fileInputRef.current?.click()}
                disabled={tablesLoading || importing}
              >
                {t(I18N_KEYS.EXPLORER.IMPORT_ADD_FILES)}
              </Button>
            </div>
            <input
              ref={fileInputRef}
              type="file"
              className="hidden"
              multiple
              accept=".csv,.json,.sql,.xlsx,.xls"
              onChange={handleFilesChange}
            />
            <div className="h-60 overflow-y-auto rounded-md border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-900">
              {tablesLoading ? (
                <div className="flex h-full items-center justify-center">
                  <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                </div>
              ) : tablesLoadFailed ? (
                <div className="flex h-full items-center justify-center text-xs text-red-500">
                  {t(I18N_KEYS.EXPLORER.IMPORT_TABLES_LOAD_FAILED)}
                </div>
              ) : plannedFiles.length === 0 ? (
                <div className="flex h-full items-center justify-center text-xs text-gray-500 dark:text-gray-400">
                  {t(I18N_KEYS.EXPLORER.IMPORT_NO_FILE)}
                </div>
              ) : (
                plannedFiles.map((item) => (
                  <div
                    key={`${item.file.name}:${item.file.size}`}
                    className="flex items-center justify-between rounded px-1 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
                  >
                    <div className="flex min-w-0 flex-1 items-center gap-2 text-sm">
                      <span className="truncate text-gray-900 dark:text-gray-100">{item.file.name}</span>
                    </div>
                    <div className="ml-2 flex shrink-0 items-center gap-1">
                      {item.ready ? (
                        <span className="text-xs text-green-600 dark:text-green-500">&#10003;</span>
                      ) : (
                        <TooltipProvider>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="max-w-[180px] cursor-help truncate text-xs text-red-500">
                                {item.reason}
                              </span>
                            </TooltipTrigger>
                            <TooltipContent side="top" className="max-w-xs text-xs">
                              {item.reason}
                            </TooltipContent>
                          </Tooltip>
                        </TooltipProvider>
                      )}
                      <button
                        type="button"
                        onClick={() => handleRemoveFile(item.file)}
                        disabled={importing}
                        className="text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-200"
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span
                className={
                  readyFiles.length === 0 && plannedFiles.length > 0
                    ? 'text-red-500'
                    : 'text-gray-500 dark:text-gray-400'
                }
              >
                {t(I18N_KEYS.EXPLORER.IMPORT_FILES_SELECTED, {
                  selected: plannedFiles.length,
                  importable: readyFiles.length,
                })}
              </span>
            </div>
          </div>
          {hasCsv && (
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium theme-text-primary">
                {t(I18N_KEYS.EXPLORER.IMPORT_FILE_ENCODING)}
              </label>
              <select
                value={fileEncoding}
                onChange={(e) => setFileEncoding(e.target.value)}
                disabled={importing}
                className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
              >
                <option value="AUTO">{t(I18N_KEYS.EXPLORER.IMPORT_ENCODING_AUTO)}</option>
                <option value="UTF-8">UTF-8</option>
                <option value="GBK">GBK</option>
              </select>
            </div>
          )}
          {progress && (
            <div className="flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              {t(I18N_KEYS.EXPLORER.IMPORT_BATCH_PROGRESS, {
                current: progress.current,
                total: progress.total,
                file: progress.file,
              })}
            </div>
          )}
          {results.length > 0 && (
            <div className="max-h-32 space-y-1 overflow-y-auto rounded-md border border-gray-300 bg-white p-2 text-xs dark:border-gray-700 dark:bg-gray-900">
              {results.map((result) => (
                <div key={result.file} className="flex min-w-0 items-start gap-1">
                  {result.success ? (
                    <span className="text-green-600 dark:text-green-500">&#10003;</span>
                  ) : (
                    <span className="text-red-500">&#10007;</span>
                  )}
                  <span className="shrink-0 text-gray-900 dark:text-gray-100">{result.file}</span>
                  {result.success ? (
                    <span className="text-gray-500 dark:text-gray-400">{formatImportResult(t, result.fileType, result.data)}</span>
                  ) : (
                    <span className="min-w-0 flex-1 break-all text-red-500">{formatImportResult(t, result.fileType, result.data)}</span>
                  )}
                </div>
              ))}
            </div>
          )}
          <div className="flex justify-end gap-2">
            {importing ? (
              <Button variant="outline" onClick={() => batchImport.stop()}>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                {t(I18N_KEYS.EXPLORER.IMPORT_BATCH_STOP)}
              </Button>
            ) : (
              <>
                <Button variant="outline" onClick={() => handleOpenChange(false)}>
                  {t(I18N_KEYS.CONNECTIONS.CANCEL)}
                </Button>
                <Button
                  onClick={() => void handleBatchImport()}
                  disabled={!importParams || tablesLoading || tablesLoadFailed || readyFiles.length === 0 || targetTable === ''}
                >
                  <FileUp className="mr-2 h-4 w-4" />
                  {t(I18N_KEYS.EXPLORER.IMPORT_DATA)}
                </Button>
              </>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

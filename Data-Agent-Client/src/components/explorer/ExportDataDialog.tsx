import { useEffect, useState } from 'react';
import { Download, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { EXPORT_FILE_TYPES, EXPORT_FILE_EXTENSIONS } from '../../constants/fileTypes';
import { tableDataService } from '../../services/tableData.service';
import { columnService } from '../../services/column.service';
import { downloadFile } from '../../utils/download';
import { useToast } from '../../hooks/useToast';

export interface ExportDataParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  /** WHERE condition currently applied on the table data page. */
  whereClause?: string;
  /** ORDER BY column currently applied on the table data page. */
  orderByColumn?: string;
  orderByDirection?: 'asc' | 'desc';
}

interface ExportDataDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  targetTablePath: string;
  exportParams: ExportDataParams | null;
}

export function ExportDataDialog({
  open,
  onOpenChange,
  targetTablePath,
  exportParams,
}: ExportDataDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const [fileType, setFileType] = useState<string>('CSV');
  const [exporting, setExporting] = useState(false);
  const [columnNames, setColumnNames] = useState<string[]>([]);
  const [selectedColumns, setSelectedColumns] = useState<Set<string>>(new Set());
  const [columnsLoading, setColumnsLoading] = useState(false);
  const [columnsLoadFailed, setColumnsLoadFailed] = useState(false);

  const fileExtension = EXPORT_FILE_EXTENSIONS[fileType] ?? 'csv';

  // Keep the export payload in table column order.
  const selectedInOrder = columnNames.filter((name) => selectedColumns.has(name));

  // Surface the filter that the export will reuse, so it is never a surprise.
  const activeFilterLines = [
    exportParams?.whereClause?.trim()
      ? `${t(I18N_KEYS.EXPLORER.WHERE_LABEL)} ${exportParams.whereClause.trim()}`
      : '',
    exportParams?.orderByColumn
      ? `${t(I18N_KEYS.EXPLORER.ORDER_BY_LABEL)} ${exportParams.orderByColumn} ${
          exportParams.orderByDirection === 'desc'
            ? t(I18N_KEYS.EXPLORER.ORDER_DESC)
            : t(I18N_KEYS.EXPLORER.ORDER_ASC)
        }`
      : '',
  ].filter(Boolean);

  useEffect(() => {
    if (!open || !exportParams) {
      return;
    }
    let cancelled = false;
    setColumnsLoading(true);
    setColumnsLoadFailed(false);
    columnService
      .listColumnNames(
        String(exportParams.connectionId),
        exportParams.tableName,
        exportParams.catalog,
        exportParams.schema
      )
      .then((names) => {
        if (cancelled) return;
        setColumnNames(names);
        setSelectedColumns(new Set(names));
        setColumnsLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        // Field list unavailable: fall back to exporting all columns.
        setColumnNames([]);
        setSelectedColumns(new Set());
        setColumnsLoadFailed(true);
        setColumnsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, exportParams]);

  const handleToggleColumn = (name: string) => {
    setSelectedColumns((previous) => {
      const next = new Set(previous);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  };

  const handleExport = async () => {
    if (!exportParams || exporting) {
      return;
    }
    setExporting(true);
    try {
      const blob = await tableDataService.exportTable({
        connectionId: exportParams.connectionId,
        tableName: exportParams.tableName,
        catalog: exportParams.catalog,
        schema: exportParams.schema,
        fileType,
        columns: columnsLoadFailed ? undefined : selectedInOrder,
        whereClause: exportParams.whereClause,
        orderByColumn: exportParams.orderByColumn,
        orderByDirection: exportParams.orderByDirection,
      });

      downloadFile(blob, `${exportParams.tableName}.${fileExtension}`);

      toast.success(t(I18N_KEYS.EXPLORER.EXPORT_SUCCESS));
      onOpenChange(false);
    } catch (error: unknown) {
      if (axios.isAxiosError(error) && error.response?.data instanceof Blob) {
        try {
          const text = await error.response.data.text();
          const parsed = JSON.parse(text) as { message?: string };
          toast.error(parsed.message || t(I18N_KEYS.EXPLORER.EXPORT_FAILED));
        } catch {
          toast.error(t(I18N_KEYS.EXPLORER.EXPORT_FAILED));
        }
      } else {
        toast.error(t(I18N_KEYS.EXPLORER.EXPORT_FAILED));
      }
    } finally {
      setExporting(false);
    }
  };

  const exportDisabled =
    exporting || !exportParams || columnsLoading || (!columnsLoadFailed && selectedInOrder.length === 0);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <Download className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.EXPORT_DATA)}
          </DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.EXPORT_TARGET_TABLE)}
            </label>
            <input
              type="text"
              readOnly
              value={targetTablePath}
              className="h-10 w-full rounded-md border border-gray-300 bg-gray-100 px-3 text-sm text-gray-500 focus:outline-none dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
            />
          </div>
          {activeFilterLines.length > 0 && (
            <div className="flex flex-col gap-1 rounded-md border border-amber-300/70 bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:border-amber-700/60 dark:bg-amber-900/20 dark:text-amber-400">
              {activeFilterLines.map((line) => (
                <span key={line} className="break-all font-mono">
                  {line}
                </span>
              ))}
            </div>
          )}
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.EXPORT_FILE_TYPE)}
            </label>
            <select
              value={fileType}
              onChange={(e) => setFileType(e.target.value)}
              className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            >
              {EXPORT_FILE_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </div>
          {columnsLoadFailed ? (
            <p className="text-xs text-amber-600 dark:text-amber-500">
              {t(I18N_KEYS.EXPLORER.EXPORT_FIELDS_LOAD_FALLBACK)}
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium theme-text-primary">
                  {t(I18N_KEYS.EXPLORER.EXPORT_AVAILABLE_FIELDS)}
                </label>
                <div className="flex items-center gap-1">
                  <Button
                    variant="outline"
                    className="h-7 px-2 text-xs"
                    onClick={() => setSelectedColumns(new Set(columnNames))}
                    disabled={columnsLoading}
                  >
                    {t(I18N_KEYS.EXPLORER.EXPORT_SELECT_ALL)}
                  </Button>
                  <Button
                    variant="outline"
                    className="h-7 px-2 text-xs"
                    onClick={() => setSelectedColumns(new Set())}
                    disabled={columnsLoading}
                  >
                    {t(I18N_KEYS.EXPLORER.EXPORT_DESELECT_ALL)}
                  </Button>
                </div>
              </div>
              <div className="h-40 overflow-y-auto rounded-md border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-900">
                {columnsLoading ? (
                  <div className="flex h-full items-center justify-center">
                    <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                  </div>
                ) : (
                  columnNames.map((name) => (
                    <label
                      key={name}
                      className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm text-gray-900 hover:bg-gray-100 dark:text-gray-100 dark:hover:bg-gray-800"
                    >
                      <input
                        type="checkbox"
                        checked={selectedColumns.has(name)}
                        onChange={() => handleToggleColumn(name)}
                        className="h-3.5 w-3.5 accent-blue-600"
                      />
                      <span className="truncate">{name}</span>
                    </label>
                  ))
                )}
              </div>
              <div className="flex items-center gap-2 text-xs">
                <span
                  className={
                    selectedInOrder.length === 0 ? 'text-red-500' : 'text-gray-500 dark:text-gray-400'
                  }
                >
                  {t(I18N_KEYS.EXPLORER.EXPORT_FIELDS_SELECTED, {
                    selected: selectedInOrder.length,
                    total: columnNames.length,
                  })}
                </span>
                {selectedInOrder.length === 0 && (
                  <span className="text-red-500">
                    {t(I18N_KEYS.EXPLORER.EXPORT_SELECT_AT_LEAST_ONE)}
                  </span>
                )}
              </div>
            </div>
          )}
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={exporting}
            >
              {t(I18N_KEYS.CONNECTIONS.CANCEL)}
            </Button>
            <Button
              onClick={() => void handleExport()}
              disabled={exportDisabled}
            >
              {exporting ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <Download className="mr-2 h-4 w-4" />
              )}
              {t(I18N_KEYS.EXPLORER.EXPORT_DATA)}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

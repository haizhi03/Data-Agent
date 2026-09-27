import { useState } from 'react';
import { Download, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableDataService } from '../../services/tableData.service';
import { useToast } from '../../hooks/useToast';

const EXPORT_FILE_TYPES = ['CSV', 'XLSX', 'XLS', 'JSON', 'SQL'] as const;

export interface ExportDataParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
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

  const FILE_EXTENSIONS: Record<string, string> = {
    CSV: 'csv',
    XLSX: 'xlsx',
    XLS: 'xls',
    JSON: 'json',
    SQL: 'sql',
  };
  const fileExtension = FILE_EXTENSIONS[fileType] ?? 'csv';

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
      });

      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${exportParams.tableName}.${fileExtension}`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);

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
              disabled={exporting || !exportParams}
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

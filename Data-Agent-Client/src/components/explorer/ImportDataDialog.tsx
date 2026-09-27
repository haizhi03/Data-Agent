import { useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import axios from 'axios';
import { FileUp, Loader2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableDataService } from '../../services/tableData.service';
import { useToast } from '../../hooks/useToast';

export interface ImportDataParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
}

const IMPORT_FILE_TYPES: Record<string, string> = {
  csv: 'CSV',
  json: 'JSON',
  sql: 'SQL',
  xlsx: 'XLSX',
  xls: 'XLS',
};

interface ImportDataDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  targetTablePath: string;
  importParams: ImportDataParams | null;
}

export function ImportDataDialog({
  open,
  onOpenChange,
  targetTablePath,
  importParams,
}: ImportDataDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);

  const resetState = () => {
    setSelectedFile(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleOpenChange = (next: boolean) => {
    if (importing) {
      return;
    }
    if (!next) {
      resetState();
    }
    onOpenChange(next);
  };

  const handleSelectFileClick = () => {
    fileInputRef.current?.click();
  };

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    setSelectedFile(file);
  };

  const handleClearFile = () => {
    setSelectedFile(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleImport = async () => {
    if (importing || !selectedFile || !importParams) {
      return;
    }

    const extension = selectedFile.name.split('.').pop()?.toLowerCase() ?? '';
    const fileType = IMPORT_FILE_TYPES[extension];
    if (!fileType) {
      toast.error(t(I18N_KEYS.EXPLORER.IMPORT_FORMAT_UNSUPPORTED));
      return;
    }

    setImporting(true);
    try {
      const result = await tableDataService.importTableData({
        connectionId: importParams.connectionId,
        tableName: importParams.tableName,
        catalog: importParams.catalog,
        schema: importParams.schema,
        fileType,
        file: selectedFile,
      });

      if (result.success) {
        toast.success(t(I18N_KEYS.EXPLORER.IMPORT_SUCCESS, { count: result.insertedRows }));
        resetState();
        onOpenChange(false);
      } else {
        const detail = result.failedAtRow
          ? (fileType === 'SQL'
            ? t(I18N_KEYS.EXPLORER.IMPORT_FAILED_STATEMENT, {
                row: result.failedAtRow,
                message: result.errorMessage ?? '',
              })
            : t(I18N_KEYS.EXPLORER.IMPORT_FAILED_ROW, {
                row: result.failedAtRow,
                message: result.errorMessage ?? '',
              }))
          : (result.errorMessage ?? t(I18N_KEYS.EXPLORER.IMPORT_FAILED));
        toast.error(detail);
      }
    } catch (error: unknown) {
      if (axios.isAxiosError(error)) {
        const message = (error.response?.data as { message?: string } | undefined)?.message;
        toast.error(message || t(I18N_KEYS.EXPLORER.IMPORT_FAILED));
      } else {
        toast.error(t(I18N_KEYS.EXPLORER.IMPORT_FAILED));
      }
    } finally {
      setImporting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <FileUp className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.IMPORT_DATA)}
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
              {t(I18N_KEYS.EXPLORER.IMPORT_SOURCE_FILE)}
            </label>
            <input
              ref={fileInputRef}
              type="file"
              className="hidden"
              accept=".csv,.json,.sql,.xlsx,.xls"
              onChange={handleFileChange}
            />
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={handleSelectFileClick}
                disabled={importing}
                className="shrink-0"
              >
                {t(I18N_KEYS.EXPLORER.IMPORT_SELECT_FILE)}
              </Button>
              <div className="flex h-10 min-w-0 flex-1 items-center justify-between gap-2 rounded-md border border-gray-300 bg-gray-100 px-3 dark:border-gray-700 dark:bg-gray-800">
                <span className="truncate text-sm text-gray-500 dark:text-gray-400">
                  {selectedFile ? selectedFile.name : t(I18N_KEYS.EXPLORER.IMPORT_NO_FILE)}
                </span>
                {selectedFile ? (
                  <button
                    type="button"
                    onClick={handleClearFile}
                    disabled={importing}
                    className="shrink-0 text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-200"
                  >
                    <X className="h-4 w-4" />
                  </button>
                ) : null}
              </div>
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => handleOpenChange(false)}
              disabled={importing}
            >
              {t(I18N_KEYS.CONNECTIONS.CANCEL)}
            </Button>
            <Button
              onClick={() => void handleImport()}
              disabled={importing || !selectedFile || !importParams}
            >
              {importing ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <FileUp className="mr-2 h-4 w-4" />
              )}
              {t(I18N_KEYS.EXPLORER.IMPORT_DATA)}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

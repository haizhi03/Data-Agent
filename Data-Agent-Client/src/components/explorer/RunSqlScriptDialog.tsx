import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { FileCode2, Play } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { cn } from '../../lib/utils';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { SqlBatchMode } from '../../types/sql';
import type { SqlScriptTarget } from '../../types/explorer';
import { containsNonAtomicStatement } from '../../utils/sqlRunner';
import { useSqlRunStore } from '../../store/sqlRunStore';
import { useToast } from '../../hooks/useToast';

interface RunSqlScriptDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  target: SqlScriptTarget | null;
}

function readFileText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read file'));
    reader.readAsText(file);
  });
}

export function RunSqlScriptDialog({ open, onOpenChange, target }: RunSqlScriptDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const runSql = useSqlRunStore((s) => s.runSql);
  const isRunning = useSqlRunStore((s) => s.isRunning);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const [fileName, setFileName] = useState('');
  const [fileContent, setFileContent] = useState('');

  // Reset transient state each time the dialog is opened for a fresh run.
  useEffect(() => {
    if (!open) return;
    setFileName('');
    setFileContent('');
  }, [open, target]);

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      const content = await readFileText(file);
      setFileName(file.name);
      setFileContent(content);
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_NO_FILE));
    }
  };

  const handleRun = () => {
    if (!target || !fileContent.trim() || isRunning) return;

    // ATOMIC mode only accepts queries and transactional DML; DDL and other
    // non-atomic statements are rejected server-side, so auto-switch to STEPWISE.
    const mode: SqlBatchMode = containsNonAtomicStatement(fileContent, target.dbType?.toUpperCase() !== 'DM') ? 'STEPWISE' : 'ATOMIC';

    // Route execution through the shared run store so the outcome streams into
    // the workspace Output panel (per-statement success/failure), like the editor.
    void runSql({
      connectionId: target.connectionId,
      databaseName: target.databaseName,
      schemaName: target.schemaName,
      dbType: target.dbType,
      sql: fileContent,
      mode,
    });

    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <FileCode2 className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_TITLE)}
          </DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <p className="text-sm text-blue-600 dark:text-blue-400">
            {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_HINT)}
          </p>

          {/* Target (read-only, from the right-clicked node) */}
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_TARGET)}
            </label>
            <input
              type="text"
              readOnly
              value={target?.targetPath ?? ''}
              className="h-10 w-full rounded-md border border-gray-300 bg-gray-100 px-3 text-sm text-gray-500 focus:outline-none dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
            />
          </div>

          {/* File picker */}
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_FILE)}
            </label>
            <div className="flex items-center gap-2">
              <input
                ref={fileInputRef}
                type="file"
                accept=".sql,text/plain,application/sql"
                className="hidden"
                onChange={(event) => { void handleFileChange(event); }}
              />
              <Button
                variant="outline"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
              >
                {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_SELECT_FILE)}
              </Button>
              <span
                className={cn(
                  'truncate text-sm',
                  fileName ? 'theme-text-primary' : 'theme-text-secondary opacity-60'
                )}
              >
                {fileName || t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_NO_FILE)}
              </span>
            </div>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              {t(I18N_KEYS.COMMON.CANCEL)}
            </Button>
            <Button onClick={handleRun} disabled={!fileContent.trim() || isRunning}>
              <Play className="mr-2 h-4 w-4" />
              {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_RUN)}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

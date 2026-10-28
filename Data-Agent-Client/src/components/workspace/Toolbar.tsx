import { Play, Square, CheckCircle, RotateCcw, WandSparkles } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { useEffect, useState } from 'react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';
import { databaseService } from '../../services/database.service';
import { Button } from '../ui/Button';
import { TransactionMode, IsolationLevel } from '../../constants/transactionSettings';
import { TransactionModeSelector } from './TransactionModeSelector';
import { getPlatformShortcuts } from '../../lib/platformShortcuts';
import type { SqlBatchMode } from '../../types/sql';
import { useRuntimeStore } from '../../store/runtimeStore';

interface ToolbarProps {
  onRun: () => void;
  onFormat?: () => void;
  onStop?: () => void;
  isRunning?: boolean;
  connectionId?: number;
  currentDatabase?: string | null;
  onDatabaseChange?: (db: string) => void;
  extraActions?: ReactNode;
  scriptMode?: SqlBatchMode;
  onScriptModeChange?: (mode: SqlBatchMode) => void;
  timeoutSeconds?: number;
  onTimeoutSecondsChange?: (seconds: number) => void;
  maxRows?: number;
  onMaxRowsChange?: (rows: number) => void;
  showTransactionControls?: boolean;
}

export function Toolbar({
  onRun,
  onFormat,
  onStop,
  isRunning = false,
  connectionId,
  currentDatabase,
  onDatabaseChange,
  extraActions,
  scriptMode,
  onScriptModeChange,
  timeoutSeconds,
  onTimeoutSecondsChange,
  maxRows,
  onMaxRowsChange,
  showTransactionControls = true,
}: ToolbarProps) {
  const { t } = useTranslation();
  const local = useRuntimeStore((s) => s.mode === 'LOCAL');
  const shortcuts = getPlatformShortcuts();
  const [databases, setDatabases] = useState<string[]>([]);
  const [loadingDatabases, setLoadingDatabases] = useState(false);
  const [transactionMode, setTransactionMode] = useState<TransactionMode>(TransactionMode.AUTO);
  const [isolationLevel, setIsolationLevel] = useState<IsolationLevel>(IsolationLevel.DEFAULT);

  useEffect(() => {
    if (!connectionId) {
      setDatabases([]);
      return;
    }

    const loadDatabases = async () => {
      setLoadingDatabases(true);
      try {
        const dbs = await databaseService.listDatabases(String(connectionId));
        setDatabases(dbs || []);
      } catch (error) {
        console.error('Failed to load databases:', error);
      } finally {
        setLoadingDatabases(false);
      }
    };

    loadDatabases();
  }, [connectionId]);

  const actionButtonClass =
    'workbench-icon-button h-7 w-7';

  return (
    <>
      {/* Left: Action Buttons */}
      <Button
        variant="ghost"
        size="icon"
        onClick={onRun}
        disabled={!connectionId || isRunning}
        title={
          isRunning
            ? t(I18N_KEYS.COMMON.STOP)
            : t(I18N_KEYS.WORKSPACE.RUN_SHORTCUT, { shortcut: shortcuts.runQuery })
        }
        className={actionButtonClass}
      >
        {isRunning ? (
          <Square className="w-3.5 h-3.5 fill-current text-amber-500" />
        ) : (
          <Play className="w-3.5 h-3.5 fill-current text-green-500" />
        )}
      </Button>

      {onFormat && (
        <Button
          variant="ghost"
          size="icon"
          onClick={onFormat}
          disabled={isRunning}
          title={t(I18N_KEYS.COMMON.FORMAT_SQL)}
          className={actionButtonClass}
        >
          <WandSparkles className="w-3.5 h-3.5" />
        </Button>
      )}

      {/* Divider */}
      <div className="mx-1 workbench-toolbar-divider" />

      {/* Transaction Mode Selector */}
      {showTransactionControls && (
        <TransactionModeSelector
          transactionMode={transactionMode}
          isolationLevel={isolationLevel}
          onTransactionModeChange={setTransactionMode}
          onIsolationLevelChange={setIsolationLevel}
        />
      )}

      {!local && onScriptModeChange && (
        <select
          aria-label="SQL script execution mode"
          className="workbench-pill theme-text-primary text-[10px]"
          value={scriptMode ?? 'ATOMIC'}
          disabled={isRunning}
          onChange={event => onScriptModeChange(event.target.value as SqlBatchMode)}
          title="Atomic: validated queries and DML only. Stepwise: each statement commits separately, including DDL."
        >
          <option value="ATOMIC">Atomic script</option>
          <option value="STEPWISE">Stepwise script</option>
        </select>
      )}
      {onTimeoutSecondsChange && (
        <label className="flex items-center gap-1 text-[10px]" title="Per-statement timeout in seconds">
          Timeout
          <input type="number" aria-label="SQL timeout seconds" min={1} max={300}
            value={timeoutSeconds ?? 30} disabled={isRunning}
            onChange={event => onTimeoutSecondsChange(Math.min(300, Math.max(1, Number(event.target.value) || 1)))}
            className="workbench-pill w-14 text-[10px]" />s
        </label>
      )}
      {onMaxRowsChange && (
        <label className="flex items-center gap-1 text-[10px]" title="Maximum rows returned per result">
          Rows
          <input type="number" aria-label="SQL maximum rows" min={1} max={100000}
            value={maxRows ?? 10000} disabled={isRunning}
            onChange={event => onMaxRowsChange(Math.min(100000, Math.max(1, Number(event.target.value) || 1)))}
            className="workbench-pill w-20 text-[10px]" />
        </label>
      )}

      {extraActions && (
        <>
          {/* Divider */}
          <div className="mx-1 workbench-toolbar-divider" />
          <div className="flex items-center gap-1">
            {extraActions}
          </div>
        </>
      )}

      {/* Commit and Rollback Buttons (Manual Mode Only) */}
      {showTransactionControls && transactionMode === TransactionMode.MANUAL && (
        <>
          <Button
            variant="ghost"
            size="icon"
            className={actionButtonClass}
            title={t(I18N_KEYS.WORKSPACE.COMMIT)}
          >
            <CheckCircle className="w-3.5 h-3.5 text-green-500 hover:text-green-600" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className={actionButtonClass}
            title={t(I18N_KEYS.WORKSPACE.ROLLBACK)}
          >
            <RotateCcw className="w-3.5 h-3.5 text-orange-500 hover:text-orange-600" />
          </Button>
        </>
      )}

      {/* Divider */}
      <div className="mx-1 workbench-toolbar-divider" />

      {/* Stop Button */}
      <Button
        variant="ghost"
        size="icon"
        onClick={onStop}
        disabled={!isRunning}
        title={t(I18N_KEYS.COMMON.STOP)}
        className={actionButtonClass}
      >
        <Square className={`w-3.5 h-3.5 fill-current ${isRunning ? 'text-red-500' : 'text-gray-400'}`} />
      </Button>

      {/* Flex spacer */}
      <div className="flex-1" />

      {/* Right: Database Selector */}
      {connectionId && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button className="workbench-pill">
              <span className="theme-text-primary font-medium">{currentDatabase || 'db'}</span>
              <span className="text-[9px] theme-text-secondary">▾</span>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-[140px]">
            {loadingDatabases ? (
              <div className="px-3 py-2 text-[10px] theme-text-secondary">
                {t(I18N_KEYS.COMMON.LOADING)}...
              </div>
            ) : databases.length === 0 ? (
              <div className="px-3 py-2 text-[10px] theme-text-secondary">
                {t(I18N_KEYS.COMMON.NO_DATA)}
              </div>
            ) : (
              <>
                {databases.map((db) => (
                  <DropdownMenuItem
                    key={db}
                    onClick={() => onDatabaseChange?.(db)}
                    className={`text-[11px] px-2 py-1.5 ${
                      currentDatabase === db
                        ? 'theme-text-primary font-semibold'
                        : 'theme-text-secondary'
                    }`}
                  >
                    <span className="w-4">
                      {currentDatabase === db && <span>✓</span>}
                    </span>
                    <span className="ml-2">{db}</span>
                  </DropdownMenuItem>
                ))}
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </>
  );
}

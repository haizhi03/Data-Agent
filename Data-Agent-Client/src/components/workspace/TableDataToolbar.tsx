import { Play, Plus, Minus, FileText, ChevronDown, ChevronUp, RefreshCcw, Search, X, ArrowUp } from 'lucide-react';
import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { TransactionModeSelector } from './TransactionModeSelector';
import { TransactionMode, IsolationLevel } from '../../constants/transactionSettings';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';
import { cn } from '../../lib/utils';

interface TableDataToolbarProps {
  loading: boolean;
  isTable: boolean;
  viewMode: 'grid' | 'transpose';
  hasRowSelection: boolean;
  hasPendingOps: boolean;
  pendingCount: number;
  submittingEdits: boolean;
  txMode: TransactionMode;
  isolationLevel: IsolationLevel;
  displayDbLabel: string;
  connectionName?: string | null;
  databases: string[];
  loadingDatabases: boolean;
  onRun: () => void;
  onRefresh: () => void;
  onSubmit: () => void;
  onTransactionModeChange: (mode: TransactionMode) => void;
  onIsolationLevelChange: (level: IsolationLevel) => void;
  onAddRow: () => void;
  onDeleteRow: () => void;
  onOpenDdl: () => void;
  onDatabaseChange: (db: string) => void;
  onViewModeChange: (mode: 'grid' | 'transpose') => void;
  findVisible: boolean;
  findDisabled: boolean;
  searchTerm: string;
  matchCount: number;
  currentMatchIndex: number;
  searchInputRef: Ref<HTMLInputElement>;
  onToggleFind: () => void;
  onSearchTermChange: (value: string) => void;
  onFindNext: () => void;
  onFindPrevious: () => void;
  onCloseFind: () => void;
}

export function TableDataToolbar({
  loading,
  isTable,
  viewMode,
  hasRowSelection,
  hasPendingOps,
  pendingCount,
  submittingEdits,
  txMode,
  isolationLevel,
  displayDbLabel,
  connectionName,
  databases,
  loadingDatabases,
  onRun,
  onRefresh,
  onSubmit,
  onTransactionModeChange,
  onIsolationLevelChange,
  onAddRow,
  onDeleteRow,
  onOpenDdl,
  onDatabaseChange,
  onViewModeChange,
  findVisible,
  findDisabled,
  searchTerm,
  matchCount,
  currentMatchIndex,
  searchInputRef,
  onToggleFind,
  onSearchTermChange,
  onFindNext,
  onFindPrevious,
  onCloseFind,
}: TableDataToolbarProps) {
  const { t } = useTranslation();
  const isTransposeMode = viewMode === 'transpose';
  const matchDisplay = matchCount > 0 ? `${currentMatchIndex + 1}/${matchCount}` : '0/0';
  const noMatches = matchCount === 0;

  return (
    <div className="h-8 flex items-center px-2 theme-bg-main border-b theme-border text-[10px] theme-text-secondary shrink-0 gap-1">
      <Button
        variant="ghost"
        size="icon"
        onClick={onRun}
        disabled={loading}
        title={t(I18N_KEYS.COMMON.EXECUTE_QUERY)}
        className="h-6 w-6"
      >
        <Play className="w-3.5 h-3.5 fill-current text-green-500" />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        onClick={onRefresh}
        disabled={loading}
        title={t(I18N_KEYS.COMMON.REFRESH)}
        className="h-6 w-6"
      >
        <RefreshCcw className="w-3.5 h-3.5 theme-text-secondary" />
      </Button>
      <TransactionModeSelector
        transactionMode={txMode}
        isolationLevel={isolationLevel}
        onTransactionModeChange={onTransactionModeChange}
        onIsolationLevelChange={onIsolationLevelChange}
      />
      <button
        type="button"
        onClick={onSubmit}
        disabled={!hasPendingOps || submittingEdits}
        title={t(I18N_KEYS.EXPLORER.BATCH_SUBMIT_TITLE)}
        className={cn(
          'h-6 min-w-[24px] rounded px-1 inline-flex items-center justify-center gap-1 transition-colors',
          hasPendingOps
            ? 'bg-green-500 text-white hover:bg-green-600'
            : 'bg-transparent text-gray-400 dark:text-gray-500',
        )}
      >
        {submittingEdits ? (
          <span className="h-3 w-3 rounded-full border-[1.5px] border-white border-t-transparent animate-spin" />
        ) : (
          <ArrowUp className="w-3.5 h-3.5" />
        )}
        {hasPendingOps && (
          <span className="text-[10px] font-semibold leading-none tabular-nums">{pendingCount}</span>
        )}
      </button>
      <div className="w-px h-4 bg-border mx-0.5" />
      <Button
        variant="ghost"
        size="icon"
        className="h-6 w-6"
        title={t(I18N_KEYS.EXPLORER.ADD_ROW)}
        onClick={onAddRow}
        disabled={!isTable || loading || isTransposeMode}
      >
        <Plus className="w-3.5 h-3.5 theme-text-secondary" />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        className="h-6 w-6"
        title={t(I18N_KEYS.EXPLORER.DELETE_ROW)}
        onClick={onDeleteRow}
        disabled={!isTable || !hasRowSelection || isTransposeMode}
      >
        <Minus className="w-3.5 h-3.5 theme-text-secondary" />
      </Button>
      <div className="w-px h-4 bg-border mx-0.5" />
      <Button
        variant="ghost"
        size="sm"
        className="h-6 px-2 gap-1 text-[11px]"
        onClick={onOpenDdl}
        title={t(I18N_KEYS.EXPLORER.VIEW_DDL)}
      >
        <FileText className="w-3.5 h-3.5 theme-text-secondary" />
        DDL
      </Button>
      <div className="w-px h-4 bg-border mx-0.5" />
      <Button
        variant="ghost"
        size="icon"
        className="h-6 w-6"
        title={t(I18N_KEYS.EXPLORER.FIND_TITLE)}
        onClick={onToggleFind}
        disabled={findDisabled}
        data-active={findVisible ? 'true' : undefined}
      >
        <Search className="w-3.5 h-3.5 theme-text-secondary" />
      </Button>
      {findVisible && (
        <div className="flex items-center gap-1">
          <input
            ref={searchInputRef}
            type="text"
            value={searchTerm}
            onChange={(e) => onSearchTermChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                if (e.shiftKey) {
                  onFindPrevious();
                } else {
                  onFindNext();
                }
              } else if (e.key === 'Escape') {
                e.preventDefault();
                onCloseFind();
              }
            }}
            placeholder={t(I18N_KEYS.EXPLORER.FIND_PLACEHOLDER)}
            className="h-6 w-40 rounded border border-gray-300 bg-white px-2 text-[11px] text-gray-900 outline-none placeholder:text-gray-400 focus:border-blue-500 dark:border-gray-600 dark:bg-gray-900 dark:text-gray-100 dark:placeholder:text-gray-500"
          />
          <span
            className={cn(
              'min-w-[38px] text-center text-[10px] tabular-nums',
              noMatches && searchTerm.trim() ? 'text-red-500' : 'theme-text-secondary',
            )}
          >
            {matchDisplay}
          </span>
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6"
            onClick={onFindPrevious}
            disabled={noMatches}
            title={t(I18N_KEYS.EXPLORER.FIND_PREVIOUS)}
          >
            <ChevronUp className="w-3.5 h-3.5 theme-text-secondary" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6"
            onClick={onFindNext}
            disabled={noMatches}
            title={t(I18N_KEYS.EXPLORER.FIND_NEXT)}
          >
            <ChevronDown className="w-3.5 h-3.5 theme-text-secondary" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6"
            onClick={onCloseFind}
            title={t(I18N_KEYS.EXPLORER.FIND_CLOSE)}
          >
            <X className="w-3.5 h-3.5 theme-text-secondary" />
          </Button>
        </div>
      )}
      <div className="w-px h-4 bg-border mx-0.5" />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="h-6 px-2 rounded flex items-center gap-1 text-[10px] theme-text-primary hover:bg-accent/30 transition-colors">
            <span>{viewMode === 'grid' ? t(I18N_KEYS.EXPLORER.GRID_VIEW) : t(I18N_KEYS.EXPLORER.TRANSPOSE_VIEW)}</span>
            <ChevronDown className="w-3 h-3 theme-text-secondary" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="min-w-[100px]">
          <DropdownMenuItem
            onClick={() => onViewModeChange('grid')}
            className={`text-[11px] px-2 py-1.5 ${
              viewMode === 'grid' ? 'theme-text-primary font-semibold' : 'theme-text-secondary'
            }`}
          >
            <span className="w-4">{viewMode === 'grid' && <span>✓</span>}</span>
            <span className="ml-2">{t(I18N_KEYS.EXPLORER.GRID_VIEW)}</span>
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => onViewModeChange('transpose')}
            className={`text-[11px] px-2 py-1.5 ${
              viewMode === 'transpose' ? 'theme-text-primary font-semibold' : 'theme-text-secondary'
            }`}
          >
            <span className="w-4">{viewMode === 'transpose' && <span>✓</span>}</span>
            <span className="ml-2">{t(I18N_KEYS.EXPLORER.TRANSPOSE_VIEW)}</span>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <div className="flex-1" />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="h-6 px-2 rounded flex items-center gap-1 text-[11px] theme-text-primary hover:bg-accent/30 transition-colors">
            <span>{displayDbLabel || connectionName}</span>
            <ChevronDown className="w-3 h-3 theme-text-secondary" />
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
            databases.map((db) => (
              <DropdownMenuItem
                key={db}
                onClick={() => onDatabaseChange(db)}
                className={`text-[11px] px-2 py-1.5 ${
                  displayDbLabel === db
                    ? 'theme-text-primary font-semibold'
                    : 'theme-text-secondary'
                }`}
              >
                <span className="w-4">{displayDbLabel === db && <span>✓</span>}</span>
                <span className="ml-2">{db}</span>
              </DropdownMenuItem>
            ))
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

import { cn } from '../../lib/utils';
import type { ExecuteSqlResponse } from '../../types/sql';
import { statementFirstLine, statementStatusColor, statementStatusLabel } from './statementStatus';

interface StatementResultRowProps {
  result: ExecuteSqlResponse;
  index: number;
  selected?: boolean;
  onSelect: () => void;
}

/** One clickable row in a per-statement result list (shared by results panel + SQL script dialog). */
export function StatementResultRow({ result, index, selected = false, onSelect }: StatementResultRowProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'flex w-full items-start gap-2 rounded px-2 py-1 text-left transition-colors',
        selected
          ? 'bg-[color:var(--workbench-chip-hover-bg)]'
          : 'hover:bg-[color:var(--workbench-chip-hover-bg)]'
      )}
    >
      <span className={cn('mt-1 h-2 w-2 shrink-0 rounded-full', statementStatusColor(result))} />
      <span className="min-w-0 flex-1">
        <span className="flex items-baseline gap-2">
          <span className="shrink-0 font-mono text-[10px] theme-text-secondary">#{index + 1}</span>
          <span className="min-w-0 flex-1 truncate font-mono text-[11px] theme-text-primary">
            {statementFirstLine(result) || '(statement)'}
          </span>
        </span>
        <span className="block truncate text-[10px] theme-text-secondary">
          {statementStatusLabel(result)}
          {result.transactionOutcome && result.transactionOutcome !== 'NONE'
            ? ` · ${result.transactionOutcome}` : ''}
          {result.errorMessage ? ` · ${result.errorMessage}` : ''}
        </span>
      </span>
    </button>
  );
}

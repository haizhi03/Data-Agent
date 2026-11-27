import type { ReactNode } from 'react';
import { ChevronDown } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';
import { ER_ROSE, ER_VIOLET } from './erDiagramEdges';
import type { EndMark } from './erDiagramRelations';

export type ErDiagramToolbarExport = 'png' | 'svg' | 'pdf' | 'json' | 'csv' | 'mmd';

function LegendSample({ mark, label, color, dashed }: { mark?: EndMark; label: string; color?: string; dashed?: boolean }) {
  const stroke = color ?? 'currentColor';
  return (
    <span className="inline-flex items-center gap-1">
      <svg width="26" height="12" aria-hidden="true">
        {mark === 'many' ? (
          <path d="M1 6 L8 1 M1 6 H25 M1 6 L8 11" fill="none" stroke={stroke} strokeWidth="1.4" strokeDasharray={dashed ? '3 2' : undefined} />
        ) : mark === 'one' ? (
          <path d="M2 1 V11 M6 6 H25" fill="none" stroke={stroke} strokeWidth="1.4" />
        ) : (
          <path d="M1 6 H25" fill="none" stroke={stroke} strokeWidth="1.4" strokeDasharray="3 2" />
        )}
      </svg>
      <span>{label}</span>
    </span>
  );
}

export function ErDiagramToolbar({
  scopeLabel,
  summary,
  limitNotice,
  localTableCount,
  externalTruncatedCount,
  showFilter,
  tableNameFilter,
  onTableNameFilterChange,
  exactTableFilter,
  onExactTableFilterChange,
  showTablePicker,
  pickerDisabled,
  onOpenPicker,
  showCollapseJunctions,
  collapseJunctionTables,
  onCollapseJunctionTablesChange,
  showCanvas,
  showAllLines,
  showAllLinesDisabled,
  onToggleShowAllLines,
  exportDisabled,
  onExport,
  resetDisabled,
  onResetLayout,
  status,
}: {
  scopeLabel: string;
  summary: { tables: number; relations: number } | null;
  limitNotice: 'truncated' | 'selected' | null;
  localTableCount: number;
  externalTruncatedCount: number | null;
  showFilter: boolean;
  tableNameFilter: string;
  onTableNameFilterChange: (value: string) => void;
  exactTableFilter: boolean;
  onExactTableFilterChange: (value: boolean) => void;
  showTablePicker: boolean;
  pickerDisabled: boolean;
  onOpenPicker: () => void;
  showCollapseJunctions: boolean;
  collapseJunctionTables: boolean;
  onCollapseJunctionTablesChange: (value: boolean) => void;
  showCanvas: boolean;
  showAllLines: boolean;
  showAllLinesDisabled: boolean;
  onToggleShowAllLines: () => void;
  exportDisabled: boolean;
  onExport: (format: ErDiagramToolbarExport) => void;
  resetDisabled: boolean;
  onResetLayout: () => void;
  status: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div className="shrink-0 border-b theme-border text-xs theme-text-secondary">
      <div className="flex min-h-10 flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
          <span className="font-medium theme-text-primary">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM)}</span>
          <span>{scopeLabel}</span>
          {summary && (
            <span>
              {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SUMMARY, {
                tables: summary.tables,
                relations: summary.relations,
              })}
            </span>
          )}
          {limitNotice === 'truncated' && (
            <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_TRUNCATED, { count: localTableCount })}</span>
          )}
          {limitNotice === 'selected' && (
            <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_STATUS, { count: localTableCount })}</span>
          )}
          {externalTruncatedCount != null && (
            <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXTERNAL_TRUNCATED, {
              count: externalTruncatedCount,
            })}</span>
          )}
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-x-2 gap-y-1">
          {showFilter && (
            <div className="flex h-6 items-center rounded border theme-border">
              <input
                type="search"
                value={tableNameFilter}
                onChange={(event) => onTableNameFilterChange(event.target.value)}
                placeholder={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER)}
                aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER)}
                className="h-full w-44 bg-transparent px-2 text-[11px] theme-text-primary outline-none"
              />
              <label
                className="inline-flex h-full shrink-0 items-center gap-1 border-l theme-border px-1.5"
                title={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EXACT)}
              >
                <input
                  type="checkbox"
                  className="accent-violet-600"
                  checked={exactTableFilter}
                  aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EXACT)}
                  onChange={(event) => onExactTableFilterChange(event.target.checked)}
                />
                {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EXACT_SHORT)}
              </label>
            </div>
          )}
          {showTablePicker && (
            <button
              type="button"
              className="rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
              disabled={pickerDisabled}
              onClick={onOpenPicker}
            >
              {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES)}
            </button>
          )}
          {showCollapseJunctions && (
            <label className="inline-flex items-center gap-1">
              <input
                type="checkbox"
                className="accent-violet-600"
                checked={collapseJunctionTables}
                onChange={(event) => onCollapseJunctionTablesChange(event.target.checked)}
              />
              {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_COLLAPSE_JUNCTIONS)}
            </label>
          )}
          {showCanvas && (
            <button
              type="button"
              aria-pressed={showAllLines}
              className={`rounded border px-2 py-0.5 disabled:opacity-40 ${showAllLines ? 'border-rose-500 text-rose-600' : 'theme-border theme-text-primary'}`}
              disabled={showAllLinesDisabled}
              onClick={onToggleShowAllLines}
            >
              {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SHOW_ALL_LINES)}
            </button>
          )}
          {showCanvas && <span className="mx-0.5 h-4 shrink-0 border-l theme-border" aria-hidden="true" />}
          {showCanvas && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
                  disabled={exportDisabled}
                >
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT)}
                  <ChevronDown className="h-3 w-3" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="min-w-[8rem]">
                <DropdownMenuItem className="text-xs" onClick={() => onExport('png')}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_PNG)}
                </DropdownMenuItem>
                <DropdownMenuItem className="text-xs" onClick={() => onExport('svg')}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_SVG)}
                </DropdownMenuItem>
                <DropdownMenuItem className="text-xs" onClick={() => onExport('pdf')}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_PDF)}
                </DropdownMenuItem>
                <DropdownMenuItem className="text-xs" onClick={() => onExport('json')}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_JSON)}
                </DropdownMenuItem>
                <DropdownMenuItem className="text-xs" onClick={() => onExport('csv')}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_CSV)}
                </DropdownMenuItem>
                <DropdownMenuItem className="text-xs" onClick={() => onExport('mmd')}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_MERMAID)}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
          {showCanvas && (
            <button
              type="button"
              className="rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
              disabled={resetDisabled}
              onClick={onResetLayout}
            >
              {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_RESET_LAYOUT)}
            </button>
          )}
        </div>
      </div>
      <div className="flex h-7 min-w-0 items-center overflow-hidden border-t theme-border px-3">
        {showCanvas && (
          <span className="mr-3 inline-flex shrink-0 items-center gap-x-3 border-r theme-border pr-3">
            <LegendSample mark="one" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_ONE)} color={ER_ROSE} />
            <LegendSample mark="many" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_MANY)} color={ER_ROSE} />
            <LegendSample mark="many" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_MANY_TO_MANY)} color={ER_VIOLET} />
            <LegendSample label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_CROSS)} dashed />
          </span>
        )}
        {status}
      </div>
    </div>
  );
}

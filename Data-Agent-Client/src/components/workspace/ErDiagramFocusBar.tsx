import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { ErRelation, ErTable } from '../../services/table.service';
import { ER_FOCUS_SPREAD_AT, focusHops, visibleFocusHops, type ErFocusSplit } from './erDiagramFocus';
import { cardinalityLabel, relationMapping, viaTableName } from './erDiagramRelations';
import type { PinnedErLinks } from './useErDiagramSelection';

export function ErDiagramRelationStatus({
  filterEmpty,
  pinnedLinks,
  pinnedSplit,
  pinnedKeys,
  selectedTableKeys,
  activeRelations,
}: {
  filterEmpty: boolean;
  pinnedLinks: PinnedErLinks | null;
  pinnedSplit: ErFocusSplit | null;
  pinnedKeys: ReadonlySet<string>;
  selectedTableKeys: ReadonlySet<string>;
  activeRelations: ErRelation[];
}) {
  const { t } = useTranslation();
  const statusSplit = pinnedSplit ?? { inside: [], outside: [] };
  const statusKeys = pinnedLinks ? pinnedKeys : selectedTableKeys;
  const focusHopsShown = visibleFocusHops(focusHops(statusSplit.outside, statusKeys));
  const focusStatus = !pinnedLinks ? '' : [
    statusSplit.inside.length === 0 && statusSplit.outside.length === 0
      ? t(I18N_KEYS.EXPLORER.ER_FOCUS_NONE)
      : '',
    statusSplit.inside.length > 0
      ? t(I18N_KEYS.EXPLORER.ER_FOCUS_INSIDE, { count: statusSplit.inside.length })
      : '',
    ...focusHopsShown.shown.map((hop) => t(I18N_KEYS.EXPLORER.ER_FOCUS_HOP, { outside: hop.outside, link: hop.link })),
    focusHopsShown.extra > 0
      ? t(I18N_KEYS.EXPLORER.ER_FOCUS_HOP_MORE, { count: focusHopsShown.extra })
      : '',
  ].filter(Boolean).join('。');
  const relationSummary = activeRelations.map((relation) => {
    const via = viaTableName(relation);
    const text = `${relationMapping(relation)} ${cardinalityLabel(relation)}`;
    return via ? `${text} ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_VIA, { table: via })}` : text;
  }).join(' · ');
  const statusText = filterEmpty
    ? t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EMPTY)
    : (focusStatus || relationSummary || t(I18N_KEYS.EXPLORER.ER_DIAGRAM_HINT));

  if (pinnedLinks) {
    return <span className="min-w-0 flex-1 truncate theme-text-primary" title={statusText}>{focusStatus}</span>;
  }
  if (activeRelations.length > 0) {
    return (
      <span className="min-w-0 flex-1 truncate theme-text-primary" title={statusText}>
        {activeRelations.map((relation, index) => {
          const via = viaTableName(relation);
          return (
            <span key={`${relationMapping(relation)}-${index}`}>
              {index > 0 ? <span className="px-1.5 theme-text-secondary">·</span> : null}
              {relationMapping(relation)}
              {' '}
              <span className={relation.cardinality === 'MANY_TO_MANY' ? 'text-violet-600' : undefined}>
                {cardinalityLabel(relation)}
              </span>
              {via ? ` ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_VIA, { table: via })}` : ''}
            </span>
          );
        })}
      </span>
    );
  }
  return <span className="min-w-0 flex-1 truncate" title={statusText}>{statusText}</span>;
}

export function ErDiagramFocusActions({
  selectedTables,
  pinnedLinks,
  onlyTableIds,
  onPinLinks,
  onClearLinks,
  onShowOnly,
  onShowAll,
  onQuery,
}: {
  selectedTables: ErTable[];
  pinnedLinks: PinnedErLinks | null;
  onlyTableIds: string[] | null;
  onPinLinks: () => void;
  onClearLinks: () => void;
  onShowOnly: () => void;
  onShowAll: () => void;
  onQuery: () => void;
}) {
  const { t } = useTranslation();
  if (selectedTables.length === 0 && !pinnedLinks && !onlyTableIds) return null;
  return (
    <div className="absolute bottom-4 left-1/2 z-20 flex w-[min(36rem,calc(100%-2rem))] -translate-x-1/2 flex-col gap-2 rounded-lg border theme-border theme-bg-popup px-3 py-2 shadow-lg">
      {selectedTables.length > 0 && (
        <div className="flex items-center justify-between gap-3 text-xs">
          <span className="font-medium theme-text-primary">
            {t(I18N_KEYS.EXPLORER.ER_FOCUS_WATCHING, { count: selectedTables.length })}
          </span>
          {selectedTables.length > ER_FOCUS_SPREAD_AT && (
            <span className="theme-text-secondary">{t(I18N_KEYS.EXPLORER.ER_FOCUS_SPREAD)}</span>
          )}
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        {selectedTables.length > 0 && (
          <button
            type="button"
            className="rounded border theme-border px-2 py-1 text-xs theme-text-primary"
            onMouseDown={(event) => event.stopPropagation()}
            onClick={onPinLinks}
          >
            {t(I18N_KEYS.EXPLORER.ER_FOCUS_CONNECT)}
          </button>
        )}
        {pinnedLinks && (
          <button
            type="button"
            className="rounded border theme-border px-2 py-1 text-xs theme-text-primary"
            onMouseDown={(event) => event.stopPropagation()}
            onClick={onClearLinks}
          >
            {t(I18N_KEYS.EXPLORER.ER_FOCUS_CLEAR)}
          </button>
        )}
        {selectedTables.length > 0 && (
          <button
            type="button"
            className="rounded border theme-border px-2 py-1 text-xs theme-text-primary"
            onMouseDown={(event) => event.stopPropagation()}
            onClick={onShowOnly}
          >
            {t(I18N_KEYS.EXPLORER.ER_FOCUS_ONLY)}
          </button>
        )}
        {onlyTableIds && (
          <button
            type="button"
            className="rounded border theme-border px-2 py-1 text-xs theme-text-primary"
            onMouseDown={(event) => event.stopPropagation()}
            onClick={onShowAll}
          >
            {t(I18N_KEYS.EXPLORER.ER_FOCUS_SHOW_ALL)}
          </button>
        )}
        {selectedTables.length > 0 && (
          <button
            type="button"
            className="rounded bg-blue-600 px-2 py-1 text-xs text-white"
            onMouseDown={(event) => event.stopPropagation()}
            onClick={onQuery}
          >
            {t(I18N_KEYS.EXPLORER.ER_FOCUS_QUERY)}
          </button>
        )}
      </div>
    </div>
  );
}

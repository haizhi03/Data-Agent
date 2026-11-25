import { Panel, useReactFlow, useViewport } from '@xyflow/react';
import { Maximize2, Minus, Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { ER_MAX_ZOOM, ER_MIN_ZOOM, ER_PANEL_MARGIN, ER_ZOOM_BAR_HEIGHT, formatZoomPercent } from './erDiagramLayout';

export function ErDiagramZoomBar({
  disabled = false,
  minZoom = ER_MIN_ZOOM,
  onFit,
}: {
  disabled?: boolean;
  minZoom?: number;
  onFit: () => void;
}) {
  const { t } = useTranslation();
  const { zoom } = useViewport();
  const { zoomIn, zoomOut, zoomTo } = useReactFlow();
  const percent = formatZoomPercent(zoom);

  return (
    <Panel position="bottom-right" className="er-diagram-zoom-bar" style={{ width: 340, maxWidth: `calc(100% - ${ER_PANEL_MARGIN * 2}px)` }}>
      <div
        className="pointer-events-auto flex w-full min-w-0 items-center gap-1 rounded border theme-border theme-bg-popup px-1.5 text-xs theme-text-primary shadow-sm"
        style={{ height: ER_ZOOM_BAR_HEIGHT }}
      >
        <button
          type="button"
          className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded theme-bg-hover disabled:opacity-40"
          aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_ZOOM_OUT)}
          disabled={disabled}
          onClick={() => { void zoomOut(); }}
        >
          <Minus size={14} />
        </button>
        <input
          type="range"
          className="er-zoom-range min-w-0 flex-1"
          min={minZoom}
          max={ER_MAX_ZOOM}
          step="any"
          value={Math.min(ER_MAX_ZOOM, Math.max(minZoom, zoom))}
          disabled={disabled}
          aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_ZOOM)}
          aria-valuetext={percent}
          onChange={(event) => { void zoomTo(Number(event.target.value), { duration: 0 }); }}
        />
        <button
          type="button"
          className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded theme-bg-hover disabled:opacity-40"
          aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_ZOOM_IN)}
          disabled={disabled}
          onClick={() => { void zoomIn(); }}
        >
          <Plus size={14} />
        </button>
        <span className="mx-0.5 h-4 shrink-0 border-l theme-border" aria-hidden="true" />
        <button
          type="button"
          className="inline-flex h-6 shrink-0 items-center whitespace-nowrap rounded px-1.5 theme-bg-hover disabled:opacity-40"
          title={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FIT)}
          aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FIT)}
          disabled={disabled}
          onClick={onFit}
        >
          <Maximize2 size={14} className="er-zoom-fit-icon" aria-hidden="true" />
          <span className="er-zoom-fit-label">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FIT)}</span>
        </button>
        <button
          type="button"
          className="min-w-[3.25rem] shrink-0 rounded px-1 text-right tabular-nums theme-bg-hover disabled:opacity-40"
          title={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_ZOOM_RESET)}
          aria-label={`${percent}, ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_ZOOM_RESET)}`}
          disabled={disabled}
          onClick={() => { void zoomTo(1, { duration: 0 }); }}
        >
          {percent}
        </button>
      </div>
    </Panel>
  );
}

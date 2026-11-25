import { Panel, useReactFlow, useViewport } from '@xyflow/react';
import { Minus, Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { ER_MAX_ZOOM, ER_MINIMAP_WIDTH, ER_MIN_ZOOM, ER_ZOOM_BAR_HEIGHT, formatZoomPercent } from './erDiagramLayout';

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
    <Panel position="bottom-right">
      <div
        className="pointer-events-auto flex w-max items-center gap-1 rounded border theme-border theme-bg-popup px-1.5 text-xs theme-text-primary shadow-sm"
        style={{ minWidth: ER_MINIMAP_WIDTH, height: ER_ZOOM_BAR_HEIGHT }}
      >
        <button
          type="button"
          className="inline-flex h-6 w-6 items-center justify-center rounded theme-bg-hover disabled:opacity-40"
          aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_ZOOM_OUT)}
          disabled={disabled}
          onClick={() => { void zoomOut(); }}
        >
          <Minus size={14} />
        </button>
        <input
          type="range"
          className="er-zoom-range w-24 shrink-0"
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
          className="inline-flex h-6 w-6 items-center justify-center rounded theme-bg-hover disabled:opacity-40"
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
          disabled={disabled}
          onClick={onFit}
        >
          {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FIT)}
        </button>
        <button
          type="button"
          className="min-w-[3.25rem] rounded px-1 text-right tabular-nums theme-bg-hover disabled:opacity-40"
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

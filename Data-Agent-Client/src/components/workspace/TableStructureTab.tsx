import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { Group, Panel, Separator, useDefaultLayout } from 'react-resizable-panels';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, Copy, Database, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { DatabaseTypeIcon } from '../common/DatabaseTypeIcon';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { EXPLORER_SQL_OBJECT_CHANGED } from '../../constants/explorer';
import { useToast } from '../../hooks/useToast';
import { resolveErrorMessage } from '../../lib/errorMessage';
import { isDmDbType } from '../../lib/sqlComment';
import {
  acceptPreview,
  adoptStructure,
  applyColumnLength,
  applyColumnType,
  canExecute,
  emptyCheck,
  emptyColumn,
  emptyForeignKey,
  emptyIndex,
  renameColumnReferences,
  structuresEqual,
  togglePrimaryKey,
  toStructurePayload,
  type BoundPreview,
} from '../../lib/tableStructureDraft';
import { tableService } from '../../services/table.service';
import { tableStructureService } from '../../services/tableStructure.service';
import { triggerService, type TriggerMetadata } from '../../services/trigger.service';
import { useStructureEditorStore } from '../../store/structureEditorStore';
import type { TableTabMetadata } from '../../types/tab';
import type {
  StructureChange,
  StructureDefaultKind,
  StructurePreview,
  TableDraft,
  TableStructure,
} from '../../types/tableStructure';

interface TableStructureTabProps {
  tabId: string;
  metadata: TableTabMetadata;
}

type SectionId = 'ddl' | 'columns' | 'indexes' | 'foreignKeys' | 'triggers' | 'checks' | 'diff';

const CONTROL = 'h-8 rounded border border-border bg-transparent px-2 text-sm font-semibold outline-none focus:border-primary';
const FIELD = `${CONTROL} w-full min-w-0`;
const DEFAULT_KINDS: StructureDefaultKind[] = ['NONE', 'NULL', 'VALUE', 'EXPRESSION'];
const RULES = ['RESTRICT', 'CASCADE', 'SET NULL', 'NO ACTION', 'SET DEFAULT'];

export function TableStructureTab({ tabId, metadata }: TableStructureTabProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const setDirty = useStructureEditorStore((state) => state.setDirty);
  const [baseline, setBaseline] = useState<TableStructure | null>(null);
  const [draft, setDraft] = useState<TableDraft | null>(null);
  const [revision, setRevision] = useState(0);
  const revisionRef = useRef(0);
  const [boundPreview, setBoundPreview] = useState<BoundPreview | null>(null);
  const [section, setSection] = useState<SectionId>('columns');
  const [loading, setLoading] = useState(true);
  const [previewing, setPreviewing] = useState(false);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ddl, setDdl] = useState('');
  const [ddlError, setDdlError] = useState<string | null>(null);
  const [triggers, setTriggers] = useState<TriggerMetadata[]>([]);
  const [selectedTrigger, setSelectedTrigger] = useState<string | null>(null);
  const [triggerDdl, setTriggerDdl] = useState('');
  const { defaultLayout: previewLayout, onLayoutChanged: onPreviewLayoutChanged } = useDefaultLayout({
    id: 'table-structure-sql-preview',
    storage: localStorage,
  });

  const scope = {
    connectionId: metadata.connectionId,
    catalog: metadata.catalog ?? metadata.databaseName,
    schema: metadata.schema ?? metadata.schemaName,
    tableName: metadata.objectName,
  };

  const adopt = useCallback((structure: TableStructure) => {
    revisionRef.current += 1;
    setRevision(revisionRef.current);
    setBaseline(structure);
    setDraft(adoptStructure(structure));
    setBoundPreview(null);
    setPreviewing(true);
    setError(null);
  }, []);

  const loadSavedObjects = useCallback(async () => {
    const connectionId = String(scope.connectionId);
    const [ddlResult, triggerResult] = await Promise.allSettled([
      tableService.getTableDdl(connectionId, scope.tableName, scope.catalog ?? undefined, scope.schema ?? undefined),
      triggerService.listTriggers(connectionId, scope.catalog ?? undefined, scope.schema ?? undefined, scope.tableName),
    ]);
    setDdl(ddlResult.status === 'fulfilled' ? ddlResult.value : '');
    setDdlError(ddlResult.status === 'rejected'
      ? resolveErrorMessage(ddlResult.reason, t(I18N_KEYS.EXPLORER.LOAD_DDL_FAILED))
      : null);
    setTriggers(triggerResult.status === 'fulfilled' ? triggerResult.value : []);
    setSelectedTrigger(null);
    setTriggerDdl('');
  }, [scope.catalog, scope.connectionId, scope.schema, scope.tableName, t]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      adopt(await tableStructureService.load(scope));
      await loadSavedObjects();
    } catch (err) {
      setError(resolveErrorMessage(err, t(I18N_KEYS.EXPLORER.STRUCTURE_LOAD_FAILED)));
    } finally {
      setLoading(false);
    }
  }, [adopt, loadSavedObjects, scope.catalog, scope.connectionId, scope.schema, scope.tableName, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const dirty = baseline != null && draft != null && !structuresEqual(toStructurePayload(draft), baseline);

  useEffect(() => {
    setDirty(tabId, dirty);
    return () => setDirty(tabId, false);
  }, [dirty, setDirty, tabId]);

  useEffect(() => {
    if (!baseline || !draft) return;
    const requestRevision = revisionRef.current;
    const payload = toStructurePayload(draft);
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setPreviewing(true);
      tableStructureService.preview({ ...scope, baseline, draft: payload })
        .then((result) => {
          if (cancelled) return;
          const accepted = acceptPreview(requestRevision, revisionRef.current, result);
          if (accepted) {
            setBoundPreview({ revision: requestRevision, payload, result: accepted });
            setError(null);
          }
        })
        .catch((err) => {
          if (!cancelled && revisionRef.current === requestRevision) {
            setBoundPreview(null);
            setError(resolveErrorMessage(err, t(I18N_KEYS.EXPLORER.STRUCTURE_PREVIEW_FAILED)));
          }
        })
        .finally(() => {
          if (!cancelled && revisionRef.current === requestRevision) setPreviewing(false);
        });
    }, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [baseline, draft, scope.catalog, scope.connectionId, scope.schema, scope.tableName, t]);

  const updateDraft = (recipe: (current: TableDraft) => TableDraft) => {
    revisionRef.current += 1;
    setRevision(revisionRef.current);
    setPreviewing(true);
    setDraft((current) => (current ? recipe(current) : current));
  };

  const refresh = () => {
    if (dirty && !window.confirm(t(I18N_KEYS.EXPLORER.STRUCTURE_DISCARD))) return;
    void load();
  };

  const execute = async () => {
    if (!baseline || !boundPreview || !canExecute(revision, boundPreview, boundPreview.result.statements.length)) return;
    const confirmed = boundPreview.result;
    const payload = boundPreview.payload;
    const destructive = confirmed.changes.filter((change) => change.destructive);
    if (destructive.length > 0) {
      const lines = destructive.map((change) => changeLabel(change, t)).join('\n');
      if (!window.confirm(`${t(I18N_KEYS.EXPLORER.STRUCTURE_EXECUTE_CONFIRM)}\n\n${lines}`)) return;
    }
    setApplying(true);
    try {
      const result = await tableStructureService.apply({ ...scope, baseline, draft: payload });
      if (result.structure) adopt(result.structure);
      await loadSavedObjects();
      if (result.errorMessage) {
        toast.error(result.errorMessage);
      } else {
        toast.success(t(I18N_KEYS.EXPLORER.STRUCTURE_EXECUTE_SUCCESS));
      }
      if (result.statements.length > 0 && result.failedIndex !== 0) {
        window.dispatchEvent(new CustomEvent(EXPLORER_SQL_OBJECT_CHANGED, {
          detail: {
            connectionId: scope.connectionId,
            catalog: scope.catalog,
            schema: scope.schema,
            objectType: 'TABLE',
          },
        }));
      }
    } catch (err) {
      toast.error(resolveErrorMessage(err, t(I18N_KEYS.EXPLORER.STRUCTURE_EXECUTE_FAILED)));
    } finally {
      setApplying(false);
    }
  };

  const currentPreview = boundPreview?.revision === revision ? boundPreview.result : null;
  const executable = canExecute(revision, boundPreview, currentPreview?.statements.length ?? 0);
  const previewPending = currentPreview == null && previewing;

  const copySql = async () => {
    const sql = currentPreview?.statements.join(';\n\n') ?? '';
    if (!sql) return;
    await navigator.clipboard.writeText(`${sql};`);
    toast.success(t(I18N_KEYS.EXPLORER.STRUCTURE_COPIED));
  };

  if (loading && !draft) {
    return <div className="flex h-full items-center justify-center text-xs theme-text-secondary">{t(I18N_KEYS.EXPLORER.STRUCTURE_LOADING)}</div>;
  }
  if (!draft || !baseline) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 text-xs theme-text-secondary">
        <span>{error ?? t(I18N_KEYS.EXPLORER.STRUCTURE_LOAD_FAILED)}</span>
        <Button size="sm" variant="outline" onClick={() => void load()}>{t(I18N_KEYS.EXPLORER.STRUCTURE_REFRESH)}</Button>
      </div>
    );
  }

  const sections: { id: SectionId; label: string }[] = [
    { id: 'ddl', label: t(I18N_KEYS.EXPLORER.STRUCTURE_DDL) },
    { id: 'columns', label: t(I18N_KEYS.EXPLORER.STRUCTURE_COLUMNS) },
    { id: 'indexes', label: t(I18N_KEYS.EXPLORER.STRUCTURE_INDEXES) },
    { id: 'foreignKeys', label: t(I18N_KEYS.EXPLORER.STRUCTURE_FOREIGN_KEYS) },
    { id: 'triggers', label: t(I18N_KEYS.EXPLORER.STRUCTURE_TRIGGERS) },
    { id: 'checks', label: t(I18N_KEYS.EXPLORER.STRUCTURE_CHECKS) },
    { id: 'diff', label: t(I18N_KEYS.EXPLORER.STRUCTURE_DIFF) },
  ];
  const location = [metadata.connectionName, scope.catalog, scope.schema, metadata.objectName].filter(Boolean);
  const dm = isDmDbType(metadata.dbType);

  return (
    <div className="flex h-full min-h-0 flex-col theme-bg-panel">
      <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border px-3 py-2.5">
        <div className="flex min-w-0 items-center gap-2 text-sm font-medium theme-text-secondary">
          <Database className="h-4 w-4 shrink-0 text-teal-500" />
          <div className="min-w-0 truncate">
            {location.map((part, index) => (
              <span key={`${part}-${index}`}>
                {index > 0 ? <span className="px-1.5 font-normal">/</span> : null}
                <span className={index === location.length - 1 ? 'theme-text-primary' : undefined}>{part}</span>
              </span>
            ))}
          </div>
        </div>
        <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-border bg-muted/50 px-3 py-1 text-sm font-medium theme-text-primary">
          <DatabaseTypeIcon dbType={metadata.dbType} className="h-4 w-4" />
          {databaseLabel(metadata.dbType)}
        </span>
      </div>
      <div className="flex shrink-0 flex-wrap items-end gap-3 border-b border-border px-3 py-2">
        <label className="min-w-[220px] flex-1 text-sm font-medium theme-text-secondary">
          {t(I18N_KEYS.EXPLORER.STRUCTURE_COMMENT)}
          <input className={`${FIELD} mt-1`} value={draft.comment} onChange={(event) => updateDraft((current) => ({ ...current, comment: event.target.value }))} />
        </label>
        {dm ? null : (
          <>
            <label className="w-28 text-sm font-medium theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.STRUCTURE_ENGINE)}
              <input className={`${FIELD} mt-1`} value={draft.engine} onChange={(event) => updateDraft((current) => ({ ...current, engine: event.target.value }))} />
            </label>
            <label className="w-32 text-sm font-medium theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.STRUCTURE_CHARSET)}
              <input className={`${FIELD} mt-1`} value={draft.charset} onChange={(event) => updateDraft((current) => ({ ...current, charset: event.target.value }))} />
            </label>
            <label className="w-52 text-sm font-medium theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.STRUCTURE_COLLATION)}
              <input className={`${FIELD} mt-1`} value={draft.collation} onChange={(event) => updateDraft((current) => ({ ...current, collation: event.target.value }))} />
            </label>
          </>
        )}
        <Button size="sm" variant="outline" onClick={refresh} disabled={loading}>
          <RefreshCw className="mr-1 h-3.5 w-3.5" />
          {t(I18N_KEYS.EXPLORER.STRUCTURE_REFRESH)}
        </Button>
        {dirty ? <span className="pb-1 text-sm text-amber-500">{t(I18N_KEYS.EXPLORER.STRUCTURE_DIRTY)}</span> : null}
      </div>

      <div className="flex shrink-0 gap-1 border-b border-border px-3 py-1.5">
        {sections.map((item) => (
          <button
            key={item.id}
            type="button"
            className={`rounded px-2.5 py-1 text-sm font-medium ${section === item.id ? 'bg-primary/15 text-primary' : 'theme-text-secondary hover:theme-text-primary'}`}
            onClick={() => setSection(item.id)}
          >
            {item.label}
            {item.id === 'diff' && currentPreview?.changes.length ? ` (${currentPreview.changes.length})` : ''}
          </button>
        ))}
      </div>

      <Group
        orientation="vertical"
        className="min-h-0 flex-1"
        defaultLayout={previewLayout}
        onLayoutChanged={onPreviewLayoutChanged}
      >
      <Panel id="structure-editor" className="min-h-0">
      <div className="h-full overflow-auto px-3 py-2">
        {error ? <div className="mb-2 rounded border border-destructive/40 px-2 py-1 text-xs text-destructive">{error}</div> : null}
        {section === 'ddl' ? (
          <div className="flex h-full min-h-[160px] flex-col gap-2">
            <p className="text-[11px] theme-text-secondary">{t(I18N_KEYS.EXPLORER.STRUCTURE_DDL_HINT)}</p>
            {ddlError ? <p className="text-xs text-destructive">{ddlError}</p> : null}
            <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap font-mono text-[11px] leading-5 theme-text-primary">{ddl}</pre>
          </div>
        ) : null}
        {section === 'columns' ? (
          <ColumnSection draft={draft} updateDraft={updateDraft} />
        ) : null}
        {section === 'indexes' ? (
          <IndexSection draft={draft} updateDraft={updateDraft} />
        ) : null}
        {section === 'foreignKeys' ? (
          <ForeignKeySection draft={draft} updateDraft={updateDraft} />
        ) : null}
        {section === 'triggers' ? (
          <TriggerSection
            triggers={triggers}
            selected={selectedTrigger}
            ddl={triggerDdl}
            onSelect={(name) => {
              setSelectedTrigger(name);
              setTriggerDdl('');
              void triggerService.getTriggerDdl(String(scope.connectionId), name, scope.catalog ?? undefined, scope.schema ?? undefined)
                .then(setTriggerDdl)
                .catch((err) => setTriggerDdl(resolveErrorMessage(err, t(I18N_KEYS.EXPLORER.LOAD_DDL_FAILED))));
            }}
          />
        ) : null}
        {section === 'checks' ? (
          <CheckSection draft={draft} updateDraft={updateDraft} />
        ) : null}
        {section === 'diff' ? (
          <DiffSection preview={currentPreview} previewing={previewPending} />
        ) : null}
      </div>
      </Panel>
      <Separator
        className="workbench-horizontal-resize-handle"
        title={t(I18N_KEYS.EXPLORER.STRUCTURE_RESIZE_PREVIEW)}
        aria-label={t(I18N_KEYS.EXPLORER.STRUCTURE_RESIZE_PREVIEW)}
      />
      <Panel id="structure-sql-preview" defaultSize="30%" minSize="15%" className="workbench-results-panel relative flex min-h-0 flex-col border-t theme-border">
        <div className="workbench-header flex h-11 shrink-0 items-center px-3">
          <span className="workbench-chip workbench-chip--active flex h-7 items-center rounded-lg px-3 text-[11px] font-medium theme-text-primary">
            {t(I18N_KEYS.EXPLORER.STRUCTURE_SQL_PREVIEW)}
            {previewPending ? ` · ${t(I18N_KEYS.EXPLORER.STRUCTURE_PREVIEWING)}` : ''}
          </span>
          <div className="flex-1" />
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => void copySql()} disabled={!currentPreview?.statements.length}>
              <Copy className="mr-1 h-3.5 w-3.5" />
              {t(I18N_KEYS.EXPLORER.STRUCTURE_COPY_SQL)}
            </Button>
            <Button size="sm" onClick={() => void execute()} disabled={applying || !executable}>
              {applying ? t(I18N_KEYS.EXPLORER.STRUCTURE_EXECUTING) : t(I18N_KEYS.EXPLORER.STRUCTURE_EXECUTE)}
            </Button>
          </div>
        </div>
        {currentPreview?.warnings.length ? (
          <div className="mx-3 mb-1 rounded border border-amber-500/40 px-2 py-1 text-[13px] leading-5 text-amber-600">
            {currentPreview.warnings.join(' · ')}
          </div>
        ) : null}
        <pre className="min-h-0 flex-1 overflow-auto px-3 pb-2 font-mono text-[13px] leading-5 theme-text-primary">
          {previewPending
            ? t(I18N_KEYS.EXPLORER.STRUCTURE_PREVIEWING)
            : currentPreview?.statements.length
              ? `${currentPreview.statements.join(';\n\n')};`
              : t(I18N_KEYS.EXPLORER.STRUCTURE_NO_CHANGES)}
        </pre>
      </Panel>
      </Group>
    </div>
  );
}

const COLUMN_WIDTH_KEY = 'table-structure-column-widths';

const DEFAULT_COLUMN_WIDTHS = {
  index: 40,
  name: 180,
  type: 200,
  length: 88,
  nullable: 64,
  primaryKey: 64,
  autoIncrement: 64,
  defaultValue: 240,
  comment: 240,
  actions: 112,
} as const;

type ColumnWidthKey = keyof typeof DEFAULT_COLUMN_WIDTHS;

const MIN_COLUMN_WIDTH: Record<ColumnWidthKey, number> = {
  index: 32,
  name: 72,
  type: 88,
  length: 56,
  nullable: 48,
  primaryKey: 48,
  autoIncrement: 48,
  defaultValue: 200,
  comment: 80,
  actions: 96,
};

const textMeasure = document.createElement('canvas');

function textWidth(text: string, font: string): number {
  const context = textMeasure.getContext('2d');
  if (!context) return 0;
  context.font = font;
  return context.measureText(text).width;
}

function readColumnWidths(): Record<ColumnWidthKey, number> {
  try {
    const parsed = JSON.parse(localStorage.getItem(COLUMN_WIDTH_KEY) ?? '') as Partial<Record<ColumnWidthKey, number>>;
    const next = { ...DEFAULT_COLUMN_WIDTHS };
    (Object.keys(DEFAULT_COLUMN_WIDTHS) as ColumnWidthKey[]).forEach((key) => {
      const value = parsed[key];
      if (typeof value === 'number' && Number.isFinite(value)) {
        next[key] = Math.max(MIN_COLUMN_WIDTH[key], value);
      }
    });
    return next;
  } catch {
    return { ...DEFAULT_COLUMN_WIDTHS };
  }
}

function ColumnSection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
  const { t } = useTranslation();
  const [widths, setWidths] = useState(readColumnWidths);
  const widthsRef = useRef(widths);
  const tableRef = useRef<HTMLTableElement>(null);
  const dragRef = useRef<{ key: ColumnWidthKey; startX: number; startWidth: number } | null>(null);

  useEffect(() => {
    const move = (event: PointerEvent) => {
      const drag = dragRef.current;
      if (!drag) return;
      const next = Math.max(MIN_COLUMN_WIDTH[drag.key], Math.round(drag.startWidth + event.clientX - drag.startX));
      setWidths((current) => {
        if (current[drag.key] === next) return current;
        const updated = { ...current, [drag.key]: next };
        widthsRef.current = updated;
        return updated;
      });
    };
    const stop = () => {
      if (!dragRef.current) return;
      dragRef.current = null;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      localStorage.setItem(COLUMN_WIDTH_KEY, JSON.stringify(widthsRef.current));
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      window.removeEventListener('pointercancel', stop);
    };
  }, []);

  const startResize = (key: ColumnWidthKey, event: ReactPointerEvent<HTMLSpanElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragRef.current = { key, startX: event.clientX, startWidth: widthsRef.current[key] };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  };

  const headers: Array<{ key: ColumnWidthKey; label: string; align?: 'center' }> = [
    { key: 'index', label: '#' },
    { key: 'name', label: t(I18N_KEYS.EXPLORER.STRUCTURE_NAME) },
    { key: 'type', label: t(I18N_KEYS.EXPLORER.STRUCTURE_TYPE) },
    { key: 'length', label: t(I18N_KEYS.EXPLORER.STRUCTURE_LENGTH) },
    { key: 'nullable', label: t(I18N_KEYS.EXPLORER.STRUCTURE_NULLABLE), align: 'center' },
    { key: 'primaryKey', label: t(I18N_KEYS.EXPLORER.STRUCTURE_PRIMARY_KEY), align: 'center' },
    { key: 'autoIncrement', label: t(I18N_KEYS.EXPLORER.STRUCTURE_AUTO_INCREMENT), align: 'center' },
    { key: 'defaultValue', label: t(I18N_KEYS.EXPLORER.STRUCTURE_DEFAULT) },
    { key: 'comment', label: t(I18N_KEYS.EXPLORER.STRUCTURE_COLUMN_COMMENT) },
    { key: 'actions', label: '' },
  ];
  const tableWidth = headers.reduce((sum, header) => sum + widths[header.key], 0);

  const commitWidth = (key: ColumnWidthKey, width: number) => {
    const next = Math.max(MIN_COLUMN_WIDTH[key], Math.min(640, Math.round(width)));
    setWidths((current) => {
      const updated = { ...current, [key]: next };
      widthsRef.current = updated;
      localStorage.setItem(COLUMN_WIDTH_KEY, JSON.stringify(updated));
      return updated;
    });
  };

  const fitColumn = (key: ColumnWidthKey) => {
    const font = tableRef.current ? getComputedStyle(tableRef.current).font : '600 14px sans-serif';
    const header = headers.find((item) => item.key === key)?.label ?? '';
    const inputPad = 38;
    let width = textWidth(header, font) + 24;
    if (key === 'index') {
      width = Math.max(width, textWidth(String(draft.columns.length), font) + 16);
    } else if (key === 'name') {
      draft.columns.forEach((column) => { width = Math.max(width, textWidth(column.name, font) + inputPad); });
    } else if (key === 'type') {
      draft.columns.forEach((column) => { width = Math.max(width, textWidth(column.columnType, font) + inputPad); });
    } else if (key === 'length') {
      draft.columns.forEach((column) => { width = Math.max(width, textWidth(column.length == null ? '' : String(column.length), font) + inputPad); });
    } else if (key === 'nullable' || key === 'primaryKey' || key === 'autoIncrement') {
      width = Math.max(width, 36);
    } else if (key === 'defaultValue') {
      draft.columns.forEach((column) => {
        const hasValue = column.defaultKind === 'VALUE' || column.defaultKind === 'EXPRESSION';
        const valueWidth = hasValue ? textWidth(column.defaultValue ?? '', font) + 26 : 0;
        width = Math.max(width, 128 + valueWidth);
      });
    } else if (key === 'comment') {
      draft.columns.forEach((column) => { width = Math.max(width, textWidth(column.comment, font) + inputPad); });
    } else {
      width = Math.max(width, 108);
    }
    commitWidth(key, width);
  };
  const patch = (clientId: string, recipe: (column: TableDraft['columns'][number]) => TableDraft['columns'][number]) => {
    updateDraft((current) => ({
      ...current,
      columns: current.columns.map((column) => (column.clientId === clientId ? recipe(column) : column)),
    }));
  };
  const move = (index: number, direction: -1 | 1) => {
    updateDraft((current) => {
      const target = index + direction;
      if (target < 0 || target >= current.columns.length) return current;
      const columns = [...current.columns];
      [columns[index], columns[target]] = [columns[target], columns[index]];
      return { ...current, columns };
    });
  };

  return (
    <div>
      <div className="overflow-x-auto">
        <table ref={tableRef} className="border-collapse text-sm font-semibold" style={{ width: tableWidth, tableLayout: 'fixed' }}>
          <colgroup>
            {headers.map((header) => <col key={header.key} style={{ width: widths[header.key] }} />)}
          </colgroup>
          <thead className="theme-text-secondary">
            <tr className="border-b border-border text-left">
              {headers.map((header) => (
                <th key={header.key} className={`relative py-1 ${header.align === 'center' ? 'text-center' : 'pr-2 text-left'}`}>
                  <span className="block truncate">{header.label}</span>
                  <span
                    role="separator"
                    aria-orientation="vertical"
                    aria-label={t(I18N_KEYS.EXPLORER.STRUCTURE_RESIZE_COLUMN)}
                    title={t(I18N_KEYS.EXPLORER.STRUCTURE_RESIZE_COLUMN)}
                    className="absolute inset-y-0 -right-1 z-10 w-2 cursor-col-resize touch-none hover:bg-primary/30"
                    onPointerDown={(event) => startResize(header.key, event)}
                    onDoubleClick={(event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      dragRef.current = null;
                      document.body.style.cursor = '';
                      document.body.style.userSelect = '';
                      fitColumn(header.key);
                    }}
                  />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {draft.columns.map((column, index) => {
              const inPrimaryKey = draft.primaryKey?.columns.includes(column.name) ?? false;
              const generated = column.generationExpression != null;
              const commentLocked = generated && column.generationExpression?.trim() === '';
              return (
                <tr key={column.clientId} className="border-b border-border/60">
                  <td className="overflow-hidden py-1 theme-text-secondary">{index + 1}</td>
                  <td className="overflow-hidden py-1 pr-2">
                    <input
                      className={FIELD}
                      value={column.name}
                      disabled={generated}
                      onChange={(event) => {
                        const nextName = event.target.value;
                        updateDraft((current) => renameColumnReferences({
                          ...current,
                          columns: current.columns.map((item) => (item.clientId === column.clientId ? { ...item, name: nextName } : item)),
                        }, column.name, nextName));
                      }}
                    />
                  </td>
                  <td className="overflow-hidden py-1 pr-2"><input className={FIELD} value={column.columnType} disabled={generated} onChange={(event) => patch(column.clientId, (item) => applyColumnType(item, event.target.value))} /></td>
                  <td className="overflow-hidden py-1 pr-2">
                    <input
                      className={FIELD}
                      type="number"
                      value={column.length ?? ''}
                      disabled={generated}
                      onChange={(event) => patch(column.clientId, (item) => applyColumnLength(item, event.target.value === '' ? null : Number(event.target.value)))}
                    />
                  </td>
                  <td className="py-1 text-center"><input type="checkbox" checked={column.nullable} disabled={generated} onChange={(event) => patch(column.clientId, (item) => ({ ...item, nullable: event.target.checked }))} /></td>
                  <td className="py-1 text-center">
                    <input
                      type="checkbox"
                      checked={inPrimaryKey}
                      disabled={generated || !column.name}
                      onChange={(event) => updateDraft((current) => ({
                        ...current,
                        primaryKey: togglePrimaryKey(current.primaryKey, column.name, event.target.checked),
                      }))}
                    />
                  </td>
                  <td className="py-1 text-center"><input type="checkbox" checked={column.autoIncrement} disabled={generated} onChange={(event) => patch(column.clientId, (item) => ({ ...item, autoIncrement: event.target.checked }))} /></td>
                  <td className="overflow-hidden py-1 pr-2">
                    <div className="flex min-w-0 gap-1">
                      <select
                        className={`${CONTROL} w-24 shrink-0`}
                        value={column.defaultKind}
                        disabled={generated}
                        onChange={(event) => patch(column.clientId, (item) => ({ ...item, defaultKind: event.target.value as StructureDefaultKind }))}
                      >
                        {DEFAULT_KINDS.map((kind) => <option key={kind} value={kind}>{t(defaultKindKey(kind))}</option>)}
                      </select>
                      {column.defaultKind === 'VALUE' || column.defaultKind === 'EXPRESSION' ? (
                        <input className={`${CONTROL} min-w-[4.5rem] flex-1`} value={column.defaultValue ?? ''} disabled={generated} onChange={(event) => patch(column.clientId, (item) => ({ ...item, defaultValue: event.target.value }))} />
                      ) : null}
                    </div>
                  </td>
                  <td className="overflow-hidden py-1 pr-2"><input className={FIELD} value={column.comment} disabled={commentLocked} onChange={(event) => patch(column.clientId, (item) => ({ ...item, comment: event.target.value }))} /></td>
                  <td className="overflow-hidden py-1">
                    <div className="flex">
                      <IconButton label={t(I18N_KEYS.EXPLORER.STRUCTURE_MOVE_UP)} disabled={generated} onClick={() => move(index, -1)}><ChevronUp className="h-3.5 w-3.5" /></IconButton>
                      <IconButton label={t(I18N_KEYS.EXPLORER.STRUCTURE_MOVE_DOWN)} disabled={generated} onClick={() => move(index, 1)}><ChevronDown className="h-3.5 w-3.5" /></IconButton>
                      <IconButton label={t(I18N_KEYS.EXPLORER.STRUCTURE_REMOVE)} disabled={generated} onClick={() => updateDraft((current) => ({ ...current, columns: current.columns.filter((item) => item.clientId !== column.clientId) }))}><Trash2 className="h-3.5 w-3.5" /></IconButton>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {(draft.primaryKey?.columns.length ?? 0) > 1 ? (
        <div className="mt-2 flex flex-wrap items-center gap-2 text-xs theme-text-secondary">
          <span>{t(I18N_KEYS.EXPLORER.STRUCTURE_PRIMARY_KEY_ORDER)}</span>
          {draft.primaryKey?.columns.map((name, index) => (
            <span key={`${name}-${index}`} className="inline-flex items-center gap-1 rounded border border-border px-1">
              {name}
              <IconButton
                label={t(I18N_KEYS.EXPLORER.STRUCTURE_MOVE_UP)}
                onClick={() => updateDraft((current) => moveKeyColumn(current, index, -1))}
              >
                <ChevronUp className="h-3 w-3" />
              </IconButton>
              <IconButton
                label={t(I18N_KEYS.EXPLORER.STRUCTURE_MOVE_DOWN)}
                onClick={() => updateDraft((current) => moveKeyColumn(current, index, 1))}
              >
                <ChevronDown className="h-3 w-3" />
              </IconButton>
            </span>
          ))}
        </div>
      ) : null}
      <Button className="mt-2" size="sm" variant="outline" onClick={() => updateDraft((current) => ({ ...current, columns: [...current.columns, emptyColumn()] }))}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        {t(I18N_KEYS.EXPLORER.STRUCTURE_ADD_COLUMN)}
      </Button>
    </div>
  );
}

function IndexSection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2">
      {draft.indexes.map((index) => (
        <div key={index.clientId} className="grid grid-cols-[1fr_1.4fr_auto_auto] items-center gap-2">
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_NAME)} value={index.name} disabled={index.expression} onChange={(event) => updateDraft((current) => ({ ...current, indexes: current.indexes.map((item) => item.clientId === index.clientId ? { ...item, name: event.target.value } : item) }))} />
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_COLUMNS_LIST)} value={index.columnText} disabled={index.expression} onChange={(event) => updateDraft((current) => ({ ...current, indexes: current.indexes.map((item) => item.clientId === index.clientId ? { ...item, columnText: event.target.value } : item) }))} />
          <label className="flex items-center gap-1 text-xs theme-text-secondary">
            <input type="checkbox" checked={index.unique} disabled={index.expression} onChange={(event) => updateDraft((current) => ({ ...current, indexes: current.indexes.map((item) => item.clientId === index.clientId ? { ...item, unique: event.target.checked } : item) }))} />
            {t(I18N_KEYS.EXPLORER.STRUCTURE_UNIQUE)}
          </label>
          <IconButton label={t(I18N_KEYS.EXPLORER.STRUCTURE_REMOVE)} disabled={index.expression} onClick={() => updateDraft((current) => ({ ...current, indexes: current.indexes.filter((item) => item.clientId !== index.clientId) }))}><Trash2 className="h-3.5 w-3.5" /></IconButton>
        </div>
      ))}
      <Button size="sm" variant="outline" onClick={() => updateDraft((current) => ({ ...current, indexes: [...current.indexes, emptyIndex()] }))}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        {t(I18N_KEYS.EXPLORER.STRUCTURE_ADD_INDEX)}
      </Button>
    </div>
  );
}

function ForeignKeySection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
  const { t } = useTranslation();
  const patch = (clientId: string, values: Partial<TableDraft['foreignKeys'][number]>) => {
    updateDraft((current) => ({
      ...current,
      foreignKeys: current.foreignKeys.map((item) => (item.clientId === clientId ? { ...item, ...values } : item)),
    }));
  };
  return (
    <div className="space-y-2">
      {draft.foreignKeys.map((foreignKey) => (
        <div key={foreignKey.clientId} className="grid grid-cols-[1fr_1fr_1fr_1fr_auto_auto_auto] items-center gap-2">
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_NAME)} value={foreignKey.name} onChange={(event) => patch(foreignKey.clientId, { name: event.target.value })} />
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_COLUMNS_LIST)} value={foreignKey.columnsText} onChange={(event) => patch(foreignKey.clientId, { columnsText: event.target.value })} />
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_REF_TABLE)} value={foreignKey.referencedTable} onChange={(event) => patch(foreignKey.clientId, { referencedTable: event.target.value })} />
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_REF_COLUMNS)} value={foreignKey.referencedColumnsText} onChange={(event) => patch(foreignKey.clientId, { referencedColumnsText: event.target.value })} />
          <select className={FIELD} value={foreignKey.onDelete ?? 'RESTRICT'} onChange={(event) => patch(foreignKey.clientId, { onDelete: event.target.value })}>
            {RULES.map((rule) => <option key={rule}>{rule}</option>)}
          </select>
          <select className={FIELD} value={foreignKey.onUpdate ?? 'RESTRICT'} onChange={(event) => patch(foreignKey.clientId, { onUpdate: event.target.value })}>
            {RULES.map((rule) => <option key={rule}>{rule}</option>)}
          </select>
          <IconButton label={t(I18N_KEYS.EXPLORER.STRUCTURE_REMOVE)} onClick={() => updateDraft((current) => ({ ...current, foreignKeys: current.foreignKeys.filter((item) => item.clientId !== foreignKey.clientId) }))}><Trash2 className="h-3.5 w-3.5" /></IconButton>
        </div>
      ))}
      <Button size="sm" variant="outline" onClick={() => updateDraft((current) => ({ ...current, foreignKeys: [...current.foreignKeys, emptyForeignKey()] }))}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        {t(I18N_KEYS.EXPLORER.STRUCTURE_ADD_FOREIGN_KEY)}
      </Button>
    </div>
  );
}

function CheckSection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2">
      {draft.checks.map((check) => (
        <div key={check.clientId} className="grid grid-cols-[1fr_2fr_auto] items-center gap-2">
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_NAME)} value={check.name} onChange={(event) => updateDraft((current) => ({ ...current, checks: current.checks.map((item) => item.clientId === check.clientId ? { ...item, name: event.target.value } : item) }))} />
          <input className={FIELD} placeholder={t(I18N_KEYS.EXPLORER.STRUCTURE_EXPRESSION)} value={check.expression} onChange={(event) => updateDraft((current) => ({ ...current, checks: current.checks.map((item) => item.clientId === check.clientId ? { ...item, expression: event.target.value } : item) }))} />
          <IconButton label={t(I18N_KEYS.EXPLORER.STRUCTURE_REMOVE)} onClick={() => updateDraft((current) => ({ ...current, checks: current.checks.filter((item) => item.clientId !== check.clientId) }))}><Trash2 className="h-3.5 w-3.5" /></IconButton>
        </div>
      ))}
      <Button size="sm" variant="outline" onClick={() => updateDraft((current) => ({ ...current, checks: [...current.checks, emptyCheck()] }))}>
        <Plus className="mr-1 h-3.5 w-3.5" />
        {t(I18N_KEYS.EXPLORER.STRUCTURE_ADD_CHECK)}
      </Button>
    </div>
  );
}

function DiffSection({ preview, previewing }: { preview: StructurePreview | null; previewing: boolean }) {
  const { t } = useTranslation();
  if (previewing && !preview) {
    return <div className="text-xs theme-text-secondary">{t(I18N_KEYS.EXPLORER.STRUCTURE_PREVIEWING)}</div>;
  }
  if (!preview?.changes.length) {
    return <div className="text-xs theme-text-secondary">{t(I18N_KEYS.EXPLORER.STRUCTURE_NO_CHANGES)}</div>;
  }
  return (
    <ul className="space-y-1 text-xs">
      {preview.changes.map((change, index) => (
        <li key={`${change.objectType}-${change.name}-${index}`} className={change.destructive ? 'text-amber-600' : 'theme-text-primary'}>
          {changeLabel(change, t)}
          {change.detail ? <span className="ml-2 theme-text-secondary">{change.detail}</span> : null}
        </li>
      ))}
    </ul>
  );
}

function IconButton({ label, onClick, disabled, children }: { label: string; onClick: () => void; disabled?: boolean; children: ReactNode }) {
  return (
    <button type="button" title={label} aria-label={label} disabled={disabled} className="rounded p-1 theme-text-secondary hover:bg-accent hover:theme-text-primary disabled:opacity-40" onClick={onClick}>
      {children}
    </button>
  );
}

function moveKeyColumn(draft: TableDraft, index: number, direction: -1 | 1): TableDraft {
  if (!draft.primaryKey) return draft;
  const columns = draft.primaryKey.columns;
  const target = index + direction;
  if (target < 0 || target >= columns.length) return draft;
  const next = [...columns];
  [next[index], next[target]] = [next[target], next[index]];
  return { ...draft, primaryKey: { ...draft.primaryKey, columns: next } };
}

function databaseLabel(dbType?: string): string {
  const value = (dbType ?? '').toUpperCase();
  if (value.includes('MYSQL')) return 'MySQL';
  if (value.includes('DAMENG') || value === 'DM' || value.startsWith('DM')) return 'DM';
  return value || 'DB';
}

function TriggerSection({
  triggers,
  selected,
  ddl,
  onSelect,
}: {
  triggers: TriggerMetadata[];
  selected: string | null;
  ddl: string;
  onSelect: (name: string) => void;
}) {
  const { t } = useTranslation();
  if (triggers.length === 0) {
    return <div className="text-xs theme-text-secondary">{t(I18N_KEYS.EXPLORER.STRUCTURE_TRIGGER_EMPTY)}</div>;
  }
  return (
    <div className="flex min-h-[160px] flex-col gap-3">
      <table className="w-full border-collapse text-sm font-semibold">
        <thead className="theme-text-secondary">
          <tr className="border-b border-border text-left">
            <th className="py-1 pr-2">{t(I18N_KEYS.EXPLORER.STRUCTURE_NAME)}</th>
            <th className="py-1 pr-2">{t(I18N_KEYS.EXPLORER.STRUCTURE_TRIGGER_TIMING)}</th>
            <th className="py-1">{t(I18N_KEYS.EXPLORER.STRUCTURE_TRIGGER_EVENT)}</th>
          </tr>
        </thead>
        <tbody>
          {triggers.map((trigger) => (
            <tr
              key={trigger.name}
              className={`cursor-pointer border-b border-border/60 ${selected === trigger.name ? 'bg-primary/10' : ''}`}
              onClick={() => onSelect(trigger.name)}
            >
              <td className="py-1 pr-2">{trigger.name}</td>
              <td className="py-1 pr-2">{trigger.timing}</td>
              <td className="py-1">{trigger.event}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {ddl ? <pre className="overflow-auto whitespace-pre-wrap font-mono text-[11px] leading-5 theme-text-primary">{ddl}</pre> : null}
    </div>
  );
}

function defaultKindKey(kind: StructureDefaultKind): string {
  if (kind === 'NULL') return I18N_KEYS.EXPLORER.STRUCTURE_DEFAULT_NULL;
  if (kind === 'VALUE') return I18N_KEYS.EXPLORER.STRUCTURE_DEFAULT_VALUE;
  if (kind === 'EXPRESSION') return I18N_KEYS.EXPLORER.STRUCTURE_DEFAULT_EXPRESSION;
  return I18N_KEYS.EXPLORER.STRUCTURE_DEFAULT_NONE;
}

function changeLabel(change: StructureChange, t: (key: string) => string): string {
  const action = t(actionKey(change.action));
  const objectType = t(objectKey(change.objectType));
  return `${action} ${objectType} ${change.name}`;
}

function actionKey(action: string): string {
  if (action === 'ADD') return I18N_KEYS.EXPLORER.STRUCTURE_ACTION_ADD;
  if (action === 'DROP') return I18N_KEYS.EXPLORER.STRUCTURE_ACTION_DROP;
  if (action === 'RENAME') return I18N_KEYS.EXPLORER.STRUCTURE_ACTION_RENAME;
  return I18N_KEYS.EXPLORER.STRUCTURE_ACTION_MODIFY;
}

function objectKey(objectType: string): string {
  if (objectType === 'COLUMN') return I18N_KEYS.EXPLORER.STRUCTURE_OBJECT_COLUMN;
  if (objectType === 'PRIMARY_KEY') return I18N_KEYS.EXPLORER.STRUCTURE_OBJECT_PRIMARY_KEY;
  if (objectType === 'INDEX') return I18N_KEYS.EXPLORER.STRUCTURE_OBJECT_INDEX;
  if (objectType === 'FOREIGN_KEY') return I18N_KEYS.EXPLORER.STRUCTURE_OBJECT_FOREIGN_KEY;
  if (objectType === 'CHECK') return I18N_KEYS.EXPLORER.STRUCTURE_OBJECT_CHECK;
  return I18N_KEYS.EXPLORER.STRUCTURE_OBJECT_TABLE;
}

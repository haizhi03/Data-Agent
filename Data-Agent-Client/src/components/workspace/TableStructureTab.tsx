import { useState } from 'react';
import { Group, Panel, Separator, useDefaultLayout } from 'react-resizable-panels';
import { useTranslation } from 'react-i18next';
import { Copy, Database, RefreshCw } from 'lucide-react';
import { DatabaseTypeIcon } from '../common/DatabaseTypeIcon';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { useTableStructureEditor } from '../../hooks/useTableStructureEditor';
import { isDmDbType } from '../../lib/sqlComment';
import { structureDiagnosticText } from '../../lib/structureDiagnostics';
import type { TableTabMetadata } from '../../types/tab';
import {
  CheckSection,
  ColumnSection,
  databaseLabel,
  DiffSection,
  ForeignKeySection,
  IndexSection,
  TriggerSection,
} from './structureEditorSections';

interface TableStructureTabProps {
  tabId: string;
  metadata: TableTabMetadata;
}

type SectionId = 'ddl' | 'columns' | 'indexes' | 'foreignKeys' | 'triggers' | 'checks' | 'diff';

const FIELD = 'h-8 w-full min-w-0 rounded border border-border bg-transparent px-2 text-sm font-semibold outline-none focus:border-primary';

export function TableStructureTab({ tabId, metadata }: TableStructureTabProps) {
  const { t } = useTranslation();
  const editor = useTableStructureEditor(tabId, metadata);
  const [section, setSection] = useState<SectionId>('columns');
  const { defaultLayout: previewLayout, onLayoutChanged: onPreviewLayoutChanged } = useDefaultLayout({
    id: 'table-structure-sql-preview',
    storage: localStorage,
  });
  const {
    scope, draft, rules, loading, applying, error, ddl, ddlError, triggers, selectedTrigger, triggerDdl,
    dirty, currentPreview, executable, previewPending, updateDraft, refresh, load, execute, copySql, selectTrigger,
  } = editor;

  if (loading && !draft) {
    return <div className="flex h-full items-center justify-center text-xs theme-text-secondary">{t(I18N_KEYS.EXPLORER.STRUCTURE_LOADING)}</div>;
  }
  if (!draft) {
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
  const showTableOptions = rules ? rules.engine || rules.charset || rules.collation : !isDmDbType(metadata.dbType);

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
        {showTableOptions ? (
          <>
            {(!rules || rules.engine) ? (
              <label className="w-28 text-sm font-medium theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.STRUCTURE_ENGINE)}
                <input className={`${FIELD} mt-1`} value={draft.engine} onChange={(event) => updateDraft((current) => ({ ...current, engine: event.target.value }))} />
              </label>
            ) : null}
            {(!rules || rules.charset) ? (
              <label className="w-32 text-sm font-medium theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.STRUCTURE_CHARSET)}
                <input className={`${FIELD} mt-1`} value={draft.charset} onChange={(event) => updateDraft((current) => ({ ...current, charset: event.target.value }))} />
              </label>
            ) : null}
            {(!rules || rules.collation) ? (
              <label className="w-52 text-sm font-medium theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.STRUCTURE_COLLATION)}
                <input className={`${FIELD} mt-1`} value={draft.collation} onChange={(event) => updateDraft((current) => ({ ...current, collation: event.target.value }))} />
              </label>
            ) : null}
          </>
        ) : null}
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
        {section === 'columns' ? <ColumnSection draft={draft} rules={rules} updateDraft={updateDraft} /> : null}
        {section === 'indexes' ? <IndexSection draft={draft} updateDraft={updateDraft} /> : null}
        {section === 'foreignKeys' ? <ForeignKeySection draft={draft} updateDraft={updateDraft} /> : null}
        {section === 'triggers' ? (
          <TriggerSection triggers={triggers} selected={selectedTrigger} ddl={triggerDdl} onSelect={selectTrigger} />
        ) : null}
        {section === 'checks' ? <CheckSection draft={draft} updateDraft={updateDraft} /> : null}
        {section === 'diff' ? <DiffSection preview={currentPreview} previewing={previewPending} /> : null}
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
            {currentPreview.warnings.map((warning) => structureDiagnosticText(warning, t)).join(' · ')}
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

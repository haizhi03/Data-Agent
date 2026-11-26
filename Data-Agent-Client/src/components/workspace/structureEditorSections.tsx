import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, Plus, Trash2 } from 'lucide-react';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { typeAllows, type StructureTypeRules } from '../../lib/structureTypeRules';
import {
  applyColumnLength,
  applyColumnType,
  emptyCheck,
  emptyColumn,
  emptyForeignKey,
  emptyIndex,
  renameColumnReferences,
  togglePrimaryKey,
} from '../../lib/tableStructureDraft';
import type { TriggerMetadata } from '../../services/trigger.service';
import type { StructureChange, StructureDefaultKind, StructurePreview, TableDraft } from '../../types/tableStructure';

const CONTROL = 'h-8 rounded border border-border bg-transparent px-2 text-sm font-semibold outline-none focus:border-primary';
const FIELD = `${CONTROL} w-full min-w-0`;
const DEFAULT_KINDS: StructureDefaultKind[] = ['NONE', 'NULL', 'VALUE', 'EXPRESSION'];
const RULES = ['RESTRICT', 'CASCADE', 'SET NULL', 'NO ACTION', 'SET DEFAULT'];


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
    const next: Record<ColumnWidthKey, number> = { ...DEFAULT_COLUMN_WIDTHS };
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

export function ColumnSection({ draft, rules, updateDraft }: {
  draft: TableDraft;
  rules: StructureTypeRules | null;
  updateDraft: (recipe: (current: TableDraft) => TableDraft) => void;
}) {
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
              const lengthEnabled = !rules || typeAllows(rules.lengthTypes, column.typeName) || typeAllows(rules.lengthTypes, column.columnType);
              const autoIncrementEnabled = !rules || typeAllows(rules.autoIncrementTypes, column.typeName) || typeAllows(rules.autoIncrementTypes, column.columnType);
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
                      disabled={generated || !lengthEnabled}
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
                  <td className="py-1 text-center"><input type="checkbox" checked={column.autoIncrement} disabled={generated || !autoIncrementEnabled} onChange={(event) => patch(column.clientId, (item) => ({ ...item, autoIncrement: event.target.checked }))} /></td>
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

export function IndexSection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
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

export function ForeignKeySection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
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

export function CheckSection({ draft, updateDraft }: { draft: TableDraft; updateDraft: (recipe: (current: TableDraft) => TableDraft) => void }) {
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

export function DiffSection({ preview, previewing }: { preview: StructurePreview | null; previewing: boolean }) {
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

export function databaseLabel(dbType?: string): string {
  const value = (dbType ?? '').toUpperCase();
  if (value.includes('MYSQL')) return 'MySQL';
  if (value.includes('DAMENG') || value === 'DM' || value.startsWith('DM')) return 'DM';
  return value || 'DB';
}

export function TriggerSection({
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

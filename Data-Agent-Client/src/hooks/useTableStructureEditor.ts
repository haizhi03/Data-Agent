import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../constants/i18nKeys';
import { EXPLORER_SQL_OBJECT_CHANGED } from '../constants/explorer';
import { useToast } from '../hooks/useToast';
import { resolveErrorMessage } from '../lib/errorMessage';
import { structureDiagnosticText } from '../lib/structureDiagnostics';
import type { StructureTypeRules } from '../lib/structureTypeRules';
import {
  acceptPreview,
  adoptStructure,
  canExecute,
  structuresEqual,
  toStructurePayload,
  type BoundPreview,
} from '../lib/tableStructureDraft';
import { tableService } from '../services/table.service';
import { tableStructureService } from '../services/tableStructure.service';
import { triggerService, type TriggerMetadata } from '../services/trigger.service';
import { useStructureEditorStore } from '../store/structureEditorStore';
import type { TableTabMetadata } from '../types/tab';
import type { StructurePreview, TableDraft, TableStructure } from '../types/tableStructure';

export function useTableStructureEditor(tabId: string, metadata: TableTabMetadata) {
  const { t } = useTranslation();
  const toast = useToast();
  const setDirty = useStructureEditorStore((state) => state.setDirty);
  const [baseline, setBaseline] = useState<TableStructure | null>(null);
  const [draft, setDraft] = useState<TableDraft | null>(null);
  const [rules, setRules] = useState<StructureTypeRules | null>(null);
  const [revision, setRevision] = useState(0);
  const revisionRef = useRef(0);
  const [boundPreview, setBoundPreview] = useState<BoundPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [previewing, setPreviewing] = useState(false);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ddl, setDdl] = useState('');
  const [ddlError, setDdlError] = useState<string | null>(null);
  const [triggers, setTriggers] = useState<TriggerMetadata[]>([]);
  const [selectedTrigger, setSelectedTrigger] = useState<string | null>(null);
  const [triggerDdl, setTriggerDdl] = useState('');

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
      const [structure, typeRules] = await Promise.all([
        tableStructureService.load(scope),
        tableStructureService.rules(scope).catch(() => null),
      ]);
      if (typeRules) setRules(typeRules);
      adopt(structure);
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

  const currentPreview: StructurePreview | null = boundPreview?.revision === revision ? boundPreview.result : null;
  const executable = canExecute(revision, boundPreview, currentPreview?.statements.length ?? 0);
  const previewPending = currentPreview == null && previewing;

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
      if (result.errorCode) {
        toast.error(structureDiagnosticText({ code: result.errorCode, args: [] }, t));
      } else if (result.errorMessage) {
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

  const copySql = async () => {
    const sql = currentPreview?.statements.join(';\n\n') ?? '';
    if (!sql) return;
    await navigator.clipboard.writeText(`${sql};`);
    toast.success(t(I18N_KEYS.EXPLORER.STRUCTURE_COPIED));
  };

  const selectTrigger = (name: string) => {
    setSelectedTrigger(name);
    setTriggerDdl('');
    void triggerService.getTriggerDdl(String(scope.connectionId), name, scope.catalog ?? undefined, scope.schema ?? undefined)
      .then(setTriggerDdl)
      .catch((err) => setTriggerDdl(resolveErrorMessage(err, t(I18N_KEYS.EXPLORER.LOAD_DDL_FAILED))));
  };

  return {
    scope,
    baseline,
    draft,
    rules,
    loading,
    applying,
    error,
    ddl,
    ddlError,
    triggers,
    selectedTrigger,
    triggerDdl,
    dirty,
    currentPreview,
    executable,
    previewPending,
    updateDraft,
    refresh,
    load,
    execute,
    copySql,
    selectTrigger,
  };
}

function changeLabel(change: { action: string; objectType: string; name: string }, t: (key: string) => string): string {
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

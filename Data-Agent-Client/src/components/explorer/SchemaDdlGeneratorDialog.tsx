import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Check, Cog, Copy, Eye, FileCode2, FunctionSquare, Loader2, Table } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableService } from '../../services/table.service';
import { viewService } from '../../services/view.service';
import { functionService, type FunctionMetadata } from '../../services/function.service';
import { procedureService, type ProcedureMetadata } from '../../services/procedure.service';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useToast } from '../../hooks/useToast';
import type { SqlScriptTarget } from '../../types/explorer';

interface SchemaDdlGeneratorDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  target: SqlScriptTarget | null;
}

/** Trim and guarantee a trailing semicolon so statements join cleanly. */
function normalizeDdl(ddl: string): string {
  const trimmed = ddl.trim();
  return trimmed.endsWith(';') ? trimmed : `${trimmed};`;
}

export function SchemaDdlGeneratorDialog({ open, onOpenChange, target }: SchemaDdlGeneratorDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const openTab = useWorkspaceStore((s) => s.openTab);

  const [tables, setTables] = useState<string[]>([]);
  const [views, setViews] = useState<string[]>([]);
  const [functions, setFunctions] = useState<string[]>([]);
  const [procedures, setProcedures] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [selectedTables, setSelectedTables] = useState<Set<string>>(new Set());
  const [selectedViews, setSelectedViews] = useState<Set<string>>(new Set());
  const [selectedFunctions, setSelectedFunctions] = useState<Set<string>>(new Set());
  const [selectedProcedures, setSelectedProcedures] = useState<Set<string>>(new Set());
  const [script, setScript] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!open || !target) return;
    let cancelled = false;
    setLoading(true);
    setScript(null);
    setCopied(false);
    setTables([]);
    setViews([]);
    setFunctions([]);
    setProcedures([]);
    setSelectedTables(new Set());
    setSelectedViews(new Set());
    setSelectedFunctions(new Set());
    setSelectedProcedures(new Set());

    const connectionId = String(target.connectionId);
    const catalog = target.databaseName ?? undefined;
    const schema = target.schemaName ?? undefined;
    const scope = target.scope ?? 'all';
    const includeTables = scope === 'all' || scope === 'tables';
    const includeViews = scope === 'all' || scope === 'views';
    const includeRoutines = scope === 'all' || scope === 'routines';

    Promise.all([
      includeTables ? tableService.listTables(connectionId, catalog, schema) : Promise.resolve<string[]>([]),
      includeViews ? viewService.listViews(connectionId, catalog, schema) : Promise.resolve<string[]>([]),
      includeRoutines ? functionService.listFunctions(connectionId, catalog, schema) : Promise.resolve<FunctionMetadata[]>([]),
      includeRoutines ? procedureService.listProcedures(connectionId, catalog, schema) : Promise.resolve<ProcedureMetadata[]>([]),
    ])
      .then(([tableNames, viewNames, functionMetas, procedureMetas]) => {
        if (cancelled) return;
        const functionNames = functionMetas.map((f) => f.name);
        const procedureNames = procedureMetas.map((p) => p.name);
        setTables(tableNames);
        setViews(viewNames);
        setFunctions(functionNames);
        setProcedures(procedureNames);
        setSelectedTables(new Set(tableNames));
        setSelectedViews(new Set(viewNames));
        setSelectedFunctions(new Set(functionNames));
        setSelectedProcedures(new Set(procedureNames));
      })
      .catch(() => {
        if (cancelled) return;
        toast.error(t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_FAILED));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [open, target, t, toast]);

  const objectCount = tables.length + views.length + functions.length + procedures.length;
  const selectedCount = selectedTables.size + selectedViews.size + selectedFunctions.size + selectedProcedures.size;

  const toggle = (set: Set<string>, name: string, apply: (next: Set<string>) => void) => {
    const next = new Set(set);
    if (next.has(name)) {
      next.delete(name);
    } else {
      next.add(name);
    }
    apply(next);
  };

  const handleGenerate = async () => {
    if (!target || generating) return;
    if (selectedCount === 0) {
      toast.warning(t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_NONE_SELECTED));
      return;
    }

    setGenerating(true);
    setCopied(false);
    try {
      const connectionId = String(target.connectionId);
      const catalog = target.databaseName ?? undefined;
      const schema = target.schemaName ?? undefined;

      const tableDdls = await Promise.all(
        tables.filter((name) => selectedTables.has(name)).map((name) =>
          tableService.getTableDdl(connectionId, name, catalog, schema)
            .catch(() => `-- Failed to generate DDL for table ${name}`),
        ),
      );
      const viewDdls = await Promise.all(
        views.filter((name) => selectedViews.has(name)).map((name) =>
          viewService.getViewDdl(connectionId, name, catalog, schema)
            .catch(() => `-- Failed to generate DDL for view ${name}`),
        ),
      );
      const functionDdls = await Promise.all(
        functions.filter((name) => selectedFunctions.has(name)).map((name) =>
          functionService.getFunctionDdl(connectionId, name, catalog, schema)
            .catch(() => `-- Failed to generate DDL for function ${name}`),
        ),
      );
      const procedureDdls = await Promise.all(
        procedures.filter((name) => selectedProcedures.has(name)).map((name) =>
          procedureService.getProcedureDdl(connectionId, name, catalog, schema)
            .catch(() => `-- Failed to generate DDL for procedure ${name}`),
        ),
      );

      setScript([...tableDdls, ...viewDdls, ...functionDdls, ...procedureDdls].map(normalizeDdl).join('\n\n'));
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_FAILED));
      setScript(null);
    } finally {
      setGenerating(false);
    }
  };

  const handleCopy = async () => {
    if (!script) return;
    try {
      await navigator.clipboard.writeText(script);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_FAILED));
    }
  };

  const handleOpenInConsole = () => {
    if (!script || !target) return;
    const tabName =
      [target.connectionName, target.databaseName, target.schemaName].filter(Boolean).join('_') || 'console';
    openTab({
      id: `console-${Date.now()}`,
      name: tabName,
      type: 'file',
      content: script,
      metadata: {
        connectionId: target.connectionId,
        connectionName: target.connectionName,
        databaseName: target.databaseName ?? null,
        schemaName: target.schemaName ?? null,
      },
    });
    onOpenChange(false);
  };

  const renderObjectList = (
    names: string[],
    selected: Set<string>,
    apply: (next: Set<string>) => void,
    icon: ReactNode,
    label: string,
  ) => {
    if (names.length === 0) return null;
    const allSelected = names.every((name) => selected.has(name));
    return (
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between">
          <span className="flex items-center gap-1.5 text-xs font-medium theme-text-primary">
            {icon}
            {label}
            <span className="text-[10px] theme-text-secondary opacity-60">{names.length}</span>
          </span>
          <button
            type="button"
            onClick={() => apply(allSelected ? new Set() : new Set(names))}
            className="text-[10px] theme-text-secondary hover:theme-text-primary"
          >
            {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_SELECT_ALL)}
          </button>
        </div>
        <div className="max-h-40 overflow-y-auto rounded-md border border-gray-300 bg-white px-2 py-1 dark:border-gray-700 dark:bg-gray-900">
          {names.map((name) => (
            <label
              key={name}
              className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm text-gray-900 hover:bg-gray-100 dark:text-gray-100 dark:hover:bg-gray-800"
            >
              <input
                type="checkbox"
                checked={selected.has(name)}
                onChange={() => toggle(selected, name, apply)}
                className="h-3.5 w-3.5 accent-blue-600"
              />
              <span className="truncate">{name}</span>
            </label>
          ))}
        </div>
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <FileCode2 className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_TITLE)}
          </DialogTitle>
          <DialogDescription className="font-mono text-xs">
            {target?.targetPath ?? ''}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 min-h-0">
          {loading ? (
            <div className="flex items-center justify-center py-8 text-sm theme-text-secondary">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_LOADING)}
            </div>
          ) : objectCount === 0 ? (
            <div className="flex items-center justify-center py-8 text-sm theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_EMPTY)}
            </div>
          ) : (
            <>
              <div className="flex flex-col gap-3">
                {renderObjectList(
                  tables,
                  selectedTables,
                  setSelectedTables,
                  <Table className="h-3 w-3" />,
                  t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_TABLES),
                )}
                {renderObjectList(
                  views,
                  selectedViews,
                  setSelectedViews,
                  <Eye className="h-3 w-3" />,
                  t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_VIEWS),
                )}
                {renderObjectList(
                  functions,
                  selectedFunctions,
                  setSelectedFunctions,
                  <FunctionSquare className="h-3 w-3" />,
                  t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_FUNCTIONS),
                )}
                {renderObjectList(
                  procedures,
                  selectedProcedures,
                  setSelectedProcedures,
                  <Cog className="h-3 w-3" />,
                  t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_PROCEDURES),
                )}
              </div>

              <div className="flex items-center justify-between">
                <span className="text-xs theme-text-secondary">
                  {selectedCount} / {objectCount}
                </span>
                <Button size="sm" onClick={() => { void handleGenerate(); }} disabled={generating || selectedCount === 0}>
                  {generating && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                  {generating
                    ? t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_GENERATING)
                    : t(I18N_KEYS.EXPLORER.SQL_GENERATOR_DDL_GENERATE)}
                </Button>
              </div>
            </>
          )}

          {script && (
            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-end gap-2">
                <Button variant="outline" size="sm" onClick={handleOpenInConsole} className="h-7">
                  <FileCode2 className="mr-1 h-3 w-3" />
                  {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_OPEN_CONSOLE)}
                </Button>
                <Button variant="outline" size="sm" onClick={() => { void handleCopy(); }} className="h-7">
                  {copied ? (
                    <>
                      <Check className="mr-1 h-3 w-3" />
                      {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_COPIED)}
                    </>
                  ) : (
                    <>
                      <Copy className="mr-1 h-3 w-3" />
                      {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_COPY)}
                    </>
                  )}
                </Button>
              </div>
              <pre className="max-h-64 overflow-auto rounded-md border border-gray-300 bg-gray-50 p-3 font-mono text-[11px] leading-5 whitespace-pre-wrap text-gray-900 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100">
                {script}
              </pre>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

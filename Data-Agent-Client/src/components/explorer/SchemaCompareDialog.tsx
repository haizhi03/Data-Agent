import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle,
  ArrowLeft,
  ArrowLeftRight,
  GitCompareArrows,
  Loader2,
  Search,
  Table2,
} from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { cn } from '../../lib/utils';
import { databaseService } from '../../services/database.service';
import { schemaService } from '../../services/schema.service';
import { tableService } from '../../services/table.service';
import { schemaCompareService } from '../../services/schemaCompare.service';
import type { DbConnection } from '../../types/connection';
import type {
  SchemaCompareResult,
  SchemaDiffObjectType,
  SchemaDiffStatus,
  SchemaObjectDiff,
  SchemaTableDiff,
} from '../../types/schemaCompare';

interface SchemaCompareDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  connections: DbConnection[];
  defaultConnectionId?: number;
  defaultCatalog?: string | null;
  defaultSchema?: string | null;
}

interface StatusMeta {
  label: string;
  dot: string;
  /** Tinted pill used for the filter chips and inline badges. */
  soft: string;
}

/** 两步向导：先选比较范围，再查看比较结果。 */
type CompareStep = 'CONFIG' | 'RESULT';

const STATUS_META: Record<SchemaDiffStatus, StatusMeta> = {
  ONLY_SOURCE: {
    label: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_ONLY_SOURCE,
    dot: 'bg-sky-500',
    soft: 'border-sky-200 bg-sky-50 text-sky-700 dark:border-sky-500/30 dark:bg-sky-500/10 dark:text-sky-300',
  },
  ONLY_TARGET: {
    label: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_ONLY_TARGET,
    dot: 'bg-rose-500',
    soft: 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-300',
  },
  DIFFERENT: {
    label: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_DIFFERENT,
    dot: 'bg-amber-500',
    soft: 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-300',
  },
  SAME: {
    label: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SAME,
    dot: 'bg-emerald-500',
    soft: 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300',
  },
  READ_ERROR: {
    label: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_READ_ERROR,
    dot: 'bg-red-500',
    soft: 'border-red-200 bg-red-50 text-red-700 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300',
  },
};

const OBJECT_I18N: Record<SchemaDiffObjectType, string> = {
  TABLE: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_TABLE,
  COLUMN: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_COLUMN,
  PRIMARY_KEY: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_PRIMARY_KEY,
  INDEX: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_INDEX,
  FOREIGN_KEY: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_FOREIGN_KEY,
  UNIQUE: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_UNIQUE,
  CHECK: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_CHECK,
  COMMENT: I18N_KEYS.EXPLORER.SCHEMA_COMPARE_OBJECT_COMMENT,
};

const ATTRIBUTE_LABELS: Record<string, string> = {
  TYPE: 'TYPE',
  LENGTH: 'LENGTH',
  SCALE: 'SCALE',
  NULLABLE: 'NULLABLE',
  DEFAULT: 'DEFAULT',
  AUTO_INCREMENT: 'AUTO_INCREMENT',
  COMMENT: 'COMMENT',
  POSITION: 'POSITION',
  COLUMNS: 'COLUMNS',
  UNIQUE: 'UNIQUE',
  REFERENCES_TABLE: 'REFERENCES_TABLE',
  REFERENCES_COLUMNS: 'REFERENCES_COLUMNS',
  EXPRESSION: 'EXPRESSION',
};

const MAX_RENDERED_OBJECTS = 60;

const FIELD_CLASS =
  'h-8 rounded-md border border-gray-300 bg-white px-2 text-[12px] text-gray-900 ' +
  'focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:cursor-not-allowed ' +
  'disabled:bg-gray-100 disabled:text-gray-500 dark:border-gray-600 dark:bg-gray-900 dark:text-gray-100 dark:disabled:bg-gray-800';

export function SchemaCompareDialog({
  open,
  onOpenChange,
  connections,
  defaultConnectionId,
  defaultCatalog,
  defaultSchema,
}: SchemaCompareDialogProps) {
  const { t } = useTranslation();

  const [sourceConnectionId, setSourceConnectionId] = useState<number | undefined>(defaultConnectionId);
  const [targetConnectionId, setTargetConnectionId] = useState<number | undefined>(defaultConnectionId);
  const [sourceCatalog, setSourceCatalog] = useState(defaultCatalog ?? '');
  const [sourceSchema, setSourceSchema] = useState(defaultSchema ?? '');
  const [targetCatalog, setTargetCatalog] = useState('');
  const [targetSchema, setTargetSchema] = useState('');
  const [compareAllTables, setCompareAllTables] = useState(true);
  const [selectedTables, setSelectedTables] = useState<string[]>([]);
  const [tableSearch, setTableSearch] = useState('');
  const [excludeTables, setExcludeTables] = useState('');
  const [ignoreColumnOrder, setIgnoreColumnOrder] = useState(false);
  const [ignoreComments, setIgnoreComments] = useState(false);

  const [sourceCatalogs, setSourceCatalogs] = useState<string[]>([]);
  const [targetCatalogs, setTargetCatalogs] = useState<string[]>([]);
  const [sourceSchemas, setSourceSchemas] = useState<string[]>([]);
  const [targetSchemas, setTargetSchemas] = useState<string[]>([]);
  const [sourceTables, setSourceTables] = useState<string[]>([]);
  const [targetTables, setTargetTables] = useState<string[]>([]);
  const [tablesLoading, setTablesLoading] = useState(false);

  const [loading, setLoading] = useState(false);
  const [step, setStep] = useState<CompareStep>('CONFIG');
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SchemaCompareResult | null>(null);
  const [selectedTable, setSelectedTable] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<'ALL' | SchemaDiffStatus>('ALL');
  // Stable scalar so the reset effect does not fire on every connections array identity change.
  const fallbackConnectionId = connections[0]?.id;
  const sourceConnection = useMemo(
    () => connections.find((connection) => connection.id === sourceConnectionId),
    [connections, sourceConnectionId],
  );
  const targetConnection = useMemo(
    () => connections.find((connection) => connection.id === targetConnectionId),
    [connections, targetConnectionId],
  );
  // MySQL only has one namespace level (database); DM/others need the schema level.
  const sourceNeedsSchema = sourceConnection ? sourceConnection.dbType?.toUpperCase() !== 'MYSQL' : false;
  const targetNeedsSchema = targetConnection ? targetConnection.dbType?.toUpperCase() !== 'MYSQL' : false;

  useEffect(() => {
    if (!open) return;
    const initialConnectionId = defaultConnectionId ?? fallbackConnectionId;
    setSourceConnectionId(initialConnectionId);
    setTargetConnectionId(initialConnectionId);
    setSourceCatalog(defaultCatalog ?? '');
    setSourceSchema(defaultSchema ?? '');
    setTargetCatalog('');
    setTargetSchema('');
    setCompareAllTables(true);
    setSelectedTables([]);
    setTableSearch('');
    setExcludeTables('');
    setIgnoreColumnOrder(false);
    setIgnoreComments(false);
    setStep('CONFIG');
    setError(null);
    setResult(null);
    setSelectedTable(null);
    setStatusFilter('ALL');
  }, [open, defaultConnectionId, defaultCatalog, defaultSchema, fallbackConnectionId]);

  useEffect(() => {
    if (!open || !sourceConnectionId) {
      setSourceCatalogs([]);
      return;
    }
    let cancelled = false;
    databaseService
      .listDatabases(String(sourceConnectionId))
      .then((names) => {
        if (!cancelled) setSourceCatalogs(names);
      })
      .catch(() => {
        if (!cancelled) setSourceCatalogs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [open, sourceConnectionId]);

  useEffect(() => {
    if (!open || !targetConnectionId) {
      setTargetCatalogs([]);
      return;
    }
    let cancelled = false;
    databaseService
      .listDatabases(String(targetConnectionId))
      .then((names) => {
        if (!cancelled) setTargetCatalogs(names);
      })
      .catch(() => {
        if (!cancelled) setTargetCatalogs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [open, targetConnectionId]);

  useEffect(() => {
    if (!open || !sourceConnectionId || !sourceCatalog) {
      setSourceSchemas([]);
      return;
    }
    let cancelled = false;
    schemaService
      .listSchemas(String(sourceConnectionId), sourceCatalog)
      .then((names) => {
        if (!cancelled) setSourceSchemas(names);
      })
      .catch(() => {
        if (!cancelled) setSourceSchemas([]);
      });
    return () => {
      cancelled = true;
    };
  }, [open, sourceConnectionId, sourceCatalog]);

  useEffect(() => {
    if (!open || !targetConnectionId || !targetCatalog) {
      setTargetSchemas([]);
      return;
    }
    let cancelled = false;
    schemaService
      .listSchemas(String(targetConnectionId), targetCatalog)
      .then((names) => {
        if (!cancelled) setTargetSchemas(names);
      })
      .catch(() => {
        if (!cancelled) setTargetSchemas([]);
      });
    return () => {
      cancelled = true;
    };
  }, [open, targetConnectionId, targetCatalog]);

  // Table checklist is only needed for "compare specific tables"; reload whenever a scope changes.
  useEffect(() => {
    if (!open) return;
    if (compareAllTables) {
      setSourceTables([]);
      setTargetTables([]);
      setSelectedTables([]);
      return;
    }
    if (!sourceConnectionId && !targetConnectionId) return;
    let cancelled = false;
    setTablesLoading(true);
    setSelectedTables([]);
    const load = async () => {
      const [sourceList, targetList] = await Promise.all([
        sourceConnectionId && (sourceCatalog || sourceSchema)
          ? tableService
              .listTables(String(sourceConnectionId), sourceCatalog || undefined, sourceSchema || undefined)
              .catch(() => [])
          : Promise.resolve<string[]>([]),
        targetConnectionId && (targetCatalog || targetSchema)
          ? tableService
              .listTables(String(targetConnectionId), targetCatalog || undefined, targetSchema || undefined)
              .catch(() => [])
          : Promise.resolve<string[]>([]),
      ]);
      if (cancelled) return;
      setSourceTables(sourceList);
      setTargetTables(targetList);
      setTablesLoading(false);
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [
    open,
    compareAllTables,
    sourceConnectionId,
    targetConnectionId,
    sourceCatalog,
    sourceSchema,
    targetCatalog,
    targetSchema,
  ]);

  const sourceTableSet = useMemo(() => new Set(sourceTables), [sourceTables]);
  const targetTableSet = useMemo(() => new Set(targetTables), [targetTables]);
  const allTableNames = useMemo(() => {
    const names = new Set<string>([...sourceTables, ...targetTables]);
    return Array.from(names).sort((a, b) => a.localeCompare(b));
  }, [sourceTables, targetTables]);
  const filteredTableNames = useMemo(() => {
    const keyword = tableSearch.trim().toLowerCase();
    if (!keyword) return allTableNames;
    return allTableNames.filter((name) => name.toLowerCase().includes(keyword));
  }, [allTableNames, tableSearch]);
  const selectedTableSet = useMemo(() => new Set(selectedTables), [selectedTables]);
  // Side badges only make sense once both scopes actually returned their table lists.
  const showSideBadges = sourceTables.length > 0 && targetTables.length > 0;

  const toggleTable = (name: string) => {
    setSelectedTables((prev) =>
      prev.includes(name) ? prev.filter((item) => item !== name) : [...prev, name],
    );
  };

  const handleSelectAllTables = () => {
    const allSelected = filteredTableNames.length > 0 && filteredTableNames.every((name) => selectedTableSet.has(name));
    setSelectedTables((prev) => {
      if (allSelected) {
        return prev.filter((name) => !filteredTableNames.includes(name));
      }
      return Array.from(new Set([...prev, ...filteredTableNames]));
    });
  };

  const handleSourceConnectionChange = (nextId: number | undefined) => {
    setSourceConnectionId(nextId);
    const next = connections.find((connection) => connection.id === nextId);
    setSourceCatalog(next?.database ?? '');
    setSourceSchema('');
  };

  const handleTargetConnectionChange = (nextId: number | undefined) => {
    setTargetConnectionId(nextId);
    const next = connections.find((connection) => connection.id === nextId);
    setTargetCatalog(next?.database ?? '');
    setTargetSchema('');
  };

  const handleSwap = () => {
    setSourceConnectionId(targetConnectionId);
    setTargetConnectionId(sourceConnectionId);
    setSourceCatalog(targetCatalog);
    setSourceSchema(targetSchema);
    setTargetCatalog(sourceCatalog);
    setTargetSchema(sourceSchema);
  };

  const handleRun = async () => {
    if (!sourceConnectionId || !targetConnectionId) {
      setError(t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_EMPTY_CONNECTION));
      return;
    }
    const source = { catalog: sourceCatalog.trim() || null, schema: sourceSchema.trim() || null };
    const target = { catalog: targetCatalog.trim() || null, schema: targetSchema.trim() || null };
    if (sourceNeedsSchema ? !source.catalog && !source.schema : !source.catalog) {
      setError(t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_EMPTY_SCOPE));
      return;
    }
    if (targetNeedsSchema ? !target.catalog && !target.schema : !target.catalog) {
      setError(t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_EMPTY_SCOPE));
      return;
    }
    if (
      sourceConnectionId === targetConnectionId
      && source.catalog === target.catalog
      && source.schema === target.schema
    ) {
      setError(t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SAME_SCOPE));
      return;
    }
    if (!compareAllTables && selectedTables.length === 0) {
      setError(t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SELECT_AT_LEAST_ONE));
      return;
    }

    setLoading(true);
    setError(null);
    setStep('RESULT');
    try {
      const response = await schemaCompareService.compare({
        sourceConnectionId,
        targetConnectionId,
        sourceCatalog: source.catalog,
        sourceSchema: source.schema,
        targetCatalog: target.catalog,
        targetSchema: target.schema,
        includeTables: compareAllTables ? [] : selectedTables,
        excludeTables: splitNames(excludeTables),
        ignoreColumnOrder,
        ignoreComments,
      });
      setResult(response);
      setStatusFilter('ALL');
      const firstDifferent = response.tables.find((table) => table.status !== 'SAME');
      setSelectedTable(firstDifferent?.tableName ?? response.tables[0]?.tableName ?? null);
    } catch (err) {
      const responseData = (err as { response?: { data?: { message?: string } } }).response?.data;
      setError(responseData?.message || (err as Error).message || t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_FAILED));
      setStep('CONFIG');
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setTargetConnectionId(sourceConnectionId);
    setSourceCatalog(sourceConnection?.database ?? '');
    setSourceSchema('');
    setTargetCatalog('');
    setTargetSchema('');
    setCompareAllTables(true);
    setSelectedTables([]);
    setTableSearch('');
    setExcludeTables('');
    setIgnoreColumnOrder(false);
    setIgnoreComments(false);
    setStep('CONFIG');
    setError(null);
    setResult(null);
    setSelectedTable(null);
    setStatusFilter('ALL');
  };

  const visibleTables = useMemo(() => {
    if (!result) return [];
    if (statusFilter === 'ALL') return result.tables;
    return result.tables.filter((table) => table.status === statusFilter);
  }, [result, statusFilter]);

  const selectedTableDiff = useMemo(() => {
    if (!result || !selectedTable) return null;
    return result.tables.find((table) => table.tableName === selectedTable) ?? null;
  }, [result, selectedTable]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className={cn(
          'w-[min(1240px,96vw)] max-w-none flex flex-col gap-0 overflow-hidden p-0',
          step === 'RESULT' ? 'h-[92vh]' : 'max-h-[92vh]',
        )}
      >
        <div className="shrink-0 border-b border-border px-6 pb-3 pt-4 pr-12">
          <DialogHeader className="gap-1">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <DialogTitle className="flex items-center gap-2 text-[15px] font-semibold">
                  <GitCompareArrows className="h-4 w-4 text-gray-400" />
                  {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TITLE)}
                </DialogTitle>
                <DialogDescription className="text-[11px] text-muted-foreground">
                  {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_DESC)}
                </DialogDescription>
              </div>
              <StepBar step={step} />
            </div>
          </DialogHeader>
        </div>

        <div className={cn('px-6 py-4', step === 'CONFIG' ? 'min-h-0 flex-1 overflow-auto' : 'hidden')}>
          <div className="grid grid-cols-[1fr_34px_1fr] items-start gap-3">
            <ScopeColumn
              tone="source"
              title={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SOURCE)}
              connectionLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CONNECTION)}
              catalogLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CATALOG)}
              schemaLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SCHEMA)}
              infoLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_INFO)}
              activeConnection={sourceConnection}
              connections={connections}
              connectionId={sourceConnectionId}
              onConnectionChange={handleSourceConnectionChange}
              catalog={sourceCatalog}
              onCatalogChange={setSourceCatalog}
              schema={sourceSchema}
              onSchemaChange={setSourceSchema}
              catalogs={sourceCatalogs}
              schemas={sourceSchemas}
              needsSchema={sourceNeedsSchema}
              selectDatabasePlaceholder={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SELECT_DATABASE)}
              defaultCatalogLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_DEFAULT)}
              labels={{
                dbType: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CONN_TYPE),
                name: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CONN_NAME),
                host: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_HOST),
                port: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_PORT),
                version: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SERVER_VERSION),
              }}
              notAvailable="—"
            />

            <div className="flex justify-center pt-5">
              <button
                type="button"
                title={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SWAP)}
                onClick={handleSwap}
                className="flex h-7 w-7 items-center justify-center rounded-full border border-border text-gray-500 transition-colors hover:border-primary/50 hover:bg-primary/5 hover:text-primary"
              >
                <ArrowLeftRight className="h-3.5 w-3.5" />
              </button>
            </div>

            <ScopeColumn
              tone="target"
              title={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TARGET)}
              connectionLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CONNECTION)}
              catalogLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CATALOG)}
              schemaLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SCHEMA)}
              infoLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_INFO)}
              activeConnection={targetConnection}
              connections={connections}
              connectionId={targetConnectionId}
              onConnectionChange={handleTargetConnectionChange}
              catalog={targetCatalog}
              onCatalogChange={setTargetCatalog}
              schema={targetSchema}
              onSchemaChange={setTargetSchema}
              catalogs={targetCatalogs}
              schemas={targetSchemas}
              needsSchema={targetNeedsSchema}
              selectDatabasePlaceholder={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SELECT_DATABASE)}
              defaultCatalogLabel={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_DEFAULT)}
              labels={{
                dbType: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CONN_TYPE),
                name: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_CONN_NAME),
                host: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_HOST),
                port: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_PORT),
                version: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SERVER_VERSION),
              }}
              notAvailable="—"
            />
          </div>

          <div className="mt-3 space-y-2">
            <OptionRow
              checked={compareAllTables}
              onCheckedChange={(checked) => setCompareAllTables(checked)}
              title={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TABLES)}
              description={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TABLES_OPTION_DESC)}
              action={
                <button
                  type="button"
                  onClick={() => setCompareAllTables(!compareAllTables)}
                  className="shrink-0 text-[12px] text-primary hover:underline"
                >
                  {compareAllTables
                    ? t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SPECIFY_TABLES)
                    : t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_COMPARE_ALL_TABLES)}
                </button>
              }
            >
              {!compareAllTables && (
                <div className="border-t border-border px-3 py-2.5">
                  <div className="relative">
                    <Search className="pointer-events-none absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400" />
                    <input
                      value={tableSearch}
                      onChange={(event) => setTableSearch(event.target.value)}
                      placeholder={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TABLE_SEARCH)}
                      className={cn(FIELD_CLASS, 'w-full pl-7')}
                    />
                  </div>

                  <div className="mt-2 flex items-center justify-between">
                    <label className="flex cursor-pointer items-center gap-2 text-[12px]">
                      <input
                        type="checkbox"
                        checked={
                          filteredTableNames.length > 0 &&
                          filteredTableNames.every((name) => selectedTableSet.has(name))
                        }
                        onChange={handleSelectAllTables}
                        className="h-3.5 w-3.5 rounded accent-primary"
                      />
                      {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SELECT_ALL)}
                    </label>
                    <span className="text-[11px] text-muted-foreground">
                      {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SELECTED_COUNT, {
                        selected: selectedTables.length,
                        total: allTableNames.length,
                      })}
                    </span>
                  </div>

                  <div className="mt-1.5 max-h-[220px] min-h-[120px] overflow-auto rounded-md border border-border bg-white dark:bg-gray-900">
                    {tablesLoading ? (
                      <div className="flex items-center justify-center gap-1.5 py-6 text-[11px] text-muted-foreground">
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_LOADING_TABLES)}
                      </div>
                    ) : filteredTableNames.length === 0 ? (
                      <div className="py-6 text-center text-[11px] text-muted-foreground">
                        {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_NO_OBJECTS)}
                      </div>
                    ) : (
                      filteredTableNames.map((name) => {
                        const inSource = sourceTableSet.has(name);
                        const inTarget = targetTableSet.has(name);
                        return (
                          <label
                            key={name}
                            className="flex cursor-pointer items-center gap-2 px-2.5 py-1 text-[12px] hover:bg-accent/30"
                          >
                            <input
                              type="checkbox"
                              checked={selectedTableSet.has(name)}
                              onChange={() => toggleTable(name)}
                              className="h-3.5 w-3.5 shrink-0 rounded accent-primary"
                            />
                            <span className="min-w-0 flex-1 truncate" title={name}>
                              {name}
                            </span>
                            {showSideBadges && !inSource && (
                              <span className={cn('shrink-0 rounded-full border px-1.5 py-0.5 text-[10px] leading-none', STATUS_META.ONLY_TARGET.soft)}>
                                {t(STATUS_META.ONLY_TARGET.label)}
                              </span>
                            )}
                            {showSideBadges && !inTarget && (
                              <span className={cn('shrink-0 rounded-full border px-1.5 py-0.5 text-[10px] leading-none', STATUS_META.ONLY_SOURCE.soft)}>
                                {t(STATUS_META.ONLY_SOURCE.label)}
                              </span>
                            )}
                          </label>
                        );
                      })
                    )}
                  </div>

                  <input
                    value={excludeTables}
                    onChange={(event) => setExcludeTables(event.target.value)}
                    placeholder={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_EXCLUDE_TABLES)}
                    className={cn(FIELD_CLASS, 'mt-2 w-full')}
                  />
                </div>
              )}
            </OptionRow>

            <OptionRow
              checked={false}
              disabled
              title={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_ROUTINES)}
              description={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_ROUTINES_DESC)}
              action={
                <span className="shrink-0 rounded-full border border-border px-2 py-0.5 text-[10px] text-muted-foreground">
                  {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_NOT_SUPPORTED)}
                </span>
              }
            />

            <div className="flex items-center gap-2.5 rounded-lg border border-border px-3 py-2.5">
              <input
                id="schema-compare-ignore-comments"
                type="checkbox"
                checked={ignoreComments}
                onChange={(event) => setIgnoreComments(event.target.checked)}
                className="h-3.5 w-3.5 rounded accent-primary"
              />
              <label htmlFor="schema-compare-ignore-comments" className="cursor-pointer text-[12px]">
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_IGNORE_COMMENTS)}
              </label>
            </div>

            <div className="flex items-center gap-2.5 rounded-lg border border-border px-3 py-2.5">
              <input
                id="schema-compare-ignore-column-order"
                type="checkbox"
                checked={ignoreColumnOrder}
                onChange={(event) => setIgnoreColumnOrder(event.target.checked)}
                className="h-3.5 w-3.5 rounded accent-primary"
              />
              <label htmlFor="schema-compare-ignore-column-order" className="cursor-pointer text-[12px]">
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_IGNORE_COLUMN_ORDER)}
              </label>
            </div>
          </div>

          {error && (
            <div className="mt-3 flex items-start gap-1.5 rounded-md border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-[11px] text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-300">
              <AlertTriangle className="mt-[1px] h-3.5 w-3.5 shrink-0" />
              <span className="break-all">{error}</span>
            </div>
          )}

          <div className="mt-4 flex items-center gap-2 border-t border-border pt-3">
            <Button variant="outline" size="sm" className="h-8 px-3 text-[12px]" onClick={handleReset}>
              {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_RESET)}
            </Button>
            <Button
              size="sm"
              className="ml-auto h-8 px-4 text-[12px]"
              onClick={handleRun}
              disabled={loading}
            >
              {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_RUN)}
            </Button>
          </div>
        </div>

        {step === 'RESULT' && (
          <div className="flex min-h-0 flex-1 flex-col">
            {loading ? (
              <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-2 text-[12px] text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin" />
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_RUNNING)}
              </div>
            ) : !result || result.tables.length === 0 ? (
              <EmptyState
                icon={<Table2 className="h-6 w-6" />}
                text={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_EMPTY_RESULT)}
              />
            ) : (
              <div className="flex h-full min-h-0 flex-col">
                <SummaryBar result={result} filter={statusFilter} onFilterChange={setStatusFilter} />
                <div className="flex min-h-0 flex-1 gap-3 px-6 pb-4">
                  <div className="flex w-[360px] shrink-0 flex-col overflow-hidden rounded-lg border border-border bg-white dark:bg-gray-900">
                    <div className="flex items-center justify-between border-b border-border bg-gray-50/70 px-3 py-1.5 text-[11px] font-medium text-gray-500 dark:bg-gray-900/60">
                      <span>{t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TABLES)}</span>
                      <span>
                        {visibleTables.length} / {result.tables.length}
                      </span>
                    </div>
                    <div className="min-h-0 flex-1 overflow-auto">
                      {visibleTables.length === 0 ? (
                        <div className="p-3 text-[12px] text-muted-foreground">
                          {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_NO_OBJECTS)}
                        </div>
                      ) : (
                        visibleTables.map((table) => (
                          <button
                            key={table.tableName}
                            type="button"
                            onClick={() => setSelectedTable(table.tableName)}
                            className={cn(
                              'flex w-full items-center gap-2 border-l-2 px-3 py-1.5 text-left text-[12px] transition-colors',
                              selectedTable === table.tableName
                                ? 'border-l-primary bg-primary/5'
                                : 'border-l-transparent hover:bg-accent/30',
                            )}
                          >
                            <span className="min-w-0 flex-1 truncate" title={table.tableName}>
                              {table.tableName}
                            </span>
                            <StatusPill status={table.status} />
                          </button>
                        ))
                      )}
                    </div>
                  </div>

                  <div className="min-w-0 flex-1 overflow-auto rounded-lg border border-border bg-white dark:bg-gray-900">
                    {selectedTableDiff ? (
                      <TableDiffDetail table={selectedTableDiff} />
                    ) : (
                      <EmptyState
                        icon={<Table2 className="h-6 w-6" />}
                        text={t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SELECT_TABLE)}
                      />
                    )}
                  </div>
                </div>
              </div>
            )}

            <div className="flex shrink-0 items-center gap-2 border-t border-border px-6 py-3">
              <Button
                variant="outline"
                size="sm"
                className="h-8 px-3 text-[12px]"
                disabled={loading}
                onClick={() => setStep('CONFIG')}
              >
                <ArrowLeft className="mr-1 h-3.5 w-3.5" />
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_BACK)}
              </Button>
              <Button
                size="sm"
                className="ml-auto h-8 px-4 text-[12px]"
                onClick={() => onOpenChange(false)}
              >
                {t(I18N_KEYS.EXPLORER.CLOSE)}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface ScopeColumnProps {
  tone: 'source' | 'target';
  title: string;
  connectionLabel: string;
  catalogLabel: string;
  schemaLabel: string;
  infoLabel: string;
  activeConnection?: DbConnection;
  connections: DbConnection[];
  connectionId?: number;
  onConnectionChange: (id: number | undefined) => void;
  catalog: string;
  onCatalogChange: (value: string) => void;
  schema: string;
  onSchemaChange: (value: string) => void;
  catalogs: string[];
  schemas: string[];
  needsSchema: boolean;
  selectDatabasePlaceholder: string;
  defaultCatalogLabel: string;
  labels: {
    dbType: string;
    name: string;
    host: string;
    port: string;
    version: string;
  };
  notAvailable: string;
}

function ScopeColumn({
  tone,
  title,
  connectionLabel,
  catalogLabel,
  schemaLabel,
  infoLabel,
  activeConnection,
  connections,
  connectionId,
  onConnectionChange,
  catalog,
  onCatalogChange,
  schema,
  onSchemaChange,
  catalogs,
  schemas,
  needsSchema,
  selectDatabasePlaceholder,
  defaultCatalogLabel,
  labels,
  notAvailable,
}: ScopeColumnProps) {
  const infoRows = [
    { label: labels.dbType, value: activeConnection?.dbType ?? notAvailable },
    { label: labels.name, value: activeConnection?.name ?? notAvailable },
    { label: labels.host, value: activeConnection?.host ?? notAvailable },
    { label: labels.port, value: activeConnection?.port != null ? String(activeConnection.port) : notAvailable },
    { label: labels.version, value: null },
  ];

  return (
    <div className="min-w-0">
      <div
        className={cn(
          'mb-2 text-[12px] font-medium',
          tone === 'source' ? 'text-blue-600 dark:text-blue-400' : 'text-emerald-600 dark:text-emerald-400',
        )}
      >
        {title}
      </div>

      <div className="mb-1 text-[11px] text-gray-500">{connectionLabel}</div>
      <div className="grid grid-cols-[88px_1fr] gap-2">
        <select
          value={activeConnection?.dbType ?? ''}
          disabled
          className={cn(FIELD_CLASS, 'w-full')}
        >
          <option value={activeConnection?.dbType ?? ''}>{activeConnection?.dbType ?? notAvailable}</option>
        </select>
        <select
          value={connectionId ?? ''}
          onChange={(event) => onConnectionChange(event.target.value ? Number(event.target.value) : undefined)}
          className={cn(FIELD_CLASS, 'w-full')}
        >
          {connections.map((connection) => (
            <option key={connection.id} value={connection.id}>
              {connection.name}
            </option>
          ))}
        </select>
      </div>

      <div className="mb-1 mt-2 text-[11px] text-gray-500">{catalogLabel}</div>
      <select
        value={catalog}
        onChange={(event) => onCatalogChange(event.target.value)}
        className={cn(FIELD_CLASS, 'w-full')}
      >
        <option value="">{needsSchema ? defaultCatalogLabel : selectDatabasePlaceholder}</option>
        {catalog && !catalogs.includes(catalog) && <option value={catalog}>{catalog}</option>}
        {catalogs.map((name) => (
          <option key={name} value={name}>
            {name}
          </option>
        ))}
      </select>

      {needsSchema && (
        <>
          <div className="mb-1 mt-2 text-[11px] text-gray-500">{schemaLabel}</div>
          <select
            value={schema}
            onChange={(event) => onSchemaChange(event.target.value)}
            className={cn(FIELD_CLASS, 'w-full')}
          >
            <option value="">{selectDatabasePlaceholder}</option>
            {schema && !schemas.includes(schema) && <option value={schema}>{schema}</option>}
            {schemas.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </>
      )}

      <div className="mt-2 rounded-md border border-border bg-gray-50/80 p-2.5 dark:bg-gray-900/50">
        <div className="mb-1 text-[11px] text-gray-500">{infoLabel}</div>
        <div className="grid grid-cols-[62px_1fr] gap-x-2 gap-y-1 text-[11px]">
          {infoRows.map((row) => (
            <div key={row.label} className="contents">
              <span className="text-gray-400">{row.label}</span>
              <span className="truncate text-gray-700 dark:text-gray-300" title={row.value ?? notAvailable}>
                {row.value ?? notAvailable}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function OptionRow({
  checked,
  onCheckedChange,
  title,
  description,
  action,
  disabled,
  children,
}: {
  checked: boolean;
  onCheckedChange?: (checked: boolean) => void;
  title: string;
  description: string;
  action?: ReactNode;
  disabled?: boolean;
  children?: ReactNode;
}) {
  return (
    <div className="rounded-lg border border-border">
      <div className="flex items-start gap-2.5 px-3 py-2.5">
        <ToggleSwitch checked={checked} onCheckedChange={onCheckedChange} disabled={disabled} />
        <div className="min-w-0 flex-1">
          <div className={cn('text-[12px]', disabled ? 'text-muted-foreground' : '')}>{title}</div>
          <div className="mt-0.5 text-[11px] text-muted-foreground">{description}</div>
        </div>
        {action}
      </div>
      {children}
    </div>
  );
}

function ToggleSwitch({
  checked,
  onCheckedChange,
  disabled,
}: {
  checked: boolean;
  onCheckedChange?: (checked: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled || !onCheckedChange}
      onClick={() => onCheckedChange?.(!checked)}
      className={cn(
        'relative mt-0.5 inline-flex h-[18px] w-8 shrink-0 items-center rounded-full transition-colors',
        checked ? 'bg-primary' : 'bg-gray-300 dark:bg-gray-600',
        (disabled || !onCheckedChange) && 'cursor-not-allowed opacity-50',
      )}
    >
      <span
        className={cn(
          'absolute h-3.5 w-3.5 rounded-full bg-white shadow transition-transform',
          checked ? 'translate-x-[15px]' : 'translate-x-[2px]',
        )}
      />
    </button>
  );
}

function StepBar({ step }: { step: CompareStep }) {
  const { t } = useTranslation();
  const steps: Array<{ key: CompareStep; label: string }> = [
    { key: 'CONFIG', label: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_STEP_CONFIG) },
    { key: 'RESULT', label: t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_STEP_RESULT) },
  ];
  return (
    <div className="flex shrink-0 items-center gap-1.5">
      {steps.map((item, index) => {
        const active = step === item.key;
        const done = step === 'RESULT' && item.key === 'CONFIG';
        return (
          <div key={item.key} className="flex items-center gap-1.5">
            {index > 0 && <span className="text-[11px] text-gray-300 dark:text-gray-600">→</span>}
            <span
              className={cn(
                'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px]',
                active
                  ? 'bg-primary/10 font-medium text-primary'
                  : done
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : 'text-gray-400',
              )}
            >
              <span
                className={cn(
                  'flex h-4 w-4 items-center justify-center rounded-full text-[9px] font-semibold',
                  active
                    ? 'bg-primary text-white'
                    : done
                      ? 'bg-emerald-500 text-white'
                      : 'bg-gray-200 text-gray-500 dark:bg-gray-700 dark:text-gray-400',
                )}
              >
                {index + 1}
              </span>
              {item.label}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function StatusPill({ status }: { status: SchemaDiffStatus }) {
  const { t } = useTranslation();
  const meta = STATUS_META[status];
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full border px-1.5 py-0.5 text-[10px] leading-none',
        meta.soft,
      )}
    >
      <span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} />
      {t(meta.label)}
    </span>
  );
}

function EmptyState({ icon, text }: { icon: ReactNode; text: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 px-4 text-center">
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-gray-100 text-gray-400 dark:bg-gray-800 dark:text-gray-500">
        {icon}
      </span>
      <span className="max-w-[360px] text-[12px] text-muted-foreground">{text}</span>
    </div>
  );
}

interface SummaryBarProps {
  result: SchemaCompareResult;
  filter: 'ALL' | SchemaDiffStatus;
  onFilterChange: (filter: 'ALL' | SchemaDiffStatus) => void;
}

function SummaryBar({ result, filter, onFilterChange }: SummaryBarProps) {
  const { t } = useTranslation();
  const items: Array<{ key: SchemaDiffStatus; count: number }> = [
    { key: 'ONLY_SOURCE', count: result.summary.onlySource },
    { key: 'ONLY_TARGET', count: result.summary.onlyTarget },
    { key: 'DIFFERENT', count: result.summary.different },
    { key: 'SAME', count: result.summary.same },
    { key: 'READ_ERROR', count: result.summary.readError },
  ];
  return (
    <div className="flex shrink-0 flex-wrap items-center gap-1.5 px-6 py-3">
      <button
        type="button"
        onClick={() => onFilterChange('ALL')}
        className={cn(
          'inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-[11px] transition-colors',
          filter === 'ALL'
            ? 'border-primary/40 bg-primary/10 text-primary'
            : 'border-border text-gray-500 hover:bg-accent/30',
        )}
      >
        {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_ALL)}
        <span className="font-semibold">{result.tables.length}</span>
      </button>
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          onClick={() => onFilterChange(item.key)}
          className={cn(
            'inline-flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-[11px] transition-colors',
            filter === item.key ? STATUS_META[item.key].soft : 'border-border text-gray-500 hover:bg-accent/30',
          )}
        >
          <span className={cn('h-1.5 w-1.5 rounded-full', STATUS_META[item.key].dot)} />
          {t(STATUS_META[item.key].label)}
          <span className="font-semibold">{item.count}</span>
        </button>
      ))}
    </div>
  );
}

function TableDiffDetail({ table }: { table: SchemaTableDiff }) {
  const { t } = useTranslation();
  const changedObjects = table.objects.filter((object) => object.status !== 'SAME');
  const rendered = changedObjects.slice(0, MAX_RENDERED_OBJECTS);

  return (
    <div className="flex flex-col">
      <div className="sticky top-0 z-10 flex items-center gap-2 border-b border-border bg-white/95 px-3 py-2 backdrop-blur dark:bg-gray-900/95">
        <Table2 className="h-3.5 w-3.5 shrink-0 text-gray-400" />
        <span className="min-w-0 truncate text-[13px] font-medium" title={table.tableName}>
          {table.tableName}
        </span>
        <StatusPill status={table.status} />
        {changedObjects.length > 0 && (
          <span className="ml-auto shrink-0 text-[11px] text-muted-foreground">
            {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_DIFF_COUNT, { count: changedObjects.length })}
          </span>
        )}
      </div>

      {table.message && (
        <div className="mx-3 mt-3 flex items-start gap-1.5 rounded-md border border-amber-200 bg-amber-50 px-2.5 py-1.5 text-[11px] text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-300">
          <AlertTriangle className="mt-[1px] h-3.5 w-3.5 shrink-0" />
          <span className="break-all whitespace-pre-wrap">{table.message}</span>
        </div>
      )}

      {changedObjects.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 px-4 py-10 text-center">
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-50 text-emerald-500 dark:bg-emerald-500/10 dark:text-emerald-400">
            <GitCompareArrows className="h-4 w-4" />
          </span>
          <span className="text-[12px] text-muted-foreground">
            {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_NO_OBJECTS)}
          </span>
        </div>
      ) : (
        <div className="flex flex-col gap-2 p-3">
          {rendered.map((object, index) => (
            <ObjectDiffBlock key={`${object.objectType}-${object.objectKey}-${index}`} object={object} />
          ))}
          {changedObjects.length > rendered.length && (
            <div className="px-1 text-[11px] text-muted-foreground">
              {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_MORE, {
                count: changedObjects.length - rendered.length,
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function ObjectDiffBlock({ object }: { object: SchemaObjectDiff }) {
  const { t } = useTranslation();
  return (
    <div className="overflow-hidden rounded-md border border-border">
      <div className="flex items-center gap-2 border-b border-border bg-gray-50/80 px-2.5 py-1.5 dark:bg-gray-900/60">
        <span className="shrink-0 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
          {t(OBJECT_I18N[object.objectType])}
        </span>
        <span className="min-w-0 truncate text-[12px] font-medium" title={object.objectKey}>
          {object.objectKey}
        </span>
        <span className="ml-auto shrink-0">
          <StatusPill status={object.status} />
        </span>
      </div>
      {object.attributes.length > 0 && (
        <table className="w-full text-[11px]">
          <thead>
            <tr className="text-muted-foreground">
              <th className="w-[170px] px-2.5 py-1 text-left font-normal">
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_ATTRIBUTE)}
              </th>
              <th className="px-2.5 py-1 text-left font-normal">
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_SOURCE_VALUE)}
              </th>
              <th className="px-2.5 py-1 text-left font-normal">
                {t(I18N_KEYS.EXPLORER.SCHEMA_COMPARE_TARGET_VALUE)}
              </th>
            </tr>
          </thead>
          <tbody>
            {object.attributes.map((attribute) => (
              <tr key={attribute.name} className="border-t border-border hover:bg-accent/20">
                <td className="px-2.5 py-1 font-mono text-[10px] text-muted-foreground">
                  {ATTRIBUTE_LABELS[attribute.name] ?? attribute.name}
                </td>
                <td className="break-all px-2.5 py-1 font-mono text-rose-600 dark:text-rose-400">
                  {attribute.sourceValue ?? <span className="text-gray-400">∅</span>}
                </td>
                <td className="break-all px-2.5 py-1 font-mono text-emerald-600 dark:text-emerald-400">
                  {attribute.targetValue ?? <span className="text-gray-400">∅</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function splitNames(raw: string): string[] {
  return raw
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean);
}

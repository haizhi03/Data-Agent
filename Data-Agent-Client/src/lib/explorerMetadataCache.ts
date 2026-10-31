import type { QueryClient } from '@tanstack/react-query';
import { FolderName } from '../constants/explorer';
import { databaseService } from '../services/database.service';
import { functionService, type FunctionMetadata } from '../services/function.service';
import { procedureService, type ProcedureMetadata } from '../services/procedure.service';
import { schemaService } from '../services/schema.service';
import { tableService } from '../services/table.service';
import { triggerService, type TriggerMetadata } from '../services/trigger.service';
import { viewService } from '../services/view.service';

const EXPLORER_METADATA_QUERY_ROOT = 'explorer-metadata';
const EXPLORER_METADATA_STALE_TIME_MS = 60 * 1000;

/** Object-list query kinds; each key is [root, kind, connectionId, catalog, schema]. */
const EXPLORER_OBJECT_KINDS = ['tables', 'views', 'functions', 'procedures', 'triggers'] as const;

type CachedListOptions = {
  force?: boolean;
  staleTime?: number;
};

const normalizeScope = (value?: string) => value ?? '';

export const getExplorerDatabasesQueryKey = (connectionId: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'databases', connectionId] as const;

export const getExplorerSchemasQueryKey = (connectionId: string, catalog?: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'schemas', connectionId, normalizeScope(catalog)] as const;

export const getExplorerTablesQueryKey = (connectionId: string, catalog?: string, schema?: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'tables', connectionId, normalizeScope(catalog), normalizeScope(schema)] as const;

export const getExplorerViewsQueryKey = (connectionId: string, catalog?: string, schema?: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'views', connectionId, normalizeScope(catalog), normalizeScope(schema)] as const;

export const getExplorerFunctionsQueryKey = (connectionId: string, catalog?: string, schema?: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'functions', connectionId, normalizeScope(catalog), normalizeScope(schema)] as const;

export const getExplorerProceduresQueryKey = (connectionId: string, catalog?: string, schema?: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'procedures', connectionId, normalizeScope(catalog), normalizeScope(schema)] as const;

export const getExplorerTriggersQueryKey = (connectionId: string, catalog?: string, schema?: string) =>
  [EXPLORER_METADATA_QUERY_ROOT, 'triggers', connectionId, normalizeScope(catalog), normalizeScope(schema)] as const;

async function fetchCachedData<T>(
  queryClient: QueryClient,
  queryKey: readonly string[],
  queryFn: () => Promise<T>,
  options?: CachedListOptions
): Promise<T> {
  const { force = false, staleTime = EXPLORER_METADATA_STALE_TIME_MS } = options ?? {};
  return queryClient.fetchQuery({
    queryKey,
    queryFn,
    staleTime: force ? 0 : staleTime,
  });
}

export function getCachedExplorerDatabases(queryClient: QueryClient, connectionId: string): string[] | undefined {
  return queryClient.getQueryData<string[]>(getExplorerDatabasesQueryKey(connectionId));
}

export function getCachedExplorerSchemas(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string
): string[] | undefined {
  return queryClient.getQueryData<string[]>(getExplorerSchemasQueryKey(connectionId, catalog));
}

export function getCachedExplorerTables(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string
): string[] | undefined {
  return queryClient.getQueryData<string[]>(getExplorerTablesQueryKey(connectionId, catalog, schema));
}

export function getCachedExplorerViews(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string
): string[] | undefined {
  return queryClient.getQueryData<string[]>(getExplorerViewsQueryKey(connectionId, catalog, schema));
}

export function getCachedExplorerFunctions(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string
): FunctionMetadata[] | undefined {
  return queryClient.getQueryData<FunctionMetadata[]>(getExplorerFunctionsQueryKey(connectionId, catalog, schema));
}

export function getCachedExplorerProcedures(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string
): ProcedureMetadata[] | undefined {
  return queryClient.getQueryData<ProcedureMetadata[]>(getExplorerProceduresQueryKey(connectionId, catalog, schema));
}

export function getCachedExplorerTriggers(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string
): TriggerMetadata[] | undefined {
  return queryClient.getQueryData<TriggerMetadata[]>(getExplorerTriggersQueryKey(connectionId, catalog, schema));
}

export function fetchExplorerDatabases(
  queryClient: QueryClient,
  connectionId: string,
  options?: CachedListOptions
): Promise<string[]> {
  return fetchCachedData(
    queryClient,
    getExplorerDatabasesQueryKey(connectionId),
    () => databaseService.listDatabases(connectionId),
    options
  );
}

export function fetchExplorerSchemas(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  options?: CachedListOptions
): Promise<string[]> {
  return fetchCachedData(
    queryClient,
    getExplorerSchemasQueryKey(connectionId, catalog),
    () => schemaService.listSchemas(connectionId, catalog),
    options
  );
}

export function fetchExplorerTables(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string,
  options?: CachedListOptions
): Promise<string[]> {
  return fetchCachedData(
    queryClient,
    getExplorerTablesQueryKey(connectionId, catalog, schema),
    () => tableService.listTables(connectionId, catalog, schema),
    options
  );
}

export function fetchExplorerViews(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string,
  options?: CachedListOptions
): Promise<string[]> {
  return fetchCachedData(
    queryClient,
    getExplorerViewsQueryKey(connectionId, catalog, schema),
    () => viewService.listViews(connectionId, catalog, schema),
    options
  );
}

export function fetchExplorerFunctions(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string,
  options?: CachedListOptions
): Promise<FunctionMetadata[]> {
  return fetchCachedData(
    queryClient,
    getExplorerFunctionsQueryKey(connectionId, catalog, schema),
    () => functionService.listFunctions(connectionId, catalog, schema),
    options
  );
}

export function fetchExplorerProcedures(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string,
  options?: CachedListOptions
): Promise<ProcedureMetadata[]> {
  return fetchCachedData(
    queryClient,
    getExplorerProceduresQueryKey(connectionId, catalog, schema),
    () => procedureService.listProcedures(connectionId, catalog, schema),
    options
  );
}

export function fetchExplorerTriggers(
  queryClient: QueryClient,
  connectionId: string,
  catalog?: string,
  schema?: string,
  options?: CachedListOptions
): Promise<TriggerMetadata[]> {
  return fetchCachedData(
    queryClient,
    getExplorerTriggersQueryKey(connectionId, catalog, schema),
    () => triggerService.listTriggers(connectionId, catalog, schema),
    options
  );
}

/** Drop a deleted object from a cached list so tree rebuilds don't resurrect it. */
function removeNameFromCachedList(
  queryClient: QueryClient,
  queryKey: readonly string[],
  name: string
) {
  const cached = queryClient.getQueryData<string[]>(queryKey);
  if (cached) queryClient.setQueryData<string[]>(queryKey, cached.filter((n) => n !== name));
}

function removeNameFromCachedMetadata<T extends { name: string }>(
  queryClient: QueryClient,
  queryKey: readonly string[],
  name: string
) {
  const cached = queryClient.getQueryData<T[]>(queryKey);
  if (cached) queryClient.setQueryData<T[]>(queryKey, cached.filter((item) => item.name !== name));
}

/** Keep a cached table list in sync with a rename so tree rebuilds don't revive the old name. */
export function renameCachedExplorerTable(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  oldName: string,
  newName: string
) {
  const queryKey = getExplorerTablesQueryKey(connectionId, catalog, schema);
  const cached = queryClient.getQueryData<string[]>(queryKey);
  if (!cached || !cached.includes(oldName)) return;
  const next = [...new Set(cached.map((name) => (name === oldName ? newName : name)))];
  queryClient.setQueryData<string[]>(queryKey, next);
}

export function removeCachedExplorerTable(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string
) {
  removeNameFromCachedList(queryClient, getExplorerTablesQueryKey(connectionId, catalog, schema), name);
}

export function removeCachedExplorerView(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string
) {
  removeNameFromCachedList(queryClient, getExplorerViewsQueryKey(connectionId, catalog, schema), name);
}

export function removeCachedExplorerFunction(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string
) {
  removeNameFromCachedMetadata<FunctionMetadata>(
    queryClient,
    getExplorerFunctionsQueryKey(connectionId, catalog, schema),
    name
  );
}

export function removeCachedExplorerProcedure(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string
) {
  removeNameFromCachedMetadata<ProcedureMetadata>(
    queryClient,
    getExplorerProceduresQueryKey(connectionId, catalog, schema),
    name
  );
}

export function removeCachedExplorerTrigger(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  name: string
) {
  removeNameFromCachedMetadata<TriggerMetadata>(
    queryClient,
    getExplorerTriggersQueryKey(connectionId, catalog, schema),
    name
  );
}

/**
 * Drop the cached object list backing a folder after its contents were deleted
 * wholesale. The next hydration fetches the real list instead of reviving
 * partially deleted objects.
 */
export function invalidateCachedExplorerFolder(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string | undefined,
  schema: string | undefined,
  folderName: FolderName
) {
  switch (folderName) {
    case FolderName.TABLES:
      queryClient.removeQueries({ queryKey: getExplorerTablesQueryKey(connectionId, catalog, schema) });
      break;
    case FolderName.VIEWS:
      queryClient.removeQueries({ queryKey: getExplorerViewsQueryKey(connectionId, catalog, schema) });
      break;
    case FolderName.ROUTINES:
      queryClient.removeQueries({ queryKey: getExplorerFunctionsQueryKey(connectionId, catalog, schema) });
      queryClient.removeQueries({ queryKey: getExplorerProceduresQueryKey(connectionId, catalog, schema) });
      break;
    case FolderName.TRIGGERS:
      queryClient.removeQueries({ queryKey: getExplorerTriggersQueryKey(connectionId, catalog, schema) });
      break;
    default:
      break;
  }
}

/**
 * Drop every cached object list belonging to one catalog (database). Query keys
 * nest catalog before schema, so a prefix match also clears all of its schemas.
 */
export function clearExplorerMetadataForCatalog(
  queryClient: QueryClient,
  connectionId: string,
  catalog: string
) {
  queryClient.removeQueries({ queryKey: getExplorerDatabasesQueryKey(connectionId) });
  queryClient.removeQueries({ queryKey: getExplorerSchemasQueryKey(connectionId, catalog) });
  // The schema slot is always present (normalized to ''), so stop the prefix at
  // the catalog to match every schema under it.
  const catalogPrefix = normalizeScope(catalog);
  for (const kind of EXPLORER_OBJECT_KINDS) {
    queryClient.removeQueries({
      queryKey: [EXPLORER_METADATA_QUERY_ROOT, kind, connectionId, catalogPrefix],
    });
  }
}

/** Drop every cached list for a connection, across all catalogs and schemas. */
export function clearExplorerMetadataForConnection(queryClient: QueryClient, connectionId: string) {
  queryClient.removeQueries({ queryKey: getExplorerDatabasesQueryKey(connectionId) });
  queryClient.removeQueries({ queryKey: [EXPLORER_METADATA_QUERY_ROOT, 'schemas', connectionId] });
  for (const kind of EXPLORER_OBJECT_KINDS) {
    queryClient.removeQueries({ queryKey: [EXPLORER_METADATA_QUERY_ROOT, kind, connectionId] });
  }
}

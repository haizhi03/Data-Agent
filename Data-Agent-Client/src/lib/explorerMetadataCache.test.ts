import { beforeEach, describe, expect, it } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { FolderName } from '../constants/explorer';
import {
  clearExplorerMetadataForCatalog,
  getCachedExplorerSchemas,
  getCachedExplorerTables,
  getCachedExplorerViews,
  getExplorerDatabasesQueryKey,
  getExplorerSchemasQueryKey,
  getExplorerTablesQueryKey,
  getExplorerViewsQueryKey,
  invalidateCachedExplorerFolder,
  removeCachedExplorerTable,
  removeCachedExplorerView,
  renameCachedExplorerTable,
} from './explorerMetadataCache';

describe('explorer metadata cache after deletes', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
  });

  it('drops a deleted table from the cached table list', () => {
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'db', undefined), ['users', 'orders']);
    removeCachedExplorerTable(queryClient, '1', 'db', undefined, 'users');
    expect(getCachedExplorerTables(queryClient, '1', 'db', undefined)).toEqual(['orders']);
  });

  it('drops a deleted view from the cached view list', () => {
    queryClient.setQueryData(getExplorerViewsQueryKey('1', 'db', 'public'), ['v1', 'v2']);
    removeCachedExplorerView(queryClient, '1', 'db', 'public', 'v1');
    expect(getCachedExplorerViews(queryClient, '1', 'db', 'public')).toEqual(['v2']);
  });

  it('keeps other scopes untouched', () => {
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'db_a', undefined), ['users']);
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'db_b', undefined), ['users']);
    removeCachedExplorerTable(queryClient, '1', 'db_a', undefined, 'users');
    expect(getCachedExplorerTables(queryClient, '1', 'db_a', undefined)).toEqual([]);
    expect(getCachedExplorerTables(queryClient, '1', 'db_b', undefined)).toEqual(['users']);
  });

  it('does nothing when the list was never cached', () => {
    removeCachedExplorerTable(queryClient, '1', 'db', undefined, 'users');
    expect(getCachedExplorerTables(queryClient, '1', 'db', undefined)).toBeUndefined();
  });

  it('removes the cached list after deleting a folder of tables', () => {
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'db', undefined), ['users']);
    invalidateCachedExplorerFolder(queryClient, '1', 'db', undefined, FolderName.TABLES);
    expect(getCachedExplorerTables(queryClient, '1', 'db', undefined)).toBeUndefined();
  });
});

describe('clearExplorerMetadataForCatalog', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
  });

  it('drops every cached list for the catalog, including all schemas', () => {
    queryClient.setQueryData(getExplorerDatabasesQueryKey('1'), ['app', 'other']);
    queryClient.setQueryData(getExplorerSchemasQueryKey('1', 'app'), ['public', 'hr']);
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'app', 'public'), ['users']);
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'app', 'hr'), ['employees']);
    queryClient.setQueryData(getExplorerViewsQueryKey('1', 'app', 'public'), ['v1']);
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'other', undefined), ['users']);

    clearExplorerMetadataForCatalog(queryClient, '1', 'app');

    expect(queryClient.getQueryData(getExplorerDatabasesQueryKey('1'))).toBeUndefined();
    expect(getCachedExplorerSchemas(queryClient, '1', 'app')).toBeUndefined();
    expect(getCachedExplorerTables(queryClient, '1', 'app', 'public')).toBeUndefined();
    expect(getCachedExplorerTables(queryClient, '1', 'app', 'hr')).toBeUndefined();
    expect(getCachedExplorerViews(queryClient, '1', 'app', 'public')).toBeUndefined();
    // Sibling catalogs and connections stay cached.
    expect(getCachedExplorerTables(queryClient, '1', 'other', undefined)).toEqual(['users']);
  });
});

describe('explorer metadata cache after renames', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
  });

  it('renames the table in place in the cached list', () => {
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'db', 'public'), ['users', 'orders']);
    renameCachedExplorerTable(queryClient, '1', 'db', 'public', 'users', 'customers');
    expect(getCachedExplorerTables(queryClient, '1', 'db', 'public')).toEqual(['customers', 'orders']);
  });

  it('does nothing when the list was never cached', () => {
    renameCachedExplorerTable(queryClient, '1', 'db', undefined, 'users', 'customers');
    expect(getCachedExplorerTables(queryClient, '1', 'db', undefined)).toBeUndefined();
  });

  it('does nothing when the old name is not in the list', () => {
    queryClient.setQueryData(getExplorerTablesQueryKey('1', 'db', undefined), ['orders']);
    renameCachedExplorerTable(queryClient, '1', 'db', undefined, 'users', 'customers');
    expect(getCachedExplorerTables(queryClient, '1', 'db', undefined)).toEqual(['orders']);
  });
});

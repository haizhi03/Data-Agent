import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';
import { sqlExecutionService } from './sqlExecution.service';
import type { ExecuteSqlResponse } from '../types/sql';

export interface CreateTableParams {
  connectionId: number;
  databaseName?: string | null;
  schemaName?: string | null;
  /** CREATE TABLE DDL SQL */
  sql: string;
}

export interface ErColumn {
  name: string;
  typeName: string;
  comment: string;
  primaryKey: boolean;
}

export interface ErTable {
  name: string;
  comment: string;
  columns: ErColumn[];
}

export interface ErRelation {
  name: string;
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
}

export interface ErDiagram {
  tables: ErTable[];
  relations: ErRelation[];
  truncated: boolean;
}

export const tableService = {
  getErDiagram: async (connectionId: string, catalog?: string, schema?: string): Promise<ErDiagram> => {
    const params: Record<string, string> = { connectionId };
    if (catalog) params.catalog = catalog;
    if (schema) params.schema = schema;
    const response = await http.get<ErDiagram>(ApiPaths.TABLES_ER_DIAGRAM, { params, timeout: 60000 });
    return response.data;
  },

  listTables: async (connectionId: string, catalog?: string, schema?: string): Promise<string[]> => {
    const params: Record<string, string> = { connectionId };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;
    
    const response = await http.get<string[]>(ApiPaths.TABLES, { params });
    return response.data;
  },

  getTableDdl: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string
  ): Promise<string> => {
    const params: Record<string, string> = {
      connectionId,
      tableName
    };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;

    const response = await http.get<string>(ApiPaths.TABLES_DDL, { params });
    return response.data;
  },

  /**
   * Create table by executing CREATE TABLE DDL.
   * Uses POST /api/db/sql/execute.
   */
  createTable: async (params: CreateTableParams): Promise<ExecuteSqlResponse> => {
    return sqlExecutionService.executeSql({
      connectionId: params.connectionId,
      databaseName: params.databaseName ?? undefined,
      schemaName: params.schemaName ?? undefined,
      sql: params.sql,
    });
  },

  deleteTable: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string
  ): Promise<void> => {
    await http.delete(ApiPaths.TABLES, {
      data: {
        connectionId,
        tableName,
        catalog,
        schema
      }
    });
  },

  renameTable: async (
    connectionId: string,
    tableName: string,
    newTableName: string,
    catalog?: string,
    schema?: string
  ): Promise<void> => {
    await http.put(ApiPaths.TABLES_RENAME, {
      connectionId,
      tableName,
      newTableName,
      catalog,
      schema
    });
  },
};

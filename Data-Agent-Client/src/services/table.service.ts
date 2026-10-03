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
  catalog?: string | null;
  schema?: string | null;
  /** True when the table lives outside the opened schema. */
  external?: boolean;
  comment: string;
  columns: ErColumn[];
}

/**
 * ONE_TO_ONE and ONE_TO_MANY describe a foreign key: the referenced side is one.
 * MANY_TO_MANY connects the two tables referenced by a junction table.
 */
export type ErCardinality = 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_MANY';

export interface ErRelation {
  name: string;
  fromCatalog?: string | null;
  fromSchema?: string | null;
  fromTable: string;
  /** First foreign-key column. Composite keys also fill fromColumns. */
  fromColumn: string;
  fromColumns: string[];
  toCatalog?: string | null;
  toSchema?: string | null;
  toTable: string;
  /** First referenced column. Composite keys also fill toColumns. */
  toColumn: string;
  toColumns: string[];
  cardinality: ErCardinality;
  /** Junction table for MANY_TO_MANY. */
  viaCatalog?: string | null;
  viaSchema?: string | null;
  viaTable?: string | null;
}

export interface ErDiagram {
  tables: ErTable[];
  relations: ErRelation[];
  truncated: boolean;
  /** Other schemas contributed more tables than the diagram keeps. */
  externalTruncated?: boolean;
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

import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';
import type { ExecuteSqlResponse } from '../types/sql';

export interface TableDataResponse {
  headers: string[];
  rows: unknown[][];
  totalCount: number;
  currentPage: number;
  pageSize: number;
  totalPages: number;
}

export interface TableRowValuePayload {
  columnName: string;
  value: unknown;
}

export interface InsertTableRowParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  values: TableRowValuePayload[];
}

export interface DeleteTableRowParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  matchValues: TableRowValuePayload[];
  force?: boolean;
}

export interface UpdateTableRowParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  setValues: TableRowValuePayload[];
  matchValues: TableRowValuePayload[];
  force?: boolean;
}

export type BatchRowOperationType = 'INSERT' | 'UPDATE' | 'DELETE';

export interface BatchRowOperation {
  type: BatchRowOperationType;
  values?: TableRowValuePayload[];
  setValues?: TableRowValuePayload[];
  matchValues?: TableRowValuePayload[];
}

export interface BatchTableRowsParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  operations: BatchRowOperation[];
  force?: boolean;
}

export interface BatchTableRowsItemResult {
  type: string;
  success: boolean;
  affectedRows: number;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface BatchTableRowsResult {
  success: boolean;
  committed: boolean;
  requiresForce: boolean;
  forceCode: string | null;
  total: number;
  succeeded: number;
  failedAtIndex: number | null;
  errorMessage: string | null;
  results: BatchTableRowsItemResult[];
}

export const tableDataService = {
  getTableData: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string,
    currentPage: number = 1,
    pageSize: number = 100,
    whereClause?: string,
    orderByColumn?: string,
    orderByDirection?: 'asc' | 'desc'
  ): Promise<TableDataResponse> => {
    const params: Record<string, string | number> = {
      connectionId,
      tableName,
      currentPage,
      pageSize
    };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;
    if (whereClause != null && whereClause !== '') params.whereClause = whereClause;
    if (orderByColumn != null && orderByColumn !== '') {
      params.orderByColumn = orderByColumn;
      params.orderByDirection = orderByDirection ?? 'asc';
    }

    const response = await http.get<TableDataResponse>(ApiPaths.TABLE_DATA, { params });
    return response.data;
  },

  getViewData: async (
    connectionId: string,
    viewName: string,
    catalog?: string,
    schema?: string,
    currentPage: number = 1,
    pageSize: number = 100,
    whereClause?: string,
    orderByColumn?: string,
    orderByDirection?: 'asc' | 'desc'
  ): Promise<TableDataResponse> => {
    const params: Record<string, string | number> = {
      connectionId,
      viewName,
      currentPage,
      pageSize
    };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;
    if (whereClause != null && whereClause !== '') params.whereClause = whereClause;
    if (orderByColumn != null && orderByColumn !== '') {
      params.orderByColumn = orderByColumn;
      params.orderByDirection = orderByDirection ?? 'asc';
    }

    const response = await http.get<TableDataResponse>(ApiPaths.VIEW_DATA, { params });
    return response.data;
  },

  insertRow: async (params: InsertTableRowParams): Promise<ExecuteSqlResponse> => {
    const response = await http.post<ExecuteSqlResponse>(ApiPaths.TABLE_ROWS, {
      connectionId: params.connectionId,
      tableName: params.tableName,
      catalog: params.catalog ?? undefined,
      schema: params.schema ?? undefined,
      values: params.values,
    });
    return response.data;
  },

  deleteRow: async (params: DeleteTableRowParams): Promise<ExecuteSqlResponse> => {
    const response = await http.delete<ExecuteSqlResponse>(ApiPaths.TABLE_ROWS, {
      data: {
        connectionId: params.connectionId,
        tableName: params.tableName,
        catalog: params.catalog ?? undefined,
        schema: params.schema ?? undefined,
        matchValues: params.matchValues,
        force: params.force ?? false,
      },
    });
    return response.data;
  },

  updateRow: async (params: UpdateTableRowParams): Promise<ExecuteSqlResponse> => {
    const response = await http.put<ExecuteSqlResponse>(ApiPaths.TABLE_ROWS, {
      connectionId: params.connectionId,
      tableName: params.tableName,
      catalog: params.catalog ?? undefined,
      schema: params.schema ?? undefined,
      setValues: params.setValues,
      matchValues: params.matchValues,
      force: params.force ?? false,
    });
    return response.data;
  },

  batchRows: async (params: BatchTableRowsParams): Promise<BatchTableRowsResult> => {
    const response = await http.post<BatchTableRowsResult>(ApiPaths.TABLE_ROWS_BATCH, {
      connectionId: params.connectionId,
      tableName: params.tableName,
      catalog: params.catalog ?? undefined,
      schema: params.schema ?? undefined,
      operations: params.operations,
      force: params.force ?? false,
    });
    return response.data;
  },
};

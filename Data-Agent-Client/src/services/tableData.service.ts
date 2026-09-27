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

export interface ExportTableParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  fileType: string;
}

export interface ImportTableDataParams {
  connectionId: number;
  tableName: string;
  catalog?: string;
  schema?: string;
  fileType: string;
  file: File;
}

export interface ImportTableDataResult {
  success: boolean;
  totalRows: number;
  insertedRows: number;
  failedAtRow: number | null;
  errorMessage: string | null;
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

  exportTable: async (params: ExportTableParams): Promise<Blob> => {
    const query: Record<string, string | number> = {
      connectionId: params.connectionId,
      tableName: params.tableName,
      fileType: params.fileType,
    };
    if (params.catalog != null && params.catalog !== '') query.catalog = params.catalog;
    if (params.schema != null && params.schema !== '') query.schema = params.schema;

    const response = await http.get<Blob>(ApiPaths.TABLE_EXPORT, {
      params: query,
      responseType: 'blob',
      timeout: 300000,
    });
    return response.data;
  },

  importTableData: async (params: ImportTableDataParams): Promise<ImportTableDataResult> => {
    const formData = new FormData();
    formData.append('connectionId', String(params.connectionId));
    formData.append('tableName', params.tableName);
    formData.append('fileType', params.fileType);
    formData.append('file', params.file);
    if (params.catalog != null && params.catalog !== '') {
      formData.append('catalog', params.catalog);
    }
    if (params.schema != null && params.schema !== '') {
      formData.append('schema', params.schema);
    }

    const response = await http.post<ImportTableDataResult>(ApiPaths.TABLE_IMPORT, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 600000,
    });
    return response.data;
  },
};

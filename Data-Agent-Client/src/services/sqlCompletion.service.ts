import http from '../lib/http';
import { SqlPaths } from '../constants/apiPaths';
import type { SqlCompletionParams, SqlCompletionResponse, SqlHoverResponse, SqlSignatureHelpResponse } from '../types/sql';

export const sqlCompletionService = {
  complete: async (params: SqlCompletionParams, signal?: AbortSignal): Promise<SqlCompletionResponse> => {
    const response = await http.post<SqlCompletionResponse>(SqlPaths.COMPLETE, {
      connectionId: params.connectionId,
      catalog: params.databaseName || undefined,
      schema: params.schemaName || undefined,
      sql: params.sql,
      cursorOffset: params.cursorOffset,
    }, { signal });
    return response.data;
  },
  dictionary: async (connectionId: number, signal?: AbortSignal,
    context?: { databaseName?: string | null; schemaName?: string | null; resolveEnvironment?: boolean }): Promise<SqlCompletionResponse> => {
    const response = await http.get<SqlCompletionResponse>(SqlPaths.COMPLETION_DICTIONARY, {
      params: { connectionId, catalog: context?.databaseName || undefined, schema: context?.schemaName || undefined,
        resolveEnvironment: context?.resolveEnvironment ?? false }, signal,
    });
    return response.data;
  },
  invalidate: async (connectionId: number) => {
    await http.post(SqlPaths.COMPLETION_INVALIDATE, null, { params: { connectionId } });
  },
  hover: async (params: SqlCompletionParams, signal?: AbortSignal): Promise<SqlHoverResponse> => {
    const response = await http.post<SqlHoverResponse>(SqlPaths.HOVER, {
      connectionId: params.connectionId,
      catalog: params.databaseName || undefined,
      schema: params.schemaName || undefined,
      sql: params.sql,
      cursorOffset: params.cursorOffset,
    }, { signal });
    return response.data;
  },
  signature: async (params: SqlCompletionParams, signal?: AbortSignal): Promise<SqlSignatureHelpResponse> => {
    const response = await http.post<SqlSignatureHelpResponse>(SqlPaths.SIGNATURE, {
      connectionId: params.connectionId,
      catalog: params.databaseName || undefined,
      schema: params.schemaName || undefined,
      sql: params.sql,
      cursorOffset: params.cursorOffset,
    }, { signal });
    return response.data;
  },
};

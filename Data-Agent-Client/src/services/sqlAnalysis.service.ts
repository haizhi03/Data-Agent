import http from '../lib/http';
import { SqlPaths } from '../constants/apiPaths';
import type { ExecuteSqlParams, SqlScriptAnalysis } from '../types/sql';

export const sqlAnalysisService = {
  analyze: async (params: ExecuteSqlParams): Promise<SqlScriptAnalysis> => {
    const response = await http.post<SqlScriptAnalysis>(SqlPaths.ANALYZE, {
      connectionId: params.connectionId,
      catalog: params.databaseName || undefined,
      schema: params.schemaName || undefined,
      sql: params.sql,
    });
    return response.data;
  },
};

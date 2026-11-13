import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';
import type { SchemaCompareParams, SchemaCompareResult } from '../types/schemaCompare';

export const schemaCompareService = {
  /** 同库结构比较；大表量时耗时较长，单独放宽超时。 */
  compare: async (params: SchemaCompareParams): Promise<SchemaCompareResult> => {
    const response = await http.post<SchemaCompareResult>(ApiPaths.SCHEMA_COMPARE, params, {
      timeout: 180000,
    });
    return response.data;
  },
};

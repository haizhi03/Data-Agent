import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';
import type { StructureTypeRules } from '../lib/structureTypeRules';
import type { ApplyTableStructureResponse, StructurePreview, TableStructure } from '../types/tableStructure';

export interface TableStructureScope {
  connectionId: number;
  catalog?: string | null;
  schema?: string | null;
  tableName: string;
}

export interface TableStructureDraftBody extends TableStructureScope {
  baseline: TableStructure;
  draft: TableStructure;
}

export const tableStructureService = {
  rules: async ({ connectionId, catalog, schema }: Omit<TableStructureScope, 'tableName'>): Promise<StructureTypeRules> => {
    const params: Record<string, string> = { connectionId: String(connectionId) };
    if (catalog) params.catalog = catalog;
    if (schema) params.schema = schema;
    const response = await http.get<StructureTypeRules>(ApiPaths.TABLES_STRUCTURE_RULES, { params, timeout: 60000 });
    return response.data;
  },

  load: async ({ connectionId, catalog, schema, tableName }: TableStructureScope): Promise<TableStructure> => {
    const params: Record<string, string> = { connectionId: String(connectionId), tableName };
    if (catalog) params.catalog = catalog;
    if (schema) params.schema = schema;
    const response = await http.get<TableStructure>(ApiPaths.TABLES_STRUCTURE, { params, timeout: 60000 });
    return response.data;
  },

  preview: async (body: TableStructureDraftBody): Promise<StructurePreview> => {
    const response = await http.post<StructurePreview>(ApiPaths.TABLES_STRUCTURE_PREVIEW, body, { timeout: 60000 });
    return response.data;
  },

  apply: async (body: TableStructureDraftBody): Promise<ApplyTableStructureResponse> => {
    const response = await http.post<ApplyTableStructureResponse>(ApiPaths.TABLES_STRUCTURE_APPLY, body, { timeout: 120000 });
    return response.data;
  },
};

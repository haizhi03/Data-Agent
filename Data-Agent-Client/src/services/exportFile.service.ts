import http from '../lib/http';
import type { DownloadExportFileResponse, ExportedFileResult, ExportFileStatus } from '../types/exportFile';

export interface ExportRowsPayload {
  format: string;
  headers: string[];
  rows: unknown[][];
}

export const exportFileService = {
  exportRows: async (payload: ExportRowsPayload): Promise<ExportedFileResult> => {
    const response = await http.post<ExportedFileResult>('/ai/files/export', payload);
    return response.data;
  },

  getFileStatus: async (fileId: string): Promise<ExportFileStatus> => {
    const response = await http.get<ExportFileStatus>(`/ai/files/${encodeURIComponent(fileId)}/status`);
    return response.data;
  },

  downloadFile: async (fileId: string): Promise<DownloadExportFileResponse> => {
    const response = await http.get<Blob>(`/ai/files/${encodeURIComponent(fileId)}`, {
      responseType: 'blob',
    });

    const headers = response.headers as Record<string, string | undefined>;
    return {
      blob: response.data,
      contentDisposition: headers['content-disposition'],
      contentType: headers['content-type'],
    };
  },
};

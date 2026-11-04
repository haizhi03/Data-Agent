import { useRef, useState, useCallback } from 'react';
import { tableDataService } from '../services/tableData.service';
import type { ImportTableDataResult } from '../services/tableData.service';

export interface BatchImportProgress {
  current: number;
  total: number;
  file: string;
}

export interface BatchImportResult {
  file: string;
  fileType: string;
  success: boolean;
  data: ImportTableDataResult;
}

export interface BatchImportFilePlan {
  file: File;
  fileType: string;
  tableName: string;
  createTableIfMissing?: boolean;
}

export function useBatchImport() {
  const cancelRef = useRef(false);
  const [importing, setImporting] = useState(false);
  const [progress, setProgress] = useState<BatchImportProgress | null>(null);
  const [results, setResults] = useState<BatchImportResult[]>([]);

  const start = useCallback(
    async (
      plans: BatchImportFilePlan[],
      baseParams: {
        connectionId: number;
        catalog?: string;
        schema?: string;
        encoding?: string;
        createTableIfMissing?: boolean;
      }
    ) => {
      cancelRef.current = false;
      setImporting(true);
      setResults([]);
      const collected: BatchImportResult[] = [];

      for (let i = 0; i < plans.length; i++) {
        if (cancelRef.current) {
          break;
        }
        const item = plans[i];
        setProgress({ current: i + 1, total: plans.length, file: item.file.name });

        try {
          const data = await tableDataService.importTableData({
            connectionId: baseParams.connectionId,
            tableName: item.tableName,
            catalog: baseParams.catalog,
            schema: baseParams.schema,
            fileType: item.fileType,
            encoding: item.fileType === 'CSV' ? baseParams.encoding : undefined,
            createTableIfMissing: item.createTableIfMissing ?? baseParams.createTableIfMissing,
            file: item.file,
          });
          collected.push({ file: item.file.name, fileType: item.fileType, success: data.success, data });
        } catch (error: unknown) {
          const msg = error instanceof Error ? error.message : String(error);
          collected.push({
            file: item.file.name,
            fileType: item.fileType,
            success: false,
            data: { success: false, totalRows: 0, insertedRows: 0, failedAtRow: null, errorMessage: msg },
          });
        }
        setResults([...collected]);
      }

      setImporting(false);
      setProgress(null);
      return { results: collected, cancelled: cancelRef.current };
    },
    []
  );

  const stop = useCallback(() => {
    cancelRef.current = true;
  }, []);

  const reset = useCallback(() => {
    cancelRef.current = true;
    setImporting(false);
    setProgress(null);
    setResults([]);
  }, []);

  return { importing, progress, results, start, stop, reset };
}

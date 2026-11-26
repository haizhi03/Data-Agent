import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExecuteSqlResponse } from '../types/sql';

vi.mock('../services/sqlExecution.service', () => ({ sqlExecutionService: {
  executeScript: vi.fn(), cancelExecution: vi.fn(),
} }));
vi.mock('../services/sqlAnalysis.service', () => ({ sqlAnalysisService: { analyze: vi.fn() } }));
import { sqlExecutionService } from '../services/sqlExecution.service';
import { useSqlRunStore } from './sqlRunStore';

const success: ExecuteSqlResponse = { success: true, query: true, affectedRows: 0, executionTimeMs: 1 };
const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
};

describe('shared SQL execution ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useSqlRunStore.setState({ isRunning: false, cancelStatus: null, scriptResults: [], executeResult: null, executionReferences: [] });
  });

  it('cancels the original connection and schema without consulting the active tab', async () => {
    const execution = deferred<ExecuteSqlResponse[]>();
    vi.mocked(sqlExecutionService.executeScript).mockReturnValueOnce(execution.promise);
    vi.mocked(sqlExecutionService.cancelExecution).mockResolvedValueOnce('CANCEL_REQUESTED');
    const run = useSqlRunStore.getState().runSql({ connectionId: 2, databaseName: 'db2', schemaName: 'owner', sql: 'SELECT 1' });
    const request = vi.mocked(sqlExecutionService.executeScript).mock.calls[0][0];
    await useSqlRunStore.getState().stopSql();
    expect(sqlExecutionService.cancelExecution).toHaveBeenCalledWith({
      connectionId: 2, databaseName: 'db2', schemaName: 'owner', executionId: request.executionId,
    });
    expect(useSqlRunStore.getState().cancelStatus).toContain('Cancel requested');
    execution.resolve([success]);
    await run;
    expect(useSqlRunStore.getState().isRunning).toBe(false);
  });

  it('does not start an overlapping execution and lose the running script', async () => {
    const execution = deferred<ExecuteSqlResponse[]>();
    vi.mocked(sqlExecutionService.executeScript).mockReturnValueOnce(execution.promise);
    const run = useSqlRunStore.getState().runSql({ connectionId: 2, sql: 'SELECT 1' });
    await useSqlRunStore.getState().runSql({ connectionId: 3, sql: 'DELETE FROM t' });
    expect(sqlExecutionService.executeScript).toHaveBeenCalledTimes(1);
    execution.resolve([success]);
    await run;
  });

  it('keeps completed statement references bound to the submitted source and scope', async () => {
    const execution = deferred<ExecuteSqlResponse[]>();
    vi.mocked(sqlExecutionService.executeScript).mockReturnValueOnce(execution.promise);
    const source = { documentId: 'query-a', documentName: 'Query A', documentVersion: 3 };
    const run = useSqlRunStore.getState().runSql({ connectionId: 2, schemaName: 'OWNER', sql: 'SELECT 1; SELECT missing;', source });
    source.documentVersion = 4;
    const result: ExecuteSqlResponse = { ...success, executedSql: 'SELECT 1', statementState: 'EXECUTED', transactionOutcome: 'NONE',
      resultSet: { columns: [{ name: '' }, { name: 'amount' }], rows: [[null, 7]], truncated: true } };
    execution.resolve([result, { ...success, success: false, executedSql: 'SELECT missing', statementState: 'FAILED', transactionOutcome: 'NONE',
      errorMessage: 'column missing', messages: [{ level: 'ERROR', sqlState: '42S22', message: 'column missing' }] }]);
    await run;
    const references = useSqlRunStore.getState().executionReferences;
    expect(references).toHaveLength(2);
    expect(references[0]).toMatchObject({ statementIndex: 0, sql: 'SELECT 1', documentId: 'query-a', documentVersion: 3,
      target: { connectionId: 2, schemaName: 'OWNER' }, truncated: true,
      result: { headers: ['Column 1', 'amount'], rows: [[null, '7']], returnedRows: 1 } });
    expect(references[1]).toMatchObject({ statementIndex: 1, sql: 'SELECT missing',
      result: { statementState: 'FAILED', errorMessage: 'column missing', sqlState: '42S22' } });
    result.resultSet!.rows![0][1] = 99;
    expect(references[0].result.rows[0][1]).toBe('7');
  });

  it('bounds samples and clears old references when a new execution starts', async () => {
    const result: ExecuteSqlResponse = { ...success, executedSql: 'x'.repeat(8100), errorMessage: 'e'.repeat(2100),
      headers: Array.from({ length: 10 }, (_, i) => 'column' + i),
      rows: Array.from({ length: 10 }, () => Array.from({ length: 10 }, () => 'x'.repeat(200))) };
    vi.mocked(sqlExecutionService.executeScript).mockResolvedValueOnce([result]);
    await useSqlRunStore.getState().runSql({ connectionId: 1, sql: 'SELECT * FROM sample' });
    const reference = useSqlRunStore.getState().executionReferences[0];
    expect(reference.truncated).toBe(true);
    expect(reference.sql).toHaveLength(8000);
    expect(reference.result.errorMessage).toHaveLength(2000);
    expect(reference.result.rows).toHaveLength(5);
    expect(reference.result.headers).toHaveLength(8);
    expect(reference.result.rows[0]).toHaveLength(8);
    expect(reference.result.rows[0][0]).toHaveLength(128);
    const next = deferred<ExecuteSqlResponse[]>();
    vi.mocked(sqlExecutionService.executeScript).mockReturnValueOnce(next.promise);
    const pending = useSqlRunStore.getState().runSql({ connectionId: 2, sql: 'SELECT 2' });
    expect(useSqlRunStore.getState().executionReferences).toEqual([]);
    next.resolve([success]);
    await pending;
  });

  it('preserves uncertainty when the transport fails', async () => {
    vi.mocked(sqlExecutionService.executeScript).mockRejectedValueOnce(new Error('connection lost'));
    await useSqlRunStore.getState().runSql({ connectionId: 2, sql: 'UPDATE sample SET n=1' });
    expect(useSqlRunStore.getState().executionReferences[0]).toMatchObject({
      target: { connectionId: 2 }, result: { success: false, statementState: 'UNKNOWN', transactionOutcome: 'UNKNOWN' },
    });
  });

  it('ignores late cancel responses for a completed execution', async () => {
    const first = deferred<ExecuteSqlResponse[]>();
    const second = deferred<ExecuteSqlResponse[]>();
    const cancellation = deferred<'CANCEL_REQUESTED'>();
    vi.mocked(sqlExecutionService.executeScript).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    vi.mocked(sqlExecutionService.cancelExecution).mockReturnValueOnce(cancellation.promise);
    const run = useSqlRunStore.getState().runSql({ connectionId: 1, sql: 'SELECT 1' });
    const stop = useSqlRunStore.getState().stopSql();
    first.resolve([success]);
    await run;
    const nextRun = useSqlRunStore.getState().runSql({ connectionId: 2, sql: 'SELECT 2' });
    cancellation.resolve('CANCEL_REQUESTED');
    await stop;
    expect(useSqlRunStore.getState().cancelStatus).toBeNull();
    expect(useSqlRunStore.getState().isRunning).toBe(true);
    second.resolve([success]);
    await nextRun;
  });
});

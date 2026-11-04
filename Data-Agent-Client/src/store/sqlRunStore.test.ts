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
    useSqlRunStore.setState({ isRunning: false, cancelStatus: null, scriptResults: [], executeResult: null });
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

import { beforeEach, describe, expect, it, vi } from 'vitest';

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));

vi.mock('../lib/http', () => ({ default: { post: postMock } }));

import { sqlAnalysisService } from './sqlAnalysis.service';
import { SqlPaths } from '../constants/apiPaths';

describe('sqlAnalysisService', () => {
  beforeEach(() => postMock.mockReset());

  it('sends the selected DM scope and returns parser diagnostics', async () => {
    const analysis = { statements: [], errors: [{ line: 2, column: 3, message: 'syntax error' }] };
    postMock.mockResolvedValue({ data: analysis });

    const result = await sqlAnalysisService.analyze({
      connectionId: 9,
      databaseName: null,
      schemaName: 'HR',
      sql: 'SELECT FROM T',
    });

    expect(postMock).toHaveBeenCalledWith(SqlPaths.ANALYZE, {
      connectionId: 9,
      catalog: undefined,
      schema: 'HR',
      sql: 'SELECT FROM T',
    });
    expect(result).toBe(analysis);
  });
});

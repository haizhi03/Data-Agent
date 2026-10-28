import { beforeEach, describe, expect, it, vi } from 'vitest';
import http from '../lib/http';
import { connectionService } from './connection.service';

vi.mock('../lib/http', () => ({ default: { put: vi.fn(), delete: vi.fn() } }));

describe('connection editing concurrency', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(http.put).mockResolvedValue({ data: { id: 7, version: 4 } }); });
  it('retains the version and explicit credential action in the update request', async () => {
    const input = { connectionId: 7, expectedVersion: 3, credentialAction: 'REMOVE' as const,
      name: 'fixture', dbType: 'mysql', host: 'db.example.invalid', port: 3306, driverJarPath: '/fixture.jar' };
    await connectionService.updateConnection(input);
    expect(http.put).toHaveBeenCalledWith('/connections', input);
  });
  it('sends the version for local deletion and keeps the legacy call without a version', async () => {
    await connectionService.deleteConnection(7, 3);
    expect(http.delete).toHaveBeenLastCalledWith('/connections/7', { params: { expectedVersion: 3 } });
    await connectionService.deleteConnection(7);
    expect(http.delete).toHaveBeenLastCalledWith('/connections/7');
  });
});

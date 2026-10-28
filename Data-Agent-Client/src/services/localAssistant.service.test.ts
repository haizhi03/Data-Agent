import { beforeEach, describe, expect, it, vi } from 'vitest';
import { localAssistantService } from './localAssistant.service';
import { localFetch } from '../lib/localRuntime';
vi.mock('../lib/localRuntime', () => ({ localFetch: vi.fn() }));
beforeEach(() => vi.clearAllMocks());
const stream = (events: unknown[]) => new Response(events.map(event => `data:${JSON.stringify(event)}\n\n`).join(''), {
  headers: { 'Content-Type': 'text/event-stream' },
});
describe('local assistant stream contract', () => {
  it('preserves ordered tool and terminal events', async () => {
    const events = [{ type: 'TOOL_RESULT', data: '{"truncated":true}', done: false }, { done: true, data: '{"status":"SUCCEEDED"}' }];
    vi.mocked(localFetch).mockResolvedValue(stream(events));
    const received = []; for await (const event of localAssistantService.chat(7, 'hello', 'model-id', new AbortController().signal)) received.push(event);
    expect(received).toEqual(events);
    expect(JSON.parse(vi.mocked(localFetch).mock.calls[0][1]!.body as string)).toEqual({ conversationId: 7, message: 'hello', modelConfigId: 'model-id' });
  });
  it('does not treat an interrupted stream as successful completion', async () => {
    vi.mocked(localFetch).mockResolvedValue(stream([{ type: 'TEXT', data: 'partial', done: false }]));
    await expect(async () => { for await (const _event of localAssistantService.chat(7, 'hello', 'model-id', new AbortController().signal)) { /* consume */ } }).rejects.toThrow('LOCAL_STREAM_FAILED');
  });
  it('surfaces stable session expiry instead of starting another conversation silently', async () => {
    vi.mocked(localFetch).mockResolvedValue(new Response('{"reasonCode":"LOCAL_SESSION_EXPIRED"}', { status: 410 }));
    await expect(async () => { for await (const _event of localAssistantService.chat(7, 'hello', 'model-id', new AbortController().signal)) { /* consume */ } }).rejects.toThrow('LOCAL_SESSION_EXPIRED');
    expect(localFetch).toHaveBeenCalledTimes(1);
  });
  it('approves only a server proposal id and never resubmits SQL or retries an interrupted decision', async () => {
    vi.mocked(localFetch).mockRejectedValue(new Error('network interrupted'));
    await expect(localAssistantService.decide(7, 'proposal-id', 'APPROVE')).rejects.toThrow('network interrupted');
    expect(localFetch).toHaveBeenCalledTimes(1);
    expect(JSON.parse(vi.mocked(localFetch).mock.calls[0][1]!.body as string)).toEqual({ conversationId: 7, proposalId: 'proposal-id', decision: 'APPROVE' });
  });
  it('can inspect the same temporary operation after a lost approval response without replaying it', async () => {
    vi.mocked(localFetch).mockResolvedValue(new Response('{"code":200,"data":[{"status":"UNKNOWN"}]}'));
    expect(await localAssistantService.proposals(7)).toEqual([{ status: 'UNKNOWN' }]);
    expect(vi.mocked(localFetch).mock.calls[0][0]).toBe('/local/conversations/7/write-proposals');
    expect(vi.mocked(localFetch).mock.calls[0][1]!.method).toBe('GET');
  });

});

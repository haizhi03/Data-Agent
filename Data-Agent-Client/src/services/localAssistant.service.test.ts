import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { localAssistantService } from './localAssistant.service';
import { localFetch } from '../lib/localRuntime';
vi.mock('../lib/localRuntime', () => ({ localFetch: vi.fn() }));
beforeEach(() => vi.clearAllMocks());
afterEach(() => vi.useRealTimers());
const stream = (events: unknown[]) => new Response(events.map(event => `data:${JSON.stringify(event)}\n\n`).join(''), {
  headers: { 'Content-Type': 'text/event-stream' },
});
describe('local assistant stream contract', () => {
  function controlledStream() {
    let controller: ReadableStreamDefaultController<Uint8Array>;
    let requestSignal: AbortSignal;
    vi.mocked(localFetch).mockImplementationOnce(async (_path, init) => {
      requestSignal = init!.signal as AbortSignal;
      const body = new ReadableStream<Uint8Array>({ start(value) { controller = value; } });
      requestSignal.addEventListener('abort', () => controller.error(requestSignal.reason), { once: true });
      return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
    });
    return {
      emit: (block: unknown) => controller.enqueue(new TextEncoder().encode(`data:${JSON.stringify(block)}\n\n`)),
      close: () => controller.close(),
      aborted: () => requestSignal.aborted,
    };
  }
  it('allows an active run to exceed 175 seconds overall and clears its idle timer', async () => {
    vi.useFakeTimers();
    const source = controlledStream();
    const iterator = localAssistantService.chat(7, 'hello', 'model-id', new AbortController().signal, undefined, 'pi');
    const first = iterator.next();
    await vi.advanceTimersByTimeAsync(100000);
    source.emit({ type: 'TEXT', data: 'still working', done: false });
    expect((await first).value).toMatchObject({ type: 'TEXT' });
    const last = iterator.next();
    await vi.advanceTimersByTimeAsync(100000);
    expect(source.aborted()).toBe(false);
    source.emit({ done: true, data: '{"status":"SUCCEEDED"}' });
    source.close();
    expect((await last).value).toMatchObject({ done: true });
    expect((await iterator.next()).done).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('aborts an idle stream and reports a distinct timeout', async () => {
    vi.useFakeTimers();
    const source = controlledStream();
    const iterator = localAssistantService.chat(7, 'hello', 'model-id', new AbortController().signal);
    const pending = expect(iterator.next()).rejects.toThrow('LOCAL_STREAM_TIMEOUT');
    await vi.advanceTimersByTimeAsync(175000);
    await pending;
    expect(source.aborted()).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('preserves explicit cancellation and clears the idle timer', async () => {
    vi.useFakeTimers();
    const source = controlledStream();
    const cancel = new AbortController();
    const iterator = localAssistantService.chat(7, 'hello', 'model-id', cancel.signal);
    const pending = expect(iterator.next()).rejects.toThrow('user stopped');
    cancel.abort(new Error('user stopped'));
    await pending;
    expect(source.aborted()).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
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

import { localFetch } from '../lib/localRuntime';
import { parseSSEResponse } from '../lib/sse';
import type { ChatContext, ChatRequest, ChatResponseBlock } from '../types/chat';

export interface LocalWriteView {
  proposal: { proposalId: string; conversationId: number; connectionName: string; connectionVersion: number;
    dbType: string; host: string; port?: number; catalogName?: string; schemaName?: string;
    sqlPreview: string; expiresAt: string; constraints: { timeoutSeconds: number } };
  status: string; cancelRequested: boolean;
  result?: { status: string; transactionOutcome: string; affectedRows?: number; errorCode?: string; elapsedMs: number };
}
async function jsonRequest<T>(path: string, method: string, body?: unknown): Promise<T> {
  const response = await localFetch(path, { method, headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(45000) });
  const envelope = await response.json();
  if (!response.ok || envelope.code !== 200) throw new Error(envelope.reasonCode ?? 'LOCAL_ASSISTANT_FAILED');
  return envelope.data;
}
export const localAssistantService = {
  create: (target: ChatContext) =>
    jsonRequest<{ conversationId: number; runtimeInstanceId: string }>('/local/conversations', 'POST', target),
  get: (id: number) => jsonRequest<{ conversationId: number; defaultTarget: ChatContext }>(`/local/conversations/${id}`, 'GET'),
  updateDefaultTarget: (id: number, target: ChatContext) => jsonRequest<ChatContext>(`/local/conversations/${id}/default-target`, 'PUT', target),
  close: (id: number) => jsonRequest<void>(`/local/conversations/${id}`, 'DELETE'),
  cancel: (id: number) => jsonRequest<{ status: string }>(`/local/conversations/${id}/cancel`, 'POST'),
  compact: (id: number) => jsonRequest<{ compacted: boolean }>(`/local/conversations/${id}/compact`, 'POST'),
  proposals: (id: number) => jsonRequest<LocalWriteView[]>(`/local/conversations/${id}/write-proposals`, 'GET'),
  decide: (id: number, proposalId: string, decision: 'APPROVE' | 'REJECT') =>
    jsonRequest<LocalWriteView>('/permissions/write-executions/approve', 'POST', { conversationId: id, proposalId, decision }),
  async *chat(conversationId: number, message: string, modelConfigId: string, signal: AbortSignal, snapshot?: Pick<ChatRequest, 'defaultTarget' | 'userMentions'>, engine?: 'java' | 'pi'): AsyncGenerator<ChatResponseBlock> {
    // The experimental Pi engine has its own endpoint; a started run never silently falls back to the other engine.
    const path = engine === 'pi' ? '/local/pi/chat/stream' : '/local/chat/stream';
    // The host uses a 150-second inactivity timeout. Active multi-step runs may
    // legitimately take longer overall, so reset this transport guard per event.
    const idle = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const resetIdle = () => {
      clearTimeout(timer);
      timer = setTimeout(() => idle.abort(), 175000);
    };
    resetIdle();
    try {
      const response = await localFetch(path, { method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ conversationId, message, modelConfigId, ...snapshot }), signal: AbortSignal.any([signal, idle.signal]) });
      if (!response.ok) {
        const envelope = await response.json();
        throw new Error(envelope.reasonCode ?? 'LOCAL_ASSISTANT_FAILED');
      }
      let terminal = false;
      for await (const block of parseSSEResponse(response)) {
        resetIdle();
        terminal ||= block.done;
        yield block;
      }
      if (!terminal) throw new Error('LOCAL_STREAM_FAILED');
    } catch (error) {
      if (idle.signal.aborted && !signal.aborted) throw new Error('LOCAL_STREAM_TIMEOUT');
      throw error;
    } finally {
      clearTimeout(timer);
    }
  },
};

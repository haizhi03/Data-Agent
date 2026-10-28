import { localFetch } from '../lib/localRuntime';
import { parseSSEResponse } from '../lib/sse';
import type { ChatResponseBlock } from '../types/chat';

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
  create: (target: { connectionId?: number; catalog?: string; schema?: string }) =>
    jsonRequest<{ conversationId: number; runtimeInstanceId: string }>('/local/conversations', 'POST', target),
  close: (id: number) => jsonRequest<void>(`/local/conversations/${id}`, 'DELETE'),
  cancel: (id: number) => jsonRequest<{ status: string }>(`/local/conversations/${id}/cancel`, 'POST'),
  compact: (id: number) => jsonRequest<{ compacted: boolean }>(`/local/conversations/${id}/compact`, 'POST'),
  proposals: (id: number) => jsonRequest<LocalWriteView[]>(`/local/conversations/${id}/write-proposals`, 'GET'),
  decide: (id: number, proposalId: string, decision: 'APPROVE' | 'REJECT') =>
    jsonRequest<LocalWriteView>('/permissions/write-executions/approve', 'POST', { conversationId: id, proposalId, decision }),
  async *chat(conversationId: number, message: string, modelConfigId: string, signal: AbortSignal): AsyncGenerator<ChatResponseBlock> {
    const response = await localFetch('/local/chat/stream', { method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ conversationId, message, modelConfigId }), signal });
    if (!response.ok) {
      const envelope = await response.json();
      throw new Error(envelope.reasonCode ?? 'LOCAL_ASSISTANT_FAILED');
    }
    let terminal = false;
    for await (const block of parseSSEResponse(response)) { terminal ||= block.done; yield block; }
    if (!terminal) throw new Error('LOCAL_STREAM_FAILED');
  },
};

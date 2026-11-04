import { useEffect, useRef, useState, useMemo, type Dispatch, type SetStateAction } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { AIAssistantHeader } from './AIAssistantHeader';
import { AIAssistantContent } from './AIAssistantContent';
import { AIAssistantProvider, type AIAssistantContextValue } from './AIAssistantContext';
import { ChatInput } from './ChatInput';
import { DefaultTargetControl } from './DefaultTargetControl';
import { mentionsInText, snapshotChatRequest } from '../../lib/chatTarget';
import { MessageRole, type ChatContext, type ChatUserMention, type ChatResponseBlock } from '../../types/chat';
import { localAssistantService as api, type LocalWriteView } from '../../services/localAssistant.service';
import { useRuntimeStore } from '../../store/runtimeStore';
import { createModelManagementService } from '../../services/modelManagement.service';
import type { ManagedModel, ModelConnection } from '../../types/modelManagement';

interface Message { role: 'user' | 'assistant'; text: string; blocks?: ChatResponseBlock[]; userMentions?: ChatUserMention[] }

/** Mounted only in local mode; no history loader, preferences persistence, account hook or legacy permission grants. */
export function LocalAssistant({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation(); const tr = (key: string) => t(`local_assistant.${key}`);
  // The server advertises the experimental Pi engine only when it is enabled and ready; Java remains the default.
  const engine: 'java' | 'pi' = useRuntimeStore(s => s.capabilities?.features.piAssistant?.state === 'READY') ? 'pi' : 'java';
  const [models, setModels] = useState<ManagedModel[]>([]); const [modelConnections, setModelConnections] = useState<ModelConnection[]>([]);
  const [model, setModel] = useState(''); const [chatContext, updateChatContext] = useState<ChatContext>({});
  const [userMentions, setUserMentions] = useState<ChatUserMention[]>([]);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [input, setInput] = useState(''); const [messages, setMessages] = useState<Message[]>([]);
  const [running, setRunning] = useState(false); const [status, setStatus] = useState(''); const [error, setError] = useState('');
  const [backendRunning, setBackendRunning] = useState(false);
  const [writes, setWrites] = useState<LocalWriteView[]>([]);
  const [statistics, setStatistics] = useState<{ modelCalls: number; toolCalls: number; inputTokens: number; outputTokens: number; usageComplete: boolean }>();
  useEffect(() => { setUserMentions(previous => mentionsInText(input, previous)); }, [input]);
  const writeRequest = useRef(0);
  const session = useRef<number | null>(null); const abort = useRef<AbortController>(); const generation = useRef(0); const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    const management = createModelManagementService();
    void Promise.all([management.models(), management.bindings(), management.connections()]).then(([items, bindings, dbs]) => {
      if (!mounted.current) return;
      const available = items.filter(m => m.available && m.chatVisible && !m.taskPreset);
      setModels(available); setModelConnections(dbs);
      setModel(bindings.find(b => b.purpose === 'MAIN_CHAT')?.modelConfigId ?? available[0]?.id ?? '');
    }).catch(() => { if (mounted.current) setError('MODEL_MANAGEMENT_UNAVAILABLE'); });
    return () => {
      mounted.current = false; generation.current++; abort.current?.abort();
      if (session.current !== null) void api.close(session.current).catch(() => {});
      session.current = null;
    };
  }, []);
  const refreshWrites = async () => {
    const id = session.current; const version = generation.current; const sequence = ++writeRequest.current;
    if (id === null) return;
    const views = await api.proposals(id);
    if (mounted.current && version === generation.current && id === session.current && sequence === writeRequest.current) setWrites(views);
    return views;
  };
  const unresolved = writes.some(w => w.status === 'PENDING' || w.status === 'EXECUTING');
  const executing = writes.some(w => w.status === 'EXECUTING');
  useEffect(() => {
    if (!unresolved) return;
    const timer = window.setInterval(() => { void refreshWrites().catch(() => {}); }, 2000);
    return () => window.clearInterval(timer);
  }, [unresolved]);
  const decide = async (view: LocalWriteView, decision: 'APPROVE' | 'REJECT') => {
    if (session.current === null || running || executing) return;
    const version = generation.current; const id = session.current;
    const current = () => mounted.current && version === generation.current;
    writeRequest.current++;
    setRunning(true); setBackendRunning(false); setError(''); setStatus(decision === 'APPROVE' ? 'EXECUTING' : 'REJECTED');
    try {
      const result = await api.decide(id, view.proposal.proposalId, decision);
      if (current()) { writeRequest.current++; setWrites(previous => previous.map(w => w.proposal.proposalId === result.proposal.proposalId ? result : w)); setStatus(result.status); }
    } catch {
      if (current()) { setStatus('UNKNOWN'); setError('LOCAL_WRITE_RESPONSE_UNCONFIRMED'); }
    } finally {
      if (current()) { setRunning(false); await refreshWrites().catch(() => {}); }
    }
  };
  const reset = async (target: ChatContext = chatContext) => {
    updateChatContext({ ...target });
    generation.current++; writeRequest.current++; abort.current?.abort();
    const previous = session.current; session.current = null;
    setMessages([]); setInput(''); setUserMentions([]); setWrites([]); setStatistics(undefined); setError(''); setStatus(''); setRunning(false); setBackendRunning(false);
    if (previous !== null) await api.close(previous).catch(() => {});
  };
  const submit = async (override?: string) => { if (running || unresolved || !model || !(override ?? input).trim()) return;
    const text = (override ?? input).trim();
    const snapshot = snapshotChatRequest({ userMentions: mentionsInText(text,userMentions) }, chatContext, text);
    setUserMentions([]); setInput(''); setRunning(true); setBackendRunning(false); setError(''); setStatus('RUNNING');
    const version = generation.current;
    const current = () => mounted.current && version === generation.current;
    try {
      if (session.current === null) {
        const created = await api.create(snapshot.defaultTarget ?? {});
        if (!current()) { await api.close(created.conversationId); return; }
        session.current = created.conversationId;
      }
      setMessages(previous => [...previous, { role: 'user', text, userMentions: snapshot.userMentions }, { role: 'assistant', text: '', blocks: [] }]);
      const controller = new AbortController(); abort.current = controller;
      for await (const block of api.chat(session.current, text, model, AbortSignal.any([controller.signal, AbortSignal.timeout(175000)]), snapshot, engine)) {
        if (!current()) break;
        if (block.done) {
          const metadata = JSON.parse(block.data ?? '{}') as { status?: string; errorCode?: string; statistics?: typeof statistics };
          setStatus(metadata.status ?? 'FAILED'); setStatistics(metadata.statistics); if (metadata.errorCode && metadata.status !== 'CANCELLED') setError(metadata.errorCode);
        } else if (block.type === 'STATUS' && block.data === 'RUNNING') {
          setBackendRunning(true);
        } else if (block.type === 'TEXT' || block.type === 'TOOL_CALL' || block.type === 'TOOL_RESULT') {
          setMessages(previous => previous.map((item, index) => index !== previous.length - 1 ? item : {
            ...item, text: block.type === 'TEXT' ? item.text + (block.data ?? '') : item.text,
            blocks: [...(item.blocks ?? []), block],
          }));
        }
      }
    } catch (e) {
      if (current()) { setError(e instanceof Error && /^[A-Z_]+$/.test(e.message) ? e.message : 'LOCAL_STREAM_FAILED'); setStatus('FAILED'); }
    } finally { if (current()) { setRunning(false); setBackendRunning(false); abort.current = undefined; await refreshWrites().catch(() => setError('LOCAL_WRITE_RESPONSE_UNCONFIRMED')); } }
  };
  const compact = async () => {
    if (session.current === null || running || unresolved) return;
    const version = generation.current; const current = () => mounted.current && version === generation.current;
    setRunning(true); setError(''); setStatus('COMPACTING');
    try { const result = await api.compact(session.current); if (current()) setStatus(result.compacted ? 'COMPACTED' : 'NO_COMPACTION'); }
    catch { if (current()) { setError('LOCAL_COMPACTION_FAILED'); setStatus('FAILED'); } }
    finally { if (current()) setRunning(false); }
  };
  const changeTarget = async (target: ChatContext) => {
    const id=session.current, version=generation.current;
    if(running && id===null) throw new Error('LOCAL_SESSION_BUSY');
    const saved=id===null?{...target}:await api.updateDefaultTarget(id,{...target});
    if(mounted.current && version===generation.current && session.current===id) updateChatContext(saved);
  };
  const setChatContext: Dispatch<SetStateAction<ChatContext>> = value => {
    const target=typeof value==='function'?value(chatContext):value;
    void changeTarget(target).catch(() => setError('LOCAL_TARGET_UPDATE_FAILED'));
  };
  const stop = () => {
    if (session.current === null || (!backendRunning && !unresolved)) return;
    const version = generation.current; setStatus('CANCEL_REQUESTED');
    void api.cancel(session.current).then(() => refreshWrites()).then(views => {
      const latest = views?.[views.length - 1];
      if (mounted.current && version === generation.current && latest && latest.status !== 'EXECUTING' && latest.status !== 'PENDING') setStatus(latest.status);
    }).catch(() => { if (mounted.current && version === generation.current) setError('LOCAL_STREAM_FAILED'); });
  };
  const modelOptions = useMemo(() => models.map(m => ({ modelName: m.id, modelConfigId: m.id, modelId: m.modelId, name: m.name,
    provider: modelConnections.find(c => c.id === m.connectionId)?.provider, supportThinking: false,
    memoryThreshold: m.memoryThreshold, maxContextTokens: m.maxContextTokens })), [models, modelConnections]);
  const displayMessages = useMemo(() => messages.map((m, i) => ({ id: String(i), role: m.role === 'user' ? MessageRole.USER : MessageRole.ASSISTANT,
    content: m.text, blocks: m.blocks, userMentions: m.userMentions, timestamp: new Date(0) })), [messages]);
  const context: AIAssistantContextValue = {
    input, setInput, onSend: () => void submit(), onStop: stop,
    submitMessage: async text => { await submit(text); }, isLoading: running || unresolved,
    conversationId: session.current, conversationTokenCount: null,
    modelState: { model, setModel: value => { if (!running) setModel(value); }, modelOptions, setThinkingMode: () => {} },
    agentState: { agent: 'Agent', setAgent: () => {}, availableAgents: ['Agent'] },
    chatContextState: { chatContext, setChatContext }, mentionState: { userMentions, setUserMentions },
    onCommand: command => { if (command === 'new') void reset(); else if (command === 'compact') void compact(); },
    supportedCommands: ['new', 'compact'], canSend: !running && !unresolved && !!model && !!input.trim(),
  };
  return <AIAssistantProvider value={context}>
    <div className="flex h-full flex-col overflow-hidden bg-transparent" data-local-assistant title={tr('ephemeral')}>
      <AIAssistantHeader currentConversationId={session.current}
        conversationTabs={[{ id: session.current, titleOverride: null, titleCandidate: messages.find(m => m.role === 'user')?.text ?? null, messageCount: messages.length, createdAt: 0, lastTouchedAt: 0 }]}
        onSelectTab={() => {}} onCloseTab={() => void reset()} onNewChat={() => void reset()} onClosePanel={onClose} />
      <AIAssistantContent messages={displayMessages} messagesEndRef={messagesEndRef} isLoading={running} isWaiting={false}
        waitingPromptMode="default" queue={[]} onRemoveFromQueue={() => {}} trailingContent={<>
          {writes.map(view => <article key={view.proposal.proposalId} data-local-write={view.status} className="mb-2 flex flex-col gap-3 rounded-lg border theme-border theme-bg-main p-4 shadow-sm text-xs">
            <strong>{tr('write_title')}</strong>
            <p className="break-all theme-text-secondary">{view.proposal.connectionName} · {view.proposal.dbType} · {view.proposal.host}:{view.proposal.port} · {view.proposal.catalogName ?? '—'} / {view.proposal.schemaName ?? '—'}</p>
            <pre className="whitespace-pre-wrap break-all select-text max-h-64 overflow-y-auto rounded border theme-border p-2">{view.proposal.sqlPreview}</pre>
            <p className="theme-text-secondary">{tr('expires')}: {new Date(view.proposal.expiresAt).toLocaleTimeString()}</p>
            <p>{tr(`status.${view.status}`)}{view.cancelRequested ? ` · ${tr('status.CANCEL_REQUESTED')}` : ''}</p>
            {view.result && <p>{tr('transaction')}: {view.result.transactionOutcome}{view.result.affectedRows != null ? ` · ${tr('affected')}: ${view.result.affectedRows}` : ''}</p>}
            {view.status === 'UNKNOWN' && <p role="alert">{tr('write_unknown')}</p>}
            <p className="text-[11px] leading-5 theme-text-secondary">{tr('write_warning')}</p>
            {view.status === 'PENDING' && <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={running || executing} onClick={() => void decide(view, 'REJECT')}>{tr('reject')}</Button>
              <Button size="sm" disabled={running || executing || Date.now() >= Date.parse(view.proposal.expiresAt)} onClick={() => void decide(view, 'APPROVE')}>{tr('approve')}</Button>
            </div>}
            <button className="text-left text-[11px] theme-text-secondary hover:underline" onClick={() => void refreshWrites().catch(() => setError('LOCAL_WRITE_RESPONSE_UNCONFIRMED'))}>{tr('refresh_writes')}</button>
          </article>)}
        </>} />
      {error && <p role="alert" className="px-3 py-2 text-xs theme-text-error">{tr(`errors.${error}`) === `local_assistant.errors.${error}` ? tr('failed') : tr(`errors.${error}`)}</p>}
      {status && <p role="status" data-engine={engine} className="px-3 pt-1 text-[10px] theme-text-secondary" title={statistics ? `${tr('calls')}: ${statistics.modelCalls} · ${tr('tools')}: ${statistics.toolCalls} · ${statistics.usageComplete ? statistics.inputTokens + statistics.outputTokens : tr('usage_partial')}` : undefined}>{tr(`status.${status}`)}{engine === 'pi' && ` · ${tr('engine_pi')}`}</p>}
      <DefaultTargetControl target={chatContext} ready loadError={false} onChange={changeTarget} onNew={target => void reset(target)} />
      <ChatInput />
    </div>
  </AIAssistantProvider>;
}

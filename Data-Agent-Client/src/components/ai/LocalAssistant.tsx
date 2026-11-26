import { useEffect, useRef, useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { AIAssistantHeader } from './AIAssistantHeader';
import { AIAssistantContent } from './AIAssistantContent';
import { AIAssistantProvider, type AIAssistantContextValue } from './AIAssistantContext';
import { ChatInput } from './ChatInput';
import { mentionsInText, snapshotChatRequest, targetLabel } from '../../lib/chatTarget';
import { MessageRole, type ChatContext, type ChatUserMention, type ChatResponseBlock } from '../../types/chat';
import { localAssistantService as api, type LocalWriteView } from '../../services/localAssistant.service';
import { useRuntimeStore } from '../../store/runtimeStore';
import { useTabStore } from '../../store/tabStore';
import { useWorkspaceStore } from '../../store/workspaceStore';
import type { TableTabMetadata } from '../../types/tab';
import { createModelManagementService } from '../../services/modelManagement.service';
import type { ModelOption } from '../../types/ai';

interface Message { role: 'user' | 'assistant'; text: string; blocks?: ChatResponseBlock[]; userMentions?: ChatUserMention[] }

// Keep aligned with LocalPiChatService.SUPPORTED and Data-Agent-Pi-Worker/src/model.js.
const PI_API_STYLES = new Set<string>(['OPENAI_CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES']);

/** Mounted only in local mode; no history loader, preferences persistence, account hook or legacy permission grants. */
export function LocalAssistant({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation(); const tr = (key: string) => t(`local_assistant.${key}`);
  // The server advertises the experimental Pi engine only when it is enabled and ready; Java remains the default.
  const piReady = useRuntimeStore(s => s.capabilities?.features.piAssistant?.state === 'READY');
  const assistantEngine = useWorkspaceStore(s => s.assistantEngine);
  const preferredEngine = assistantEngine === 'java' || (assistantEngine === 'auto' && !piReady) ? 'java'
    : piReady ? 'pi' : 'java';
  const [sessionEngine, setSessionEngine] = useState<'java' | 'pi' | null>(null);
  const engine = sessionEngine ?? preferredEngine;
  const [models, setModels] = useState<ModelOption[]>([]);
  const [model, setModel] = useState('');
  const activeTab = useTabStore(s => s.tabs.find(tab => tab.id === s.activeTabId));
  const dbTypes = useWorkspaceStore(s => s.supportedDbTypes);
  const metadata = activeTab?.type === 'file' || activeTab?.type === 'table' ? activeTab.metadata as TableTabMetadata | undefined : undefined;
  const dbType = dbTypes.find(type => type.code.toLowerCase() === metadata?.dbType?.toLowerCase());
  // Table tabs also keep a display databaseName that can actually be a schema (e.g. DM).
  const catalog = activeTab?.type === 'table' ? metadata?.catalog : metadata?.databaseName ?? metadata?.catalog;
  const schema = activeTab?.type === 'table' ? metadata?.schema ?? metadata?.schemaName : metadata?.schemaName;
  const activeTarget: ChatContext = metadata?.connectionId ? {
    connectionId: metadata.connectionId,
    catalogName: dbType?.supportDatabase === false ? undefined : catalog || undefined,
    schemaName: dbType?.supportSchema === false ? undefined : schema || undefined,
  } : {};
  const [sessionTarget, setSessionTarget] = useState<{ target: ChatContext; connectionName?: string } | null>(null);
  const chatContext = sessionTarget?.target ?? activeTarget;
  const connectionName = sessionTarget ? sessionTarget.connectionName : metadata?.connectionName;
  const scopeLabel = targetLabel(chatContext, connectionName);
  const [userMentions, setUserMentions] = useState<ChatUserMention[]>([]);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [input, setInput] = useState(''); const [messages, setMessages] = useState<Message[]>([]);
  const [running, setRunning] = useState(false); const [status, setStatus] = useState(''); const [error, setError] = useState('');
  const [backendRunning, setBackendRunning] = useState(false);
  const [writes, setWrites] = useState<LocalWriteView[]>([]);
  const [statistics, setStatistics] = useState<{ modelCalls: number; toolCalls: number; inputTokens: number; outputTokens: number; usageComplete: boolean }>();
  const selectedModel = models.find(item => item.modelName === model);
  const modelError = engine !== 'pi' || !model ? '' : !selectedModel?.apiStyle ? 'MODEL_CONFIG_UNAVAILABLE'
    : PI_API_STYLES.has(selectedModel.apiStyle) ? '' : 'PI_UNSUPPORTED_API_STYLE';
  const visibleError = modelError || error;
  useEffect(() => { setUserMentions(previous => mentionsInText(input, previous)); }, [input]);
  const writeRequest = useRef(0);
  const session = useRef<number | null>(null); const abort = useRef<AbortController>(); const generation = useRef(0); const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    const management = createModelManagementService();
    // The chat availability API includes authorized Qwen variants; saved configurations alone do not.
    void management.chatModels().then(items => {
      if (!mounted.current) return;
      setModels(items);
      setModel(items.find(item => item.defaultModel)?.modelName ?? items[0]?.modelName ?? '');
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
  const reset = async () => {
    setSessionTarget(null);
    generation.current++; writeRequest.current++; abort.current?.abort();
    const previous = session.current; session.current = null;
    setSessionEngine(null);
    setMessages([]); setInput(''); setUserMentions([]); setWrites([]); setStatistics(undefined); setError(''); setStatus(''); setRunning(false); setBackendRunning(false);
    if (previous !== null) await api.close(previous).catch(() => {});
  };
  const submit = async (override?: string) => { if (running || unresolved || !model || !(override ?? input).trim()) return;
    const text = (override ?? input).trim();
    if (engine === 'pi' && text === '/compact') { setError('PI_COMPACTION_NOT_SUPPORTED'); return; }
    if (modelError) return;
    const snapshot = snapshotChatRequest({ userMentions: mentionsInText(text,userMentions) }, chatContext, text);
    setUserMentions([]); setInput(''); setRunning(true); setBackendRunning(false); setError(''); setStatus('RUNNING');
    const version = generation.current;
    const current = () => mounted.current && version === generation.current;
    try {
      if (session.current === null) {
        // Freeze at submission, including while creation is pending; tab navigation cannot redirect this conversation.
        setSessionTarget({ target: { ...snapshot.defaultTarget }, connectionName });
        const created = await api.create(snapshot.defaultTarget ?? {});
        if (!current()) { await api.close(created.conversationId); return; }
        session.current = created.conversationId;
        setSessionEngine(engine);
      }
      setMessages(previous => [...previous, { role: 'user', text, userMentions: snapshot.userMentions }, { role: 'assistant', text: '', blocks: [] }]);
      const controller = new AbortController(); abort.current = controller;
      for await (const block of api.chat(session.current, text, model, controller.signal, snapshot, engine)) {
        if (!current()) break;
        if (block.done) {
          const metadata = JSON.parse(block.data ?? '{}') as { status?: string; errorCode?: string; statistics?: typeof statistics };
          setStatus(metadata.status ?? 'FAILED'); setStatistics(metadata.statistics); if (metadata.errorCode && metadata.status !== 'CANCELLED') setError(metadata.errorCode);
        } else if (block.type === 'STATUS' && block.data === 'RUNNING') {
          setBackendRunning(true);
        } else if (block.type === 'STATUS' && block.data === 'TEXT_RESET') {
          setMessages(previous => previous.map((item, index) => index !== previous.length - 1 ? item : {
            ...item, text: '', blocks: item.blocks?.filter(entry => entry.type !== 'TEXT'),
          }));
        } else if (block.type === 'TEXT' || block.type === 'TOOL_CALL' || block.type === 'TOOL_RESULT' || block.type?.startsWith('SUB_AGENT_')) {
          setMessages(previous => previous.map((item, index) => index !== previous.length - 1 ? item : {
            ...item, text: block.type === 'TEXT' ? item.text + (block.data ?? '') : item.text,
            blocks: [...(item.blocks ?? []), block],
          }));
        }
      }
    } catch (e) {
      if (current()) {
        if (session.current === null) setSessionTarget(null);
        setError(e instanceof Error && /^[A-Z_]+$/.test(e.message) ? e.message : 'LOCAL_STREAM_FAILED'); setStatus('FAILED');
      }
    } finally { if (current()) { setRunning(false); setBackendRunning(false); abort.current = undefined; await refreshWrites().catch(() => setError('LOCAL_WRITE_RESPONSE_UNCONFIRMED')); } }
  };
  const compact = async () => {
    if (engine === 'pi') { setError('PI_COMPACTION_NOT_SUPPORTED'); return; }
    if (session.current === null || running || unresolved) return;
    const version = generation.current; const current = () => mounted.current && version === generation.current;
    setRunning(true); setError(''); setStatus('COMPACTING');
    try { const result = await api.compact(session.current); if (current()) setStatus(result.compacted ? 'COMPACTED' : 'NO_COMPACTION'); }
    catch { if (current()) { setError('LOCAL_COMPACTION_FAILED'); setStatus('FAILED'); } }
    finally { if (current()) setRunning(false); }
  };
  const stop = () => {
    if (session.current === null || (!backendRunning && !unresolved)) return;
    const version = generation.current; setStatus('CANCEL_REQUESTED');
    void api.cancel(session.current).then(() => refreshWrites()).then(views => {
      const latest = views?.[views.length - 1];
      if (mounted.current && version === generation.current && latest && latest.status !== 'EXECUTING' && latest.status !== 'PENDING') setStatus(latest.status);
    }).catch(() => { if (mounted.current && version === generation.current) setError('LOCAL_STREAM_FAILED'); });
  };
  const displayMessages = useMemo(() => messages.map((m, i) => ({ id: String(i), role: m.role === 'user' ? MessageRole.USER : MessageRole.ASSISTANT,
    content: m.text, blocks: m.blocks, userMentions: m.userMentions, timestamp: new Date(0) })), [messages]);
  const context: AIAssistantContextValue = {
    input, setInput, onSend: () => void submit(), onStop: stop,
    submitMessage: async text => { await submit(text); }, isLoading: running || unresolved,
    conversationId: session.current, conversationTokenCount: null,
    modelState: { model, setModel: value => { if (!running) setModel(value); }, modelOptions: models },
    agentState: { agent: 'Agent', setAgent: () => {}, availableAgents: ['Agent'] },
    chatContextState: { chatContext }, mentionState: { userMentions, setUserMentions },
    onCommand: command => { if (command === 'new') void reset(); else if (command === 'compact') void compact(); },
    supportedCommands: engine === 'pi' ? ['new'] : ['new', 'compact'], canSend: !running && !unresolved && !!model && !modelError && !!input.trim(),
  };
  return <AIAssistantProvider value={context}>
    <div className="flex h-full flex-col overflow-hidden bg-transparent" data-local-assistant title={tr(engine === 'pi' ? 'ephemeral_pi' : 'ephemeral')}>
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
      {visibleError && <p role="alert" className="px-3 py-2 text-xs theme-text-error">{tr(`errors.${visibleError}`) === `local_assistant.errors.${visibleError}` ? tr('failed') : tr(`errors.${visibleError}`)}</p>}
      {status && status !== 'SUCCEEDED' && <p role="status" data-engine={engine} className="px-3 pt-1 text-[10px] theme-text-secondary" title={statistics ? `${tr('calls')}: ${statistics.modelCalls} · ${tr('tools')}: ${statistics.toolCalls} · ${statistics.usageComplete ? statistics.inputTokens + statistics.outputTokens : tr('usage_partial')}` : undefined}>{tr(`status.${status}`)}</p>}
      {scopeLabel && <div className="shrink-0 px-3 pt-1" data-chat-scope>
        <span className="inline-block max-w-full truncate rounded theme-bg-panel px-2 py-0.5 text-[10px] theme-text-secondary" title={scopeLabel} aria-label={tr('connection')}>{scopeLabel}</span>
      </div>}
      <ChatInput />
    </div>
  </AIAssistantProvider>;
}

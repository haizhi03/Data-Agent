import type { ChatContext, ChatRequest, ChatUserMention, ChatWorkbenchContext } from '../types/chat';
import type { Tab } from '../store/tabStore';
import { findMentionTokens } from '../components/ai/mentionTypes';

/** Copy at submission, including nested identities, before placing anything in a queue. */
export function snapshotChatRequest(body: Partial<ChatRequest>, target: ChatContext, text?: string): Partial<ChatRequest> {
  const defaultTarget = { ...(body.defaultTarget ?? target) };
  return { ...body, defaultTarget, connectionId: defaultTarget.connectionId,
    ...(body.workbenchContext ? { workbenchContext: { ...body.workbenchContext, target: { ...body.workbenchContext.target } } } : {}),
    ...(body.executionReference ? { executionReference: {
      ...body.executionReference, target: { ...body.executionReference.target },
      result: { ...body.executionReference.result, headers: [...body.executionReference.result.headers],
        rows: body.executionReference.result.rows.map(row => [...row]) },
    } } : {}),
    catalogName: defaultTarget.catalogName, schemaName: defaultTarget.schemaName,
    userMentions: (text == null ? body.userMentions ?? [] : mentionsInText(text, body.userMentions ?? [])).map(mention => ({ ...mention })) };
}

export function snapshotWorkbenchContext(tab: Tab | undefined, target: ChatContext): ChatWorkbenchContext | undefined {
  if (tab?.type !== 'file') return undefined;
  const content = tab.content ?? '';
  const selection = tab.selection;
  const selected = !!selection && selection.start >= 0 && selection.end > selection.start && selection.end <= content.length;
  const sql = selected ? content.slice(selection!.start, selection!.end) : content;
  if (!sql.trim()) return undefined;
  return { documentId: tab.id, documentName: (tab.name.trim() || 'SQL').slice(0, 256), documentVersion: tab.contentVersion ?? 0,
    sql: sql.slice(0, 16000), selected, truncated: sql.length > 16000, target: { ...target } };
}

export function mentionsInText(text: string, mentions: ChatUserMention[]): ChatUserMention[] {
  const tokens = new Set(findMentionTokens(text, mentions.map(mention => mention.token)));
  return mentions.filter(mention => tokens.has(mention.token));
}

export function targetLabel(target: ChatContext, connectionName?: string): string {
  if (target.connectionId == null) return '';
  return [connectionName || `#${target.connectionId}`, target.catalogName, target.schemaName].filter(Boolean).join(' / ');
}

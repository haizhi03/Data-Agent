import type { ChatContext, ChatRequest, ChatUserMention } from '../types/chat';
import { findMentionTokens } from '../components/ai/mentionTypes';

/** Copy at submission, including nested identities, before placing anything in a queue. */
export function snapshotChatRequest(body: Partial<ChatRequest>, target: ChatContext, text?: string): Partial<ChatRequest> {
  const defaultTarget = { ...(body.defaultTarget ?? target) };
  return { ...body, defaultTarget, connectionId: defaultTarget.connectionId,
    catalogName: defaultTarget.catalogName, schemaName: defaultTarget.schemaName,
    userMentions: (text == null ? body.userMentions ?? [] : mentionsInText(text, body.userMentions ?? [])).map(mention => ({ ...mention })) };
}

export function mentionsInText(text: string, mentions: ChatUserMention[]): ChatUserMention[] {
  const tokens = new Set(findMentionTokens(text, mentions.map(mention => mention.token)));
  return mentions.filter(mention => tokens.has(mention.token));
}

export function targetLabel(target: ChatContext, connectionName?: string): string {
  if (target.connectionId == null) return '';
  return [connectionName || `#${target.connectionId}`, target.catalogName, target.schemaName].filter(Boolean).join(' / ');
}

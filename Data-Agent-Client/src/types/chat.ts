import type { ThinkingMode } from './modelManagement';
import type { SqlStatementState, SqlTransactionOutcome, SqlBatchState } from './sql';

export interface ChatContext {
  connectionId?: number;
  catalogName?: string;
  schemaName?: string;
}

export interface ChatUserMention {
  token: string;
  objectType: string;
  connectionId?: number;
  connectionName: string;
  catalogName?: string;
  schemaName?: string;
  objectName?: string;
}

export interface ChatWorkbenchContext {
  documentId: string;
  documentName: string;
  documentVersion: number;
  sql: string;
  selected: boolean;
  truncated: boolean;
  target: ChatContext;
}

export interface ChatExecutionReference {
  executionId: string;
  statementIndex: number;
  sql: string;
  target: ChatContext;
  documentId?: string;
  documentName?: string;
  documentVersion?: number;
  truncated: boolean;
  result: {
    success: boolean;
    statementState: SqlStatementState;
    transactionOutcome: SqlTransactionOutcome;
    batchState?: SqlBatchState;
    errorMessage?: string;
    sqlState?: string;
    headers: string[];
    rows: (string | null)[][];
    returnedRows: number;
    affectedRows: number | null;
  };
}

export interface ChatRequest {
  message: string;
  model?: string;
  modelConfigId?: string;
  thinkingMode?: ThinkingMode;
  language?: string;
  agentType?: string;
  conversationId?: number;
  connectionId?: number;
  catalogName?: string;
  schemaName?: string;
  userMentions?: ChatUserMention[];
  defaultTarget?: ChatContext;
  workbenchContext?: ChatWorkbenchContext;
  executionReference?: ChatExecutionReference;
}

export interface SubmitMessageOptions {
  hideUserMessage?: boolean;
  waitingPromptMode?: WaitingPromptMode;
}

export type WaitingPromptMode = 'default' | 'answer';

export const MessageBlockType = {
  TEXT: 'TEXT',
  THOUGHT: 'THOUGHT',
  TOOL_CALL: 'TOOL_CALL',
  TOOL_RESULT: 'TOOL_RESULT',
  SUB_AGENT_START: 'SUB_AGENT_START',
  SUB_AGENT_PROGRESS: 'SUB_AGENT_PROGRESS',
  SUB_AGENT_COMPLETE: 'SUB_AGENT_COMPLETE',
  SUB_AGENT_ERROR: 'SUB_AGENT_ERROR',
  STATUS: 'STATUS',
} as const;

export type MessageBlockType = (typeof MessageBlockType)[keyof typeof MessageBlockType];

export function isContentBlockType(type: MessageBlockType | undefined): boolean {
  return type === MessageBlockType.TEXT || type === MessageBlockType.THOUGHT;
}

export interface ToolCallData {
  id?: string;
  toolName: string;
  arguments: string;
  description?: string;
  startedAt?: number;
  streaming?: boolean;
}

export interface ToolResultData {
  id?: string;
  toolName: string;
  result: string;
  description?: string;
  startedAt?: number;
  finishedAt?: number;
  error?: boolean;
}

export interface ChatResponseBlock {
  type?: MessageBlockType;
  data?: string;
  conversationId?: number;
  parentToolCallId?: string;
  subAgentTaskId?: string;
  done: boolean;
}

export enum MessageRole {
  USER = 'user',
  ASSISTANT = 'assistant',
}

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  userMentions?: ChatUserMention[];
  defaultTarget?: ChatContext;
  blocks?: ChatResponseBlock[];
  doneMetadata?: DoneMetadata;
  messageStatus?: 'NORMAL' | 'DELETED' | 'COMPRESSED' | 'COMPRESSION_SUMMARY';
  localKind?: 'compact-command' | 'compact-status' | 'compact-result' | 'hidden-user-boundary';
  createdAt?: Date;
}

export interface DoneMetadata {
  runId?: string;
  status?: 'SUCCEEDED' | 'FAILED' | 'INCOMPLETE' | 'CANCELLED';
  errorCode?: string;
  toolCount?: number;
  toolCounts?: Record<string, number>;
  totalTokens?: number;
  outputTokens?: number;
  memoryCompressed?: boolean;
  tokenCountBefore?: number;
  tokenCountAfter?: number;
  compressedMessageCount?: number;
  keptRecentCount?: number;
  compressionOutputTokens?: number;
  compressionTotalTokens?: number;
}

export interface SubAgentEventData {
  agentType?: string;
  message?: string;
  toolCount?: number;
  toolCounts?: Record<string, number>;
  taskId?: string;
  connectionId?: number;
  timeoutSeconds?: number;
  summaryText?: string;
  resultJson?: string;
}

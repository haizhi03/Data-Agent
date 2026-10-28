export enum ExecuteNonSelectToolStatus {
  EXECUTED = 'EXECUTED',
}

export interface ExecuteNonSelectToolResultPayload {
  status: ExecuteNonSelectToolStatus;
  ruleMatched: boolean;
  requiresConfirmation: boolean;
  execution?: unknown;
  message?: string;
}

export function parseExecuteNonSelectToolResult(
  responseData: string | null | undefined
): ExecuteNonSelectToolResultPayload | null {
  if (!responseData?.trim()) return null;
  try {
    const parsed = JSON.parse(responseData) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const obj = parsed as Record<string, unknown>;
    if (obj.status !== ExecuteNonSelectToolStatus.EXECUTED) return null;
    return {
      status: obj.status,
      ruleMatched: obj.ruleMatched === true,
      requiresConfirmation: obj.requiresConfirmation === true,
      execution: obj.execution,
      message: typeof obj.message === 'string' ? obj.message : undefined,
    };
  } catch {
    return null;
  }
}

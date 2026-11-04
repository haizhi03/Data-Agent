import { STATUS_LABEL_KEYS } from '../constants/chat';

/** Read only recognized model codes; never display arbitrary upstream response bodies. */
export async function readModelRequestError(response: Response): Promise<string | null> {
  try {
    const body: unknown = await response.json();
    if (!body || typeof body !== 'object' || !('message' in body) || typeof body.message !== 'string') return null;
    const code = body.message;
    return /^(MODEL_|AGENT_|TOOL_)/.test(code) && Object.prototype.hasOwnProperty.call(STATUS_LABEL_KEYS, code) ? code : null;
  } catch {
    return null;
  }
}

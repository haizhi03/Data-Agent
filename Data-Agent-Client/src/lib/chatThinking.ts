import type { ModelOption } from '../types/ai';
import type { ThinkingMode } from '../types/modelManagement';

/** Only the server's supported modes can expose a callable control. */
export function chatThinkingState(model: ModelOption | undefined, preferred?: ThinkingMode) {
  const modes = model?.thinkingModes ?? [];
  const enabledMode = modes.find((mode) => mode !== 'DISABLED');
  const mode = preferred && modes.includes(preferred) ? preferred
    : model?.thinkingMode && modes.includes(model.thinkingMode) ? model.thinkingMode
    : modes.includes('DISABLED') ? 'DISABLED' : modes[0];
  return { visible: !!enabledMode, mode, enabled: !!mode && mode !== 'DISABLED',
    canToggle: !!enabledMode && modes.includes('DISABLED'), enabledMode };
}

export function chatThinkingRequest(model: ModelOption | undefined, preferred?: ThinkingMode): { thinkingMode?: ThinkingMode } {
  const state = chatThinkingState(model, preferred);
  return state.visible && state.mode ? { thinkingMode: state.mode } : {};
}

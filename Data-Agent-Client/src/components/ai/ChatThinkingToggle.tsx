import { Brain } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { chatThinkingState } from '../../lib/chatThinking';
import type { ModelOption } from '../../types/ai';
import type { ThinkingMode } from '../../types/modelManagement';

export function ChatThinkingToggle({ model, value, onChange }: {
  model?: ModelOption; value?: ThinkingMode; onChange: (mode: ThinkingMode) => void;
}) {
  const { t } = useTranslation();
  const state = chatThinkingState(model, value);
  if (!state.visible) return null;
  return <button type="button" aria-pressed={state.enabled} aria-label={t('chat_thinking.label')}
    title={t(!state.canToggle ? 'chat_thinking.required' : state.enabled ? 'chat_thinking.on' : 'chat_thinking.off')}
    disabled={!state.canToggle} onClick={() => {
      if (state.canToggle && state.enabledMode) onChange(state.enabled ? 'DISABLED' : state.enabledMode);
    }}
    className={`h-7 px-2 shrink-0 rounded-lg border text-xs transition-colors flex items-center gap-1.5 ${state.enabled
      ? 'border-primary bg-primary/10 text-primary' : 'border-[color:var(--border-color)] bg-[color:var(--bg-panel)] theme-text-secondary hover:theme-text-primary'} disabled:cursor-default`}>
    <Brain className="w-3.5 h-3.5" aria-hidden /><span>{t('chat_thinking.label')}</span>
  </button>;
}

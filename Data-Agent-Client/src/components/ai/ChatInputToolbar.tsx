import { Send, Square, ChevronDown } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { ROUTES } from '../../constants/routes';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';
import { AGENT_COLORS, type AgentType } from './agentTypes';
import type { ModelOption } from '../../types/ai';
import { useTranslation } from 'react-i18next';
import { ChatModelSelector } from './ChatModelSelector';
import { ChatThinkingToggle } from './ChatThinkingToggle';
import type { ThinkingMode } from '../../types/modelManagement';

interface AgentOption {
  type: AgentType;
  icon: React.ElementType;
  label: string;
}

interface ChatInputToolbarProps {
  agent: AgentType;
  setAgent: (agent: AgentType) => void;
  model: string;
  setModel: (model: string) => void;
  /** Available models authorized by the current workspace API. */
  modelOptions: ModelOption[];
  thinkingMode?: ThinkingMode;
  setThinkingMode?: (mode: ThinkingMode) => void;
  onSend: () => void;
  /** Abort current stream when user clicks stop. */
  onStop?: () => void;
  /** When true, show stop button instead of send. */
  isLoading?: boolean;
  agents: AgentOption[];
  CurrentAgentIcon: React.ElementType;
}

export function ChatInputToolbar({
  agent,
  setAgent,
  model,
  setModel,
  modelOptions,
  thinkingMode,
  setThinkingMode,
  onSend,
  onStop,
  isLoading = false,
  agents,
  CurrentAgentIcon,
}: ChatInputToolbarProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const configure = () => navigate(ROUTES.MODELS);
  const currentOption = modelOptions.find((m) => m.modelName === model);

  return (
    <div className="flex items-end justify-between gap-2">
      <div className="flex flex-wrap items-center gap-2 min-w-0">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className={`h-7 px-2 rounded-lg border text-xs transition-colors flex items-center gap-1.5 ${AGENT_COLORS[agent].bg} ${AGENT_COLORS[agent].border} theme-text-secondary hover:theme-text-primary`}
            >
              <CurrentAgentIcon className={`w-3 h-3 ${AGENT_COLORS[agent].icon}`} />
              <span className="font-medium">{agents.find((a) => a.type === agent)?.label}</span>
              <ChevronDown className="w-2.5 h-2.5 opacity-50" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-32">
            {agents.map((a) => (
              <DropdownMenuItem
                key={a.type}
                onClick={() => setAgent(a.type)}
                className="text-[10px] flex items-center space-x-2"
              >
                <a.icon className={`w-3 h-3 ${AGENT_COLORS[a.type].icon}`} />
                <span>{a.label}</span>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        <ChatModelSelector value={model} options={modelOptions} onSelect={setModel} onConfigure={configure} />
        {setThinkingMode && <ChatThinkingToggle model={currentOption} value={thinkingMode} onChange={setThinkingMode} />}
        {!currentOption && <button type="button" onClick={configure} className="text-xs text-primary hover:underline">{t('model_settings.configure')}</button>}
      </div>

      <div className="flex items-center space-x-2">
        <button
          type="button"
          onClick={isLoading ? (onStop ?? (() => {})) : onSend}
          disabled={!isLoading && !currentOption}
          className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${AGENT_COLORS[agent].sendBtn}`}
          aria-label={isLoading ? 'Stop' : 'Send'}
        >
          {isLoading ? (
            <Square className="w-3.5 h-3.5 fill-current" />
          ) : (
            <Send className="w-3.5 h-3.5" />
          )}
        </button>
      </div>
    </div>
  );
}

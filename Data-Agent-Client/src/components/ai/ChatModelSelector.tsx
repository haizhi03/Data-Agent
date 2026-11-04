import { Check, ChevronDown, Settings2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { chatModelLabel, groupChatModels } from '../../lib/chatModelOptions';
import type { ModelOption } from '../../types/ai';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuPortal,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '../ui/DropdownMenu';

interface ChatModelSelectorProps {
  value: string;
  options: ModelOption[];
  onSelect: (value: string) => void;
  onConfigure: () => void;
}

export function ChatModelSelector({ value, options, onSelect, onConfigure }: ChatModelSelectorProps) {
  const { t } = useTranslation();
  const current = options.find((model) => model.modelName === value);
  const currentLabel = current ? chatModelLabel(current) : t(I18N_KEYS.AI.NO_AVAILABLE_MODELS);

  return <DropdownMenu>
    <DropdownMenuTrigger asChild>
      <button type="button" aria-label={`${t('chat_model_selector.choose')}: ${currentLabel}`} title={currentLabel}
        className="h-7 px-2 rounded-lg border border-[color:var(--border-color)] bg-[color:var(--bg-panel)] text-xs theme-text-secondary hover:theme-text-primary transition-colors flex items-center gap-1.5 min-w-0">
        <span className="max-w-[10rem] truncate">{currentLabel}</span>
        <ChevronDown className="w-2.5 h-2.5 opacity-50 shrink-0" aria-hidden />
      </button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="start" className="w-52 max-w-[calc(100vw-1rem)] max-h-[var(--radix-dropdown-menu-content-available-height)] overflow-y-auto">
      <DropdownMenuLabel className="text-xs theme-text-secondary font-normal">{t('chat_model_selector.title')}</DropdownMenuLabel>
      {!options.length && <DropdownMenuItem disabled className="text-xs">{t(I18N_KEYS.AI.NO_AVAILABLE_MODELS)}</DropdownMenuItem>}
      {groupChatModels(options).map((group) => <DropdownMenuSub key={group.id}>
        <DropdownMenuSubTrigger className="gap-2 text-xs">
          <span className="min-w-0 flex-1 truncate">{group.labelKey ? t(group.labelKey) : group.label}</span>
          {group.models.some((model) => model.modelName === value)
            && <Check className="w-3.5 h-3.5 shrink-0 text-primary" aria-label={t('chat_model_selector.selected')} />}
          <span className="text-[10px] theme-text-secondary tabular-nums">{group.models.length}</span>
        </DropdownMenuSubTrigger>
        <DropdownMenuPortal>
          <DropdownMenuSubContent sideOffset={4} className="w-72 max-w-[calc(100vw-1rem)] max-h-[min(24rem,var(--radix-dropdown-menu-content-available-height))] overflow-y-auto">
            <DropdownMenuRadioGroup value={value} onValueChange={onSelect}>
              {group.models.map((model) => {
                const label = chatModelLabel(model);
                const alias = group.models.some((other) => other.modelName !== model.modelName && chatModelLabel(other) === label)
                  ? model.name?.trim() : undefined;
                return <DropdownMenuRadioItem key={model.modelName} value={model.modelName} className="gap-2 text-xs py-2">
                  <span className="min-w-0 flex-1">
                    <span className="block break-all font-medium">{label}</span>
                    {alias && alias !== label && <span className="mt-0.5 block break-words theme-text-secondary text-[10px]">{alias}</span>}
                  </span>
                </DropdownMenuRadioItem>;
              })}
            </DropdownMenuRadioGroup>
          </DropdownMenuSubContent>
        </DropdownMenuPortal>
      </DropdownMenuSub>)}
      <DropdownMenuSeparator />
      <DropdownMenuItem onSelect={onConfigure} className="text-xs flex items-center gap-2">
        <Settings2 className="w-3.5 h-3.5" />{t('model_settings.configure')}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>;
}

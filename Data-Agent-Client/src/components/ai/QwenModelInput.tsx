import { useRef, useState, type KeyboardEvent } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Input } from '../ui/Input';
import { filterQwenModels } from '../../lib/qwenModelPresets';
import type { ModelSpecification } from '../../types/modelManagement';

/** Editable combobox: choosing a candidate and entering a custom ID share the same field. */
export function QwenModelInput({ id, value, onChange, specifications = [] }: { id: string; value: string; onChange: (value: string) => void; specifications?: ModelSpecification[] }) {
  const { t } = useTranslation();
  const tr = (key: string, values?: Record<string, string | number>) => t(`model_settings.setup.${key}`, values);
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [query, setQuery] = useState('');
  const models = filterQwenModels(query, specifications.length ? specifications.map((s) => s.modelId) : undefined);
  const listId = `${id}-suggestions`;

  const close = () => { setOpen(false);setActive(-1);setQuery(''); };
  const choose = (model: string) => { onChange(model);close(); };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();setOpen(true);
      if (models.length) setActive((current) => {
        if (!open || current < 0) return event.key === 'ArrowDown' ? 0 : models.length - 1;
        return (current + (event.key === 'ArrowDown' ? 1 : -1) + models.length) % models.length;
      });
    } else if (event.key === 'Enter' && open && active >= 0 && models[active]) {
      event.preventDefault();choose(models[active]);
    } else if (event.key === 'Escape' && open) {
      event.preventDefault();event.stopPropagation();close();
    } else if (event.key === 'Tab') close();
  };

  return <div className="relative" onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) close();
  }}>
    <Input ref={input} id={id} className="h-11 rounded-lg pr-12" required maxLength={256} value={value} autoComplete="off" spellCheck={false}
      placeholder={tr('qwen_model_placeholder')} role="combobox" aria-autocomplete="list" aria-expanded={open} aria-controls={listId}
      aria-activedescendant={open && active >= 0 && models[active] ? `${listId}-${active}` : undefined}
      onFocus={() => { setQuery('');setActive(-1);setOpen(true); }} onClick={() => setOpen(true)} onKeyDown={onKeyDown}
      onChange={(event) => { onChange(event.target.value);setQuery(event.target.value);setOpen(true);setActive(-1); }} />
    <button type="button" tabIndex={-1} aria-label={tr(open ? 'close_models' : 'choose_models')} aria-controls={listId} aria-expanded={open}
      className="absolute top-1 right-1 flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      onMouseDown={(event) => event.preventDefault()} onClick={() => { const next = !open;input.current?.focus();setQuery('');setOpen(next);setActive(-1); }}>
      <ChevronDown size={17} className={open ? 'rotate-180 transition-transform' : 'transition-transform'} />
    </button>
    <div hidden={!open} className="absolute z-30 mt-1 w-full rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg">
      <div role="listbox" id={listId} aria-label={tr('qwen_models')} className="max-h-60 overflow-y-auto">
        {models.map((model, index) => {
          const specification = specifications.find((s) => s.modelId === model);
          return <button key={model} type="button" tabIndex={-1} role="option" id={`${listId}-${index}`}
          aria-selected={active === index} className={`flex min-h-10 w-full items-center justify-between gap-3 rounded-md px-3 py-2 text-left text-sm ${active === index ? 'bg-accent text-accent-foreground' : 'hover:bg-accent hover:text-accent-foreground'}`}
          onMouseDown={(event) => event.preventDefault()} onMouseEnter={() => setActive(index)} onClick={() => choose(model)}>
          <span><span className="block">{model}</span>{specification && <span className="mt-1 block text-xs text-muted-foreground">
            {tr('candidate_limits', { context: specification.contextTokens.toLocaleString(), output: specification.maxOutputTokens.toLocaleString() })}
            {specification.maxOutputTokens !== specification.maxThinkingOutputTokens && <span className="block">{tr('candidate_thinking_limit', { output: specification.maxThinkingOutputTokens.toLocaleString() })}</span>}
          </span>}</span>{value === model && <Check size={15} className="text-primary" />}
        </button>;
        })}
      </div>
      {!models.length && <p className="px-3 py-3 text-sm text-muted-foreground">{tr('custom_model_hint')}</p>}
      <p className="border-t border-border px-3 py-2 text-xs text-muted-foreground">{tr('qwen_presets_hint')}</p>
    </div>
  </div>;
}

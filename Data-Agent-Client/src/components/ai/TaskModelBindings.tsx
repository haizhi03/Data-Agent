import { useTranslation } from 'react-i18next';
import { taskModelOptions, type TaskModelOption } from '../../lib/taskModelOptions';
import type { ManagedModel, ModelConnection, ModelSpecification, PurposeBinding } from '../../types/modelManagement';

interface Props {
  bindings: PurposeBinding[]; connections: ModelConnection[]; models: ManagedModel[]; specifications: ModelSpecification[];
  busy: boolean; onSelect: (binding: PurposeBinding, option: TaskModelOption) => void;
}
export function TaskModelBindings({ bindings, connections, models, specifications, busy, onSelect }: Props) {
  const { t } = useTranslation();const tr = (key: string) => t(`model_settings.${key}`);
  return <details className="rounded-xl border border-border p-5">
    <summary className="cursor-pointer text-sm font-medium">{tr('setup.task_defaults')}</summary>
    <p className="text-sm text-muted-foreground mt-3 mb-4">{tr('setup.defaults_hint')}</p>
    <div className="space-y-3">{bindings.map((binding) => {
      const options = taskModelOptions(connections, models, specifications, binding);
      const selected = binding.modelConfigId ? `model/${binding.modelConfigId}` : '';
      return <div key={binding.purpose} className="grid sm:grid-cols-2 gap-3 items-center">
        <label htmlFor={`purpose-${binding.purpose}`} className="text-sm">{tr(`purposes.${binding.purpose}`)}</label>
        <select id={`purpose-${binding.purpose}`} className="w-full h-10 px-3 rounded-md border border-input bg-background text-sm" disabled={busy} value={selected}
          onChange={(event) => { const option = options.find((item) => item.value === event.target.value);if (option) onSelect(binding, option); }}>
          <option value="" disabled>{tr('choose_model')}</option>
          {selected && !options.some((option) => option.value === selected) && <option value={selected} disabled>{tr('unavailable')}</option>}
          {options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </div>;
    })}</div>
  </details>;
}

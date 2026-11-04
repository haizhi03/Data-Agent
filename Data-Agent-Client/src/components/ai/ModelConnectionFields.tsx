import { useId, useState, type ReactNode } from 'react';
import { ChevronDown, Eye, EyeOff } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Input } from '../ui/Input';
import { ModelSettingRow } from './ModelSettingRow';
import { QwenModelInput } from './QwenModelInput';
import { connectionRequestUrl, providerApis, providerPresets, selectProviderApi, type ProviderPreset } from '../../lib/modelProviderPresets';
import type { ConnectionInput, ModelProfile, ModelSpecification } from '../../types/modelManagement';

interface Props {
  value: ConnectionInput;
  preset: ProviderPreset;
  profiles: ModelProfile[];
  secret: string;
  editing: boolean;
  credentialStorageReady: boolean;
  onChange: (value: ConnectionInput) => void;
  onProviderChange: (provider: ProviderPreset) => void;
  onSecretChange: (secret: string) => void;
  modelId: string;
  onModelIdChange: (modelId: string) => void;
  advanced?: ReactNode;
  limits?: ReactNode;
  specifications?: ModelSpecification[];
}

export function ModelConnectionFields({ value, preset, profiles, secret, editing, credentialStorageReady, onChange, onProviderChange, onSecretChange, modelId, onModelIdChange, advanced, limits, specifications = [] }: Props) {
  const { t } = useTranslation();
  const tr = (key: string) => t(`model_settings.${key}`);
  const id = useId();
  const [keyVisible, setKeyVisible] = useState(false);
  const availableApis = providerApis(preset, profiles);
  const row = (name: string, title: string, children: ReactNode) => <ModelSettingRow title={title} htmlFor={`${id}-${name}`}>{children}</ModelSettingRow>;
  const inputClass = 'h-11 rounded-lg';

  return <div className="space-y-7">
    {row('name', tr('setup.name'), <Input className={inputClass} id={`${id}-name`} maxLength={200} placeholder={tr('setup.name_placeholder')} value={value.name} onChange={(e) => onChange({ ...value, name: e.target.value })} />)}
    {row('provider', tr('provider'), <>
      <div className="relative inline-flex max-w-full items-center">
      <select id={`${id}-provider`} className="h-11 max-w-full appearance-none rounded-lg border border-input bg-background pl-4 pr-10 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" value={preset} onChange={(e) => { setKeyVisible(false);onProviderChange(e.target.value as ProviderPreset); }}>
        {providerPresets.map((provider) => <option key={provider} value={provider} disabled={provider !== 'CUSTOM' && !providerApis(provider, profiles).length}>{tr(`providers.${provider}`)}</option>)}
      </select>
      <ChevronDown size={16} aria-hidden="true" className="pointer-events-none absolute right-3 text-muted-foreground" />
      </div>
    </>)}
    {preset === 'CUSTOM' && row('custom-provider', tr('custom_provider'), <Input className={inputClass} id={`${id}-custom-provider`} required maxLength={64} value={value.provider} onChange={(e) => onChange({ ...value, provider: e.target.value })} />)}
    {row('key', 'API Key', <>
      <div className="relative">
      <Input className={`${inputClass} pr-12`} id={`${id}-key`} required={!editing} type={keyVisible ? 'text' : 'password'} autoComplete="new-password" spellCheck={false} disabled={!credentialStorageReady}
        placeholder={editing ? tr('setup.key_placeholder') : tr('setup.key_new_placeholder')} value={secret} onChange={(e) => onSecretChange(e.target.value)} />
      <button type="button" disabled={!credentialStorageReady || !secret} aria-controls={`${id}-key`} aria-pressed={keyVisible}
        aria-label={tr(keyVisible ? 'setup.hide_key' : 'setup.show_key')}
        className="absolute right-1 top-1 flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-40"
        onClick={() => setKeyVisible((visible) => !visible)}>{keyVisible ? <EyeOff size={17} /> : <Eye size={17} />}</button>
      </div>
      {editing && <p className="text-xs text-muted-foreground">{tr('setup.key_hint')}</p>}
    </>)}
    {row('endpoint', 'Endpoint', <>
      <Input className={inputClass} id={`${id}-endpoint`} required type="url" readOnly={value.apiStyle === 'DASHSCOPE_CHAT'} value={value.endpoint} onChange={(e) => onChange({ ...value, endpoint: e.target.value })} />
      <p className="text-xs text-muted-foreground">{tr(value.apiStyle === 'DASHSCOPE_CHAT' ? 'dashscope_endpoint_hint' : 'setup.endpoint_hint')}</p>
    </>)}
    {row('model', tr('setup.model_id'), <>
      {preset === 'QWEN' ? <QwenModelInput id={`${id}-model`} value={modelId} onChange={onModelIdChange} specifications={specifications} />
        : <Input className={inputClass} id={`${id}-model`} required maxLength={256} placeholder={tr('setup.model_placeholder')} value={modelId} onChange={(e) => onModelIdChange(e.target.value)} />}
      <p className="text-xs text-muted-foreground">{tr('setup.model_hint')}</p>
    </>)}
    {limits}
    <details key={preset} open={preset === 'CUSTOM' ? true : undefined} className="border-t border-border pt-5">
      <summary className="cursor-pointer text-sm font-medium">{tr('setup.advanced')}</summary>
      <div className="space-y-7 mt-6">
    <ModelSettingRow title="API">
      <div className="space-y-2 min-w-0">
        <div role="group" aria-label="API" className="flex flex-wrap gap-2">
          {availableApis.map((profile) => {
            const selected = value.apiStyle === profile.apiStyle && value.compatibilityProfile === profile.compatibilityProfile;
            return <button key={`${profile.apiStyle}/${profile.compatibilityProfile}`} type="button" aria-pressed={selected}
              className={`min-h-10 min-w-0 flex-1 rounded-md border px-3 py-2 text-sm transition-colors ${selected ? 'border-primary bg-primary/10 text-primary font-medium' : 'border-input text-muted-foreground hover:bg-muted'}`}
              onClick={() => onChange(selectProviderApi(value, preset, profile))}>
              {tr(`api_labels.${preset === 'QWEN' && profile.apiStyle === 'OPENAI_CHAT_COMPLETIONS' ? 'QWEN_COMPATIBLE' : profile.apiStyle}`)}{preset === 'CUSTOM' && <span className="block text-xs mt-1">{profile.compatibilityProfile}</span>}
            </button>;
          })}
        </div>
        <p className="text-xs text-muted-foreground break-all">{tr('request_preview')}: {connectionRequestUrl(value, profiles) || '—'}</p>
      </div>
    </ModelSettingRow>
    {advanced}
      </div>
    </details>
  </div>;
}

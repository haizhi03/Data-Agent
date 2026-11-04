import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { ModelConnectionFields } from './ModelConnectionFields';
import { ModelSettingRow, modelSettingColumns } from './ModelSettingRow';
import { ConnectionTestPanel } from './ConnectionTestPanel';
import { inferProvider, selectProvider, type ProviderPreset } from '../../lib/modelProviderPresets';
import { changeSetupConnection, setupConnection } from '../../lib/modelSetup';
import { findModelSpecification, modelOutputCeiling } from '../../lib/modelSpecifications';
import type { ConnectionTestInput, ConnectionTestResult, ModelProfile, ModelSetupInput, ModelSpecification, ThinkingMode } from '../../types/modelManagement';

interface Props {
  value: ModelSetupInput; profiles: ModelProfile[]; hasCredential: boolean; credentialStorageReady: boolean; secret: string; busy: boolean;
  onChange: (value: ModelSetupInput) => void; onSecretChange: (secret: string) => void;
  onSubmit: (event: FormEvent) => void; onCancel: () => void;
  testConnection: (input: ConnectionTestInput, signal?: AbortSignal) => Promise<ConnectionTestResult>;
  specifications?: ModelSpecification[];
}
const selectClass = 'w-full h-10 px-3 rounded-md border border-input bg-background text-sm';
function Field({ title, children, hint }: { title: string; children: (id: string) => ReactNode; hint?: ReactNode }) {
  const id = useId();
  return <ModelSettingRow title={title} htmlFor={id}>{children(id)}{hint && <p className="text-xs text-muted-foreground">{hint}</p>}</ModelSettingRow>;
}

export function ModelSetupEditor({ value, profiles, hasCredential, credentialStorageReady, secret, busy, onChange, onSecretChange, onSubmit, onCancel, testConnection, specifications = [] }: Props) {
  const { t } = useTranslation();const tr = (key: string, values?: Record<string, string | number>) => t(`model_settings.${key}`, values);
  const [preset, setPreset] = useState<ProviderPreset>(() => inferProvider(value));
  const connection = { ...setupConnection(value), provider: preset === 'CUSTOM' ? value.provider : preset };
  const available = profiles.filter((profile) => profile.apiStyle === value.apiStyle && profile.compatibilityProfile === value.compatibilityProfile);
  const selected = available.find((profile) => profile.capabilityProfile === value.capabilityProfile);
  const specification = value.compatibilityProfile === 'qwen-chat-v1' ? findModelSpecification(specifications, value.modelId) : undefined;
  const outputCeiling = specification ? modelOutputCeiling(specification, value.defaultOptions.thinkingMode) : 1000000;
  const sourceLink = specification && <a href={specification.sourceUrl} target="_blank" rel="noreferrer" className="underline underline-offset-2 ml-1">
    {tr('setup.spec_source', { date: specification.verifiedAt })}</a>;
  const setThinking = (mode: ThinkingMode) => onChange({ ...value, defaultOptions: { ...value.defaultOptions, thinkingMode: mode,
    temperature: null, thinkingBudget: mode === 'ENABLED' && value.capabilityProfile === 'anthropic-thinking-v1' ? 1024 : null,
    reasoningEffort: value.capabilityProfile === 'responses-reasoning-v1' ? 'medium' : null } });
  const limits = <>
      <Field title={tr('context')} hint={specification ? <>{tr('setup.known_context_hint', { context: specification.contextTokens.toLocaleString() })}{sourceLink}</> : tr('setup.context_hint')}>{(id) => <Input id={id} className="h-11 rounded-lg" type="number" min={2} max={specification?.contextTokens ?? 100000000}
        placeholder={tr('setup.context_placeholder')} value={value.maxContextTokens ?? ''}
        onChange={(e) => onChange({ ...value, maxContextTokens: e.target.value ? Number(e.target.value) : null,
          ...(!e.target.value ? { memoryThreshold: null } : {}) })} onBlur={(e) => {
        if (!e.target.value) return;
        const limit = Number(e.target.value);
        if (limit < 2) return;
        onChange({ ...value, maxContextTokens: limit, memoryThreshold: value.memoryThreshold == null ? null : Math.max(1, Math.min(value.memoryThreshold, limit - 1)) });
      }} />}</Field>
      <Field title={tr('max_output')} hint={specification ? <>{tr('setup.known_output_hint', { output: outputCeiling.toLocaleString() })}{sourceLink}</> : tr('setup.output_hint')}>{(id) => <Input id={id} className="h-11 rounded-lg" type="number" min={256} max={Math.min(outputCeiling, value.maxContextTokens == null ? outputCeiling : value.maxContextTokens - 1)}
        placeholder={tr('setup.output_placeholder')} value={value.defaultOptions.maxOutputTokens ?? ''}
        onChange={(e) => onChange({ ...value, defaultOptions: { ...value.defaultOptions, maxOutputTokens: e.target.value ? Number(e.target.value) : null } })} />}</Field>
  </>;
  const advanced = <div className="space-y-7 border-t border-border pt-6">
      <Field title={tr('setup.capability')}>{(id) => <select id={id} className={selectClass} value={value.capabilityProfile} onChange={(e) => {
        const profile = available.find((p) => p.capabilityProfile === e.target.value);
        onChange({ ...value, capabilityProfile: e.target.value, defaultOptions: { thinkingMode: profile?.thinkingModes[0] ?? 'DISABLED',
          maxOutputTokens: value.defaultOptions.maxOutputTokens ?? null } });
      }}>{available.map((profile) => <option key={profile.capabilityProfile} value={profile.capabilityProfile}>{tr(`setup.capabilities.${profile.capabilityProfile}`)}</option>)}</select>}</Field>
      <Field title={tr('thinking')}>{(id) => <select id={id} className={selectClass} value={value.defaultOptions.thinkingMode} onChange={(e) => setThinking(e.target.value as ThinkingMode)}>
        {selected?.thinkingModes.map((mode) => <option key={mode} value={mode}>{tr(`thinking_modes.${mode}`)}</option>)}
      </select>}</Field>
      <Field title={tr('threshold')}>{(id) => <Input id={id} type="number" min={1} max={value.maxContextTokens == null ? undefined : value.maxContextTokens - 1}
        placeholder={tr('setup.threshold_placeholder')} value={value.memoryThreshold ?? ''}
        onChange={(e) => onChange({ ...value, memoryThreshold: e.target.value ? Number(e.target.value) : null })} />}</Field>
      {value.defaultOptions.thinkingMode === 'DISABLED' && <Field title={tr('temperature')}>{(id) => <Input id={id} type="number" step="0.1" min={0} max={value.apiStyle === 'ANTHROPIC_MESSAGES' ? 1 : 2}
        value={value.defaultOptions.temperature ?? ''} onChange={(e) => onChange({ ...value, defaultOptions: { ...value.defaultOptions, temperature: e.target.value ? Number(e.target.value) : null } })} />}</Field>}
      {value.defaultOptions.thinkingMode === 'ENABLED' && ['qwen-tools-v1', 'anthropic-thinking-v1'].includes(value.capabilityProfile) && <Field title={tr('thinking_budget')}>{(id) => <Input id={id} type="number"
        min={value.capabilityProfile === 'anthropic-thinking-v1' ? 1024 : 1} required={value.capabilityProfile === 'anthropic-thinking-v1'} value={value.defaultOptions.thinkingBudget ?? ''}
        onChange={(e) => onChange({ ...value, defaultOptions: { ...value.defaultOptions, thinkingBudget: e.target.value ? Number(e.target.value) : null } })} />}</Field>}
      {value.capabilityProfile === 'responses-reasoning-v1' && <Field title={tr('effort')}>{(id) => <select id={id} className={selectClass} value={value.defaultOptions.reasoningEffort ?? 'medium'}
        onChange={(e) => onChange({ ...value, defaultOptions: { ...value.defaultOptions, reasoningEffort: e.target.value } })}>{['low', 'medium', 'high'].map((effort) => <option key={effort}>{effort}</option>)}</select>}</Field>}
    <ModelSettingRow title={tr('setup.availability')}><div className="flex flex-wrap gap-5 text-sm py-2">
      <label className="flex gap-2 items-center"><input type="checkbox" checked={value.chatVisible} onChange={(e) => onChange({ ...value, chatVisible: e.target.checked, makeDefault: e.target.checked && value.makeDefault })} />{tr('chat_visible')}</label>
      <label className="flex gap-2 items-center"><input type="checkbox" checked={value.enabled} onChange={(e) => onChange({ ...value, enabled: e.target.checked, makeDefault: e.target.checked && value.makeDefault })} />{tr('enabled')}</label>
    </div></ModelSettingRow>
    <p className="text-xs text-muted-foreground">{tr('setup.advanced_hint')}</p>
  </div>;

  return <form className="space-y-7" onSubmit={onSubmit}
    onInvalidCapture={(event) => (event.target as HTMLElement).closest('details')?.setAttribute('open', '')}>
    <h2 className="sr-only">{tr(value.modelConfigId ? 'setup.edit' : 'setup.add')}</h2>
    <fieldset disabled={busy} className="min-w-0">
      <ModelConnectionFields value={connection} preset={preset} profiles={profiles} secret={secret} editing={hasCredential} credentialStorageReady={credentialStorageReady}
        modelId={value.modelId} onModelIdChange={(modelId) => onChange({ ...value, modelId, ...(value.maxContextTokens == null ? { memoryThreshold: null } : {}) })} advanced={advanced} limits={limits} specifications={specifications}
        onChange={(next) => onChange(changeSetupConnection(value, next, profiles))} onSecretChange={onSecretChange}
        onProviderChange={(provider) => { setPreset(provider);onChange(changeSetupConnection(value, selectProvider(connection, provider, profiles), profiles));onSecretChange(''); }} />
    </fieldset>
    <div className={modelSettingColumns}><div aria-hidden="true" className="hidden md:block" /><label className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={busy || !value.chatVisible || !value.enabled} checked={value.makeDefault}
      onChange={(e) => onChange({ ...value, makeDefault: e.target.checked })} />{tr('setup.make_default')}</label></div>
    <div className={`${modelSettingColumns} border-t border-border pt-6`}><div aria-hidden="true" className="hidden md:block" />
      <ConnectionTestPanel compact value={connection} profiles={profiles} connectionId={value.connectionId} secret={secret} hasCredential={hasCredential}
        modelId={value.modelId} disabled={busy} testConnection={testConnection} actions={<>
          <Button type="submit" disabled={busy || !value.modelId.trim() || (!hasCredential && !secret.trim())}>{tr('save')}</Button>
          <Button type="button" disabled={busy} variant="ghost" onClick={onCancel}>{tr('cancel')}</Button>
        </>} />
    </div>
  </form>;
}

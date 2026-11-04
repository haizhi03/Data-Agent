import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, Plus, RefreshCw, FlaskConical, Star } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../store/authStore';
import { Button } from '../components/ui/Button';
import { ModelSetupEditor } from '../components/ai/ModelSetupEditor';
import { TaskModelBindings } from '../components/ai/TaskModelBindings';
import { inferProvider } from '../lib/modelProviderPresets';
import { setupFromModel, setupSaveBody } from '../lib/modelSetup';
import { createModelManagementService, modelManagementError } from '../services/modelManagement.service';
import type { ManagedModel, ModelConnection, ModelManagementStatus, ModelProfile, ModelSetupInput, ModelSpecification, ModelTest, PurposeBinding } from '../types/modelManagement';

const selectClass = 'w-full h-10 px-3 rounded-md border border-input bg-background text-sm';
export default function ModelSettings() {
  const scope = useAuthStore((s) => `${s.user?.id ?? ''}/${s.workspaceType}/${s.workspaceOrgId ?? ''}/${s.workspaceOrgRole ?? ''}`);
  return <ModelSettingsScope key={scope} />;
}

function ModelSettingsScope() {
  const { t } = useTranslation();const navigate = useNavigate();const tr = (key: string) => t(`model_settings.${key}`);
  const api = useMemo(createModelManagementService, []);
  const workspaceType = useAuthStore((s) => s.workspaceType);
  const orgName = useAuthStore((s) => s.user?.organizations?.find((o) => o.orgId === s.workspaceOrgId)?.orgName);
  const [status, setStatus] = useState<ModelManagementStatus | null>(null);
  const [connections, setConnections] = useState<ModelConnection[]>([]);
  const [models, setModels] = useState<ManagedModel[]>([]);
  const [profiles, setProfiles] = useState<ModelProfile[]>([]);
  const [specifications, setSpecifications] = useState<ModelSpecification[]>([]);
  const [bindings, setBindings] = useState<PurposeBinding[]>([]);
  const [form, setForm] = useState<ModelSetupInput | null>(null);
  const [secret, setSecret] = useState('');
  const [loading, setLoading] = useState(true);const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');const [notice, setNotice] = useState('');
  const [diagnostic, setDiagnostic] = useState<ManagedModel | null>(null);
  const [testLevel, setTestLevel] = useState('L4');const [test, setTest] = useState<ModelTest | null>(null);
  const editor = useRef<HTMLDivElement>(null);
  const editorKey = form ? form.modelConfigId ?? form.connectionId ?? 'new' : '';
  useEffect(() => { if (editorKey) editor.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, [editorKey]);

  const load = useCallback(async (background = false) => {
    if (!background) setLoading(true);setError('');
    try {
      const next = await api.status();setStatus(next);
      if (next.enabled && next.persistent && next.canManage) {
        const [c, m, b, p, specifications] = await Promise.all([api.connections(), api.models(), api.bindings(), api.profiles(), api.specifications()]);
        setConnections(c);setModels(m);setBindings(b);setProfiles(p);setSpecifications(specifications);
      }
    } catch (e) { setStatus(null);setError(modelManagementError(e)); }
    finally { setLoading(false); }
  }, [api]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!test || test.status !== 'RUNNING') return;
    let active = true;
    const timer = window.setTimeout(() => { api.testResult(test.id).then((value) => { if (active) setTest(value); }).catch((e: unknown) => {
      if (active) { setError(modelManagementError(e));setTest(null); }
    }); }, 1200);
    return () => { active = false;window.clearTimeout(timer); };
  }, [api, test]);
  const closeForm = () => { setForm(null);setSecret(''); };
  const act = async (work: () => Promise<unknown>, message = 'saved') => {
    setBusy(true);setError('');setNotice('');
    try { await work();setNotice(message);await load(true); }
    catch (e) { setError(modelManagementError(e)); }
    finally { setBusy(false); }
  };
  const open = (model?: ManagedModel, connection?: ModelConnection) => {
    const c = connection ?? connections.find((item) => item.id === model?.connectionId);
    const next = setupFromModel(profiles, c, model);
    const preset = inferProvider(next);if (preset !== 'CUSTOM') next.provider = preset;
    const defaultId = bindings.find((b) => b.purpose === 'MAIN_CHAT')?.modelConfigId;
    next.makeDefault = !model && !models.some((m) => m.id === defaultId && m.available);
    setForm(next);setSecret('');setDiagnostic(null);setTest(null);setNotice('');
  };
  const save = (event: FormEvent) => {
    event.preventDefault();if (!form) return;
    const body = setupSaveBody(form, secret, bindings);
    void act(async () => { await api.saveSetup(body);closeForm(); });
  };
  const makeDefault = (model: ManagedModel) => {
    const connection = connections.find((c) => c.id === model.connectionId);if (!connection) return;
    const next = setupFromModel(profiles, connection, model);next.makeDefault = true;closeForm();
    void act(() => api.saveSetup(setupSaveBody(next, '', bindings)));
  };
  const enable = (model: ManagedModel) => {
    const connection = connections.find((c) => c.id === model.connectionId);if (!connection) return;
    const next = setupFromModel(profiles, connection, model);next.enabled = true;closeForm();
    void act(() => api.saveSetup(setupSaveBody(next, '', bindings)));
  };
  const openDiagnostic = async (model: ManagedModel) => {
    closeForm();setDiagnostic(model);setTest(null);setError('');
    try { setTest((await api.tests(model.id))[0] ?? null); } catch (e) { setError(modelManagementError(e)); }
  };
  const main = bindings.find((b) => b.purpose === 'MAIN_CHAT')?.modelConfigId;
  const configuredModels = models.filter((model) => !model.taskPreset);
  const orphanConnections = connections.filter((c) => !configuredModels.some((m) => m.connectionId === c.id));
  const result = (() => { try { const value: unknown = JSON.parse(test?.result ?? '[]');return Array.isArray(value) ? value as Array<{ stage: string; status: string; code?: string }> : []; } catch { return []; } })();

  return <div ref={editor} className="max-w-5xl mx-auto space-y-8 pb-10 scroll-mt-6">
    <header className="flex flex-wrap justify-between items-start gap-4">
      <div><button type="button" disabled={busy} className="flex items-center gap-1 text-sm text-muted-foreground mb-3 disabled:opacity-50" onClick={() => form ? closeForm() : navigate('/')}><ArrowLeft size={15} />{tr(form ? 'setup.back_to_list' : 'back')}</button>
        <h1 className="text-2xl font-semibold">{tr(form ? form.modelConfigId ? 'setup.edit' : 'setup.add' : 'title')}</h1><p className="text-sm text-muted-foreground mt-2">{tr('setup.subtitle')}</p>
        <p className="text-sm mt-2">{tr('workspace')}: {workspaceType === 'PERSONAL' ? tr('personal') : orgName ?? tr('organization')}</p></div>
      {!form && <Button variant="outline" disabled={loading || busy} onClick={() => { closeForm();void load(); }}><RefreshCw size={15} className="mr-2" />{tr('refresh')}</Button>}
    </header>
    {error && <div role="alert" className="rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm"><p>{tr(`errors.${error}`)}</p>
      {error === 'MODEL_CONFIG_CONFLICT' && <Button variant="outline" className="mt-3" onClick={() => { closeForm();void load(); }}>{tr('reload')}</Button>}</div>}
    {notice && !error && <p role="status" className="text-sm text-primary">{tr(notice)}</p>}
    {loading ? <p>{tr('loading')}</p> : status && (!status.enabled || !status.persistent) ? <div className="p-6 rounded-xl border border-border">{tr('deployment_required')}</div>
      : status && !status.canManage ? <div className="p-6 rounded-xl border border-border">{tr('member_readonly')}</div> : status && <>
      {!status.credentialStorageReady && <div role="status" className="rounded-lg border border-amber-500/40 p-4 text-sm">{tr('key_required')}</div>}
      {!form && <section className="space-y-4">
        <div className="flex items-center justify-between gap-4"><h2 className="font-medium">{tr('setup.list')}</h2><Button disabled={busy} onClick={() => open()}><Plus size={16} className="mr-1" />{tr('setup.add')}</Button></div>
        {!configuredModels.length && !orphanConnections.length && <p className="rounded-xl border border-dashed border-border p-8 text-center text-muted-foreground">{tr('setup.empty')}</p>}
        {configuredModels.map((model) => {
          const connection = connections.find((c) => c.id === model.connectionId);const preset = connection ? inferProvider(connection) : 'CUSTOM';
          const active = model.enabled && !!connection?.enabled;
          return <article key={model.id} className="rounded-xl border border-border bg-card p-5 space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div><h3 className="font-medium flex items-center gap-2">{model.name}{main === model.id && <span className="text-xs text-primary bg-primary/10 rounded px-2 py-0.5">{tr('setup.default')}</span>}</h3>
                <p className="text-sm text-muted-foreground mt-2 break-all">{connection ? preset === 'CUSTOM' ? connection.provider : tr(`providers.${preset}`) : '—'} · {model.modelId}</p>
                <p className="text-xs text-muted-foreground mt-1">{tr(!active ? 'disabled' : model.available ? 'setup.ready' : 'unavailable')}</p></div>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="outline" size="sm" disabled={busy} onClick={() => open(model)}><FlaskConical size={14} className="mr-1" />{tr('setup.configure_test')}</Button>
                <Button variant="ghost" size="sm" disabled={busy || !model.available || !model.chatVisible || main === model.id} onClick={() => makeDefault(model)}><Star size={14} className="mr-1" />{tr('setup.set_default')}</Button>
                {active ? <Button variant="ghost" size="sm" disabled={busy} onClick={() => { closeForm();void act(() => api.disableModel(model.id, model.version)); }}>{tr('disable')}</Button>
                  : <Button variant="ghost" size="sm" disabled={busy} onClick={() => enable(model)}>{tr('setup.enable')}</Button>}
              </div>
            </div>
            <details className="text-xs text-muted-foreground"><summary className="cursor-pointer">{tr('setup.more')}</summary>
              <Button className="mt-2" variant="ghost" size="sm" disabled={busy || !model.available} onClick={() => void openDiagnostic(model)}>{tr('test')}</Button>
            </details>
          </article>;
        })}
        {orphanConnections.map((connection) => <article key={connection.id} className="rounded-xl border border-border p-5 flex flex-wrap justify-between gap-4 items-center">
          <div><h3 className="font-medium">{connection.name}</h3><p className="text-sm text-muted-foreground mt-1">{tr('setup.incomplete')}</p></div>
          <Button variant="outline" disabled={busy} onClick={() => open(undefined, connection)}>{tr('setup.complete')}</Button>
        </article>)}
      </section>}
      {form && <div className="border-t border-border pt-8"><ModelSetupEditor key={editorKey} value={form} profiles={profiles} specifications={specifications} secret={secret} busy={busy}
        hasCredential={connections.find((c) => c.id === form.connectionId)?.hasCredential ?? false} credentialStorageReady={status.credentialStorageReady}
        onChange={setForm} onSecretChange={setSecret} onSubmit={save} onCancel={closeForm} testConnection={api.testConnection} /></div>}
      {!form && !!models.length && <TaskModelBindings bindings={bindings} connections={connections} models={models} specifications={specifications} busy={busy}
        onSelect={(binding, option) => void act(() => option.kind === 'model' ? api.bind(binding.purpose, option.modelConfigId, binding.version)
          : api.bindPreset(binding.purpose, { expectedVersion: binding.version, connectionId: option.connectionId,
            connectionExpectedVersion: option.connectionExpectedVersion, modelId: option.modelId }))} />}
      {!form && diagnostic && <section className="rounded-xl border border-border bg-card p-6 space-y-4"><h2 className="font-semibold">{tr('test')} · {diagnostic.name}</h2><p className="text-sm text-muted-foreground">{tr('test_hint')}</p>
        <div className="flex gap-2"><select className={`${selectClass} max-w-72`} value={testLevel} onChange={(e) => setTestLevel(e.target.value)}>{['L1', 'L2', 'L3', 'L4'].map((level) => <option key={level} value={level}>{tr(`levels.${level}`)}</option>)}</select>
          <Button disabled={busy || test?.status === 'RUNNING'} onClick={() => { void act(async () => setTest(await api.test(diagnostic.id, testLevel)), 'test_started'); }}>{tr('run_test')}</Button></div>
        {test && <div role="status" className="text-sm space-y-2"><p>{tr(`test_status.${test.status}`)} · {new Date(test.createdAt).toLocaleString()}{!test.current && <span className="ml-2 text-amber-600">{tr('stale_test')}</span>}</p>
          {result.map((r, index) => <p key={index}>{r.stage} · {tr(`stage_status.${r.status}`)}{r.code ? ` · ${r.code}` : ''}</p>)}</div>}
      </section>}
    </>}
  </div>;
}

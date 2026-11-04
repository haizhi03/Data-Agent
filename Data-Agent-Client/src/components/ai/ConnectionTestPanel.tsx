import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { CheckCircle2, FlaskConical, Loader2, XCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { connectionRequestUrl } from '../../lib/modelProviderPresets';
import { connectionTestCode, connectionTestFailure } from '../../lib/connectionTest';
import type { ConnectionInput, ConnectionTestInput, ConnectionTestResult, ModelProfile } from '../../types/modelManagement';

interface Props {
  value: ConnectionInput;
  connectionId?: string;
  profiles: ModelProfile[];
  secret: string;
  hasCredential: boolean;
  initialModelId?: string;
  modelId?: string;
  compact?: boolean;
  actions?: ReactNode;
  disabled: boolean;
  testConnection: (input: ConnectionTestInput, signal?: AbortSignal) => Promise<ConnectionTestResult>;
}

export function ConnectionTestPanel({ value, connectionId, profiles, secret, hasCredential, initialModelId = '', modelId: configuredModelId, disabled, testConnection, compact = false, actions }: Props) {
  const { t } = useTranslation();
  const tr = (key: string) => t(`model_settings.connection_test.${key}`);
  const id = useId();
  const [localModelId, setModelId] = useState(initialModelId);
  const modelId = configuredModelId ?? localModelId;
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);
  const request = useRef<AbortController | null>(null);
  // A result only describes the exact API, credential, model and connection version that was tested.
  useEffect(() => {
    request.current?.abort();setResult(null);setTesting(false);
    return () => { request.current?.abort(); };
  }, [connectionId, value.provider, value.apiStyle, value.endpoint, value.compatibilityProfile, value.expectedVersion, secret, modelId]);

  const ready = !!modelId.trim() && !!value.provider.trim() && !!connectionRequestUrl(value, profiles)
    && (!!secret.trim() || (!!connectionId && hasCredential));
  const start = async () => {
    if (!ready || testing || disabled) return;
    request.current?.abort();
    const controller = new AbortController();request.current = controller;
    setResult(null);setTesting(true);
    const input: ConnectionTestInput = {
      ...(connectionId ? { connectionId, expectedVersion: value.expectedVersion } : {}),
      provider: value.provider, apiStyle: value.apiStyle, endpoint: value.endpoint, compatibilityProfile: value.compatibilityProfile,
      modelId: modelId.trim(), ...(secret ? { apiKey: secret } : {}),
    };
    try {
      const response = await testConnection(input, controller.signal);
      if (!controller.signal.aborted && request.current === controller) setResult(response);
    } catch (error) {
      if (!controller.signal.aborted && request.current === controller) setResult(connectionTestFailure(error));
    } finally { if (!controller.signal.aborted && request.current === controller) setTesting(false); }
  };

  return <section className={compact ? 'space-y-3 min-w-0' : 'rounded-lg border border-border bg-muted/20 p-4 space-y-3'} aria-label={tr('title')}>
    {!compact && <div><h3 className="text-sm font-medium">{tr('title')}</h3><p className="text-xs text-muted-foreground mt-1">{tr('hint')}</p></div>}
    <div className={compact ? 'flex flex-wrap items-center gap-3' : 'flex flex-col sm:flex-row sm:items-end gap-3'}>
      {configuredModelId === undefined ? <label htmlFor={id} className="grid gap-1.5 text-sm flex-1"><span>{tr('model_id')}</span>
        <Input id={id} maxLength={256} disabled={disabled} value={modelId} placeholder={tr('model_placeholder')} onChange={(event) => setModelId(event.target.value)} />
      </label> : !compact && <p className="flex-1 text-sm py-2 break-all">{tr('configured_model')}: {modelId || '—'}</p>}
      <Button type="button" variant="outline" disabled={disabled || testing || !ready} onClick={() => void start()}>
        {testing ? <Loader2 size={15} className="mr-2 animate-spin" /> : <FlaskConical size={15} className="mr-2" />}{tr(testing ? 'running' : 'action')}
      </Button>
      {actions}
    </div>
    {compact && <p className="text-xs text-muted-foreground">{tr('hint')}</p>}
    {!ready && <p className="text-xs text-muted-foreground">{tr('required')}</p>}
    {testing && <p role="status" className="text-sm text-muted-foreground">{tr('running')}</p>}
    {result && <div role={result.success ? 'status' : 'alert'} className={`flex items-start gap-2 rounded-md p-3 text-sm ${result.success ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400' : 'bg-destructive/10 text-destructive'}`}>
      {result.success ? <CheckCircle2 size={17} className="shrink-0 mt-0.5" /> : <XCircle size={17} className="shrink-0 mt-0.5" />}
      <div><p className="font-medium">{tr(result.success ? 'success' : 'failure')}</p>
        <p className="mt-1">{tr(`codes.${connectionTestCode(result.code)}`)}</p>
        {Number.isFinite(result.elapsedMs) && result.elapsedMs > 0 && <p className="text-xs mt-1">{tr('elapsed')}: {Math.round(result.elapsedMs)} ms</p>}
      </div>
    </div>}
  </section>;
}

import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { connectionService } from '../../services/connection.service';
import { fetchExplorerDatabases, fetchExplorerSchemas } from '../../lib/explorerMetadataCache';
import { targetLabel } from '../../lib/chatTarget';
import { useWorkspaceStore } from '../../store/workspaceStore';
import type { ChatContext } from '../../types/chat';
import { Dialog, DialogContent, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';

interface Props {
  target: ChatContext;
  ready: boolean;
  loadError: boolean;
  onChange: (target: ChatContext) => Promise<void>;
  onNew: (target: ChatContext) => void;
}

/** Picker state is only a draft. Only the two explicit action buttons apply it. */
export function DefaultTargetControl({ target, ready, loadError, onChange, onNew }: Props) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const types = useWorkspaceStore(s => s.supportedDbTypes);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<ChatContext>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const { data: connections = [], isError: connectionsError } = useQuery({ queryKey: ['connections'], queryFn: () => connectionService.getConnections() });
  const connection = connections.find(c => c.id === draft.connectionId);
  const type = types.find(item => item.code === connection?.dbType);
  const hasDatabase = type?.supportDatabase === true;
  const hasSchema = type?.supportSchema === true;
  const databases = useQuery({ queryKey: ['chat-target-databases', draft.connectionId],
    queryFn: () => fetchExplorerDatabases(client, String(draft.connectionId)),
    enabled: open && !!draft.connectionId && hasDatabase });
  const schemas = useQuery({ queryKey: ['chat-target-schemas', draft.connectionId, draft.catalogName],
    queryFn: () => fetchExplorerSchemas(client, String(draft.connectionId), draft.catalogName),
    enabled: open && !!draft.connectionId && hasSchema && (!hasDatabase || !!draft.catalogName) });
  const invalid = !!draft.connectionId && (!connection || !type);
  const label = targetLabel(target, connections.find(c => c.id === target.connectionId)?.name);
  const save = async () => {
    setBusy(true); setError(false);
    try { await onChange({ ...draft }); setOpen(false); }
    catch { setError(true); }
    finally { setBusy(false); }
  };
  return <div className="border-t theme-border px-3 py-2 text-xs theme-text-secondary" data-chat-default-target>
    <div className="flex items-center justify-between gap-2">
      <span className="min-w-0 break-all" title={label}>{t('ai.default_target')}: {loadError ? t('ai.target_load_failed') : !ready ? t('ai.target_loading') : label || t('ai.target_unspecified')}</span>
      <button type="button" className="shrink-0 hover:underline" disabled={!ready}
        onClick={() => { setDraft({ ...target }); setError(false); setOpen(true); }}>{t('ai.target_change')}</button>
    </div>
    <Dialog open={open} onOpenChange={value => { if (!busy) setOpen(value); }}>
      <DialogContent aria-describedby={undefined}>
        <DialogTitle>{t('ai.target_change')}</DialogTitle>
        <label className="grid gap-1 text-xs">{t('ai.mention_data_source')}
          <select aria-label={t('ai.mention_data_source')} className="rounded border theme-border theme-bg-main p-2"
            value={draft.connectionId ?? ''} disabled={busy} onChange={event => setDraft(event.target.value ? { connectionId: Number(event.target.value) } : {})}>
            <option value="">{t('ai.target_unspecified')}</option>
            {connections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        {hasDatabase && <label className="grid gap-1 text-xs">{t('ai.mention_database')}
          <select aria-label={t('ai.mention_database')} className="rounded border theme-border theme-bg-main p-2"
            value={draft.catalogName ?? ''} disabled={busy || databases.isFetching}
            onChange={event => setDraft({ connectionId: draft.connectionId, catalogName: event.target.value || undefined })}>
            <option value="">{t('ai.target_unspecified')}</option>
            {databases.data?.map(name => <option key={name}>{name}</option>)}
          </select>
        </label>}
        {hasSchema && (!hasDatabase || draft.catalogName) && <label className="grid gap-1 text-xs">{t('ai.mention_schema')}
          <select aria-label={t('ai.mention_schema')} className="rounded border theme-border theme-bg-main p-2"
            value={draft.schemaName ?? ''} disabled={busy || schemas.isFetching}
            onChange={event => setDraft({ ...draft, schemaName: event.target.value || undefined })}>
            <option value="">{t('ai.target_unspecified')}</option>
            {schemas.data?.map(name => <option key={name}>{name}</option>)}
          </select>
        </label>}
        {(error || connectionsError || databases.isError || schemas.isError) && <p role="alert" className="text-xs text-red-500">{t('ai.target_save_failed')}</p>}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={() => setOpen(false)}>{t('ai.target_cancel')}</Button>
          <Button variant="outline" disabled={busy || invalid} onClick={() => { onNew({ ...draft }); setOpen(false); }}>{t('ai.target_new_chat')}</Button>
          <Button disabled={busy || invalid} onClick={() => void save()}>{t('ai.target_apply')}</Button>
        </div>
      </DialogContent>
    </Dialog>
  </div>;
}

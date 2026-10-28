import { useNavigate } from 'react-router-dom';
import { Settings } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { getPlatformShortcuts } from '../../lib/platformShortcuts';
import { I18N_KEYS } from '../../constants/i18nKeys';

export function Header({ onToggleAI }: { onToggleAI?: () => void }) {
    const { t } = useTranslation(); const navigate = useNavigate();
    const setSettingsModalOpen = useWorkspaceStore(s => s.setSettingsModalOpen);
    return <header className="h-12 border-b theme-border bg-[color:var(--bg-toolbar)] px-4 select-none shrink-0" data-local-runtime>
        <div className="relative h-full flex items-center justify-between">
            <div className="flex-1 min-w-0" />
            <button type="button" onClick={() => navigate('/')} className="absolute left-1/2 -translate-x-1/2 px-3 py-1 rounded-md text-sm font-medium theme-text-primary hover:bg-white/5 transition-colors" aria-label={t(I18N_KEYS.AI.BOT_NAME)}>{t(I18N_KEYS.AI.BOT_NAME)}</button>
            <div className="flex flex-1 justify-end items-center gap-3 theme-text-secondary min-w-0">
                <Button variant="ghost" size="sm" onClick={onToggleAI} className="h-7 text-xs px-2.5" data-local-assistant-toggle>{t('local_assistant.title')}</Button>
                <button type="button" onClick={() => setSettingsModalOpen(true)} title={`${t(I18N_KEYS.COMMON.SETTINGS)} (${getPlatformShortcuts().openSettings})`} className="flex items-center justify-center hover:theme-text-primary"><Settings className="h-4 w-4" /></button>
            </div>
        </div>
    </header>;
}

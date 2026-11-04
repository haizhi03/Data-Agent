import { useWorkspaceStore } from '../../store/workspaceStore';
import { SettingsModalHeader } from './SettingsModalHeader';
import { LanguageSection } from './LanguageSection';
import { AppearanceSection } from './AppearanceSection';
import { QueryBehaviorSection } from './QueryBehaviorSection';
import { ResetSection } from './ResetSection';
import { SettingsModalFooter } from './SettingsModalFooter';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Settings2 } from 'lucide-react';
import { ROUTES } from '../../constants/routes';

export function SettingsModal() {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const {
    isSettingsModalOpen,
    setSettingsModalOpen,
    resultBehavior,
    tableDblClickMode,
    tableDblClickConsoleTarget,
    updatePreferences,
    resetPreferences,
  } = useWorkspaceStore();

  if (!isSettingsModalOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 z-[200] flex items-center justify-center font-sans animate-in fade-in duration-200">
      <div className="theme-bg-popup w-[450px] rounded-lg shadow-2xl border theme-border flex flex-col text-sm animate-in zoom-in-95 duration-200">
        <SettingsModalHeader onClose={() => setSettingsModalOpen(false)} />

        <div className="p-5 space-y-6 overflow-y-auto max-h-[70vh] no-scrollbar">
          <button type="button" className="w-full flex items-center gap-2 rounded-md border theme-border p-3 theme-text-primary hover:bg-primary/5" onClick={() => { setSettingsModalOpen(false); navigate(ROUTES.MODELS); }}>
            <Settings2 size={16} />{t('model_settings.title')}
          </button>
          <LanguageSection />
          <AppearanceSection />

          <QueryBehaviorSection
            resultBehavior={resultBehavior}
            tableDblClickMode={tableDblClickMode}
            tableDblClickConsoleTarget={tableDblClickConsoleTarget}
            onResultBehaviorChange={(val) => updatePreferences({ resultBehavior: val })}
            onTableDblClickModeChange={(val) => updatePreferences({ tableDblClickMode: val })}
            onConsoleTargetChange={(val) => updatePreferences({ tableDblClickConsoleTarget: val })}
          />

          <ResetSection onReset={resetPreferences} />
        </div>

        <SettingsModalFooter onClose={() => setSettingsModalOpen(false)} />
      </div>
    </div>
  );
}

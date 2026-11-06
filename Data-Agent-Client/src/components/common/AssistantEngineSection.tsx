import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { AssistantEnginePreference } from '../../constants/workspacePreferences';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useRuntimeStore } from '../../store/runtimeStore';
import { RadioGroup } from './RadioGroup';

export function AssistantEngineSection() {
  const { t } = useTranslation();
  const engine = useWorkspaceStore(s => s.assistantEngine);
  const updatePreferences = useWorkspaceStore(s => s.updatePreferences);
  const pi = useRuntimeStore(s => s.capabilities?.features.piAssistant);
  const piReady = pi?.state === 'READY';

  return (
    <section className="space-y-3 pt-4 border-t theme-border" aria-label={t(I18N_KEYS.SETTINGS_MODAL.ASSISTANT_ENGINE)}>
      <label className="block theme-text-secondary text-[11px] uppercase font-bold tracking-wider">
        {t(I18N_KEYS.SETTINGS_MODAL.ASSISTANT_ENGINE)}
      </label>
      <RadioGroup
        name="assistant-engine"
        value={engine}
        onChange={value => updatePreferences({ assistantEngine: value as AssistantEnginePreference })}
        options={[
          { value: 'auto', label: t(I18N_KEYS.SETTINGS_MODAL.ENGINE_AUTO) },
          { value: 'java', label: t(I18N_KEYS.SETTINGS_MODAL.ENGINE_JAVA) },
          { value: 'pi', label: t(I18N_KEYS.SETTINGS_MODAL.ENGINE_PI), disabled: !piReady },
        ]}
      />
      <p className="text-[11px] leading-5 theme-text-secondary">
        {piReady ? t(I18N_KEYS.SETTINGS_MODAL.ENGINE_NEXT_CHAT) : t(I18N_KEYS.SETTINGS_MODAL.ENGINE_PI_UNAVAILABLE)}
      </p>
    </section>
  );
}

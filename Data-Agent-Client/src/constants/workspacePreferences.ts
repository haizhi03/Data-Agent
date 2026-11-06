export enum ResultBehaviorEnum {
  MULTI = 'multi',
  OVERWRITE = 'overwrite',
}

export enum TableDblClickModeEnum {
  TABLE = 'table',
  CONSOLE = 'console',
}

export enum TableDblClickConsoleTargetEnum {
  REUSE = 'reuse',
  NEW = 'new',
}

export type ResultBehavior = 'multi' | 'overwrite';
export type TableDblClickMode = 'table' | 'console';
export type TableDblClickConsoleTarget = 'reuse' | 'new';
export type AssistantEnginePreference = 'auto' | 'java' | 'pi';

export interface PreferenceState {
  resultBehavior: ResultBehavior;
  tableDblClickMode: TableDblClickMode;
  tableDblClickConsoleTarget: TableDblClickConsoleTarget;
  aiAutoSelect: boolean;
  aiAutoWrite: boolean;
  aiWriteTransaction: boolean;
  aiMaxRetries: number;
  assistantEngine: AssistantEnginePreference;
}

export const DEFAULT_PREFERENCES: PreferenceState = {
  resultBehavior: ResultBehaviorEnum.MULTI,
  tableDblClickMode: TableDblClickModeEnum.TABLE,
  tableDblClickConsoleTarget: TableDblClickConsoleTargetEnum.REUSE,
  aiAutoSelect: true,
  aiAutoWrite: false,
  aiWriteTransaction: true,
  aiMaxRetries: 3,
  assistantEngine: 'auto',
};

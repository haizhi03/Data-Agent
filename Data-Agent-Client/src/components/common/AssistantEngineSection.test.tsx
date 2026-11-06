// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AssistantEngineSection } from './AssistantEngineSection';
import { usePreferenceStore } from '../../store/preferenceStore';
import { useRuntimeStore } from '../../store/runtimeStore';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { STORAGE_KEYS } from '../../constants/storageKeys';

// Install isolated storage before Zustand initializes; Node's global storage
// depends on launch flags and is not available in every test environment.
const storage=vi.hoisted(()=>{
  const records=new Map<string,string>();
  const value={getItem:(key:string)=>records.get(key)??null,setItem:(key:string,item:string)=>{records.set(key,item);},
    removeItem:(key:string)=>{records.delete(key);},clear:()=>records.clear()};
  Object.defineProperty(globalThis,'localStorage',{value,configurable:true});
  return value;
});

vi.mock('react-i18next',()=>({useTranslation:()=>({t:(key:string)=>({
  'settings.assistant_engine':'AI 助手引擎','settings.engine_auto':'自动','settings.engine_java':'Java Agent',
  'settings.engine_pi':'Pi 实验引擎','settings.engine_pi_unavailable':'Pi 当前不可用',
  'settings.engine_next_chat':'选择将在新会话中生效',
}[key]??key)})}));

function piState(state:'READY'|'UNAVAILABLE') {
  useRuntimeStore.setState({status:'READY',capabilities:{contractVersion:1,mode:'LOCAL',runtimeInstanceId:'test',
    accountLoginEnabled:false,organizationEnabled:false,chatHistoryPersistenceEnabled:false,
    features:{databaseWorkbench:{state:'READY',reasonCode:null},aiAssistant:{state:'READY',reasonCode:null},
      piAssistant:{state,reasonCode:state==='READY'?null:'PI_DISABLED'},knowledgeMemory:{state:'NOT_IMPLEMENTED',reasonCode:null},
      skills:{state:'NOT_IMPLEMENTED',reasonCode:null}},
  }});
}

describe('AssistantEngineSection',()=>{
  beforeEach(()=>{storage.clear();useWorkspaceStore.getState().updatePreferences({assistantEngine:'auto'});piState('READY');});
  afterEach(cleanup);

  it('saves a manual engine selection',()=>{
    render(<AssistantEngineSection/>);
    fireEvent.click(screen.getByRole('radio',{name:'Java Agent'}));
    expect(usePreferenceStore.getState().assistantEngine).toBe('java');
    fireEvent.click(screen.getByRole('radio',{name:'Pi 实验引擎'}));
    expect(usePreferenceStore.getState().assistantEngine).toBe('pi');
    expect(JSON.parse(storage.getItem(STORAGE_KEYS.WORKSPACE)!).state.assistantEngine).toBe('pi');
    expect(screen.getByText('选择将在新会话中生效')).toBeTruthy();
  });

  it('disables Pi and explains why when the backend does not advertise it',()=>{
    piState('UNAVAILABLE');render(<AssistantEngineSection/>);
    expect((screen.getByRole('radio',{name:'Pi 实验引擎'}) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText('Pi 当前不可用')).toBeTruthy();
  });
});

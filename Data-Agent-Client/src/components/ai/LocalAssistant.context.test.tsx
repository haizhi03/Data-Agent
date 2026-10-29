// @vitest-environment jsdom
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocalAssistant } from './LocalAssistant';
import { useAIAssistantContext } from './AIAssistantContext';
import { localAssistantService as api } from '../../services/localAssistant.service';
import { connectionService } from '../../services/connection.service';
import { useMention } from '../../hooks/useMention';
import { renderHook } from '@testing-library/react';

const fixture=vi.hoisted(() => ({
  workspace:{supportedDbTypes:[{code:'mysql',supportDatabase:true,supportSchema:false},{code:'dm',supportDatabase:false,supportSchema:true}]},
  t:(key:string) => ({'ai.default_target':'默认目标','ai.target_unspecified':'未指定','ai.target_change':'设置默认目标','ai.target_apply':'应用到当前会话',
    'ai.target_new_chat':'以此目标新建会话','ai.target_cancel':'取消','ai.mention_data_source':'连接','ai.mention_database':'数据库','ai.mention_schema':'Schema'}[key]??key),
}));
vi.mock('react-i18next',()=>({useTranslation:()=>({t:fixture.t})}));
vi.mock('../../store/workspaceStore',()=>({useWorkspaceStore:(selector?:any)=>selector?selector(fixture.workspace):fixture.workspace}));
vi.mock('../../store/runtimeStore',()=>({useRuntimeStore:()=>true,localWorkbenchReady:()=>true}));
vi.mock('../../services/connection.service',()=>({connectionService:{getConnections:vi.fn()}}));
vi.mock('../../services/modelManagement.service',()=>({createModelManagementService:()=>({
  models:async()=>[{id:'model',available:true,chatVisible:true,connectionId:'vendor'}],bindings:async()=>[],connections:async()=>[{id:'vendor',provider:'CUSTOM'}],
})}));
vi.mock('../../services/localAssistant.service',()=>({localAssistantService:{create:vi.fn(),chat:vi.fn(),close:vi.fn(async()=>{}),proposals:vi.fn(async()=>[]),updateDefaultTarget:vi.fn(async(_id,target)=>({...target}))}}));
vi.mock('../../lib/explorerMetadataCache',()=>({
  fetchExplorerDatabases:vi.fn(async()=>['sales','archive']),fetchExplorerSchemas:vi.fn(async()=>['PUBLIC','ARCHIVE']),
  fetchExplorerTables:vi.fn(async()=>['orders']),fetchExplorerViews:vi.fn(async()=>[]),fetchExplorerFunctions:vi.fn(async()=>[]),fetchExplorerProcedures:vi.fn(async()=>[]),fetchExplorerTriggers:vi.fn(async()=>[]),
  getCachedExplorerDatabases:()=>undefined,getCachedExplorerSchemas:()=>undefined,getCachedExplorerTables:()=>undefined,getCachedExplorerViews:()=>undefined,
  getCachedExplorerFunctions:()=>undefined,getCachedExplorerProcedures:()=>undefined,getCachedExplorerTriggers:()=>undefined,
}));
vi.mock('./AIAssistantHeader',()=>({AIAssistantHeader:(props:any)=><button onClick={()=>props.onNewChat()}>普通新会话</button>}));
vi.mock('./AIAssistantContent',()=>({AIAssistantContent:()=>null}));
vi.mock('./ChatInput',()=>({ChatInput:()=>{
  const c=useAIAssistantContext();
  return <div><textarea aria-label="message" value={c.input} onChange={e=>c.setInput(e.target.value)}/>
    <button onClick={()=>{c.setInput('compare @orders and @other/ARCHIVE/orders');c.mentionState.setUserMentions([
      {token:'@orders',connectionId:1,connectionName:'development',catalogName:'sales',objectType:'TABLE',objectName:'orders'},
      {token:'@other/ARCHIVE/orders',connectionId:2,connectionName:'warehouse',schemaName:'ARCHIVE',objectType:'TABLE',objectName:'orders'},
    ]);}}>选择引用</button><button onClick={c.onSend}>发送</button><output data-testid="refs">{JSON.stringify(c.mentionState.userMentions)}</output></div>;
}}));
let client:QueryClient;
function wrapper({children}:{children:React.ReactNode}){return <QueryClientProvider client={client}>{children}</QueryClientProvider>;}
beforeEach(()=>{
  vi.clearAllMocks();
  client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:Infinity}}});
  vi.mocked(connectionService.getConnections).mockResolvedValue([
    {id:1,name:'development',dbType:'mysql',host:'local',port:3306,driverJarPath:''},
    {id:2,name:'warehouse',dbType:'dm',host:'local',port:5236,driverJarPath:''},
  ]);
  vi.mocked(api.create).mockResolvedValue({conversationId:7,runtimeInstanceId:'test'});
  vi.mocked(api.chat).mockImplementation(async function*(){yield{done:true,data:'{"status":"SUCCEEDED"}'};});
});
afterEach(()=>{cleanup();client.clear();});
async function choose(connection:string,namespace:string,action='应用到当前会话'){
  fireEvent.click(screen.getByText('设置默认目标'));
  fireEvent.change(screen.getByLabelText('连接'),{target:{value:connection}});
  await waitFor(()=>expect(screen.getByRole('option',{name:namespace})).toBeTruthy());
  fireEvent.change(screen.getByLabelText(connection==='1'?'数据库':'Schema'),{target:{value:namespace}});
  fireEvent.click(screen.getByText(action));
}

describe('desktop local default targets and references',()=>{
  it('sends copied full identities, drops deleted references and changes defaults without new sessions',async()=>{
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(connectionService.getConnections).toHaveBeenCalled());
    await choose('1','sales');
    await waitFor(()=>expect(screen.getByText('默认目标: development / sales')).toBeTruthy());
    fireEvent.click(screen.getByText('选择引用'));fireEvent.click(screen.getByText('发送'));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.chat).mock.calls[0][4]).toMatchObject({defaultTarget:{connectionId:1,catalogName:'sales'},userMentions:[{connectionId:1,catalogName:'sales'},{connectionId:2,schemaName:'ARCHIVE'}]});
    fireEvent.click(screen.getByText('选择引用'));
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'only @orders'}});
    await choose('2','ARCHIVE');
    await waitFor(()=>expect(screen.getByText('默认目标: warehouse / ARCHIVE')).toBeTruthy());
    expect(api.updateDefaultTarget).toHaveBeenCalledWith(7,{connectionId:2,schemaName:'ARCHIVE'});
    fireEvent.click(screen.getByText('发送'));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.chat).mock.calls[1][4]?.userMentions).toHaveLength(1);
    expect(vi.mocked(api.chat).mock.calls[0][4]?.defaultTarget).toEqual({connectionId:1,catalogName:'sales'});
  });
  it('explicit new-target action clears the draft, preserves old scope and closes the old session',async()=>{
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(connectionService.getConnections).toHaveBeenCalled());
    await choose('1','sales');fireEvent.change(screen.getByLabelText('message'),{target:{value:'first'}});fireEvent.click(screen.getByText('发送'));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByText('选择引用'));
    await choose('2','ARCHIVE','以此目标新建会话');
    expect((screen.getByLabelText('message') as HTMLTextAreaElement).value).toBe('');
    expect(screen.getByTestId('refs').textContent).toBe('[]');
    expect(api.close).toHaveBeenCalledWith(7);
    expect(api.updateDefaultTarget).not.toHaveBeenCalled();
    expect(screen.getByText('默认目标: warehouse / ARCHIVE')).toBeTruthy();
    fireEvent.click(screen.getByText('普通新会话'));
    expect(screen.getByText('默认目标: warehouse / ARCHIVE')).toBeTruthy();
  });
  it('local @ browsing, back and cancellation have no default-target side effects',async()=>{
    const onConfirmDisplay=vi.fn();
    const {result}=renderHook(()=>useMention({onConfirmDisplay}),{wrapper});
    act(()=>result.current.openMention());
    await waitFor(()=>expect(result.current.mentionItems).toHaveLength(2));
    act(()=>result.current.handleMentionSelect(result.current.mentionItems[1]));
    await waitFor(()=>expect(result.current.mentionLevel).toBe('schema'));
    const key=(value:string)=>({key:value,preventDefault:()=>{}} as React.KeyboardEvent);
    act(()=>result.current.handleMentionKeyDown(key('ArrowLeft')));
    act(()=>result.current.handleMentionKeyDown(key('Escape')));
    expect(onConfirmDisplay).not.toHaveBeenCalled();
    expect(api.updateDefaultTarget).not.toHaveBeenCalled();expect(api.create).not.toHaveBeenCalled();
  });
});

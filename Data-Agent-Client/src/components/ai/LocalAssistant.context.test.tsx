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
import { useRuntimeStore } from '../../store/runtimeStore';
import { useTabStore, type Tab } from '../../store/tabStore';
import type { ModelOption } from '../../types/ai';

const modelApi=vi.hoisted(()=>({chatModels:vi.fn()}));

const fixture=vi.hoisted(() => ({
  modelApiStyle:'OPENAI_CHAT_COMPLETIONS', missingModelConnection:false,
  workspace:{assistantEngine:'auto',supportedDbTypes:[{code:'mysql',supportDatabase:true,supportSchema:false},{code:'dm',supportDatabase:false,supportSchema:true}]},
  t:(key:string) => ({'ai.default_target':'默认目标','ai.target_unspecified':'未指定','ai.target_change':'设置默认目标','ai.target_apply':'应用到当前会话',
    'ai.target_new_chat':'以此目标新建会话','ai.target_cancel':'取消','ai.mention_data_source':'连接','ai.mention_database':'数据库','ai.mention_schema':'Schema',
    'local_assistant.errors.PI_COMPACTION_NOT_SUPPORTED':'Pi 会话暂不支持压缩，请新建对话。',
    'local_assistant.errors.PI_UNSUPPORTED_API_STYLE':'当前模型的接口不受 Pi 支持，请选择使用 OpenAI Chat Completions 或 Anthropic Messages 接口的模型。',
    'local_assistant.errors.MODEL_CONFIG_UNAVAILABLE':'模型配置不可用，请检查模型设置。',
    'local_assistant.errors.MODEL_MANAGEMENT_UNAVAILABLE':'可用模型列表加载失败，请检查本地服务后重试。',
    'local_assistant.ephemeral_pi':'对话仅在内存中，关闭面板后清空。'}[key]??key),
}));
vi.mock('react-i18next',()=>({useTranslation:()=>({t:fixture.t})}));
vi.mock('../../store/workspaceStore',()=>({useWorkspaceStore:(selector?:any)=>selector?selector(fixture.workspace):fixture.workspace}));
vi.mock('../../services/connection.service',()=>({connectionService:{getConnections:vi.fn()}}));
vi.mock('../../services/modelManagement.service',()=>({createModelManagementService:()=>modelApi}));
vi.mock('../../services/localAssistant.service',()=>({localAssistantService:{create:vi.fn(),chat:vi.fn(),compact:vi.fn(async()=>({compacted:false})),close:vi.fn(async()=>{}),proposals:vi.fn(async()=>[]),updateDefaultTarget:vi.fn(async(_id,target)=>({...target}))}}));
vi.mock('../../lib/explorerMetadataCache',()=>({
  fetchExplorerDatabases:vi.fn(async()=>['sales','archive']),fetchExplorerSchemas:vi.fn(async()=>['PUBLIC','ARCHIVE']),
  fetchExplorerTables:vi.fn(async()=>['orders']),fetchExplorerViews:vi.fn(async()=>[]),fetchExplorerFunctions:vi.fn(async()=>[]),fetchExplorerProcedures:vi.fn(async()=>[]),fetchExplorerTriggers:vi.fn(async()=>[]),
  getCachedExplorerDatabases:()=>undefined,getCachedExplorerSchemas:()=>undefined,getCachedExplorerTables:()=>undefined,getCachedExplorerViews:()=>undefined,
  getCachedExplorerFunctions:()=>undefined,getCachedExplorerProcedures:()=>undefined,getCachedExplorerTriggers:()=>undefined,
}));
vi.mock('./AIAssistantHeader',()=>({AIAssistantHeader:(props:any)=><button onClick={()=>props.onNewChat()}>普通新会话</button>}));
vi.mock('./AIAssistantContent',()=>({AIAssistantContent:({messages}:any)=><>
  <output data-testid="response-blocks">{JSON.stringify(messages.at(-1)?.blocks??[])}</output>
  <output data-testid="messages">{JSON.stringify(messages)}</output>
</>}));
vi.mock('./ChatInput',()=>({ChatInput:()=>{
  const c=useAIAssistantContext();
  return <div><textarea aria-label="message" value={c.input} onChange={e=>c.setInput(e.target.value)}/>
    <button onClick={()=>{c.setInput('compare @orders and @other/ARCHIVE/orders');c.mentionState.setUserMentions([
      {token:'@orders',connectionId:1,connectionName:'development',catalogName:'sales',objectType:'TABLE',objectName:'orders'},
      {token:'@other/ARCHIVE/orders',connectionId:2,connectionName:'warehouse',schemaName:'ARCHIVE',objectType:'TABLE',objectName:'orders'},
    ]);}}>选择引用</button><button onClick={c.onSend} disabled={c.isLoading || c.canSend === false}>发送</button>
    <button onClick={c.onSend}>直接发送</button>
    <select aria-label="模型" value={c.modelState.model} onChange={e=>c.modelState.setModel(e.target.value)}>
      {c.modelState.modelOptions.map(option=><option key={option.modelName} value={option.modelName}>{option.modelId ?? option.name}</option>)}
    </select>
    <output data-testid="loading">{String(c.isLoading)}</output>
    <output data-testid="commands">{JSON.stringify(c.supportedCommands)}</output>
    {c.supportedCommands?.includes('compact') && <button onClick={()=>c.onCommand?.('compact')} disabled={c.isLoading}>压缩上下文</button>}
    <button onClick={()=>c.onCommand?.('compact')}>直接调用压缩</button>
    <output data-testid="refs">{JSON.stringify(c.mentionState.userMentions)}</output></div>;
}}));
let client:QueryClient;
function wrapper({children}:{children:React.ReactNode}){return <QueryClientProvider client={client}>{children}</QueryClientProvider>;}
beforeEach(()=>{
  vi.clearAllMocks();
  useTabStore.setState({tabs:[],activeTabId:null});
  fixture.modelApiStyle='OPENAI_CHAT_COMPLETIONS'; fixture.missingModelConnection=false;
  fixture.workspace.assistantEngine='auto';
  modelApi.chatModels.mockImplementation(async()=>[
    {modelName:'model',modelConfigId:'model',name:'默认模型',defaultModel:true,
      apiStyle:fixture.missingModelConnection?undefined:fixture.modelApiStyle},
    {modelName:'compatible-model',modelConfigId:'compatible-model',name:'兼容模型',apiStyle:'ANTHROPIC_MESSAGES'},
  ]);
  useRuntimeStore.setState({status:'READY',capabilities:{contractVersion:1,mode:'LOCAL',runtimeInstanceId:'test',
    accountLoginEnabled:false,organizationEnabled:false,chatHistoryPersistenceEnabled:false,
    features:{databaseWorkbench:{state:'READY',reasonCode:null},aiAssistant:{state:'READY',reasonCode:null},
      piAssistant:{state:'UNAVAILABLE',reasonCode:'PI_DISABLED'},knowledgeMemory:{state:'NOT_IMPLEMENTED',reasonCode:null},skills:{state:'NOT_IMPLEMENTED',reasonCode:null}},
  }});
  client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:Infinity}}});
  vi.mocked(connectionService.getConnections).mockResolvedValue([
    {id:1,name:'development',dbType:'mysql',host:'local',port:3306,driverJarPath:''},
    {id:2,name:'warehouse',dbType:'dm',host:'local',port:5236,driverJarPath:''},
  ]);
  vi.mocked(api.create).mockResolvedValue({conversationId:7,runtimeInstanceId:'test'});
  vi.mocked(api.chat).mockImplementation(async function*(){yield{done:true,data:'{"status":"SUCCEEDED"}'};});
});
afterEach(()=>{cleanup();client.clear();});
function activate(tab:Tab){
  act(()=>useTabStore.setState({tabs:[tab],activeTabId:tab.id}));
}
const salesTab:Tab={id:'sql-sales',name:'Query',type:'file',active:true,metadata:{
  connectionId:1,connectionName:'development',databaseName:'sales',schemaName:null,
}};
const archiveTab:Tab={id:'table-archive',name:'orders',type:'table',active:true,metadata:{
  connectionId:2,connectionName:'warehouse',dbType:'dm',databaseName:'ARCHIVE',schemaName:'ARCHIVE',
  schema:'ARCHIVE',objectName:'orders',objectType:'table',
}};

function piReady(ready:boolean) {
  const capabilities=useRuntimeStore.getState().capabilities!;
  act(()=>useRuntimeStore.setState({capabilities:{...capabilities,features:{...capabilities.features,
    piAssistant:{state:ready?'READY':'UNAVAILABLE',reasonCode:ready?null:'PI_DISABLED'},
  }}}));
}
async function send(text:string) {
  await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
  fireEvent.change(screen.getByLabelText('message'),{target:{value:text}});
  await waitFor(()=>expect((screen.getByText('发送') as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByText('发送'));
}

describe('durable text recovery',()=>{
  it('replaces interrupted text and preserves tool evidence and earlier turns',async()=>{
    piReady(true);
    vi.mocked(api.chat).mockImplementationOnce(async function*(){
      yield {done:false,type:'TEXT',data:'earlier answer'};
      yield {done:true,data:'{"status":"SUCCEEDED"}'};
    }).mockImplementationOnce(async function*(){
      yield {done:false,type:'TOOL_CALL',data:'tool evidence'};
      yield {done:false,type:'TEXT',data:'interrupted answer'};
      yield {done:false,type:'STATUS',data:'TEXT_RESET'};
      yield {done:false,type:'STATUS',data:'TEXT_RESET'};
      yield {done:false,type:'TEXT',data:'recovered answer'};
      yield {done:true,data:'{"status":"SUCCEEDED"}'};
    });
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('first question');
    await waitFor(()=>expect(screen.getByTestId('messages').textContent).toContain('earlier answer'));
    await send('second question');
    await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    const messages=JSON.parse(screen.getByTestId('messages').textContent!);
    expect(messages[1].content).toBe('earlier answer');
    expect(messages[3].content).toBe('recovered answer');
    expect(messages[3].blocks).toEqual([
      {done:false,type:'TOOL_CALL',data:'tool evidence'}, {done:false,type:'TEXT',data:'recovered answer'},
    ]);
  });
});

describe('local chat model availability',()=>{
  const qwenIds=['qwen3.8-max','qwen3.6-flash','qwen-plus','qwen3.5-plus'];
  const choices:ModelOption[]=qwenIds.map((modelId,index)=>({
    modelName:index?`qwen-preset/configured-qwen/${modelId}`:'configured-qwen',
    modelConfigId:index?`qwen-preset/configured-qwen/${modelId}`:'configured-qwen',
    modelId,name:'qwen',provider:'QWEN',apiStyle:'OPENAI_CHAT_COMPLETIONS',compatibilityProfile:'qwen-chat-v1',
    defaultModel:index===0,supportThinking:true,memoryThreshold:24000,maxContextTokens:1000000,
  }));

  it.each([false,true])('offers and sends all four authorized Qwen models when Pi readiness is %s',async ready=>{
    piReady(ready);modelApi.chatModels.mockResolvedValue(choices);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(screen.getAllByRole('option')).toHaveLength(4));
    for(const [index,choice] of choices.entries()){
      expect((screen.getByRole('option',{name:choice.modelId}) as HTMLOptionElement).value).toBe(choice.modelConfigId);
      fireEvent.change(screen.getByLabelText('模型'),{target:{value:choice.modelName}});
      await send(`question ${index}`);
      await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(index+1));
      expect(vi.mocked(api.chat).mock.calls[index][2]).toBe(choice.modelConfigId);
      expect(vi.mocked(api.chat).mock.calls[index][5]).toBe(ready?'pi':'java');
      await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    }
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(modelApi.chatModels).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).toBeNull();
  });
  it('honors the default returned by the chat API even when it is a derived choice',async()=>{
    modelApi.chatModels.mockResolvedValue(choices.map((choice,index)=>({...choice,defaultModel:index===2})));
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect((screen.getByLabelText('模型') as HTMLSelectElement).value).toBe(choices[2].modelName));
    await send('default question');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.chat).mock.calls[0][2]).toBe(choices[2].modelConfigId);
  });
  it('does not add unavailable Qwen models to an empty availability list',async()=>{
    modelApi.chatModels.mockResolvedValue([]);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(modelApi.chatModels).toHaveBeenCalledTimes(1));
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'hello'}});
    expect((screen.getByText('发送') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByText('直接发送'));
    expect(api.create).not.toHaveBeenCalled();expect(api.chat).not.toHaveBeenCalled();
  });
  it('keeps sending disabled if loading the authorized list fails',async()=>{
    modelApi.chatModels.mockRejectedValue(new Error('offline'));
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toBe('可用模型列表加载失败，请检查本地服务后重试。'));
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'hello'}});
    fireEvent.click(screen.getByText('直接发送'));
    expect((screen.getByText('发送') as HTMLButtonElement).disabled).toBe(true);
    expect(api.create).not.toHaveBeenCalled();expect(api.chat).not.toHaveBeenCalled();
  });
});

describe('Pi model API compatibility',()=>{
  it.each(['OPENAI_RESPONSES','DASHSCOPE_CHAT','GEMINI_GENERATE_CONTENT','UNKNOWN_API_STYLE'])('blocks %s before sending and preserves the draft',async style=>{
    fixture.modelApiStyle=style; piReady(true);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toContain('当前模型的接口不受 Pi 支持'));
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'请解释表结构'}});
    expect((screen.getByText('发送') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByText('直接发送'));
    expect((screen.getByLabelText('message') as HTMLTextAreaElement).value).toBe('请解释表结构');
    expect(api.create).not.toHaveBeenCalled(); expect(api.chat).not.toHaveBeenCalled();
  });
  it.each(['OPENAI_CHAT_COMPLETIONS','ANTHROPIC_MESSAGES'])('allows the wired %s API style',async style=>{
    fixture.modelApiStyle=style; piReady(true);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('请解释表结构');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.chat).mock.calls[0][5]).toBe('pi');
    expect(screen.queryByRole('alert')).toBeNull();
  });
  it('blocks missing model protocol metadata before sending',async()=>{
    fixture.missingModelConnection=true; piReady(true);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toBe('模型配置不可用，请检查模型设置。'));
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'hello'}});
    expect((screen.getByText('发送') as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByText('直接发送'));
    expect(api.create).not.toHaveBeenCalled(); expect(api.chat).not.toHaveBeenCalled();
  });
  it('recovers after selecting a compatible model and keeps the existing Pi conversation',async()=>{
    fixture.modelApiStyle='OPENAI_RESPONSES'; piReady(true);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toContain('当前模型的接口不受 Pi 支持'));
    fireEvent.change(screen.getByLabelText('模型'),{target:{value:'compatible-model'}});
    expect(screen.queryByRole('alert')).toBeNull();
    await send('first');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    fireEvent.change(screen.getByLabelText('模型'),{target:{value:'model'}});
    expect(screen.getByRole('alert').textContent).toContain('当前模型的接口不受 Pi 支持');
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'second'}});
    fireEvent.click(screen.getByText('直接发送'));
    expect(api.chat).toHaveBeenCalledTimes(1);
    fireEvent.change(screen.getByLabelText('模型'),{target:{value:'compatible-model'}});
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByText('发送'));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.chat).mock.calls.map(call=>[call[0],call[2],call[5]])).toEqual([
      [7,'compatible-model','pi'],[7,'compatible-model','pi'],
    ]);
  });
  it('keeps models with other API styles usable in Java conversations',async()=>{
    fixture.modelApiStyle='OPENAI_RESPONSES';
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('hello java');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.chat).mock.calls[0][5]).toBe('java');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('local conversation engines and compaction',()=>{
  it.each(['java','pi'] as const)('uses the manually selected %s engine for a new conversation',async selected=>{
    piReady(true);fixture.workspace.assistantEngine=selected;
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('hello');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.chat).mock.calls[0][5]).toBe(selected);
    expect(screen.queryByRole('note')).toBeNull();
  });
  it('falls back to Java when the saved Pi preference is unavailable',async()=>{
    fixture.workspace.assistantEngine='pi';
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('hello');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.chat).mock.calls[0][5]).toBe('java');
  });
  it('retains Explorer lifecycle and nested tool events in the assistant response',async()=>{
    piReady(true);
    const events=([
      {type:'SUB_AGENT_START',data:'{"agentType":"explorer","phase":"start","taskId":"task-1"}',parentToolCallId:'delegate',subAgentTaskId:'task-1'},
      {type:'TOOL_CALL',data:'{"toolName":"list_tables"}',parentToolCallId:'delegate',subAgentTaskId:'task-1'},
      {type:'SUB_AGENT_PROGRESS',data:'{"agentType":"explorer","phase":"progress"}',parentToolCallId:'delegate',subAgentTaskId:'task-1'},
      {type:'SUB_AGENT_COMPLETE',data:'{"agentType":"explorer","phase":"complete","summaryText":"verified orders"}',parentToolCallId:'delegate',subAgentTaskId:'task-1'},
    ] as const).map(event=>({...event,done:false}));
    vi.mocked(api.chat).mockImplementation(async function*(){for(const event of events)yield event;yield{done:true,data:'{"status":"SUCCEEDED"}'};});
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('find tables');
    await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    expect(JSON.parse(screen.getByTestId('response-blocks').textContent!)).toEqual(events);
  });
  it('hides Pi compaction and rejects direct commands and typed /compact without any request',async()=>{
    piReady(true);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect((screen.getByLabelText('模型') as HTMLSelectElement).value).toBe('model'));
    expect(screen.queryByRole('note')).toBeNull();
    expect(screen.getByTitle('对话仅在内存中，关闭面板后清空。')).toBeTruthy();
    expect(screen.getByTestId('commands').textContent).toBe('["new"]');
    expect(screen.queryByText('压缩上下文')).toBeNull();
    fireEvent.click(screen.getByText('直接调用压缩'));
    expect(screen.getByRole('alert').textContent).toBe('Pi 会话暂不支持压缩，请新建对话。');
    await send('/compact');
    expect(api.compact).not.toHaveBeenCalled();
    expect(api.chat).not.toHaveBeenCalled();
    expect(api.create).not.toHaveBeenCalled();
  });
  it('keeps Java compaction available after a conversation starts',async()=>{
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect((screen.getByLabelText('模型') as HTMLSelectElement).value).toBe('model'));
    expect(screen.queryByRole('note')).toBeNull();
    await send('hello');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    await waitFor(()=>expect((screen.getByText('压缩上下文') as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByText('压缩上下文'));
    await waitFor(()=>expect(api.compact).toHaveBeenCalledWith(7));
    expect(vi.mocked(api.chat).mock.calls[0][5]).toBe('java');
  });
  it.each([false,true])('pins the engine when Pi readiness initially is %s, then reselects on a new chat',async initial=>{
    piReady(initial);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect((screen.getByLabelText('模型') as HTMLSelectElement).value).toBe('model'));
    await send('first');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    piReady(!initial);
    expect(screen.getByTestId('commands').textContent).toBe(initial?'["new"]':'["new","compact"]');
    expect(screen.queryByRole('note')).toBeNull();
    await send('second');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.chat).mock.calls.map(call=>call[5])).toEqual([initial?'pi':'java',initial?'pi':'java']);
    await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    fireEvent.click(screen.getByText('普通新会话'));
    await send('new chat');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(3));
    expect(vi.mocked(api.chat).mock.calls[2][5]).toBe(initial?'java':'pi');
    expect(screen.queryByRole('note')).toBeNull();
    expect(api.close).toHaveBeenCalledWith(7);
  });
});

describe('desktop local automatic targets and references',()=>{
  it('inherits the SQL tab, keeps the conversation scope on navigation and copies reference identities',async()=>{
    activate(salesTab);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await waitFor(()=>expect((screen.getByLabelText('模型') as HTMLSelectElement).value).toBe('model'));
    expect(screen.getByText('development / sales')).toBeTruthy();
    expect(screen.queryByText('设置默认目标')).toBeNull();
    expect(document.querySelector('[data-chat-default-target]')).toBeNull();
    fireEvent.click(screen.getByText('选择引用'));fireEvent.click(screen.getByText('发送'));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(api.create).toHaveBeenCalledWith({connectionId:1,catalogName:'sales'});
    expect(vi.mocked(api.chat).mock.calls[0][4]).toMatchObject({defaultTarget:{connectionId:1,catalogName:'sales'},userMentions:[{connectionId:1,catalogName:'sales'},{connectionId:2,schemaName:'ARCHIVE'}]});
    await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    fireEvent.click(screen.getByText('选择引用'));
    fireEvent.change(screen.getByLabelText('message'),{target:{value:'only @orders'}});
    activate(archiveTab);
    expect(screen.getByText('development / sales')).toBeTruthy();
    expect(api.updateDefaultTarget).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('发送'));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.chat).mock.calls[1][4]?.userMentions).toHaveLength(1);
    expect(vi.mocked(api.chat).mock.calls[1][4]?.defaultTarget).toEqual({connectionId:1,catalogName:'sales'});
    expect(vi.mocked(api.chat).mock.calls[0][4]?.defaultTarget).toEqual({connectionId:1,catalogName:'sales'});
  });
  it('new chat inherits the current table scope, clears the draft and closes the old session',async()=>{
    activate(salesTab);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('first');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    await waitFor(()=>expect(screen.getByTestId('loading').textContent).toBe('false'));
    fireEvent.click(screen.getByText('选择引用'));
    activate(archiveTab);
    fireEvent.click(screen.getByText('普通新会话'));
    expect((screen.getByLabelText('message') as HTMLTextAreaElement).value).toBe('');
    expect(screen.getByTestId('refs').textContent).toBe('[]');
    expect(api.close).toHaveBeenCalledWith(7);
    expect(api.updateDefaultTarget).not.toHaveBeenCalled();
    expect(screen.getByText('warehouse / ARCHIVE')).toBeTruthy();
    await send('new table question');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.chat).mock.calls[1][4]?.defaultTarget).toEqual({connectionId:2,schemaName:'ARCHIVE'});
    expect(vi.mocked(api.chat).mock.calls[0][4]?.defaultTarget).toEqual({connectionId:1,catalogName:'sales'});
  });
  it('follows tab changes before the first send and uses canonical table catalog and schema',async()=>{
    activate(salesTab);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    activate({...archiveTab,metadata:{...archiveTab.metadata,connectionId:3,connectionName:'analytics',dbType:'postgresql',
      databaseName:'display-only',schemaName:'old-schema',catalog:'reporting',schema:'public'}});
    expect(screen.getByText('analytics / reporting / public')).toBeTruthy();
    await send('first');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(api.create).toHaveBeenCalledWith({connectionId:3,catalogName:'reporting',schemaName:'public'});
  });
  it('freezes the submitted scope while session creation is pending',async()=>{
    let finishCreate!:(value:{conversationId:number;runtimeInstanceId:string})=>void;
    vi.mocked(api.create).mockImplementationOnce(()=>new Promise(resolve=>{finishCreate=resolve;}));
    activate(salesTab);
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    await send('first');
    await waitFor(()=>expect(api.create).toHaveBeenCalledTimes(1));
    activate(archiveTab);
    expect(screen.getByText('development / sales')).toBeTruthy();
    await act(async()=>finishCreate({conversationId:7,runtimeInstanceId:'test'}));
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    await send('follow-up');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.chat).mock.calls.map(call=>call[4]?.defaultTarget)).toEqual([
      {connectionId:1,catalogName:'sales'},{connectionId:1,catalogName:'sales'},
    ]);
  });
  it('hides empty scope and keeps a conversation without a target unbound after navigation',async()=>{
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    expect(document.querySelector('[data-chat-scope]')).toBeNull();
    expect(screen.queryByText(/未指定|设置默认目标/)).toBeNull();
    await send('hello');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    activate(salesTab);
    expect(document.querySelector('[data-chat-scope]')).toBeNull();
    await send('follow-up');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.chat).mock.calls.map(call=>call[4]?.defaultTarget)).toEqual([{},{}]);
  });
  it.each(['plan','subagent-console','er'] as const)('does not infer a database from a %s tab',async type=>{
    activate({...salesTab,type});
    render(<LocalAssistant onClose={()=>{}}/>,{wrapper});
    expect(document.querySelector('[data-chat-scope]')).toBeNull();
    await send('hello');
    await waitFor(()=>expect(api.chat).toHaveBeenCalledTimes(1));
    expect(api.create).toHaveBeenCalledWith({});
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

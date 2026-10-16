// Real Codex regression, isolated home and localhost provider; never uses production threads or credentials.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { CodexAppServerControl } from './codex-control.mjs';
import { readSharedRuntime } from './codex-shared-transport.mjs';
import { fileURLToPath } from 'node:url';

const root = await mkdtemp(join(tmpdir(), 'rearcue-codex-shared-check-'));
const home = join(root, 'home'), workspace = join(root, 'workspace');
await mkdir(home); await mkdir(workspace);
const delay = ms => new Promise(r => setTimeout(r, ms));
let calls = 0;
const provider = createServer(async (req, res) => {
  let raw = ''; for await (const chunk of req) raw += chunk;
  if (req.method !== 'POST' || req.url !== '/v1/responses') { res.writeHead(404).end(); return; }
  const body = JSON.parse(raw);
  calls++;
  const needsApproval = raw.includes('APPROVAL_FIXTURE') && !body.input?.some(i=>i.type==='function_call_output');
  const item = needsApproval ? {
    type: 'function_call', id: `fc_fixture_${calls}`, call_id: `call_fixture_${calls}`, name: 'exec_command', namespace: 'functions',
    arguments: JSON.stringify({ cmd: "Write-Output 'APPROVAL_FIXTURE_OK'", sandbox_permissions: 'require_escalated', justification: 'Isolated approval regression fixture.' }),
  } : { id: 'msg_fixture', type: 'message', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: 'FIXTURE_OK', annotations: [] }] };
  const response = { id: `resp_${calls}`, object: 'response', status: 'completed', model: 'fixture-model', output: [item], usage: {input_tokens:1,output_tokens:1,total_tokens:2} };
  res.writeHead(200, {'content-type':'text/event-stream'});
  for (const ev of [
    {type:'response.created',response:{...response,status:'in_progress',output:[]}},
    {type:'response.output_item.added',output_index:0,item:{...item,status:'in_progress'}},
    {type:'response.output_item.done',output_index:0,item},
    {type:'response.completed',response},
  ]) res.write(`event: ${ev.type}\ndata: ${JSON.stringify(ev)}\n\n`);
  res.end();
});
await new Promise(r => provider.listen(0,'127.0.0.1',r));
await writeFile(join(home,'config.toml'), `model = "fixture-model"\nmodel_provider = "fixture"\ncli_auth_credentials_store = "file"\napproval_policy = "on-request"\nsandbox_mode = "read-only"\n[model_providers.fixture]\nname = "Local regression"\nbase_url = "http://127.0.0.1:${provider.address().port}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\n`);
const env = {...process.env, CODEX_HOME:home};
for (const key of Object.keys(env)) if (/TOKEN|SECRET|PASSWORD|API_KEY|CODEX_APP_TOOLS_PIPE_PATH|CODEX_TASK_WORKSPACE_VERIFYING_IDENTITY/i.test(key)) delete env[key];
const child = spawn(process.execPath, [fileURLToPath(new URL('../codex-shared-runtime.mjs',import.meta.url)), '--serve'], {cwd:workspace,env,windowsHide:true,stdio:['pipe','pipe','pipe']});
let stderr = ''; child.stderr.setEncoding('utf8'); child.stderr.on('data',s=>stderr+=s);
child.stdout.resume();
let endpoint;
class Rpc {
  constructor(ws) {
    this.ws=ws; this.id='desktop-'; this.sequence=0; this.pending=new Map(); this.messages=[];
    ws.addEventListener('message',ev=>{const m=JSON.parse(ev.data);if(m.id!=null&&this.pending.has(m.id)&&!m.method){const p=this.pending.get(m.id);this.pending.delete(m.id);clearTimeout(p.timer);m.error?p.reject(Object.assign(new Error(m.error.message),{code:m.error.code})):p.resolve(m.result);}else this.messages.push(m);});
  }
  static async open() {const ws=new WebSocket(endpoint);await new Promise((r,j)=>{ws.addEventListener('open',r,{once:true});ws.addEventListener('error',j,{once:true});});const rpc=new Rpc(ws);await rpc.request('initialize',{clientInfo:{name:'RearCueSharedFixture',version:'1'},capabilities:{experimentalApi:true}});ws.send(JSON.stringify({method:'initialized'}));return rpc;}
  request(method,params={}) {const id=this.id+(++this.sequence);return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`timeout ${method}`));},10000);this.pending.set(id,{resolve,reject,timer});this.ws.send(JSON.stringify({id,method,params}));});}
  async wait(predicate) {for(let i=0;i<150;i++){const m=this.messages.find(predicate);if(m)return m;await delay(100);}throw new Error('notification timeout: '+this.messages.map(m=>m.method).join(','));}
  reply(id,result) {this.ws.send(JSON.stringify({id,result}));}
  close(){for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(new Error('fixture closed'));}this.pending.clear();this.ws.close();}
}
let mobile, desktop;
try {
  for(let i=0;i<150;i++){const config=readSharedRuntime(env);if(config){endpoint=config.url;break;}if(child.exitCode!==null)throw new Error(stderr);await delay(100);}
  assert.ok(endpoint,'shared runtime must start');
  mobile=new CodexAppServerControl({env}); desktop=await Rpc.open();
  const approvals=[]; const patches=[];
  mobile.on('approval',a=>approvals.push(a)); mobile.on('event',p=>patches.push(p));
  const first=await mobile.startConversation({workspace,model:'fixture-model',prompt:'APPROVAL_FIXTURE'});
  for(let i=0;i<150&&!approvals.length;i++)await delay(100);
  assert.ok(approvals.length,'real native command approval required');
  const resumed=await desktop.request('thread/resume',{threadId:first.threadId,excludeTurns:true});
  assert.equal(resumed.thread.id,first.threadId);
  const desktopApproval=await desktop.wait(m=>m.method==='item/commandExecution/requestApproval');
  assert.equal(desktopApproval.params.threadId,first.threadId);
  desktop.reply(desktopApproval.id,{decision:'decline'});
  await desktop.wait(m=>m.method==='turn/completed'&&m.params?.threadId===first.threadId);
  assert.equal(mobile.approvals.size,0,'PC-resolved approval must disappear from mobile');
  // A pending conversation must not prevent PC access to a different, completed conversation.
  const waiting=await mobile.startConversation({workspace,model:'fixture-model',prompt:'APPROVAL_FIXTURE'});
  for(let i=0;i<150&&approvals.length<2;i++)await delay(100);
  assert.equal(approvals.at(-1).threadId,waiting.threadId);
  const finished=await mobile.startConversation({workspace,model:'fixture-model',prompt:'Return FIXTURE_OK.'});
  for(let i=0;i<150&&!patches.some(p=>p.sessionId===finished.threadId&&p.status==='idle');i++)await delay(100);
  assert.ok(patches.some(p=>p.sessionId===finished.threadId&&p.status==='idle'));
  assert.ok(mobile.activeTurns.has(waiting.threadId),'other conversation is genuinely waiting');
  assert.equal((await desktop.request('thread/resume',{threadId:finished.threadId,excludeTurns:true})).thread.id,finished.threadId);
  await mobile.stop();
  assert.ok((await desktop.request('thread/loaded/list')).data.includes(first.threadId),'closing bridge connection must preserve PC in-memory thread, independent of asynchronous rollout flush');
  console.log(JSON.stringify({pass:true,pcOpenedWhileWaiting:true,pcReceivedApproval:true,mobileSawResolved:true,unrelatedConversationUnlocked:true,bridgeDisconnectPreservesPc:true,calls}));
} finally {
  await mobile?.stop(); desktop?.close();
  if(child.exitCode===null)await new Promise(r=>{child.once('close',r);child.kill();});
  provider.closeAllConnections(); await new Promise(r=>provider.close(r));
  assert.equal(dirname(resolve(root)),resolve(tmpdir())); assert.ok(basename(root).startsWith('rearcue-codex-shared-check-'));
  await rm(root,{recursive:true,force:true,maxRetries:20,retryDelay:100});
}

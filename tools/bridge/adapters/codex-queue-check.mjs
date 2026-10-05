// Real app-server regression: isolated CODEX_HOME and localhost Responses stub; no account or production threads.
// Run: node tools/bridge/adapters/codex-queue-check.mjs (requires installed Codex CLI).
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { join, resolve, dirname, basename } from 'node:path';
import { rm } from 'node:fs/promises';
import { CodexAppServerControl, resolveCodexCommand } from './codex-control.mjs';
import { tmpdir } from 'node:os';
import assert from 'node:assert/strict';
const bin=resolveCodexCommand();
const root=await mkdtemp(join(tmpdir(),'rearcue-codex-queue-check-'));
const home=join(root,'codex-home'), workspace=join(root,'workspace');
await mkdir(home);await mkdir(workspace);
const requests=[];
const provider=createServer(async(req,res)=>{
 let raw='';for await(const chunk of req)raw+=chunk;
 requests.push({method:req.method,path:req.url});
 if(req.method==='POST'&&req.url==='/v1/responses') {
   const message={id:'fixture-msg',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'DIAGNOSTIC_OK',annotations:[]}]};
   const response={id:'fixture-response',object:'response',created_at:1,status:'completed',model:'fixture-model',output:[message],usage:{input_tokens:1,output_tokens:1,total_tokens:2}};
   res.writeHead(200,{'content-type':'text/event-stream'});
   for(const event of [
      {type:'response.created',response:{...response,status:'in_progress',output:[]}},
      {type:'response.output_item.added',output_index:0,item:{...message,status:'in_progress',content:[]}},
      {type:'response.content_part.added',output_index:0,content_index:0,item_id:message.id,part:{type:'output_text',text:'',annotations:[]}},
      {type:'response.output_text.delta',output_index:0,content_index:0,item_id:message.id,delta:'DIAGNOSTIC_OK'},
      {type:'response.output_text.done',output_index:0,content_index:0,item_id:message.id,text:'DIAGNOSTIC_OK'},
      {type:'response.output_item.done',output_index:0,item:message},
      {type:'response.completed',response},
   ]) res.write(`event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
   res.end();
 } else {res.writeHead(404,{'content-type':'application/json'});res.end('{}');}
});
await new Promise(r=>provider.listen(0,'127.0.0.1',r));
await writeFile(join(home,'config.toml'),`model = "fixture-model"\nmodel_provider = "fixture"\ncli_auth_credentials_store = "file"\napproval_policy = "on-request"\nsandbox_mode = "read-only"\n[model_providers.fixture]\nname = "Isolated diagnostic provider"\nbase_url = "http://127.0.0.1:${provider.address().port}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\n`);
const env={...process.env,CODEX_HOME:home};
for(const key of Object.keys(env)) if(/TOKEN|SECRET|PASSWORD|API_KEY|CODEX_APP_TOOLS_PIPE_PATH|CODEX_TASK_WORKSPACE_VERIFYING_IDENTITY/i.test(key)) delete env[key];
class Rpc {
 constructor(name) {
  this.name=name;this.id=0;this.pending=new Map();this.notifications=[];this.stderr='';this.buffer='';
  this.child=spawn(bin,['app-server','--stdio'],{cwd:workspace,env,stdio:['pipe','pipe','pipe'],windowsHide:true});
  this.child.stdout.setEncoding('utf8');this.child.stderr.setEncoding('utf8');
  this.child.stderr.on('data',s=>this.stderr+=s);
  this.child.stdout.on('data',s=>{this.buffer+=s;let at;while((at=this.buffer.indexOf('\n'))>=0){let line=this.buffer.slice(0,at);this.buffer=this.buffer.slice(at+1);let msg;try{msg=JSON.parse(line)}catch{continue}if(msg.id!=null&&this.pending.has(msg.id)){const p=this.pending.get(msg.id);this.pending.delete(msg.id);clearTimeout(p.timer);msg.error?p.reject(Object.assign(new Error(msg.error.message),{code:msg.error.code})):p.resolve(msg.result)}else this.notifications.push({...msg,observedAt:Date.now()})}});
  this.child.on('exit',()=>{for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(new Error(`${name} exited`))}this.pending.clear()});
 }
 request(method,params={}){const id=++this.id;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`${this.name} timeout ${method}`))},12000);this.pending.set(id,{resolve,reject,timer});this.child.stdin.write(JSON.stringify({id,method,params})+'\n')})}
 async init(){await this.request('initialize',{clientInfo:{name:'RearCueQueueFixture',version:'1'},capabilities:{experimentalApi:true}});this.child.stdin.write(JSON.stringify({method:'initialized'})+'\n')}
 async stop(){if(this.child.exitCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>{this.child.kill();resolve()},2000);this.child.once('close',()=>{clearTimeout(timer);resolve()});this.child.stdin.end()})}
}

const A=new Rpc('A');
const B=new CodexAppServerControl({command:bin,env});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
try {
 await A.init();
 const thread=(await A.request('thread/start',{cwd:workspace,model:'fixture-model',modelProvider:'fixture',approvalPolicy:'on-request',sandbox:'read-only'})).thread;
 const first=await A.request('turn/start',{threadId:thread.id,input:[{type:'text',text:'Return DIAGNOSTIC_OK only.'}]});
 for(let i=0;i<100&&!A.notifications.some(n=>n.method==='turn/completed'&&n.params?.turn?.id===first.turn.id);i++)await delay(100);
 assert.ok(A.notifications.some(n=>n.method==='turn/completed'&&n.params?.turn?.id===first.turn.id),'fixture must be genuinely idle');
 await A.request('thread/queue/list',{threadId:thread.id});
 const started=Date.now();
 const result=await B.sendTurn({threadId:thread.id,prompt:'Return DIAGNOSTIC_OK only.',requestId:'parent-controller-regression'});
 assert.equal(result.managedBy,'desktop');
 const read=await A.request('thread/read',{threadId:thread.id,includeTurns:true});
 assert.ok(read.thread.turns.some(t=>t.items?.some(i=>i.clientId==='parent-controller-regression')),'must confirm exact clientId');
 assert.equal((await A.request('thread/queue/list',{threadId:thread.id})).data.length,0);
 for(let i=0;i<100&&!A.notifications.some(n=>n.method==='turn/completed'&&n.params?.turn?.id===result.turnId);i++)await delay(100);
 assert.ok(A.notifications.some(n=>n.method==='turn/completed'&&n.params?.turn?.id===result.turnId),'delivered fixture turn must finish');
 console.log(JSON.stringify({pass:true,originalWriterDelivered:true,latencyMs:Date.now()-started,requestIdMatched:true,queuedItems:0}));
} finally {
 await B.stop(); await A.stop(); await new Promise(r=>provider.close(r));
 const target=resolve(root);
 assert.equal(dirname(target),resolve(tmpdir()));
 assert.ok(basename(target).startsWith('rearcue-codex-queue-check-'));
 await rm(target,{recursive:true,force:true,maxRetries:30,retryDelay:100});
}


import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join, resolve, dirname, basename } from 'node:path';
import { tmpdir } from 'node:os';
import { EventEmitter, once } from 'node:events';
import { readSharedRuntime, sharedRuntimeFile, connectSharedCodex } from './codex-shared-transport.mjs';
import { CodexAppServerControl } from './codex-control.mjs';

test('shared runtime config accepts only credentialed loopback endpoints; broken config never becomes stdio', async () => {
  const root=await mkdtemp(join(tmpdir(),'rearcue-transport-'));
  const env={CODEX_HOME:root};
  try {
    const file=sharedRuntimeFile(env);
    assert.equal(readSharedRuntime(env),null);
    const control=new CodexAppServerControl({command:'must-not-be-spawned',env});
    await assert.rejects(control.startConversation({workspace:root,prompt:'fixture'}),/Codex-RearCue/);
    assert.equal(control.child,null,'missing setup must not create another independent writer');
    const valid={version:1,url:'ws://127.0.0.1:12345/'+'a'.repeat(64)};
    await writeFile(file,JSON.stringify(valid));
    assert.equal(readSharedRuntime(env).url,valid.url);
    for(const url of ['ws://0.0.0.0:12345/'+'a'.repeat(64),'wss://127.0.0.1:12345/'+'a'.repeat(64),'ws://127.0.0.1:12345/','ws://127.0.0.1:12345/'+'a'.repeat(64)+'?token=x']) {
      await writeFile(file,JSON.stringify({...valid,url})); assert.throws(()=>readSharedRuntime(env),/协同配置无效/);
    }
    await writeFile(file,'{'); assert.throws(()=>readSharedRuntime(env),/协同配置不可读/);
  } finally { assert.equal(dirname(resolve(root)),resolve(tmpdir()));assert.ok(basename(root).startsWith('rearcue-transport-'));await rm(root,{recursive:true,force:true}); }
});

class FakeSocket extends EventTarget {
  static last;
  constructor(url) { super(); this.url=url; this.frames=[]; this.closed=false; FakeSocket.last=this; }
  send(frame){ this.frames.push(JSON.parse(frame)); }
  close(){ this.closed=true;this.dispatchEvent(new Event('close')); }
}
test('JSONL is framed separately; bridge disconnect closes only its connection', async () => {
  const child=connectSharedCodex('ws://127.0.0.1:12345/'+'a'.repeat(64),{WebSocketClass:FakeSocket});
  const ws=FakeSocket.last;
  child.stdin.write('{"id":1}\n{"id":2}\n');
  assert.equal(ws.frames.length,0);
  ws.dispatchEvent(new Event('open'));
  assert.deepEqual(ws.frames,[{id:1},{id:2}]);
  const next=once(child.stdout,'data');
  ws.dispatchEvent(new MessageEvent('message',{data:'{"id":1,"result":{}}'}));
  assert.equal((await next)[0],'{"id":1,"result":{}}\n');
  const closed=once(child,'exit');child.stdin.end();await closed;assert.ok(ws.closed);
});
test('connection failures do not expose endpoint credentials', async () => {
  const secret='b'.repeat(64);
  const child=connectSharedCodex('ws://127.0.0.1:12345/'+secret,{WebSocketClass:FakeSocket});
  const failed=once(child,'error'); FakeSocket.last.dispatchEvent(new Event('error'));
  const [error]=await failed;assert.ok(!error.message.includes(secret));assert.match(error.message,/协同入口/);
});

class ApprovalChild extends EventEmitter {
  constructor(){
    super();this.stdout=new EventEmitter();this.stderr=new EventEmitter();this.stdout.setEncoding=this.stderr.setEncoding=()=>{};this.requests=[];
    this.stdin={write:raw=>{const request=JSON.parse(raw);this.requests.push(request);if(request.method==='initialize')queueMicrotask(()=>this.message({id:request.id,result:{}}));},end:()=>queueMicrotask(()=>this.emit('exit',0,null))};
  }
  message(m){this.stdout.emit('data',JSON.stringify(m)+'\n');}
}
test('PC resolving an approval removes only that request from the mobile controller', async () => {
  const child=new ApprovalChild();const control=new CodexAppServerControl({command:'fixture',spawnProcess:()=>child});
  await control.start(); control.activeTurns.set('t','turn');
  for(const id of [90,91])child.message({id,method:'item/commandExecution/requestApproval',params:{threadId:'t',turnId:'turn',itemId:'cmd'+id}});
  const resolved=[];control.on('approvalResolved',e=>resolved.push(e));
  child.message({method:'serverRequest/resolved',params:{threadId:'different',requestId:90}});
  assert.equal(control.approvals.size,2);
  child.message({method:'serverRequest/resolved',params:{threadId:'t',requestId:90}});
  assert.equal(control.approvals.has(90),false);assert.equal(control.approvals.has(91),true);assert.equal(resolved.length,1);
  child.message({method:'serverRequest/resolved',params:{threadId:'t',requestId:90}});
  assert.equal(resolved.length,1);assert.equal(control.resolveApproval(90,'approve'),false);
  await control.stop();
});
test('server approval IDs can collide with client request IDs without consuming the request', async () => {
  const child=new ApprovalChild();const control=new CodexAppServerControl({command:'fixture',spawnProcess:()=>child});await control.start();
  const models=control.listModels();await new Promise(r=>setImmediate(r));
  const id=child.requests.find(r=>r.method==='model/list').id;
  child.message({id,method:'item/commandExecution/requestApproval',params:{threadId:'t',turnId:'turn',itemId:'cmd'}});
  assert.ok(control.approvals.has(id));assert.ok(control.pending.has(id));
  child.message({id,result:{data:[{id:'model'}]}});assert.deepEqual(await models,[{id:'model'}]);await control.stop();
});

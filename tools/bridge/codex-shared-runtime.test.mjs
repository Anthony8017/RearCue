import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { createRuntimeGateway } from './codex-shared-runtime.mjs';

test('shared runtime gateway hides the backend token and rejects browser origins and unauthenticated URLs', async () => {
  const seen=[];
  const backend=createServer((req,res)=>{seen.push(req.url);res.writeHead(200).end();});
  backend.on('upgrade',(req,socket)=>{seen.push({path:req.url,auth:req.headers.authorization});socket.end('HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');});
  await new Promise(r=>backend.listen(0,'127.0.0.1',r));
  const capability='a'.repeat(64);const gateway=createRuntimeGateway({backendPort:backend.address().port,token:'backend-test-token',pathToken:capability});
  await new Promise(r=>gateway.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+gateway.address().port;
  try {
    assert.equal((await fetch(base+'/readyz')).status,404);
    assert.equal((await fetch(base+'/'+capability+'/readyz',{headers:{Origin:'http://unrelated.test'}})).status,404);
    assert.equal((await fetch(base+'/'+capability+'/readyz',{headers:{'X-Forwarded-For':'127.0.0.1'}})).status,404);
    assert.equal((await fetch(base+'/'+capability+'/readyz')).status,200);
    const status=await new Promise((resolve,reject)=>{const req=request(base+'/'+capability,{headers:{Connection:'Upgrade',Upgrade:'websocket','Sec-WebSocket-Version':'13','Sec-WebSocket-Key':'dGhlIHNhbXBsZSBub25jZQ==',Authorization:'Bearer unrelated-client-token'}},res=>{res.resume();resolve(res.statusCode);});req.on('error',reject);req.end();});
    assert.equal(status,400);
    assert.deepEqual(seen.at(-1),{path:'/',auth:'Bearer backend-test-token'});
  } finally {gateway.closeClients();await new Promise(r=>gateway.close(r));backend.closeAllConnections();await new Promise(r=>backend.close(r));}
});

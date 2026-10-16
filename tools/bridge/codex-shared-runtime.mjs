// Shared Codex lifetime is independent of RearCueBridge and of individual desktop windows.
import { spawn } from 'node:child_process';
import { createServer, request as httpRequest } from 'node:http';
import { connect } from 'node:net';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { open, mkdir, readFile, writeFile, rename, unlink } from 'node:fs/promises';
import { openSync, closeSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolveCodexCommand } from './adapters/codex-control.mjs';
import { sharedRuntimeFile, readSharedRuntime } from './adapters/codex-shared-transport.mjs';

const delay = ms => new Promise(r => setTimeout(r, ms));
const alive = pid => { try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; } };

export async function runtimeReady(config) {
  try {
    const url = new URL(config.url); url.protocol = 'http:'; url.pathname += '/readyz';
    return (await fetch(url, { signal: AbortSignal.timeout(1000) })).ok;
  } catch { return false; }
}

/** The URL is an on-disk capability. Never print it or expose the listener through the phone tunnel. */
export function createRuntimeGateway({ backendPort, token, pathToken }) {
  const expected = Buffer.from('/' + pathToken);
  const allowed = req => {
    const path = Buffer.from(req.url || '');
    return req.socket.remoteAddress === '127.0.0.1' && !req.headers.origin &&
      !req.headers.forwarded && !req.headers['x-forwarded-for'] &&
      path.length === expected.length && timingSafeEqual(path, expected);
  };
  const sockets = new Set();
  const server = createServer((req, res) => {
    const health = req.url?.endsWith('/readyz');
    if (health) req.url = req.url.slice(0, -'/readyz'.length);
    if (req.method !== 'GET' || !health || !allowed(req)) { res.writeHead(404).end(); return; }
    const probe = httpRequest({ hostname:'127.0.0.1', port:backendPort, path:'/readyz', timeout:1000 }, upstream => {
      upstream.resume(); res.writeHead(upstream.statusCode === 200 ? 200 : 503).end();
    });
    probe.on('error', () => res.writeHead(503).end()); probe.on('timeout', () => probe.destroy()); probe.end();
  });
  server.on('upgrade', (req, socket, head) => {
    if (!allowed(req) || req.headers['sec-websocket-version'] !== '13') {
      socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); return;
    }
    const upstream = connect({host:'127.0.0.1',port:backendPort});
    sockets.add(socket); sockets.add(upstream);
    socket.on('error', () => upstream.destroy()); upstream.on('error', () => socket.destroy());
    socket.on('close', () => { sockets.delete(socket); upstream.destroy(); });
    upstream.on('close', () => { sockets.delete(upstream); socket.destroy(); });
    upstream.once('connect', () => {
      const headers = [];
      for (let i=0;i<req.rawHeaders.length;i+=2) {
        if (!['host','authorization','proxy-authorization'].includes(req.rawHeaders[i].toLowerCase())) headers.push(`${req.rawHeaders[i]}: ${req.rawHeaders[i+1]}`);
      }
      upstream.write(`GET / HTTP/1.1\r\nHost: 127.0.0.1:${backendPort}\r\nAuthorization: Bearer ${token}\r\n${headers.join('\r\n')}\r\n\r\n`);
      if (head.length) upstream.write(head);
      socket.pipe(upstream); upstream.pipe(socket);
    });
  });
  server.closeClients = () => { for (const socket of sockets) socket.destroy(); server.closeAllConnections(); };
  return server;
}

async function freePort() {
  const server = createServer(); await new Promise((r,j) => { server.once('error',j); server.listen(0,'127.0.0.1',r); });
  const port = server.address().port; await new Promise(r=>server.close(r)); return port;
}

export async function serveSharedRuntime(env = process.env) {
  const file = sharedRuntimeFile(env), lockFile = file + '.lock', tokenFile = file + '.token';
  await mkdir(dirname(file), {recursive:true});
  let lock;
  try { lock = await open(lockFile,'wx',0o600); }
  catch (e) {
    if (e.code !== 'EEXIST') throw e;
    const owner = JSON.parse(await readFile(lockFile,'utf8'));
    if (!Number.isInteger(owner.pid) || owner.pid < 1 || alive(owner.pid)) throw new Error('Codex 协同进程正在运行或启动，请稍后重试。');
    await unlink(lockFile); lock = await open(lockFile,'wx',0o600);
  }
  await lock.writeFile(JSON.stringify({pid:process.pid})); await lock.close();
  let child, gateway, config;
  try {
    const token = randomBytes(32).toString('hex'), pathToken = randomBytes(32).toString('hex');
    await writeFile(tokenFile,token,{mode:0o600});
    const backendPort = await freePort();
    const coreEnv = {...env}; delete coreEnv.CODEX_APP_SERVER_WS_URL;
    child = spawn(resolveCodexCommand(env), [
      '-c','features.code_mode_host=true','-c','features.prefer_mxc=true',
      'app-server','--listen',`ws://127.0.0.1:${backendPort}`,'--ws-auth','capability-token','--ws-token-file',tokenFile,
    ], {env:coreEnv,windowsHide:true,stdio:['ignore','ignore','pipe']});
    let exited = false, spawnError = false;
    const closed = new Promise(r => { child.once('error',()=>{spawnError=true;exited=true;r();}); child.once('close',()=>{exited=true;r();}); });
    child.stderr.on('data', () => {}); // No request bodies or credentials in the launcher log.
    let ready = false;
    for (let i=0;i<150&&!exited;i++) {
      try { ready = (await fetch(`http://127.0.0.1:${backendPort}/readyz`,{signal:AbortSignal.timeout(1000)})).ok; } catch {}
      if (ready) break; await delay(100);
    }
    if (!ready) throw new Error(spawnError ? 'Codex 协同进程启动失败。' : 'Codex 协同进程未就绪。');
    gateway = createRuntimeGateway({backendPort,token,pathToken});
    await new Promise((r,j)=>{gateway.once('error',j);gateway.listen(0,'127.0.0.1',r);});
    config = {version:1,pid:process.pid,corePid:child.pid,url:`ws://127.0.0.1:${gateway.address().port}/${pathToken}`,createdAt:Date.now()};
    const temporary = file + `.${process.pid}.tmp`;
    await writeFile(temporary,JSON.stringify(config),{mode:0o600}); await rename(temporary,file);
    const terminate = () => child.kill();
    process.once('SIGTERM',terminate); process.once('SIGINT',terminate);
    await closed;
    process.off('SIGTERM',terminate); process.off('SIGINT',terminate);
  } finally {
    gateway?.closeClients(); if (gateway?.listening) await new Promise(r=>gateway.close(r));
    if (child && child.exitCode === null) child.kill();
    // Keep the configured endpoint after exit. A stale endpoint must fail closed,
    // rather than letting an already configured bridge fall back to another writer.
    await unlink(lockFile); await unlink(tokenFile).catch(e=>{if(e.code!=='ENOENT')throw e;});
  }
}

export async function ensureSharedRuntime(env = process.env) {
  const current = readSharedRuntime(env);
  if (current && await runtimeReady(current)) return current;
  if (current && alive(current.pid)) throw new Error('Codex 协同进程暂不可用；未重启正在运行的电脑任务。');
  const file = sharedRuntimeFile(env); await mkdir(dirname(file),{recursive:true});
  const log = openSync(file + '.log','a',0o600);
  const child = spawn(process.execPath,[fileURLToPath(import.meta.url),'--serve'],{env,windowsHide:true,detached:true,stdio:['ignore',log,log]});
  child.on('error',()=>{}); child.unref(); closeSync(log);
  for (let i=0;i<200;i++) {
    const config = readSharedRuntime(env);
    if (config && await runtimeReady(config)) return config;
    await delay(100);
  }
  throw new Error('Codex 协同进程启动超时，请查看协同运行日志。');
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    if (process.argv[2] === '--serve') await serveSharedRuntime();
    else if (process.argv[2] === '--ensure') { await ensureSharedRuntime(); console.log('Codex shared runtime ready.'); }
    else throw new Error('Usage: node codex-shared-runtime.mjs --ensure');
  } catch (error) { console.error(error.message); process.exitCode=1; }
}

import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export function sharedRuntimeFile(env = process.env) {
  return env.BRIDGE_CODEX_RUNTIME_FILE || join(env.CODEX_HOME || join(homedir(), '.codex'), 'rearcue-shared-runtime.json');
}

export function readSharedRuntime(env = process.env) {
  let config;
  try { config = JSON.parse(readFileSync(sharedRuntimeFile(env), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw new Error('Codex 协同配置不可读，请从 Codex 协同入口重新启动。'); }
  let url;
  try { url = new URL(config.url); } catch { /* invalid config is never a stdio fallback */ }
  if (config.version !== 1 || !url || url.protocol !== 'ws:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.username || url.password || url.search || url.hash || !/^\/[a-f0-9]{64}$/.test(url.pathname)) {
    throw new Error('Codex 协同配置无效，请从 Codex 协同入口重新启动。');
  }
  return { ...config, url: url.href };
}

/** Child-compatible transport. Closing this client never stops the shared Codex server. */
export function connectSharedCodex(url, { WebSocketClass = WebSocket, timeoutMs = 10_000 } = {}) {
  const child = new EventEmitter();
  child.stdout = new EventEmitter(); child.stderr = new EventEmitter();
  child.stdout.setEncoding = child.stderr.setEncoding = () => {};
  const ws = new WebSocketClass(url);
  let open = false, ended = false, finished = false, buffer = '';
  const writes = [];
  const timer = setTimeout(() => fail(), timeoutMs); timer.unref?.();
  const finish = () => {
    if (finished) return;
    finished = true; clearTimeout(timer);
    for (const entry of writes.splice(0)) entry.callback?.(new Error('Codex 协同连接已关闭。'));
    child.emit('exit', 0, null); child.emit('close', 0, null);
  };
  const fail = () => {
    if (finished) return;
    if (ended) { finish(); return; }
    // WebSocket errors can contain the credential-bearing URL. Never forward them.
    child.emit('error', new Error('无法连接 Codex 协同进程，请从 Codex 协同入口启动电脑应用。'));
    try { ws.close(); } catch { /* already closed */ }
    finish();
  };
  const write = (payload, callback) => {
    if (finished || ended) { callback?.(new Error('Codex 协同连接已关闭。')); return; }
    if (!open) { writes.push({ payload, callback }); return; }
    buffer += String(payload);
    let at;
    try {
      while ((at = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, at); buffer = buffer.slice(at + 1);
        if (line.trim()) ws.send(line);
      }
      callback?.();
    } catch { callback?.(new Error('Codex 协同发送失败。')); fail(); }
  };
  child.stdin = { write, end() { ended = true; clearTimeout(timer); ws.close(); } };
  child.kill = () => { child.stdin.end(); return true; };
  ws.addEventListener('open', () => {
    clearTimeout(timer); open = true;
    if (ended) { ws.close(); return; }
    for (const entry of writes.splice(0)) write(entry.payload, entry.callback);
  });
  ws.addEventListener('message', event => child.stdout.emit('data', String(event.data) + '\n'));
  ws.addEventListener('error', fail);
  ws.addEventListener('close', finish);
  return child;
}

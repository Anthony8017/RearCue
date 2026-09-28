// E1 relay connectivity spike — spec 0010 ticket #79.
// Zero-dependency (node builtin tls/crypto/dns only). Direct connection, no proxy.
//
// What it does:
//   1. DNS -> TCP 443 -> TLS 1.2/1.3 handshake -> HTTP Upgrade (RFC6455) to wss://<host>/ws
//   2. Sends device_register_init with throwaway credentials (random device_mid + random pass_hash)
//   3. Follows the reversed handshake: register_ack -> auth_init -> auth_challenge -> auth_response
//   4. Records every frame (JSONL, hex prefixes) and every log line (stdout) for poc-logs evidence
//
// GREEN criterion (ticket #79): any protocol-internal response — device_register_ack /
// auth_challenge / error{code,message} frame / semantic close code in the documented
// 4004/4009/4010/4011/4012/4013 set. Exit code 0 = GREEN, 2 = RED.
//
// Usage:
//   node tools/ex/e1-relay-spike.mjs --host zcode.z.ai \
//        --capture-dir docs/poc-logs/20260927-e1-captures --label zai
// Options:
//   --host <name>         relay host (default zcode.z.ai)
//   --path <path>         WS path (default /ws)
//   --role <role>         register/auth role string (default mobile)
//   --hash <base64url>    pass_hash to present (default: random 32 bytes)
//   --capture-dir <dir>   where to write handshake/frames JSONL (default: off)
//   --label <name>        filename label for captures (default: host)
//   --dwell-ms <n>        keep socket open after handshake result (default 5000)

import tls from "node:tls";
import dns from "node:dns";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

// ---------- args ----------
const argv = process.argv.slice(2);
function argOf(name, fallback) {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback;
}
const HOST = argOf("host", "zcode.z.ai");
const WS_PATH = argOf("path", "/ws");
const ROLE = argOf("role", "mobile");
const PASS_HASH = argOf("hash", crypto.randomBytes(32).toString("base64url"));
const CAPTURE_DIR = argOf("capture-dir", null);
const LABEL = argOf("label", HOST.replace(/[^a-z0-9.-]/gi, "_"));
const DWELL_MS = Number(argOf("dwell-ms", "5000"));
const WAIT_CHALLENGE = argv.includes("--wait-challenge"); // skip auth_init, wait for server-initiated challenge
const NO_META = argv.includes("--no-meta"); // omit meta from auth_init
const NO_DEVICE_ID_HEADER = argv.includes("--no-device-id-header"); // omit X-Device-ID on upgrade
const SKIP_REGISTER = argv.includes("--skip-register"); // terminal path: no register, auth_init directly
const PROBE = argv.includes("--probe"); // 票 #86 phase B：matched 后发控制面序列抓真实响应
const V4 = argv.includes("--v4"); // 票 #88：matched 后走 workspace-bridge → V4 二进制通道订阅（回复原文/pendingApproval 真数据）
const HEARTBEAT = argv.includes("--heartbeat"); // 票 #88 顺带：waiting 期每 10s 发 pair_status_query（A/B 实验：观测 relay 是否掐断）
const SID = argOf("sid", null); // device_sid to present in auth_init (terminal path)
const MID = argOf("mid", null); // X-Device-ID 覆盖（QR mid；随机 mid 疑似导致 waiting 永不 matched——票 #88 实验）
const DEADLINE_MS = argOf("deadline-ms", null); // 绝对 deadline 覆盖（票 #88：短窗重连重试实验）
const APP_VERSION = argOf("app-version", "0.16.9");

// Documented close-code table (reversed from zcode.cjs WebRemoteControlCloseCodes).
const SEMANTIC_CLOSE = {
  4004: "SessionNotFound",
  4009: "SessionConflict",
  4010: "DesktopDisconnected",
  4011: "SessionExpired",
  4012: "WorkspaceClosed",
  4013: "InvalidMobileConnection",
};

const DEVICE_MID = MID ?? crypto.randomUUID();
const t0 = Date.now();
let protocolInternalSeen = null; // string describing first protocol-internal response
let captureStream = null;
let captureFile = null;
let finishHook = null; // 票 #88：v4 流程完成后的提前收口（不等绝对 deadline）

function log(msg) {
  const rel = String(Date.now() - t0).padStart(6, " ");
  console.log(`[${rel}ms] ${msg}`);
}
function capture(obj) {
  if (!captureStream) return;
  captureStream.write(JSON.stringify({ ts: Date.now() - t0, ...obj }) + "\n");
}

function openCapture() {
  if (!CAPTURE_DIR) return;
  fs.mkdirSync(CAPTURE_DIR, { recursive: true });
  captureFile = path.join(CAPTURE_DIR, `${nowStamp()}-e1-frames-${LABEL}.jsonl`);
  captureStream = fs.createWriteStream(captureFile, { flags: "w" });
  log(`capture -> ${captureFile}`);
}
function nowStamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

// ---------- websocket client (RFC6455, client-side masking, text only) ----------
class WsClient {
  constructor(socket) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.onText = null;
    this.onClose = null;
    this.closed = false;
    socket.on("data", (d) => this._feed(d));
    socket.on("error", (e) => this._terminate(`socket error: ${e.message}`));
    socket.on("close", () => { if (!this.closed) this._terminate("socket closed by peer (no close frame)"); });
  }

  _feed(d) {
    this.buf = Buffer.concat([this.buf, d]);
    // drain frames
    for (;;) {
      const f = this._parseFrame();
      if (!f) break;
      this._handleFrame(f);
      if (this.closed) break;
    }
  }

  // returns null when more data needed
  _parseFrame() {
    const b = this.buf;
    if (b.length < 2) return null;
    const fin = (b[0] & 0x80) !== 0;
    const opcode = b[0] & 0x0f;
    const masked = (b[1] & 0x80) !== 0;
    let len = b[1] & 0x7f;
    let off = 2;
    if (len === 126) {
      if (b.length < 4) return null;
      len = b.readUInt16BE(2); off = 4;
    } else if (len === 127) {
      if (b.length < 10) return null;
      const big = b.readBigUInt64BE(2);
      if (big > 64n * 1024n * 1024n) return this._terminate(`frame too large: ${big}`);
      len = Number(big); off = 10;
    }
    if (len > 64 * 1024 * 1024) return this._terminate(`frame too large: ${len}`);
    let maskKey = null;
    if (masked) {
      if (b.length < off + 4) return null;
      maskKey = b.subarray(off, off + 4); off += 4;
    }
    if (b.length < off + len) return null;
    let payload = Buffer.from(b.subarray(off, off + len));
    if (maskKey) for (let i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
    this.buf = b.subarray(off + len);
    return { fin, opcode, payload };
  }

  _handleFrame(f) {
    if (f.opcode === 0x9) { // ping -> pong
      capture({ dir: "S", kind: "ping", hex: hexOf(f.payload) });
      log(`<- ping (${f.payload.length}B) -> pong`);
      this._sendRaw(0xA, f.payload);
      return;
    }
    if (f.opcode === 0xA) {
      capture({ dir: "S", kind: "pong", hex: hexOf(f.payload) });
      return;
    }
    if (f.opcode === 0x8) { // close
      let code = null, reason = "";
      if (f.payload.length >= 2) {
        code = f.payload.readUInt16BE(0);
        reason = f.payload.subarray(2).toString("utf8");
      }
      capture({ dir: "S", kind: "close", code, reason, hex: hexOf(f.payload) });
      const semantic = SEMANTIC_CLOSE[code];
      log(`<- CLOSE code=${code}${semantic ? ` (${semantic})` : ""} reason="${reason}"`);
      if (semantic && !protocolInternalSeen) protocolInternalSeen = `semantic close ${code} ${semantic}`;
      this._terminate(`peer close ${code}`);
      return;
    }
    if (f.opcode === 0x1 || f.opcode === 0x2) {
      const kind = f.opcode === 0x1 ? "text" : "binary";
      capture({ dir: "S", kind, hex: hexOf(f.payload), utf8: utf8Of(f.payload) });
      if (!f.fin) log(`<- ${kind} frame (fragmented, continuation unhandled; logged only)`);
      if (f.opcode === 0x1 && this.onText) this.onText(f.payload.toString("utf8"));
      else log(`<- binary frame ${f.payload.length}B`);
      return;
    }
    log(`<- unknown opcode 0x${f.opcode.toString(16)} (${f.payload.length}B)`);
  }

  sendText(str) {
    const payload = Buffer.from(str, "utf8");
    capture({ dir: "C", kind: "text", hex: hexOf(payload), utf8: utf8Of(payload) });
    this._sendRaw(0x1, payload);
  }

  sendClose(code = 1000) {
    const p = Buffer.alloc(2);
    p.writeUInt16BE(code, 0);
    try { this._sendRaw(0x8, p); } catch { /* already gone */ }
  }

  _sendRaw(opcode, payload) {
    const mask = crypto.randomBytes(4);
    const masked = Buffer.from(payload);
    for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    let header;
    if (payload.length < 126) {
      header = Buffer.from([0x80 | opcode, 0x80 | payload.length]);
    } else if (payload.length < 65536) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode; header[1] = 0x80 | 126;
      header.writeUInt16BE(payload.length, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode; header[1] = 0x80 | 127;
      header.writeBigUInt64BE(BigInt(payload.length), 2);
    }
    this.socket.write(Buffer.concat([header, mask, masked]));
  }

  _terminate(why) {
    if (this.closed) return;
    this.closed = true;
    log(`WS end: ${why}`);
    try { this.socket.destroy(); } catch { /* ignore */ }
    if (this.onClose) this.onClose(why);
  }
}

function hexOf(buf) { return buf.subarray(0, 96).toString("hex") + (buf.length > 96 ? "…" : ""); }
function utf8Of(buf) {
  const s = buf.subarray(0, 4096).toString("utf8");
  return buf.length > 4096 ? s + "…(truncated)" : s;
}

// ---------- protocol helpers (reversed shapes) ----------
function nowMs() { return Date.now(); }
function meta() {
  return { platform: "web", version: APP_VERSION, name: "mobile-browser" };
}
function proofFor(passHash, nonce, role, deviceSid) {
  return crypto.createHmac("sha256", Buffer.from(passHash))
    .update(`${nonce}|${role}|${deviceSid}`)
    .digest("base64url");
}

// ---------- V4 bridge (ticket #88): rpc-frame assembly + VSCode-style channel codec ----------
// 逆向来源：webjs/index.js (ju/Bu/zu/Rn), asar host chunk-UHHNTW2R (ChannelServer), 主进程 routePayload。
// 逻辑消息 = rpc-frame dataBase64 解出的原始字节（uh/Kee：messageBytes=e.byteLength，无 JSON 外壳）。
const V4STATE = {
  bridge: null, // {bridgeSessionId, bridgeGeneration, workspacePath, initialTaskId}
  workspaceKey: null,
  activeTaskId: null,
  taskList: null,
  chanInit: false,
  listenId: null,
  subscribeAck: null,
  frames: 0,
  lastAssistantText: null,
  pendingApproval: null,
  reqId: 0,
  waiters: new Map(), // id -> {label, resolve, timer}
  physSeq: 1,
  msgSeq: 1,
  assemblies: new Map(), // messageSeq -> assembly
  started: false,
  seen201: false,
};

function crc32hex(buf) {
  return (zlib.crc32(buf) >>> 0).toString(16).padStart(8, "0");
}

// ---- VQL value encoding (VSCode rpc wire: tag byte + payload) ----
function vqlVarint(n) {
  const out = [];
  do {
    let b = n % 128;
    n = Math.floor(n / 128);
    if (n > 0) b |= 0x80;
    out.push(b);
  } while (n > 0);
  return Buffer.from(out);
}

function vqlEncodeValue(v) {
  if (v === undefined || v === null) return Buffer.from([0]);
  if (typeof v === "string") {
    const b = Buffer.from(v, "utf8");
    return Buffer.concat([Buffer.from([1]), vqlVarint(b.length), b]);
  }
  if (Buffer.isBuffer(v) || v instanceof Uint8Array) {
    const b = Buffer.from(v);
    return Buffer.concat([Buffer.from([2]), vqlVarint(b.length), b]);
  }
  if (Array.isArray(v)) {
    const parts = [Buffer.from([4]), vqlVarint(v.length)];
    for (const x of v) parts.push(vqlEncodeValue(x));
    return Buffer.concat(parts);
  }
  if (typeof v === "number" && Number.isInteger(v)) {
    return Buffer.concat([Buffer.from([6]), vqlVarint(v)]);
  }
  const b = Buffer.from(JSON.stringify(v), "utf8");
  return Buffer.concat([Buffer.from([5]), vqlVarint(b.length), b]);
}

function vqlEncodeMessage(values) {
  return Buffer.concat(values.map(vqlEncodeValue));
}

function vqlDecodeOne(buf, start) {
  let i = start;
  const readVarint = () => {
    let r = 0, s = 0, b;
    do {
      if (i >= buf.length) throw new Error("vql eof");
      b = buf[i++];
      r += (b & 0x7f) * Math.pow(2, s);
      s += 7;
    } while (b & 0x80);
    return r;
  };
  const tag = buf[i++];
  if (tag === 0) return [undefined, i];
  if (tag === 1) { const n = readVarint(); return [buf.subarray(i, i + n).toString("utf8"), i + n]; }
  if (tag === 2 || tag === 3) { const n = readVarint(); return [Buffer.from(buf.subarray(i, i + n)), i + n]; }
  if (tag === 4) {
    const n = readVarint();
    const a = [];
    for (let k = 0; k < n; k++) {
      const [v, j] = vqlDecodeOne(buf, i);
      a.push(v);
      i = j;
    }
    return [a, i];
  }
  if (tag === 5) {
    const n = readVarint();
    const s = buf.subarray(i, i + n).toString("utf8");
    i += n;
    try { return [JSON.parse(s), i]; } catch { return [{ __rawJson: s }, i]; }
  }
  if (tag === 6) return [readVarint(), i];
  throw new Error(`vql bad tag ${tag} @${start}`);
}

function vqlDecodeMessage(buf) {
  const vals = [];
  let i = 0;
  while (i < buf.length) {
    const [v, j] = vqlDecodeOne(buf, i);
    vals.push(v);
    i = j;
  }
  return vals;
}

function vqlSelfTest() {
  const msg = vqlEncodeMessage([[100, 3, "zcode-agent", "subscribeConversationV4"], [{ workspacePath: "/x", sessionId: "t1" }]]);
  const vals = vqlDecodeMessage(msg);
  const ok = JSON.stringify(vals[0]) === JSON.stringify([100, 3, "zcode-agent", "subscribeConversationV4"])
    && JSON.stringify(vals[1]) === JSON.stringify([{ workspacePath: "/x", sessionId: "t1" }]);
  if (!ok) {
    log(`VQL SELFTEST FAIL: ${JSON.stringify(vals).slice(0, 300)}`);
    process.exit(3);
  }
  log("VQL selftest ok");
}

// ---- channel message builders (ChannelServer: onRawMessage = header array + arg value) ----
function chanSend(ws, type, channelName, name, arg) {
  const id = V4STATE.reqId++;
  const bytes = vqlEncodeMessage([[type, id, channelName, name], arg]);
  sendChannelFrame(ws, bytes);
  return id;
}

function chanAwait(id, label, timeoutMs) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      V4STATE.waiters.delete(id);
      resolve({ __timeout: label });
    }, timeoutMs);
    V4STATE.waiters.set(id, { label, resolve: (v) => { clearTimeout(timer); resolve(v); }, timer });
  });
}

// ---- outbound rpc-frame (physical envelope; identity must match bridge-open) ----
function sendChannelFrame(ws, bytes) {
  const maxPayload = 700000; // 保持 envelope < maxPhysicalFrameBytes(1MiB)
  const count = Math.max(1, Math.ceil(bytes.length / maxPayload));
  const crc = crc32hex(bytes);
  const bridge = V4STATE.bridge;
  for (let idx = 0; idx < count; idx++) {
    const slice = bytes.subarray(idx * maxPayload, Math.min((idx + 1) * maxPayload, bytes.length));
    const frame = {
      zcode_type: "rpc-frame",
      bridgeSessionId: bridge.bridgeSessionId,
      bridgeGeneration: bridge.bridgeGeneration ?? 0,
      seq: V4STATE.physSeq++,
      messageSeq: V4STATE.msgSeq,
      fragmentIndex: idx,
      fragmentCount: count,
      messageBytes: bytes.length,
      checksum: { algorithm: "crc32", value: crc },
      dataBase64: slice.toString("base64"),
    };
    ws.sendText(JSON.stringify({ type: "data", payload: frame, client_ts: Date.now() }));
  }
  V4STATE.msgSeq++;
  capture({ dir: "C", kind: "chan-send", hex: bytes.subarray(0, 96).toString("hex"), byteLength: bytes.length });
}

// ---- inbound rpc-frame assembly → ack → logical bytes ----
function handleRpcFrame(ws, p) {
  const key = p.messageSeq;
  let a = V4STATE.assemblies.get(key);
  if (!a) {
    a = {
      count: p.fragmentCount,
      parts: new Map(),
      messageBytes: p.messageBytes,
      checksum: p.checksum,
      bridgeSessionId: p.bridgeSessionId,
    };
    V4STATE.assemblies.set(key, a);
  }
  const part = Buffer.from(p.dataBase64 ?? "", "base64");
  a.parts.set(p.fragmentIndex, part);
  if (a.parts.size < a.count) return null;
  V4STATE.assemblies.delete(key);
  const bufs = [];
  for (let i = 0; i < a.count; i++) {
    const piece = a.parts.get(i);
    if (!piece) return null;
    bufs.push(piece);
  }
  const merged = Buffer.concat(bufs);
  if (merged.length !== a.messageBytes) {
    log(`rpc-frame length mismatch seq=${key} got=${merged.length} want=${a.messageBytes}`);
    return null;
  }
  const crc = crc32hex(merged);
  if (a.checksum && a.checksum.value && String(a.checksum.value) !== crc) {
    log(`rpc-frame crc mismatch seq=${key} got=${crc} want=${a.checksum.value}`);
    return null;
  }
  ws.sendText(JSON.stringify({
    type: "data",
    payload: { zcode_type: "rpc-frame-ack", bridgeSessionId: a.bridgeSessionId, ackMessageSeq: key },
    client_ts: Date.now(),
  }));
  capture({ dir: "S", kind: "chan-recv", hex: merged.subarray(0, 96).toString("hex"), byteLength: merged.length });
  return merged;
}

// ---- inbound logical channel message dispatch ----
function jbrief(v, n) {
  let s;
  try { s = v === undefined ? "undefined" : JSON.stringify(v); } catch { s = String(v); }
  return (s ?? "undefined").slice(0, n);
}

function chanDispatch(bytes) {
  let vals;
  try {
    vals = vqlDecodeMessage(bytes);
  } catch (e) {
    log(`chan decode fail: ${e.message} hex=${bytes.subarray(0, 48).toString("hex")}`);
    return;
  }
  const header = vals[0];
  const data = vals[1];
  if (!Array.isArray(header)) {
    log(`chan bad header: ${JSON.stringify(vals).slice(0, 300)}`);
    return;
  }
  const [type, id] = header;
  switch (type) {
    case 200:
      V4STATE.chanInit = true;
      log("chan <- Initialize (200)");
      break;
    case 201:
      V4STATE.seen201 = true;
      log(`chan <- Success id=${id} ${jbrief(data, 400)}`);
      V4STATE.waiters.get(id)?.resolve(data);
      break;
    case 202:
    case 203:
      log(`chan <- Error type=${type} id=${id} ${jbrief(data, 500)}`);
      V4STATE.waiters.get(id)?.resolve({ __error: data });
      break;
    case 204: {
      V4STATE.frames++;
      log(`chan <- EventFire id=${id} frame#=${V4STATE.frames} ${jbrief(data, 300)}`);
      inspectFrame(data);
      V4STATE.waiters.get(id)?.resolve(data);
      break;
    }
    default:
      log(`chan <- type=${type} id=${id} ${jbrief(data, 300)}`);
  }
}

// ---- frame inspection: latest assistantText / pendingApproval (ticket #88 验收目标) ----
function inspectFrame(frame) {
  const payload = frame?.payload;
  if (!payload) return;
  const rows = [];
  if (payload.kind === "snapshot" && payload.snapshot?.rows?.window) {
    rows.push(...payload.snapshot.rows.window);
  } else if (payload.kind === "deltas" && Array.isArray(payload.deltas)) {
    for (const d of payload.deltas) {
      if (d.op === "row.appended" || d.op === "row.upserted") rows.push(d.row);
      // row.delta (text 增量) 不整行替换——由后续 upsert/close 收口
    }
  }
  for (const row of rows) {
    if (row?.kind === "assistantText") {
      V4STATE.lastAssistantText = { rowId: row.rowId, state: row.state, text: (row.text || "").slice(0, 400) };
      log(`   row assistantText#${row.rowId} state=${row.state} "${(row.text || "").slice(0, 120)}"`);
    } else if (row?.kind === "toolCall") {
      if (row.status === "pendingApproval") {
        V4STATE.pendingApproval = { rowId: row.rowId, toolName: row.toolName, approvalInteractionId: row.approvalInteractionId };
        log(`   row toolCall#${row.rowId} pendingApproval tool=${row.toolName}`);
      } else if (row.status === "running" || row.status === "inputStreaming") {
        log(`   row toolCall#${row.rowId} ${row.status} tool=${row.toolName}`);
      }
    }
  }
}

// ---- v4 flow: hello → initialize → onDynamicConversationFrame → subscribeConversationV4 ----
async function v4Flow(ws) {
  if (V4STATE.started) return;
  V4STATE.started = true;
  log("v4 flow start (hello → initialize → eventListen → subscribe)");
  for (let i = 0; i < 40 && !V4STATE.chanInit; i++) await new Promise((r) => setTimeout(r, 250));
  if (!V4STATE.chanInit) log("v4: no Initialize received in 10s — proceeding anyway");

  const helloId = chanSend(ws, 100, "zcode-agent", "helloConversationV4", []);
  const hello = await chanAwait(helloId, "hello", 15000);
  if (hello?.__error || hello?.__timeout) {
    log(`v4 hello failed: ${JSON.stringify(hello)}`);
    return;
  }
  const clientKind = hello?.clientMode === "desktop-continuous" ? "desktop" : "web";
  const initId = chanSend(ws, 100, "zcode-agent", "initializeConversationV4", [{
    kind: "clientHello",
    protocolVersion: 3,
    clientId: crypto.randomUUID(),
    clientKind,
    appVersion: "unknown",
    capabilities: { workspaceHookReviewUi: true },
  }]);
  const initRes = await chanAwait(initId, "initialize", 15000);
  if (initRes?.__error || initRes?.__timeout) {
    log(`v4 initialize failed: ${JSON.stringify(initRes)}`);
    return;
  }

  const ctx = { workspacePath: V4STATE.bridge.workspacePath };
  if (V4STATE.bridge.workspaceIdentity) ctx.workspaceIdentity = V4STATE.bridge.workspaceIdentity;
  V4STATE.listenId = chanSend(ws, 102, "zcode-agent", "onDynamicConversationFrame", ctx);

  const sessionId = V4STATE.activeTaskId;
  if (!sessionId) {
    log("v4: no taskId available — skip subscribe");
    return;
  }
  const subId = chanSend(ws, 100, "zcode-agent", "subscribeConversationV4", [{ ...ctx, sessionId }]);
  const sub = await chanAwait(subId, "subscribe", 20000);
  V4STATE.subscribeAck = sub;
  log(`v4 subscribe ack: ${JSON.stringify(sub).slice(0, 600)}`);
  if (sub?.__error || sub?.__timeout) {
    log(`v4 subscribe FAILED: ${JSON.stringify(sub)}`);
    return;
  }
  log("v4 SUBSCRIBE OK — waiting for frames (snapshot/deltas)");
  // 订阅成功后给 20s 抓 snapshot/delta 帧，然后提前收口（不必等绝对 deadline）。
  setTimeout(() => finishHook?.(), 20000);
}

// ---------- main ----------
const socketWaiters = [];
function fail(msg) {
  log(`FAIL: ${msg}`);
  console.log(`\nVERDICT: RED (${msg})`);
  process.exit(2);
}

function run() {
  openCapture();
  if (V4) vqlSelfTest();
  log(`E1 spike host=${HOST} path=${WS_PATH} role=${ROLE} device_mid=${DEVICE_MID}`);
  log(`pass_hash=<random,${PASS_HASH.length} chars, not printed>`);

  // stage 1: DNS
  const dnsT = nowMs();
  dns.lookup(HOST, { family: 0 }, (err, addr) => {
    if (err) fail(`DNS lookup failed: ${err.message}`);
    log(`DNS ${HOST} -> ${addr} (${nowMs() - dnsT}ms)`);
    connectTLS(addr);
  });
}

function connectTLS(addr) {
  const tcpT = nowMs();
  const socket = tls.connect(
    { host: HOST, port: 443, servername: HOST, rejectUnauthorized: true },
    () => {
      const cert = socket.getPeerCertificate();
      log(`TLS established (${nowMs() - tcpT}ms incl. TCP) proto=${socket.getProtocol()} cipher=${socket.getCipher()?.name}`);
      log(`TLS cert subject=${JSON.stringify(cert.subject)} issuer=${JSON.stringify(cert.issuer)} valid_to=${cert.valid_to}`);
      upgrade(socket);
    }
  );
  socket.setTimeout(20000, () => fail(`connect/upgrade timeout (20s)`));
  socket.on("error", (e) => {
    if (!socketWaiters.done) fail(`TLS error: ${e.message}`);
  });
}

function upgrade(socket) {
  const key = crypto.randomBytes(16).toString("base64");
  const req =
    `GET ${WS_PATH} HTTP/1.1\r\n` +
    `Host: ${HOST}\r\n` +
    `Upgrade: websocket\r\n` +
    `Connection: Upgrade\r\n` +
    `Sec-WebSocket-Key: ${key}\r\n` +
    `Sec-WebSocket-Version: 13\r\n` +
    (NO_DEVICE_ID_HEADER ? "" : `X-Device-ID: ${DEVICE_MID}\r\n`) +
    `User-Agent: RearCue-E1-Spike/0.1\r\n` +
    `\r\n`;
  if (captureStream) {
    fs.existsSync(captureFile) && null;
    capture({ dir: "C", kind: "http-upgrade", utf8: req.replace(key, "<SEC-KEY>") });
  }
  const upT = nowMs();
  let head = Buffer.alloc(0);
  const onHead = (d) => {
    head = Buffer.concat([head, d]);
    const idx = head.indexOf("\r\n\r\n");
    if (idx < 0) return;
    socket.removeListener("data", onHead);
    const respText = head.subarray(0, idx + 4).toString("utf8");
    if (captureStream) capture({ dir: "S", kind: "http-upgrade-response", utf8: respText });
    const statusLine = respText.split("\r\n")[0];
    log(`HTTP upgrade (${nowMs() - upT}ms): ${statusLine}`);
    if (!statusLine.includes("101")) {
      fail(`WS upgrade rejected: ${statusLine}; headers:\n${respText}`);
      return;
    }
    log(`WS upgrade OK (101 Switching Protocols)`);
    startProtocol(socket, head.subarray(idx + 4));
  };
  socket.on("data", onHead);
  socket.write(req);
}

function startProtocol(socket, leftover) {
  const ws = new WsClient(socket);
  if (leftover.length) ws._feed(leftover);
  let deviceSid = null;
  let stage = "registering";

  ws.onText = (text) => {
    let msg;
    try { msg = JSON.parse(text); } catch {
      log(`<- non-JSON text frame: ${text.slice(0, 300)}`);
      return;
    }
    log(`<- ${text.length}B JSON type=${msg.type}${msg.pair_status ? ` pair_status=${msg.pair_status}` : ""}`);
    switch (msg.type) {
      case "device_register_ack": {
        deviceSid = msg.device_sid;
        if (!protocolInternalSeen) protocolInternalSeen = `device_register_ack (device_sid assigned)`;
        log(`   registered, device_sid=${deviceSid}`);
        stage = "authenticating";
        if (WAIT_CHALLENGE) {
          log(`   --wait-challenge: holding, waiting for server-initiated challenge (8s window)`);
        } else {
          const init = { type: "auth_init", role: ROLE, device_sid: deviceSid, client_ts: nowMs() };
          if (!NO_META) init.meta = meta();
          log(`-> auth_init role=${ROLE}${NO_META ? " (no meta)" : ""}`);
          ws.sendText(JSON.stringify(init));
        }
        break;
      }
      case "auth_challenge": {
        if (!protocolInternalSeen) protocolInternalSeen = `auth_challenge`;
        const proof = proofFor(PASS_HASH, msg.nonce, ROLE, deviceSid);
        stage = "proving";
        log(`-> auth_response (proof=HMAC-SHA256(pass_hash,"${msg.nonce}|${ROLE}|${deviceSid}").base64url)`);
        ws.sendText(JSON.stringify({ type: "auth_response", device_sid: deviceSid, proof, client_ts: nowMs() }));
        break;
      }
      case "auth_ack":
      case "pair_status_ack": {
        log(`   pair_status=${msg.pair_status}`);
        if (!protocolInternalSeen) protocolInternalSeen = `${msg.type} pair_status=${msg.pair_status}`;
        // 票 #86：matched 在同一连接上晚到（桌面踢除重连周期 ~13s 后）；控制面序列等 matched 再发，
        // waiting 阶段发的数据帧 relay 一律回 INTERNAL（实证）。
        if (PROBE && msg.pair_status === "matched" && !probeStarted) {
          probeStarted = true;
          scheduleProbe(ws, () => probeWorkspaceKey);
        }
        // 票 #88 顺带 A/B：waiting 期每 10s 心跳（官方客户端语义）；观测 relay 是否 ~15s 掐断。
        if (HEARTBEAT && msg.pair_status === "waiting" && !heartbeatStarted) {
          heartbeatStarted = true;
          heartbeatTimer = setInterval(() => {
            const q = { type: "pair_status_query", device_sid: deviceSid, client_ts: Date.now() };
            log(`-> pair_status_query (waiting, t+${Math.round((Date.now() - t0) / 1000)}s)`);
            ws.sendText(JSON.stringify(q));
          }, 10000);
        }
        if (msg.pair_status === "matched" && heartbeatTimer) {
          clearInterval(heartbeatTimer);
          heartbeatTimer = null;
        }
        // 票 #88：matched → workspace-list（拿 workspaceKey/activeTaskId）→ bridge-open → v4 订阅。
        if (V4 && msg.pair_status === "matched" && !V4STATE.started) {
          setTimeout(() => {
            log("v4: workspace-list-request");
            ws.sendText(JSON.stringify({
              type: "data",
              payload: { zcode_type: "workspace-list-request", requestId: "v4-w1" },
              client_ts: Date.now(),
            }));
          }, 500);
        }
        break;
      }
      case "error": {
        log(`   relay error code=${msg.code} message=${msg.message}`);
        if (!protocolInternalSeen) protocolInternalSeen = `error frame code=${JSON.stringify(msg.code)}`;
        break;
      }
      case "data": {
        const payload = msg.payload ?? {};
        const zt = payload.zcode_type;
        if (!protocolInternalSeen && zt !== "rpc-frame") protocolInternalSeen = `data payload zcode_type=${zt}`;
        if (zt === "rpc-frame") {
          const logical = handleRpcFrame(ws, payload);
          if (logical) chanDispatch(logical);
          return;
        }
        if (zt === "rpc-frame-ack") {
          log(`   rpc-frame-ack messageSeq=${payload.ackMessageSeq}`);
          return;
        }
        log(`   data payload ${text.slice(0, 900)}`);
        // 探针：从 bootstrap/workspace-list 响应里抓 workspaceKey（activeWorkspaceKey 优先）。
        try {
          const result = payload.result;
          const key = result?.activeWorkspaceKey ?? result?.workspaces?.[0]?.workspacePath;
          if (key && !probeWorkspaceKey) {
            probeWorkspaceKey = key;
            log(`   probe captured workspaceKey=${key}`);
          }
        } catch { /* probe best-effort */ }
        if (V4) {
          if (zt === "workspace-list-response" && payload.success) {
            const result = payload.result ?? {};
            V4STATE.workspaceKey = result.activeWorkspaceKey ?? probeWorkspaceKey;
            V4STATE.activeTaskId = result.activeTaskId
              ?? (result.tasks ?? [])
                .filter((t) => !t.archived)
                .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))[0]?.taskId
              ?? null;
            V4STATE.taskList = (result.tasks ?? []).map((t) => ({
              taskId: t.taskId, displayStatus: t.displayStatus, updatedAt: t.updatedAt,
            }));
            log(`v4 workspaceKey=${V4STATE.workspaceKey} activeTaskId=${V4STATE.activeTaskId} tasks=${(result.tasks ?? []).length}`);
            setTimeout(() => {
              if (!V4STATE.bridge) {
                const req = {
                  zcode_type: "workspace-bridge-open",
                  requestId: "v4-o1",
                  bridgeSessionId: `rearcue-bridge-${Date.now().toString(36)}`,
                  bridgeGeneration: 1,
                  workspaceKey: V4STATE.workspaceKey ?? "unknown",
                };
                if (V4STATE.activeTaskId) req.taskId = V4STATE.activeTaskId;
                log(`v4 -> workspace-bridge-open key=${req.workspaceKey} taskId=${req.taskId ?? "-"}`);
                ws.sendText(JSON.stringify({ type: "data", payload: req, client_ts: Date.now() }));
              }
            }, 300);
          } else if (zt === "workspace-bridge-ready") {
            V4STATE.bridge = {
              bridgeSessionId: payload.bridgeSessionId,
              bridgeGeneration: payload.bridgeGeneration ?? 0,
              workspacePath: payload.bridge?.workspacePath,
              workspaceIdentity: payload.bridge?.workspaceIdentity,
              initialTaskId: payload.bridge?.initialTaskId,
            };
            log(`v4 bridge-ready path=${V4STATE.bridge.workspacePath} initialTaskId=${V4STATE.bridge.initialTaskId ?? "-"}`);
            v4Flow(ws);
          } else if (zt === "workspace-bridge-error") {
            log(`v4 bridge-ERROR reason=${payload.reason} error=${payload.error}`);
          }
        }
        break;
      }
      default:
        log(`   (unhandled type)`);
    }
  };

  // Absolute wall-clock deadline: server pings keep the socket "active", so
  // socket inactivity timeouts never fire once pairing is waiting.
  let finishCalled = false;
  finishHook = () => finish(ws);
  function finish(client) {
    if (finishCalled) return;
    finishCalled = true;
    if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
    const dwell = protocolInternalSeen ? DWELL_MS : 0; // 票 #86：matched 晚到于踢除重连周期（~13s），dwell 不再封顶 4s
    setTimeout(() => {
      if (!client.closed) { client.sendClose(1000); }
      setTimeout(() => {
        if (V4) {
          const ok = !!V4STATE.subscribeAck && !V4STATE.subscribeAck.__error && !V4STATE.subscribeAck.__timeout;
          console.log(`\nV4 SUMMARY: bridge=${V4STATE.bridge ? "ready" : "MISSING"} chanInit=${V4STATE.chanInit} subscribe=${ok ? "OK" : "FAILED"} frames=${V4STATE.frames}`);
          console.log(`  lastAssistantText=${JSON.stringify(V4STATE.lastAssistantText)}`);
          console.log(`  pendingApproval=${JSON.stringify(V4STATE.pendingApproval)}`);
          if (ok && V4STATE.frames > 0) {
            console.log(`VERDICT: GREEN — v4 subscribe ack + ${V4STATE.frames} conversation frames`);
            process.exit(0);
          }
          console.log(`VERDICT: RED — v4 flow incomplete (bridge=${!!V4STATE.bridge} subscribe=${ok} frames=${V4STATE.frames})`);
          process.exit(2);
        }
        if (protocolInternalSeen) {
          console.log(`\nVERDICT: GREEN — first protocol-internal response: ${protocolInternalSeen}`);
          process.exit(0);
        } else {
          console.log(`\nVERDICT: RED — no protocol-internal response received`);
          process.exit(2);
        }
      }, 300);
    }, dwell);
  }
  const deadline = setTimeout(() => {
    log(`stage=${stage} — absolute deadline reached`);
    finish(ws);
  }, DEADLINE_MS ? Number(DEADLINE_MS) : V4 ? 150000 : PROBE ? 90000 : 30000);

  // 探针时序（票 #86 phase B）：auth_ack 后依次发控制面帧，抓桌面真实响应。
  // 桌面 schema（zcode.cjs kzi）：控制面 payload 直接是 {zcode_type,...}，不套 rpc-frame。
  let probeStarted = false;
  let probeWorkspaceKey = null;
  let heartbeatStarted = false;
  let heartbeatTimer = null;
  function sendPayload(ws, payload) {
    const text = JSON.stringify({ type: "data", payload, client_ts: nowMs() });
    log(`-> probe ${payload.zcode_type ?? payload.method ?? "?"} (${text.length}B)`);
    ws.sendText(text);
  }
  function scheduleProbe(ws, keyOf) {
    const steps = [
      [500, () => sendPayload(ws, { zcode_type: "bootstrap-request", requestId: "probe-b1" })],
      [3500, () => sendPayload(ws, { zcode_type: "workspace-list-request", requestId: "probe-w1" })],
      [8000, () => {
        const key = keyOf();
        log(`probe bridge-open workspaceKey=${key ?? "<none-captured>"}`);
        sendPayload(ws, {
          zcode_type: "workspace-bridge-open",
          requestId: "probe-o1",
          bridgeSessionId: "probe-bridge-1",
          workspaceKey: key ?? "unknown",
        });
      }],
      [13000, () => sendPayload(ws, { method: "v4/conversation/subscribe", params: {} })],
    ];
    for (const [delayMs, step] of steps) setTimeout(step, delayMs);
  }

  const origOnClose = ws.onClose;
  ws.onClose = (why) => { clearTimeout(deadline); if (typeof origOnClose === "function") origOnClose(why); finish(ws); };

  // stage: register (mobile-register path) or auth_init directly (terminal path)
  if (SKIP_REGISTER) {
    deviceSid = SID ?? `d_${crypto.randomBytes(16).toString("base64url").slice(0, 22)}`;
    stage = "authenticating";
    log(`-> auth_init (terminal path, no register) device_sid=${deviceSid}`);
    ws.sendText(JSON.stringify({ type: "auth_init", role: ROLE, device_sid: deviceSid, meta: meta(), client_ts: nowMs() }));
    return;
  }
  const reg = {
    type: "device_register_init",
    device_mid: DEVICE_MID,
    pass_hash: PASS_HASH,
    meta: meta(),
    client_ts: nowMs(),
  };
  log(`-> device_register_init (fake credentials, ${JSON.stringify(reg).length}B)`);
  ws.sendText(JSON.stringify(reg));

}

run();

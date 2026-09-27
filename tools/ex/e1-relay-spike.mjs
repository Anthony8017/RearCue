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
const SID = argOf("sid", null); // device_sid to present in auth_init (terminal path)
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

const DEVICE_MID = crypto.randomUUID();
const t0 = Date.now();
let protocolInternalSeen = null; // string describing first protocol-internal response
let captureStream = null;
let captureFile = null;

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

// ---------- main ----------
const socketWaiters = [];
function fail(msg) {
  log(`FAIL: ${msg}`);
  console.log(`\nVERDICT: RED (${msg})`);
  process.exit(2);
}

function run() {
  openCapture();
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
        break;
      }
      case "error": {
        log(`   relay error code=${msg.code} message=${msg.message}`);
        if (!protocolInternalSeen) protocolInternalSeen = `error frame code=${JSON.stringify(msg.code)}`;
        break;
      }
      case "data": {
        log(`   data payload ${text.slice(0, 800)}`);
        if (!protocolInternalSeen) protocolInternalSeen = `data payload`;
        // 探针：从 bootstrap/workspace-list 响应里抓 workspaceKey（activeWorkspaceKey 优先）。
        try {
          const result = msg.payload?.result;
          const key = result?.activeWorkspaceKey ?? result?.workspaces?.[0]?.workspacePath;
          if (key && !probeWorkspaceKey) {
            probeWorkspaceKey = key;
            log(`   probe captured workspaceKey=${key}`);
          }
        } catch { /* probe best-effort */ }
        break;
      }
      default:
        log(`   (unhandled type)`);
    }
  };

  // Absolute wall-clock deadline: server pings keep the socket "active", so
  // socket inactivity timeouts never fire once pairing is waiting.
  let finishCalled = false;
  function finish(client) {
    if (finishCalled) return;
    finishCalled = true;
    const dwell = protocolInternalSeen ? DWELL_MS : 0; // 票 #86：matched 晚到于踢除重连周期（~13s），dwell 不再封顶 4s
    setTimeout(() => {
      if (!client.closed) { client.sendClose(1000); }
      setTimeout(() => {
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
  }, PROBE ? 90000 : 30000);

  // 探针时序（票 #86 phase B）：auth_ack 后依次发控制面帧，抓桌面真实响应。
  // 桌面 schema（zcode.cjs kzi）：控制面 payload 直接是 {zcode_type,...}，不套 rpc-frame。
  let probeStarted = false;
  let probeWorkspaceKey = null;
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

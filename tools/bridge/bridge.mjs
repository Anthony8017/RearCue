#!/usr/bin/env node
/**
 * RearCue PC 桥（ADR 0006 / 票 #116）：Codex 与 Claude Desktop 共用的常驻采集进程。
 *
 * 本票交付基础设施 + 示例事件源；Codex / Claude Desktop 适配器（#118/#119）往
 * appendEvent 里灌同一种「统一会话事件」即可，手机端零特例。
 *
 * 统一会话事件（唯一契约，字段与手机侧 BridgeEventCodec 对齐）：
 *   { sessionId, status: working|waiting|idle, workspace?, currentAction?, latestReply?, updatedAt? }
 * - id 由桥分配（单调递增游标）；updatedAt 缺省取桥侧时间。
 *
 * 接口：
 *   GET  /events?since=<cursor>  长轮询（无新事件持有 ~25s 后空页返回；游标单调）
 *   POST /inject                 灌一条会话事件（适配器/示例源/调试）
 *   GET  /health                 存活探测
 *
 * 隧道：默认拉起 `tunwg -p <port>`（ntnj/tunwg，URL 由 key 派生、重启不变——手机配一次
 * 长期有效）；`--no-tunnel` 只监听本机（LAN 直连调试用）。UDP 被拦时 `TUNWG_RELAY=true`
 * 走 HTTPS 中继（见 tunwg README）。开机自启见 enable-autostart.ps1。
 */
import http from "node:http";
import { spawn } from "node:child_process";
import { writeFileSync, existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startCodexAdapter } from "./adapters/codex.mjs";
import { startClaudeAdapter } from "./adapters/claude.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
// 默认 18787：本机 8787 已被其它代理占用（实测 EADDRINUSE），避开。
const PORT = Number(process.env.BRIDGE_PORT || 18787);
const HOST = process.env.BRIDGE_HOST || "127.0.0.1";
const HOLD_MS = 25_000; // 长轮询持有上限（手机读超时 35s，留裕量）
const MAX_EVENTS = 5000; // 事件环容量：只留最近 N 条（since=0 的重放即「近史快照」）
const STATUSES = new Set(["working", "waiting", "idle"]);
// seq 持久化（票 #118 评审）：重启归零会让手机游标（since=N）永远追不上新小 id——
// 长轮询彻底失明。落盘 bridge.seq，重启续号（事件环不持久，丢失仅限近史回放）。
const SEQ_FILE = join(HERE, "bridge.seq");

const wantTunnel = !process.argv.includes("--no-tunnel");
const wantDemo = process.argv.includes("--demo");
const wantCodex = !process.argv.includes("--no-codex");
const wantClaude = !process.argv.includes("--no-claude");

// seq 续号：读取上次持久值（缺/坏文件按 0 起）。
let seq = (() => {
  try {
    const n = parseInt(readFileSync(SEQ_FILE, "utf8").trim(), 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
  } catch {
    return 0;
  }
})();
/** @type {Array<Record<string, any>>} */
const events = [];
/** @type {Set<(v: any[]) => void>} */
const waiters = new Set();
/** 会话最新态（hooks 部分事件回填 workspace/reply 用；codex/claude 适配器与 /hooks 共享）。 */
const latestBySession = new Map();
/** 最近活跃的 codex 会话（agent-turn-complete 载荷无 sessionId，回落到这里）。 */
let lastCodexSession = null;

function log(msg) {
  console.log(`[bridge] ${new Date().toISOString()} ${msg}`);
}

/** 灌一条统一会话事件：非法返回 null（400），合法入环并唤醒长轮询。
 *  缺字段用该会话最新态回填（hooks 只带 status 的部分事件不丢正文）。 */
function appendEvent(partial) {
  if (!partial || typeof partial !== "object") return null;
  if (typeof partial.sessionId !== "string" || !partial.sessionId) return null;
  if (!STATUSES.has(partial.status)) return null;
  const remembered = latestBySession.get(partial.sessionId) || {};
  const ev = {
    workspace: null,
    currentAction: null,
    latestReply: null,
    ...remembered,
    ...partial,
    updatedAt: partial.updatedAt ?? Date.now(), // 回填链不能盖掉新鲜时间戳
    id: ++seq, // id 恒由桥分配（外部传入被忽略）
  };
  latestBySession.set(partial.sessionId, { ...ev });
  events.push(ev);
  if (events.length > MAX_EVENTS) events.splice(0, events.length - MAX_EVENTS);
  try {
    writeFileSync(SEQ_FILE, String(seq)); // 重启续号（手机游标不倒退）
  } catch {
    /* 落盘失败只影响下次重启的续号，不阻塞事件 */
  }
  for (const wake of [...waiters]) wake();
  return ev;
}

/** hooks 载荷 → 统一会话事件（部分补丁，缺字段由 appendEvent 回填）。导出供测试。 */
export function mapHookToPatch(source, body) {
  if (!body || typeof body !== "object") return null;
  if (source === "claude") {
    const event = body.hook_event_name || body.type;
    const sessionId = body.session_id || body.sessionId;
    if (!sessionId) return null;
    if (event === "Stop" || event === "stop") {
      const patch = { sessionId, status: "idle", currentAction: null };
      if (typeof body.last_assistant_message === "string" && body.last_assistant_message.trim()) {
        patch.latestReply = body.last_assistant_message;
      }
      if (typeof body.cwd === "string" && body.cwd) patch.workspace = body.cwd;
      return patch;
    }
    if (event === "Notification" || event === "notification") {
      return { sessionId, status: "waiting" };
    }
    return null;
  }
  if (source === "codex") {
    const type = String(body.type || body.event || "");
    const sessionId = body.session_id || body.sessionId || lastCodexSession;
    if (!sessionId) return null;
    if (/turn-complete|task_complete|turn_complete/.test(type)) {
      const patch = { sessionId, status: "idle", currentAction: null };
      if (typeof body.last_assistant_message === "string" && body.last_assistant_message.trim()) {
        patch.latestReply = body.last_assistant_message;
      }
      return patch;
    }
    if (/approval|waiting/.test(type)) return { sessionId, status: "waiting" };
    if (/turn-started|task_started|working/.test(type)) return { sessionId, status: "working" };
    return null;
  }
  return null;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      if (data.length > 1_000_000) reject(new Error("body too large"));
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

async function handleEvents(req, res, since, holdMs) {
  const filter = () => events.filter((e) => e.id > since);
  let batch = filter();
  if (batch.length === 0 && holdMs > 0) {
    batch = await new Promise((resolve) => {
      let done = false;
      const finish = (v) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        waiters.delete(onWake);
        resolve(v);
      };
      const onWake = () => finish(filter());
      const timer = setTimeout(() => finish([]), holdMs);
      waiters.add(onWake);
      const now = filter();
      if (now.length > 0) finish(now);
      req.on("close", () => finish([])); // 手机端断开：立刻放行
    });
  }
  if (res.writableEnded || res.destroyed) return;
  const cursor = events.length > 0 ? Math.max(events[events.length - 1].id, since) : since;
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ events: batch, cursor }));
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    if (req.method === "GET" && url.pathname === "/health") {
      res.writeHead(200).end("ok");
      return;
    }
    if (req.method === "GET" && url.pathname === "/events") {
      const since = Number(url.searchParams.get("since") || 0);
      if (!Number.isFinite(since) || since < 0) {
        res.writeHead(400).end();
        return;
      }
      // hold 覆盖（测试/调试用）：wait=0 立即返回空页；缺省 HOLD_MS。
      const holdRaw = Number(url.searchParams.get("wait") ?? HOLD_MS);
      const holdMs = Number.isFinite(holdRaw) ? Math.min(Math.max(holdRaw, 0), HOLD_MS) : HOLD_MS;
      await handleEvents(req, res, Math.floor(since), holdMs);
      return;
    }
    if (req.method === "POST" && url.pathname === "/inject") {
      const raw = await readBody(req);
      let body;
      try {
        body = JSON.parse(raw || "{}");
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"error":"bad json"}');
        return;
      }
      const ev = appendEvent(body);
      if (!ev) {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"error":"invalid event"}');
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: true, id: ev.id }));
      return;
    }
    // hooks 转发入口（票 #118/#119）：notify-dispatch / claude-hook.mjs POST 到这里，
    // 映射为统一会话事件（部分补丁，缺字段由会话最新态回填）。不认识的载荷 → 202 空操作。
    if (req.method === "POST" && (url.pathname === "/hooks/claude" || url.pathname === "/hooks/codex")) {
      const source = url.pathname.endsWith("claude") ? "claude" : "codex";
      const raw = await readBody(req);
      let body;
      try {
        body = JSON.parse(raw || "{}");
      } catch {
        res.writeHead(400).end();
        return;
      }
      const patch = mapHookToPatch(source, body);
      if (!patch) {
        res.writeHead(202, { "Content-Type": "application/json" }).end('{"ok":true,"ignored":true}');
        return;
      }
      const ev = appendEvent(patch);
      res.writeHead(ev ? 200 : 400, { "Content-Type": "application/json" })
        .end(ev ? JSON.stringify({ ok: true, id: ev.id }) : '{"error":"invalid event"}');
      return;
    }
    res.writeHead(404).end();
  } catch (e) {
    log(`请求处理异常 ${e?.message || e}`);
    if (!res.writableEnded) res.writeHead(500).end();
  }
});

/** 示例事件源（票 #116 端到端演示）：working（回复增长）→ waiting → idle 循环。 */
function startDemo() {
  let phase = "working";
  let ticks = 0;
  let lines = 0;
  const reply = [];
  setInterval(() => {
    ticks++;
    if (phase === "working") {
      lines++;
      reply.push(`[${lines}] demo output line ${lines} — 统一会话事件流实时上屏测试。`);
      if (reply.length > 40) reply.shift();
      appendEvent({
        sessionId: "demo",
        workspace: "C:/demo/repo",
        status: "working",
        currentAction: "generate demo output",
        latestReply: reply.join("\n"),
      });
      if (ticks >= 15) {
        phase = "waiting";
        ticks = 0;
      }
    } else if (phase === "waiting") {
      appendEvent({
        sessionId: "demo",
        workspace: "C:/demo/repo",
        status: "waiting",
        currentAction: "approve `cargo build`",
        latestReply: reply.join("\n"),
      });
      if (ticks >= 4) {
        phase = "idle";
        ticks = 0;
      }
    } else {
      appendEvent({
        sessionId: "demo",
        workspace: "C:/demo/repo",
        status: "idle",
        currentAction: null,
        latestReply: reply.join("\n"),
      });
      if (ticks >= 3) {
        phase = "working";
        ticks = 0;
        lines = 0;
        reply.length = 0;
      }
    }
  }, 2000);
  log("示例事件源已开（demo 会话，working→waiting→idle 循环）");
}

/** 拉起 tunwg 隧道并捕捉 URL（写 bridge.url，供手机配置/自检）。 */
function startTunnel() {
  const localBin = join(HERE, "bin", "tunwg.exe");
  const bin = process.env.TUNWG_BIN || (existsSync(localBin) ? localBin : "tunwg");
  const child = spawn(bin, ["-p", String(PORT)], {
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
  });
  const onLine = (chunk) => {
    for (const line of String(chunk).split(/\r?\n/)) {
      // 真实隧道 URL 形如 https://<sub>.l.tunwg.com；错误信息里的 /add 端点不是 URL，别吞。
      const m = line.match(/https:\/\/[a-z0-9]+\.l\.tunwg\.com/i);
      if (m) {
        const url = m[0].replace(/[.,)"]+$/, "");
        log(`隧道 URL: ${url}`);
        try {
          writeFileSync(join(HERE, "bridge.url"), url + "\n");
        } catch (e) {
          log(`bridge.url 写入失败 ${e?.message || e}`);
        }
      }
      if (line.trim()) log(`tunwg: ${line.trim()}`);
    }
  };
  child.stdout.on("data", onLine);
  child.stderr.on("data", onLine);
  child.on("error", (e) => {
    log(`tunwg 启动失败（${e?.code || e?.message}）——本机/LAN 模式不受影响，隧道需安装 tunwg`);
    log("下载: https://github.com/ntnj/tunwg/releases 放到 tools/bridge/bin/tunwg.exe 或加入 PATH");
  });
  child.on("exit", (code) => log(`tunwg 退出 code=${code}`));
}

server.listen(PORT, HOST, () => {
  log(`监听 http://${HOST}:${PORT}（长轮询持有 ${HOLD_MS / 1000}s，环容量 ${MAX_EVENTS}）`);
  if (wantDemo) startDemo();
  // 会话文件适配器（ADR 0006）：目录存在即自动挂载，--no-codex / --no-claude 可关。
  if (wantCodex) {
    startCodexAdapter(
      (ev) => {
        lastCodexSession = ev.sessionId || lastCodexSession;
        appendEvent(ev);
      },
      { log },
    );
  }
  if (wantClaude) startClaudeAdapter(appendEvent, { log });
  if (wantTunnel) startTunnel();
  else log("隧道关闭（--no-tunnel）：仅本机/LAN 可达");
});

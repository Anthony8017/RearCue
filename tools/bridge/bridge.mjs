#!/usr/bin/env node
/**
 * RearCue PC 桥（ADR 0006 / 票 #116）：Codex 与 Claude Desktop 共用的常驻采集进程。
 *
 * 本票交付基础设施 + 示例事件源；Codex / Claude Desktop 适配器（#118/#119）往
 * appendEvent 里灌同一种「统一会话事件」即可，手机端零特例。
 *
 * 统一会话事件（唯一契约，字段与手机侧 BridgeEventCodec 对齐）：
 *   { sessionId, source: codex|claude|null, status: working|waiting|idle,
 *     workspace?, currentAction?, latestReply?, turns?, updatedAt? }
 * - id 由桥分配（单调递增游标）；updatedAt 缺省取桥侧时间。
 * - source 由适配器/hook 填充；缺省 null（旧事件与 /inject 兼容）。
 * - `turns` 是**问答流**（spec 0017 / 票 #169）：`[{role:"user"|"assistant", text, ts, open?}]`，
 *   由本进程的会话滚动窗口（[createTurnLog]，20 条 / 16000 字）持有；适配器与 hooks 只需给
 *   `userText` / `assistantText` / `assistantDelta` 三种**增量补丁**之一，桥负责攒与裁剪。
 *   `latestReply` 仍然照发（取末尾助手输出），未升级的手机端读它照旧能用。
 *
 * 接口：
 *   GET  /events?since=<cursor>  长轮询（无新事件持有 ~25s 后空页返回；游标单调）
 *   GET  /snapshot               当前在册会话只读快照（键集 + 最小字段）
 *   POST /inject                 灌一条会话事件（适配器/示例源/调试）
 *   POST /hooks/claude           Claude hooks 转发（Stop→idle / Notification→waiting）
 *   POST /hooks/codex            Codex notify 转发（turn-complete→idle / approval→waiting）
 *   GET  /health                 存活探测
 *
 * 隧道：默认拉起 **cloudflared quick tunnel**（`https://<随机>.trycloudflare.com`——
 * 2026-09-28 实测大陆可达：PC / 手机 Wi-Fi / 手机蜂窝三路全通）；拿到 URL 后落
 * bridge.url 并**自动 adb 推给手机**（quick tunnel 重启换 URL 也免手工重配）。
 * `BRIDGE_TUNNEL=tunwg` 切回 tunwg（key 派生 URL 稳定，但公共实例 2026-09-28 被墙/403，
 * 见 README 排障）；`--no-tunnel` 只监听本机（LAN/adb reverse 调试用）。
 * 开机自启见 enable-autostart.ps1。
 */
import http from "node:http";
import { spawn } from "node:child_process";
import { writeFileSync, existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startCodexAdapter } from "./adapters/codex.mjs";
import { startClaudeAdapter } from "./adapters/claude.mjs";
import { createTurnLog } from "./adapters/turn-log.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
// 默认 18787：本机 8787 已被其它代理占用（实测 EADDRINUSE），避开。
const PORT = Number(process.env.BRIDGE_PORT || 18787);
const HOST = process.env.BRIDGE_HOST || "127.0.0.1";
const HOLD_MS = 25_000; // 长轮询持有上限（手机读超时 35s，留裕量）
const MAX_EVENTS = 5000; // 事件环容量：只留最近 N 条（since=0 的重放即「近史快照」）
const STATUSES = new Set(["working", "waiting", "idle"]);
// seq 持久化（票 #118 评审）：重启归零会让手机游标（since=N）永远追不上新小 id——
// 长轮询彻底失明。落盘 bridge.seq，重启续号（事件环不持久，丢失仅限近史回放）。
// BRIDGE_SEQ_FILE 可覆盖（测试实例与生产实例分文件，互不串号）。
const SEQ_FILE = process.env.BRIDGE_SEQ_FILE || join(HERE, "bridge.seq");

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
/**
 * 每会话的问答流窗口（spec 0017 / 票 #169）：桥持有、事件里整份发出。
 * 适配器只给增量（userText/assistantText/assistantDelta），攒与裁剪都在这里，
 * 免得两个适配器各写一套窗口逻辑。
 */
const turnsBySession = new Map();

function turnLogFor(sessionId) {
  let log = turnsBySession.get(sessionId);
  if (!log) {
    log = createTurnLog();
    turnsBySession.set(sessionId, log);
  }
  return log;
}

/**
 * 把一次事件里的增量补丁应用到该会话的问答流上（spec 0017）。
 * 返回是否动过 turns——没动过就不覆盖事件里已有的 turns（适配器继续发老字段也不会丢流）。
 */
function applyTurnPatch(sessionId, partial, ts) {
  const log = turnLogFor(sessionId);
  let touched = false;
  if (typeof partial.userText === "string" && partial.userText.trim()) {
    log.user(partial.userText, ts);
    touched = true;
  }
  if (typeof partial.assistantText === "string" && partial.assistantText.trim()) {
    log.agent(partial.assistantText, ts);
    touched = true;
  }
  if (typeof partial.assistantDelta === "string" && partial.assistantDelta) {
    log.delta(partial.assistantDelta, ts);
    touched = true;
  }
  if (partial.resetTurns === true) {
    log.reset();
    touched = true;
  }
  return touched;
}

function log(msg) {
  console.log(`[bridge] ${new Date().toISOString()} ${msg}`);
}

/** 灌一条统一会话事件：非法返回 null（400），合法入环并唤醒长轮询。
 *  缺字段用该会话最新态回填（hooks 只带 status 的部分事件不丢正文）。 */
/**
 * 老字段 `latestReply` → 问答流补丁（spec 0017 兼容面，**只此一处**）：
 * hooks 与适配器可能还在发旧的整段正文，/inject 也常直接给整段——一律当「一条完整助手输出」
 * 交给会话窗口（同文复读由窗口去重收口）。
 */
function latestReplyToAssistantText(partial) {
  const out = { ...partial };
  if (typeof out.latestReply === "string" && out.latestReply.trim()) {
    if (typeof out.assistantText !== "string") out.assistantText = out.latestReply;
    delete out.latestReply;
  }
  return out;
}

function appendEvent(partial) {
  if (!partial || typeof partial !== "object") return null;
  if (typeof partial.sessionId !== "string" || !partial.sessionId) return null;
  if (!STATUSES.has(partial.status)) return null;
  const remembered = latestBySession.get(partial.sessionId) || {};
  const ts = Number.isFinite(partial.updatedAt) ? partial.updatedAt : Date.now();
  const incoming = latestReplyToAssistantText(partial);
  // 问答流先攒后发：增量补丁在这里落进会话窗口（spec 0017 / 票 #169）。
  const touchedTurns = applyTurnPatch(partial.sessionId, incoming, ts);
  const turnLog = turnLogFor(partial.sessionId);
  const ev = {
    source: null,
    workspace: null,
    currentAction: null,
    latestReply: null,
    ...remembered,
    ...incoming,
    updatedAt: partial.updatedAt ?? ts, // 回填链不能盖掉新鲜时间戳
    id: ++seq, // id 恒由桥分配（外部传入被忽略）
  };
  // 内部补丁字段不上线（手机端只认 turns / latestReply）；两者每帧按当前窗口重算。
  delete ev.userText;
  delete ev.assistantText;
  delete ev.assistantDelta;
  delete ev.resetTurns;
  ev.turns = turnLog.list();
  const derived = turnLog.latestReply();
  if (derived !== null) {
    ev.latestReply = derived;
  } else if (touchedTurns) {
    ev.latestReply = null; // 只有提问、还没有回答：旧字段不该留着上一轮的回答
  }
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
      const patch = { sessionId, source, status: "idle", currentAction: null };
      if (typeof body.last_assistant_message === "string" && body.last_assistant_message.trim()) {
        patch.latestReply = body.last_assistant_message;
      }
      if (typeof body.cwd === "string" && body.cwd) patch.workspace = body.cwd;
      return patch;
    }
    if (event === "Notification" || event === "notification") {
      return { sessionId, source, status: "waiting" };
    }
    // MessageDisplay（spec 0017 / 票 #169）：Claude 在回合进行中按「新完成的整行」分批吐正文。
    // 官方语义：Display-only（不改 Claude 的存储内容与模型输入），字段
    // {turn_id, message_id, index, final, delta}——把 delta 当**增量**交给问答流即可。
    if (event === "MessageDisplay") {
      const delta = typeof body.delta === "string" ? body.delta : "";
      if (!delta) return null; // 最后一次 flush 的 delta 可能为空（正文以换行收尾）：无正文可言
      const patch = { sessionId, source, status: "working", assistantDelta: delta };
      if (typeof body.cwd === "string" && body.cwd) patch.workspace = body.cwd;
      return patch;
    }
    return null;
  }
  if (source === "codex") {
    const type = String(body.type || body.event || "");
    const sessionId = body.session_id || body.sessionId || lastCodexSession;
    if (!sessionId) return null;
    if (/turn-complete|task_complete|turn_complete/.test(type)) {
      const patch = { sessionId, source, status: "idle", currentAction: null };
      if (typeof body.last_assistant_message === "string" && body.last_assistant_message.trim()) {
        patch.latestReply = body.last_assistant_message;
      }
      return patch;
    }
    if (/approval|waiting/.test(type)) return { sessionId, source, status: "waiting" };
    if (/turn-started|task_started|working/.test(type)) return { sessionId, source, status: "working" };
    return null;
  }
  return null;
}

/** 当前在册会话快照：只读、不带正文，按 latestBySession 的键集投影最小字段。 */
function sessionSnapshot() {
  const sessions = [];
  for (const ev of latestBySession.values()) {
    if (!ev || typeof ev.sessionId !== "string" || !ev.sessionId) continue;
    sessions.push({
      sessionId: ev.sessionId,
      source: typeof ev.source === "string" && ev.source ? ev.source : null,
      workspace: typeof ev.workspace === "string" && ev.workspace ? ev.workspace : null,
      status: STATUSES.has(ev.status) ? ev.status : null,
      updatedAt: Number.isFinite(ev.updatedAt) ? ev.updatedAt : null,
    });
  }
  return { sessions };
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
    if (req.method === "GET" && url.pathname === "/snapshot") {
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(sessionSnapshot()));
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
      // hooks 的 last_assistant_message 也是「一条完整助手输出」：走与 /inject 同一条兼容
      // 换算（spec 0017 起只此一处），同文复读交给会话窗口去重。
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
      const line = `[${lines}] demo output line ${lines} — 统一会话事件流实时上屏测试。`;
      reply.push(line);
      if (reply.length > 40) reply.shift();
      appendEvent({
        sessionId: "demo",
        workspace: "C:/demo/repo",
        status: "working",
        currentAction: "generate demo output",
        // spec 0017：走**增量**（一行一批），与 Claude 的 MessageDisplay 同形——
        // 演示源才真的演示了「问答流」而不是「每拍换一条越来越长的整段」。
        assistantDelta: `${line}\n`,
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
        // 完整正文收口那条开放条目（与 Claude 的 Stop 同形）。
        assistantText: reply.join("\n"),
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
        // 新回合开始：先给一句机主提问，让演示源也带上「问答流」的提问侧。
        userText: "继续跑下一轮演示",
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

/** 记录隧道 URL 并自动推给手机（桥重启/换 URL 时免手工重配；adb 不在则跳过）。 */
let lastPushedUrl = null;
function publishTunnelUrl(url, log) {
  log(`隧道 URL: ${url}`);
  try {
    writeFileSync(join(HERE, "bridge.url"), url + "\n");
  } catch (e) {
    log(`bridge.url 写入失败 ${e?.message || e}`);
  }
  if (url === lastPushedUrl) return;
  lastPushedUrl = url;
  const adbCandidates = [
    process.env.ADB,
    join(process.env.LOCALAPPDATA || "", "RearCue-tools/android-sdk/platform-tools/adb.exe"),
    "adb",
  ].filter(Boolean);
  for (const adb of adbCandidates) {
    try {
      const r = spawn(
        adb,
        [
          "shell", "am", "broadcast",
          "-n", "com.rearcue.poc/.DebugCommandReceiver",
          "-a", "com.rearcue.poc.action.BRIDGE_URL",
          "--es", "url", url,
        ],
        { stdio: "ignore" },
      );
      r.on("error", () => {}); // 找不到 adb：换下一个候选
      r.on("exit", (code) => {
        if (code === 0) log(`已自动推送隧道 URL 到手机（adb）`);
      });
      return;
    } catch {
      /* 试下一个候选 */
    }
  }
  log("未找到 adb：URL 已落 bridge.url，手机需手动配置一次");
}

/** cloudflared quick tunnel（默认；2026-09-28 实测大陆可达：PC/手机 Wi-Fi/手机蜂窝全通）。 */
function startCloudflared(log) {
  const localBin = join(HERE, "bin", "cloudflared.exe");
  const bin = process.env.CLOUDFLARED_BIN || (existsSync(localBin) ? localBin : "cloudflared");
  const child = spawn(bin, ["tunnel", "--url", `http://127.0.0.1:${PORT}`], {
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
  });
  const onLine = (chunk) => {
    for (const line of String(chunk).split(/\r?\n/)) {
      // quick tunnel URL 形如 https://<words>.trycloudflare.com
      const m = line.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
      if (m) publishTunnelUrl(m[0], log);
      if (line.trim()) log(`cloudflared: ${line.trim()}`);
    }
  };
  child.stdout.on("data", onLine);
  child.stderr.on("data", onLine);
  child.on("error", (e) => {
    log(`cloudflared 启动失败（${e?.code || e?.message}）——本机/LAN 模式不受影响`);
    log("下载: https://github.com/cloudflare/cloudflared/releases 放到 tools/bridge/bin/cloudflared.exe");
  });
  child.on("exit", (code) => log(`cloudflared 退出 code=${code}（quick tunnel URL 随重启更换）`));
}

/** tunwg 隧道（备选：--tunnel tunwg；公共实例 2026-09-28 实测被墙/403，见 README 排障）。 */
function startTunwg(log) {
  const localBin = join(HERE, "bin", "tunwg.exe");
  const bin = process.env.TUNWG_BIN || (existsSync(localBin) ? localBin : "tunwg");
  const child = spawn(bin, ["-p", String(PORT)], {
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
  });
  const onLine = (chunk) => {
    for (const line of String(chunk).split(/\r?\n/)) {
      // 真实隧道 URL 形如 https://<sub>.l.tunwg.com；错误信息里的 /add 端点不是 URL，别吞。
      const m = line.match(/https:\/\/[a-z0-9]+\.l\.tunwg\.com/);
      if (m) publishTunnelUrl(m[0].replace(/[.,)"]+$/, ""), log);
      if (line.trim()) log(`tunwg: ${line.trim()}`);
    }
  };
  child.stdout.on("data", onLine);
  child.stderr.on("data", onLine);
  child.on("error", (e) => {
    log(`tunwg 启动失败（${e?.code || e?.message}）`);
    log("下载: https://github.com/ntnj/tunwg/releases 放到 tools/bridge/bin/tunwg.exe 或加入 PATH");
  });
  child.on("exit", (code) => log(`tunwg 退出 code=${code}`));
}

function startTunnel(log) {
  const kind = (process.env.BRIDGE_TUNNEL || "cf").toLowerCase();
  if (kind === "tunwg") startTunwg(log);
  else startCloudflared(log);
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
  if (wantTunnel) startTunnel(log);
  else log("隧道关闭（--no-tunnel）：仅本机/LAN 可达");
});

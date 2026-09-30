#!/usr/bin/env node
/**
 * RearCue PC 桥（ADR 0006 / 票 #116）：Codex 与 Claude Desktop 共用的常驻采集进程。
 *
 * 本票交付基础设施 + 示例事件源；Codex / Claude Desktop 适配器（#118/#119）往
 * appendEvent 里灌同一种「统一会话事件」即可，手机端零特例。
 *
 * 统一会话事件（唯一契约，字段与手机侧 BridgeEventCodec 对齐）：
 *   { sessionId, source: codex|claude|dsh|null, status: working|waiting|idle,
 *     workspace?, currentAction?, latestReply?, turns?, summary?, updatedAt? }
 * - id 由桥分配（单调递增游标）；updatedAt 缺省取桥侧时间。
 * - source 由适配器/hook 填充；缺省 null（旧事件与 /inject 兼容）。
 * - `turns` 是**问答流**（spec 0017 / 票 #169）：`[{role:"user"|"assistant", text, ts, open?}]`，
 *   由本进程的会话滚动窗口（[createTurnLog]，20 条 / 16000 字）持有；适配器与 hooks 只需给
 *   `userText` / `assistantText` / `assistantDelta` 三种**增量补丁**之一，桥负责攒与裁剪。
 *   `latestReply` 仍然照发（取末尾助手输出），未升级的手机端读它照旧能用。
 * - `summary`（spec 0018-1 留位，#173/#174 的提醒与批准上下文用）：一句话摘要，可缺省；
 *   旧手机端不认识该字段照常忽略（缺省退化，不破坏兼容）。
 *
 * 接口：
 *   GET  /events?since=<cursor>  长轮询（无新事件持有 ~25s 后空页返回；游标单调）
 *   GET  /snapshot               当前在册会话只读快照（键集 + 最小字段）
 *   POST /inject                 灌一条会话事件（适配器/示例源/调试）
 *   POST /hooks/claude           Claude hooks 转发（Stop→idle / Notification→waiting）
 *   POST /hooks/codex            Codex notify 转发（turn-complete→idle / approval→waiting）
 *   POST /hooks/dsh              DSH 只读插件转发（ADR 0010；会话退出会摘出在册快照）
 *   POST /action                 会话动作——手机批准（同意/拒绝/选中选项）的唯一写方向，
 *                                恒回明确回执（accepted|unknown-session|unsupported|bad-request）
 *   GET  /action/pending         PreToolUse 钩子取远程批准决定（一次性、过期即无）
 *   GET  /health                 存活探测
 *
 * 隧道：默认拉起 **cloudflared quick tunnel**（`https://<随机>.trycloudflare.com`——
 * 2026-09-28 实测大陆可达：PC / 手机 Wi-Fi / 手机蜂窝三路全通）；拿到 URL 后落
 * bridge.url 并**自动 adb 推给手机**（quick tunnel 重启换 URL 也免手工重配）。
 * `BRIDGE_TUNNEL=tunwg` 切回 tunwg（key 派生 URL 稳定，但公共实例 2026-09-28 被墙/403，
 * 见 README 排障）；`--no-tunnel` 只监听本机（LAN/adb reverse 调试用）。
 *
 * 隧道看门狗（票 #171）：隧道进程退出即自动重拉一条，新地址自动推手机 + 托盘弹气泡；
 * 每 15s 探一次隧道 /health，把「就绪」写进托盘状态。**但地址真的变了才弹气泡**——
 * 探活抖动不该吵人。
 *
 * 托盘（票 #171）：桥没有窗口，任务栏托盘是它唯一的人机界面（图标在＝桥在）；
 * 状态与气泡的契约在 tray.mjs / tray.ps1 两侧对齐。开机自启见 enable-autostart.ps1。
 */
import http from "node:http";
import https from "node:https";
import { spawn } from "node:child_process";
import { appendFileSync, writeFileSync, existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startCodexAdapter } from "./adapters/codex.mjs";
import { startClaudeAdapter } from "./adapters/claude.mjs";
import { createTurnLog } from "./adapters/turn-log.mjs";
import { mapDshHookToPatch, dshRemovalFromHook } from "./adapters/dsh/dsh-events.mjs";
import { adbArgs, adbCandidates, balloon, readTrayState, setTrayState, startTray, stopTray } from "./tray.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
// 默认 18787：本机 8787 已被其它代理占用（实测 EADDRINUSE），避开。
const PORT = Number(process.env.BRIDGE_PORT || 18787);
const HOST = process.env.BRIDGE_HOST || "127.0.0.1";
const HOLD_MS = 25_000; // 长轮询持有上限（手机读超时 35s，留裕量）
const MAX_EVENTS = 5000; // 事件环容量：只留最近 N 条（since=0 的重放即「近史快照」）
const STATUSES = new Set(["working", "waiting", "idle", "error"]);
// seq 持久化（票 #118 评审）：重启归零会让手机游标（since=N）永远追不上新小 id——
// 长轮询彻底失明。落盘 bridge.seq，重启续号（事件环不持久，丢失仅限近史回放）。
// BRIDGE_SEQ_FILE 可覆盖（测试实例与生产实例分文件，互不串号）。
const SEQ_FILE = process.env.BRIDGE_SEQ_FILE || join(HERE, "bridge.seq");
// 桥自己的日志路径（常驻启动器经 BRIDGE_LOG 传进来，托盘的「打开 bridge.log」菜单用它）。
const LOG_FILE = process.env.BRIDGE_LOG || join(HERE, "bridge.log");

/**
 * 日志：**直写文件**，不走 stdout。
 *
 * 为什么（2026-09-29 实测）：常驻形态下 stdout 是管道（`node ... | Out-File`），
 * Node 会把管道写缓冲起来（约 4KB 或 1s 才落一次），于是新日志迟迟不出现、
 * 排障时看着像"桥没在干活"。桥自己 append 就没有这一层。
 * 父进程的 stdout 重定向仍然保留——Node 崩溃时的栈是它抓的，那部分不能丢。
 */
function makeLog(file) {
  return (msg) => {
    const line = `[bridge] ${new Date().toISOString()} ${msg}\n`;
    try {
      appendFileSync(file, line);
    } catch {
      /* 日志写不进去不该拖垮桥 */
    }
    process.stdout.write(line);
  };
}
const log = makeLog(LOG_FILE);
// 隧道看门狗（票 #171）：重拉前的退避、以及隧道 /health 探活间隔。
const TUNNEL_RETRY_MS = Number(process.env.BRIDGE_TUNNEL_RETRY_MS || 5000);
const TUNNEL_PROBE_MS = Number(process.env.BRIDGE_PROBE_MS || 15_000);
// 看门狗与探活的句柄、托盘是否已起（收到停止信号时清掉，免得退出途中又拉起新进程）。
let trayWatchdogTimer = null;
let tunnelProbe = null;
let trayStarted = false;

const wantTunnel = !process.argv.includes("--no-tunnel");
const wantDemo = process.argv.includes("--demo");
const wantCodex = !process.argv.includes("--no-codex");
const wantClaude = !process.argv.includes("--no-claude");
// DSH（ADR 0010 / spec 0018-1）：数据面是**推送**——只读插件 POST /hooks/dsh，桥不拉文件。
// --no-dsh 关闭该入口（不用 DSH 时不留这条面）。
const wantDsh = !process.argv.includes("--no-dsh");

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
    summary: null,
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
    // 批准通道（spec 0018-4 / 票 #174）：claude-hook.mjs 应用远程批准后回报——等待标记
    // 随之消失（working＝继续干活）。桥内合成事件，不是 Claude 官方 hook 名。
    if (event === "approval-resolved") {
      return { sessionId, source, status: "working", currentAction: null };
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
    // 出错词（spec 0018-4 票 #174 顺手项）：只认**明确字面** `error`（有明确信号才映射，
    // 不造词）；codex notify 的其他形态一律照旧跳过。
    if (type === "error") {
      const patch = { sessionId, source, status: "error" };
      if (typeof body.summary === "string" && body.summary.trim()) patch.summary = body.summary.trim();
      return patch;
    }
    return null;
  }
  // DSH（ADR 0010）：映射在 adapters/dsh/dsh-events.mjs 的纯函数里（fixture 判例锁语义）。
  if (source === "dsh") return mapDshHookToPatch(body);
  return null;
}

/**
 * 来源能力表与生效折扣（票 #172/#174/#176）：纯定义在 capabilities.mjs（判例可直引），
 * 这里重导出保持既有 API 面。
 */
import { capabilitiesFor, SOURCE_CAPABILITIES } from "./capabilities.mjs";
export { capabilitiesFor, SOURCE_CAPABILITIES };

/**
 * 会话动作（Remote Approval 的请求面，spec 0018-4 / ADR 0009）：手机 `POST /action`
 * 批准（同意/拒绝/选中选项）落这里，PreToolUse 钩子经 `GET /action/pending` 取走**一次性**
 * 决定（[ACTION_TTL_MS] 内有效、取走即清）。批准是唯一的写方向，且只做批准类应答——
 * 自由文字输入/发 prompt/按键在契约里根本不存在（ADR 0009 红线）。
 */
const ACTION_TTL_MS = Number(process.env.BRIDGE_ACTION_TTL_MS || 120_000);
/** @type {Map<string, {requestId: string, action: string, optionId: string|null, expiresAt: number}>} */
const pendingActions = new Map();

/** 手机侧「在线看护」最近一次露面时刻（/events 长轮询或 /snapshot）：批准等待窗只对在线手机开。 */
let lastPhoneSeenAt = 0;
const PHONE_SEEN_WINDOW_MS = 90_000;

function phoneSeen() {
  lastPhoneSeenAt = Date.now();
}

function phoneWatching() {
  return lastPhoneSeenAt > 0 && Date.now() - lastPhoneSeenAt < PHONE_SEEN_WINDOW_MS;
}

/**
 * DSH 插件活性（票 #176）：/hooks/dsh 转发或 /action/pending&plugin=dsh 心跳即露面；
 * 失联超窗后 dsh 的 approve 声明自动收回（快照收紧、动作回 unsupported——不悬挂）。
 */
let dshPluginSeenAt = 0;
const DSH_PLUGIN_SEEN_WINDOW_MS = Number(process.env.DSH_PLUGIN_SEEN_WINDOW_MS || 90_000);

function dshPluginSeen() {
  dshPluginSeenAt = Date.now();
}

function dshPluginLive() {
  return dshPluginSeenAt > 0 && Date.now() - dshPluginSeenAt < DSH_PLUGIN_SEEN_WINDOW_MS;
}

/**
 * 会话动作回执判定（导出供判例锁契约）：合法动作 + 在册 + 来源声明 approve ⇒ accepted；
 * 其余各有明确回执词——`unknown-session`（不在册）/ `unsupported`（来源没声明 approve）/
 * `bad-request`（动作词不认识、select 缺选项、缺会话键）。**回执恒定、不悬挂**。
 */
export function actionReceiptFor(sessionId, action, optionId, sourceOf, capabilities) {
  if (typeof sessionId !== "string" || !sessionId) return "bad-request";
  if (action !== "approve" && action !== "reject" && action !== "select") return "bad-request";
  if (action === "select" && (typeof optionId !== "string" || !optionId)) return "bad-request";
  const source = sourceOf(sessionId);
  if (source === undefined) return "unknown-session";
  const caps = capabilities[source] || [];
  return caps.includes("approve") ? "accepted" : "unsupported";
}

function consumePendingAction(sessionId) {
  const entry = pendingActions.get(sessionId);
  if (!entry) return null;
  pendingActions.delete(sessionId);
  if (entry.expiresAt <= Date.now()) return null;
  return entry;
}

/** 当前在册会话快照：只读、不带正文，按 latestBySession 的键集投影最小字段；附来源能力表。 */
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
  return { sessions, capabilities: capabilitiesFor(dshPluginLive()) };
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
    // HEAD 也认（票 #171）：隧道探活本该用 HEAD（不要正文），而 Cloudflare 的隧道对
    // 不支持的方法直接回 404——原先只认 GET 时，探活恒 404、托盘图标永远停在琥珀黄（实测）。
    if ((req.method === "GET" || req.method === "HEAD") && url.pathname === "/health") {
      res.writeHead(200).end(req.method === "HEAD" ? undefined : "ok");
      return;
    }
    if (req.method === "GET" && url.pathname === "/snapshot") {
      phoneSeen();
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(sessionSnapshot()));
      return;
    }
    if (req.method === "GET" && url.pathname === "/events") {
      phoneSeen();
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
    // 会话动作（Remote Approval，spec 0018-4 / ADR 0009）：手机批准的唯一写方向。
    // 契约：{sessionId, requestId, action: approve|reject|select, optionId?} → 恒有明确回执
    // {ok, receipt: accepted|unknown-session|unsupported|bad-request, requestId}——不悬挂。
    if (req.method === "POST" && url.pathname === "/action") {
      const raw = await readBody(req);
      let body;
      try {
        body = JSON.parse(raw || "{}");
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"ok":false,"receipt":"bad-request"}');
        return;
      }
      const sessionId = body.sessionId;
      const action = body.action;
      const optionId = typeof body.optionId === "string" && body.optionId ? body.optionId : null;
      const requestId = typeof body.requestId === "string" && body.requestId ? body.requestId : null;
      const receipt = actionReceiptFor(
        sessionId,
        action,
        optionId,
        (id) => (latestBySession.has(id) ? latestBySession.get(id).source ?? null : undefined),
        capabilitiesFor(dshPluginLive()),
      );
      if (receipt !== "accepted") {
        res.writeHead(200, { "Content-Type": "application/json" })
          .end(JSON.stringify({ ok: false, receipt, requestId }));
        return;
      }
      pendingActions.set(sessionId, {
        requestId,
        action,
        optionId,
        expiresAt: Date.now() + ACTION_TTL_MS,
      });
      log(`action accepted session=${sessionId} action=${action} requestId=${requestId}`);
      res.writeHead(200, { "Content-Type": "application/json" })
        .end(JSON.stringify({ ok: true, receipt: "accepted", requestId }));
      return;
    }
    // PreToolUse 钩子取远程批准决定（票 #174 本地批准通道）：一次性、过期即无。
    // 回 {armed, waitMs, decision?, optionId?}：有决定立即回；没有时 waitMs>0 才让钩子
    // 开批准等待窗（armed＝手机在线看护；缺省 0＝零等待，正常工作流零打扰）。
    if (req.method === "GET" && url.pathname === "/action/pending") {
      if (url.searchParams.get("plugin") === "dsh") dshPluginSeen(); // 插件活性心跳（票 #176）
      const sessionId = url.searchParams.get("sessionId") || "";
      const entry = consumePendingAction(sessionId);
      const armed = phoneWatching();
      const waitMs = Number(process.env.BRIDGE_APPROVE_WAIT_MS || 0);
      const payload = entry
        ? {
            armed,
            waitMs: 0,
            decision: entry.action === "reject" ? "deny" : "allow",
            optionId: entry.optionId,
            requestId: entry.requestId,
          }
        : { armed, waitMs: armed && waitMs > 0 ? waitMs : 0 };
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(payload));
      return;
    }
    // hooks 转发入口（票 #118/#119）：notify-dispatch / claude-hook.mjs POST 到这里，
    // 映射为统一会话事件（部分补丁，缺字段由会话最新态回填）。不认识的载荷 → 202 空操作。
    if (req.method === "POST" && (url.pathname === "/hooks/claude" || url.pathname === "/hooks/codex" || url.pathname === "/hooks/dsh")) {
      const source = url.pathname.slice("/hooks/".length);
      if (source === "dsh" && !wantDsh) {
        res.writeHead(404).end();
        return;
      }
      if (source === "dsh") dshPluginSeen(); // 插件活性（票 #176）：转发即露面
      const raw = await readBody(req);
      let body;
      try {
        body = JSON.parse(raw || "{}");
      } catch {
        res.writeHead(400).end();
        return;
      }
      // DSH 会话退出（spec 0018-1）：不是状态补丁——从在册与问答流里摘掉，/snapshot 不再列出
      // （手机重连对账据此清锁：「锁定的 DSH 会话在电脑端消失自动清锁」的桥侧前提）。
      if (source === "dsh") {
        const removal = dshRemovalFromHook(body);
        if (removal) {
          latestBySession.delete(removal.sessionId);
          turnsBySession.delete(removal.sessionId);
          res.writeHead(200, { "Content-Type": "application/json" })
            .end(JSON.stringify({ ok: true, removed: true }));
          return;
        }
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

// ── 托盘与隧道（票 #171）──────────────────────────────────────────────────────
// 桥没有窗口：任务栏托盘图标是唯一的人机界面（图标在＝桥在，见 tray.ps1）。
// 隧道看门狗：隧道进程退出即重拉一条——2026-09-29 实测隧道死掉后桥会照旧跑，
// 手机侧只剩 http 530，只能人工重启计划任务；这条自愈就是补这个洞。

/** 隧道进程句柄、隧道就绪事实、地址与上一次已弹气泡的地址。 */
// 桥**启动前**那个隧道地址：只用来判断"地址是不是真的变了"（气泡只在真变时弹）。
// 它**不是**当前地址——桥刚起来时隧道还没连上，拿它当当前地址会让托盘面板显示一个已失效的域名。
const urlBeforeStart = readTrayState()?.url || "";
/** 当前隧道地址（拿到之前为 null：托盘显示"隧道未就绪"，探活不发请求）。 */
const tunnel = { child: null, ready: false, url: null, lastBalloon: null };

/** 把当前事实写进托盘状态文件（就绪与否 + 地址）；失败不掀桌子。 */
function syncTray() {
  setTrayState({ url: tunnel.url, port: PORT, log: LOG_FILE, ready: tunnel.ready }, log);
}

/**
 * 记录隧道 URL 并自动推给手机（桥重启/换 URL 时免手工重配；adb 不在则跳过）。
 * 地址**真的变了**才弹托盘气泡——同一地址的重启不吵人。
 */
let lastPushedUrl = null;
function publishTunnelUrl(url, log) {
  // 比较基准是"桥启动前那个地址"（urlBeforeStart），不是"本进程上一次的地址"——
  // 否则桥一重启，第一个 URL 永远算"没变"，换了域名的气泡就丢了（无人值守下机主无从知道要改手机端）。
  const previous = tunnel.url || urlBeforeStart;
  log(`隧道 URL: ${url}`);
  tunnel.url = url;
  try {
    writeFileSync(join(HERE, "bridge.url"), url + "\n");
  } catch (e) {
    log(`bridge.url 写入失败 ${e?.message || e}`);
  }
  if (previous && previous !== url && tunnel.lastBalloon !== url) {
    tunnel.lastBalloon = url;
    balloon(`桥地址已更换，手机端需要新地址：${url}`, log);
  }
  syncTray();
  if (url === lastPushedUrl) return;
  lastPushedUrl = url;
  pushUrlToPhone(url, log, 0);
}

/**
 * 逐个候选 adb 试推送；找不到 adb（ENOENT/非 0 退出）就换下一个。
 *
 * 多设备（USB 线 + 无线调试同时在线）时必须靠 `ADB_SERIAL` 指一台：不给的话 adb 直接
 * "more than one device" 失败，广播发不出去——2026-09-29 实测就是这样把自动推送打哑的，
 * 而那正是"手机端手填桥地址"要兜的坑（两条路都在，别让人只能靠手填）。
 */
function pushUrlToPhone(url, log, index) {
  const adb = adbCandidates()[index];
  if (!adb) {
    log("未找到可用的 adb：URL 已落 bridge.url，手机端可手填这一条");
    return;
  }
  const args = adbArgs(url);
  if (!args.includes("-s")) {
    log("adb 目标不唯一（ADB_SERIAL 未设且连着一台以上设备）：URL 已落 bridge.url，可在手机设置页手填");
  }
  let child;
  try {
    child = spawn(adb, args, { stdio: "ignore" });
  } catch {
    pushUrlToPhone(url, log, index + 1);
    return;
  }
  child.on("error", () => pushUrlToPhone(url, log, index + 1));
  child.on("exit", (code) => {
    if (code === 0) {
      log(`已自动推送隧道 URL 到手机（${adb}${args.includes("-s") ? ` -s ${args[args.indexOf("-s") + 1]}` : ""}）`);
    } else {
      pushUrlToPhone(url, log, index + 1);
    }
  });
}

/** 通用隧道子进程接线：逐行解析、出 URL 上报、退出交给看门狗重拉。 */
function spawnTunnelChild(bin, args, tag, log, onLine) {
  const child = spawn(bin, args, { stdio: ["ignore", "pipe", "pipe"], env: process.env });
  tunnel.child = child;
  const handle = (chunk) => {
    for (const line of String(chunk).split(/\r?\n/)) {
      onLine(line);
      if (line.trim()) log(`${tag}: ${line.trim()}`);
    }
  };
  child.stdout?.on("data", handle);
  child.stderr?.on("data", handle);
  child.on("error", (e) => {
    log(`${tag} 启动失败（${e?.code || e?.message}）`);
    if (e?.code === "ENOENT") {
      log(
        tag === "cloudflared"
          ? "下载: https://github.com/cloudflare/cloudflared/releases 放到 tools/bridge/bin/cloudflared.exe"
          : "下载: https://github.com/ntnj/tunwg/releases 放到 tools/bridge/bin/tunwg.exe 或加入 PATH",
      );
    }
  });
  child.on("exit", (code) => {
    if (tunnel.child === child) tunnel.child = null;
    handleTunnelExit(code, log);
  });
}

/** cloudflared quick tunnel（默认；2026-09-28 实测大陆可达：PC/手机 Wi-Fi/手机蜂窝全通）。 */
function startCloudflared(log) {
  const localBin = join(HERE, "bin", "cloudflared.exe");
  const bin = process.env.CLOUDFLARED_BIN || (existsSync(localBin) ? localBin : "cloudflared");
  log(`拉起 cloudflared（${bin}）`);
  spawnTunnelChild(
    bin,
    ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${PORT}`],
    "cloudflared",
    log,
    (line) => {
      // quick tunnel URL 形如 https://<words>.trycloudflare.com
      const m = line.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
      if (m) publishTunnelUrl(m[0], log);
    },
  );
}

/** tunwg 隧道（备选：BRIDGE_TUNNEL=tunwg；公共实例 2026-09-28 实测被墙/403，见 README 排障）。 */
function startTunwg(log) {
  const localBin = join(HERE, "bin", "tunwg.exe");
  const bin = process.env.TUNWG_BIN || (existsSync(localBin) ? localBin : "tunwg");
  log(`拉起 tunwg（${bin}）`);
  spawnTunnelChild(bin, ["-p", String(PORT)], "tunwg", log, (line) => {
    // 真实隧道 URL 形如 https://<sub>.l.tunwg.com；错误信息里的 /add 端点不是 URL，别吞。
    const m = line.match(/https:\/\/[a-z0-9]+\.l\.tunwg\.com/);
    if (m) publishTunnelUrl(m[0].replace(/[.,)"]+$/, ""), log);
  });
}

function startTunnel(log) {
  const kind = (process.env.BRIDGE_TUNNEL || "cf").toLowerCase();
  if (kind === "tunwg") startTunwg(log);
  else startCloudflared(log);
}

/** 隧道进程退出：先标记未就绪（图标转黄），退避后重拉一条（新地址自动推手机 + 弹气泡）。 */
function handleTunnelExit(code, log) {
  if (tunnel.child) return; // 主动换隧道：新进程已接手，别把看门狗当成崩溃
  tunnel.ready = false;
  syncTray();
  log(`隧道进程退出 code=${code}，${TUNNEL_RETRY_MS / 1000}s 后自动重拉一条`);
  trayWatchdogTimer = setTimeout(() => {
    trayWatchdogTimer = null;
    if (process.env.BRIDGE_NO_WATCHDOG === "1") {
      log("看门狗已关（BRIDGE_NO_WATCHDOG=1）：隧道不会自动重拉");
      return;
    }
    log("看门狗重拉隧道");
    startTunnel(log);
  }, TUNNEL_RETRY_MS);
}

/** 停掉当前隧道进程（重拉、或桥退出时用）：先摘句柄，免得 exit 触发看门狗。 */
function stopTunnelChild() {
  const child = tunnel.child;
  tunnel.child = null;
  try {
    child?.kill();
  } catch {
    /* 已经没了 */
  }
}

/**
 * 每 TUNNEL_PROBE_MS 探一次隧道 /health，结果写进托盘状态（图标两态的依据）。
 *
 * 两个踩过的坑都在这儿（2026-09-29 实测）：
 *   ① 按 URL 的协议挑 http/https 模块——隧道地址是 https，用 node:http 打它会同步抛
 *      `ERR_INVALID_PROTOCOL`，它在定时器回调里又没人接，**整个桥进程会被打崩**（桥起来 20s 后自死、手机只剩 530）；
 *   ② 探活必须用 HEAD 且服务端要认 HEAD——Cloudflare 隧道对不支持的方法直接回 404，
 *      服务端原先只认 GET 时探活恒 404、图标永远转不了绿。
 * 同步抛错也要挡住：探活失败只该让图标转黄，不该让桥消失。
 */
function startTunnelProbe(log) {
  const probe = () => {
    if (!wantTunnel) return;
    const url = tunnel.url;
    if (!url) {
      if (tunnel.ready) {
        tunnel.ready = false;
        syncTray();
      }
      return;
    }
    const markDown = (why) => {
      if (tunnel.ready) {
        tunnel.ready = false;
        syncTray();
        log(`隧道探活：不通（${why}）`);
      }
    };
    try {
      const client = url.startsWith("https:") ? https : http;
      const req = client.request(`${url}/health`, { method: "HEAD", timeout: 8000 }, (res) => {
        res.resume();
        const ok = res.statusCode === 200;
        if (ok !== tunnel.ready) {
          tunnel.ready = ok;
          syncTray();
          log(`隧道探活：${ok ? "通" : `不通（HTTP ${res.statusCode}）`}`);
        }
      });
      req.on("timeout", () => req.destroy());
      req.on("error", (e) => markDown(`连接失败 ${e?.code || e?.message || ""}`.trim()));
      req.end();
    } catch (e) {
      markDown(`探活异常 ${e?.code || e?.message || ""}`.trim());
    }
  };
  tunnelProbe = setInterval(probe, TUNNEL_PROBE_MS);
  tunnelProbe.unref?.();
  probe();
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
  if (wantTunnel) {
    // 托盘先起：图标在＝桥在；地址一拿到就写进状态文件（图标同时从黄转绿）。
    trayStarted = startTray({ port: PORT, log: LOG_FILE }, log);
    if (trayStarted) {
      syncTray();
      balloon("RearCue PC 桥已启动，隧道连接中……", log);
    }
    startTunnel(log);
    startTunnelProbe(log);
  } else {
    log("隧道关闭（--no-tunnel）：仅本机/LAN 可达（托盘图标不出现）");
  }
});

// 退出路径：收掉隧道与托盘，不留孤儿进程/孤儿图标。
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(sig, () => {
    log(`收到 ${sig}，收尾退出`);
    if (trayWatchdogTimer) clearTimeout(trayWatchdogTimer);
    if (tunnelProbe) clearInterval(tunnelProbe);
    stopTunnelChild();
    if (trayStarted) stopTray();
    process.exit(0);
  });
}


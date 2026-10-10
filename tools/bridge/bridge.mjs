#!/usr/bin/env node
/**
 * RearCue PC 桥（ADR 0006 / 票 #116）：Codex 与 Claude Desktop 共用的常驻采集进程。
 *
 * 本票交付基础设施 + 示例事件源；Codex / Claude Desktop 适配器（#118/#119）往
 * appendEvent 里灌同一种「统一会话事件」即可，手机端零特例。
 *
 * 统一会话事件（唯一契约，字段与手机侧 BridgeEventCodec 对齐）：
 *   { sessionId, source: codex|claude|dsh|zcode|null, status: working|waiting|idle|error,
 *     title?, workspace?, currentAction?, latestReply?, turns?, summary?, updatedAt? }
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
 *   GET  /history?sessionId=     当前会话**全量问答历史**（票 #177，回看从头到尾；未知会话回空列表）
 *   POST /read                   会话已阅回执（打开正文，跨端共享）
 *   POST /inject                 灌一条会话事件（适配器/示例源/调试）
 *   POST /hooks/claude           Claude hooks 转发（Stop→idle / Notification→waiting）
 *   POST /hooks/codex            Codex notify 转发（turn-complete→idle / approval→waiting）
 *   POST /hooks/dsh              DSH 只读插件转发（ADR 0010；会话退出会摘出在册快照）
 *   POST /action                 会话动作——手机批准（同意/拒绝/选中选项）的批准类写面，
 *                                恒回明确回执（accepted|unknown-session|unsupported|bad-request）
 *   GET  /action/pending         PreToolUse 钩子取远程批准决定（一次性、过期即无）
 *   GET  /codex/options          Remote Codex Conversation 的项目/模型
 *   POST /codex/*                 发起、追问、停止、失败空会话删除
 *   GET  /health                 存活探测
 *
 * 隧道：默认拉起 **cloudflared quick tunnel**（`https://<随机>.trycloudflare.com`——
 * 2026-09-28 实测大陆可达：PC / 手机 Wi-Fi / 手机蜂窝三路全通）；拿到 URL 后落
 * bridge.url 并**自动 adb 推给手机**（quick tunnel 重启换 URL 也免手工重配）。
 * `BRIDGE_TUNNEL=tunwg` 切回 tunwg（key 派生 URL 稳定，但公共实例 2026-09-28 被墙/403，
 * 见 README 排障）；`--no-tunnel` 只监听本机（LAN/adb reverse 调试用）。
 *
 * 隧道看门狗（票 #171）：隧道进程退出即自动重拉一条，新地址自动推手机 + 托盘弹气泡 + 飞书通知；
 * 每 15s 探一次隧道 /health，把「就绪」写进托盘状态。**但地址真的变了才弹气泡**——
 * 探活抖动不该吵人。
 *
 * 托盘（票 #171）：桥没有窗口，任务栏托盘是它唯一的人机界面（图标在＝桥在）；
 * 状态与气泡的契约在 tray.mjs / tray.ps1 两侧对齐。开机自启见 enable-autostart.ps1。
 * 图标三态（2026-09-30 起，配色同日机主定夺对调）：手机已连＝薄荷绿、就绪未连＝晴空蓝、
 * 隧道未就绪＝琥珀黄。
 */
import http from "node:http";
import https from "node:https";
import { spawn, spawnSync } from "node:child_process";
import { appendFileSync, writeFileSync, existsSync, readFileSync, rmSync } from "node:fs";
import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startCodexAdapter, mergeCodexHistory } from "./adapters/codex.mjs";
import { SessionSpeechFacts } from "./adapters/speech-facts.mjs";
import { CodexAppServerControl } from "./adapters/codex-control.mjs";
import { startClaudeAdapter } from "./adapters/claude.mjs";
import { startZCodeAdapter } from "./adapters/zcode.mjs";
import { startDshRosterAdapter } from "./adapters/dsh/dsh-roster.mjs";
import { createTurnLog, applyTurnLogPatch } from "./adapters/turn-log.mjs";
import { mapDshHookToPatch, dshRemovalFromHook } from "./adapters/dsh/dsh-events.mjs";
import { membershipFromExplicitHook, membershipFact, SourceMembershipLedger } from "./adapters/source-membership.mjs";
import { adbArgs, adbCandidates, balloon, readTrayState, setTrayState, startTray, stopTray } from "./tray.mjs";
import { notifyFeishuBridgeUrl } from "./feishu-notify.mjs";
import { MirrorRoster, mirrorKey } from "./mirror-roster.mjs";
import { SessionLibrary } from "./session-library.mjs";
import { startMirrorManager } from "./mirror-manager.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
// 默认 18787：本机 8787 已被其它代理占用（实测 EADDRINUSE），避开。
const PORT = Number(process.env.BRIDGE_PORT || 18787);
const HOST = process.env.BRIDGE_HOST || "127.0.0.1";
const HOLD_MS = 25_000; // 长轮询持有上限（手机读超时 35s，留裕量）
const MAX_EVENTS = 5000; // 事件环容量：只留最近 N 条（since=0 的重放即「近史快照」）
/**
 * 事件环的容量（字节）与单页上限（issue #309：手机 bridge-poll OOM 崩进程）。
 * DSH 事件带整段问答流明细，单条可达数百 KB；5000 条环 × 几百 KB ＝ 上百 MB，
 * 而手机首连（since=0）一次拉整环 ＋ kotlinx JSON 成树两头吃内存 → OutOfMemoryError，
 * 且崩后重启游标复位 0，再拉全量再崩（死循环，界面永远停在「连接中」）。
 * 因此：环按字节封顶（重放体量有硬上限），单页也按条数与字节双封顶（一次一页，增量推进）。
 * BRIDGE_EVENTS_PAGE_BYTES / BRIDGE_EVENTS_RING_BYTES 可覆盖（测试与运维调参缝）。
 */
const EVENTS_PAGE_BYTES = Number(process.env.BRIDGE_EVENTS_PAGE_BYTES || 5_000_000);
const EVENTS_RING_BYTES = Number(process.env.BRIDGE_EVENTS_RING_BYTES || 48_000_000);
/** 单页默认条数上限（旧手机端不发 limit，也拿这个兜底）。 */
const DEFAULT_EVENTS_PAGE = 300;
const STATUSES = new Set(["working", "waiting", "idle", "error"]);
/** 会话已阅状态（ADR 0018）：read|unread；旧事件缺省 read。 */
const READ_STATES = new Set(["read", "unread"]);
// seq 持久化（票 #118 评审）：重启归零会让手机游标（since=N）永远追不上新小 id——
// 长轮询彻底失明。落盘 bridge.seq，重启续号（事件环不持久，丢失仅限近史回放）。
// BRIDGE_SEQ_FILE 可覆盖（测试实例与生产实例分文件，互不串号）。
const SEQ_FILE = process.env.BRIDGE_SEQ_FILE || join(HERE, "bridge.seq");
// 会话身份表（issue #306）：{sessionId: {workspace, title}} 落盘。**为什么必须持久化**：DSH 的
// 标题只在会话开场发一次（fallback ＋ provider 各一条，此后不再重发），而桥重启会清空内存态——
// 不落盘的话每次桥重启，全部 DSH 会话都会退回「未命名会话」且再也补不回来。
// 只存身份（目录名/真标题），不存状态与窗口：重启后仍要等一条新事件才把该会话带回在册/上屏。
const IDENTITY_FILE = process.env.BRIDGE_IDENTITY_FILE || join(HERE, "bridge.identity.json");
/** 身份表上限：只服务「重启后恢复名字」，按插入序裁老。 */
const MAX_IDENTITY = 200;
// 桥自己的日志路径（常驻启动器经 BRIDGE_LOG 传进来，托盘的「打开 bridge.log」菜单用它）。
const LOG_FILE = process.env.BRIDGE_LOG || join(HERE, "bridge.log");
// bridge.url 落点（BRIDGE_URL_FILE 可覆盖：隔离实测实例分文件，不盖生产地址）。
const URL_FILE = process.env.BRIDGE_URL_FILE || join(HERE, "bridge.url");
// 访问凭据只用于主动写面（发 prompt / 停止 / 删除 / 远程批准），并藏在 URL fragment，
// fragment 不会发给隧道服务端。health 仍免凭据，便于现有探活。
const ACCESS_TOKEN_FILE = process.env.BRIDGE_ACCESS_TOKEN_FILE || join(HERE, "bridge.token");
// 飞书地址通知的去重状态（票 #303）：只存脱敏 URL，绝不把访问 token 落进状态文件。
const FEISHU_STATE_FILE = process.env.BRIDGE_FEISHU_STATE_FILE || join(HERE, "bridge.feishu-url");
const ACCESS_TOKEN = process.env.BRIDGE_ACCESS_TOKEN || loadAccessToken();
const MIRROR_FILE = process.env.BRIDGE_MIRROR_FILE || `${SEQ_FILE}.mirror.json`;
const MANAGER_FILE = process.env.BRIDGE_MANAGER_FILE || `${SEQ_FILE}.manager.json`;

function loadAccessToken() {
  try {
    const existing = readFileSync(ACCESS_TOKEN_FILE, "utf8").trim();
    if (existing) return existing;
  } catch { /* first run */ }
  const token = randomBytes(32).toString("base64url");
  try { writeFileSync(ACCESS_TOKEN_FILE, token + "\n", { mode: 0o600 }); } catch { /* env/test fallback */ }
  return token;
}

function tokenMatches(req) {
  const raw = req.headers.authorization || "";
  const got = Buffer.from(raw.replace(/^Bearer\s+/i, ""));
  const expected = Buffer.from(ACCESS_TOKEN);
  return got.length === expected.length && timingSafeEqual(got, expected);
}

function bridgeUrlWithToken(raw) {
  const url = new URL(raw);
  url.hash = `token=${encodeURIComponent(ACCESS_TOKEN)}`;
  return url.toString();
}

function redactBridgeUrl(raw) {
  try {
    const url = new URL(raw);
    url.hash = "";
    return url.toString();
  } catch {
    return raw;
  }
}
function writeAuthorized(req, url) {
  if (req.method !== "POST") return true;
  if (url.pathname === "/action" || url.pathname.startsWith("/codex/")) return tokenMatches(req);
  return true;
}
function unauthorized(res) {
  res.writeHead(401, { "Content-Type": "application/json" }).end(`{"ok":false,"error":"unauthorized"}`);
}


/**
 * 日志：**直写文件**，不走 stdout。
 *
 * 为什么（2026-09-29 实测）：常驻形态下 stdout 是管道（
ode ... | Out-File`），
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

// 手动停止旗（#189 评审修复）：托盘右键「退出桥」先在 `<LOG_FILE>.stopflag` 立旗再硬杀桥，
// start-bridge.cmd 的重试引擎见旗即当「干净停」、不重来（堵住手动跑启动器时无任务可停的洞）。
// 桥启动时清残旗——上一轮的手动停不能把下一轮的崩溃也误判成手动停。
try {
  rmSync(`${LOG_FILE}.stopflag`, { force: true });
} catch {
  /* 清不掉不挡启动 */
}

// ── 退出留痕（spec 0019-1 / #185）────────────────────────────────────────────
// 「图标在 ⇔ 桥在」的事后归因：任何退出路径都该能从日志读出「谁、用什么方式弄死的」。
// Windows 的现实：taskkill /F / Stop-Process -Force 是硬杀（TerminateProcess），受害者
// 自己收不到任何信号——这类只能靠目击者（托盘）留痕；能真正走进桥进程的退出（控制台
// Ctrl+C／关窗口的 SIGHUP，以及后续票的主动自关）在这里统一走 shutdown() 留痕。
let shuttingDown = false;

/** 父进程链（尽力而为）：沿 ParentProcessId 上溯几层，拿不到就只留 ppid。 */
function parentChain() {
  if (process.platform !== "win32") return `ppid=${process.ppid}`;
  const script = [
    `$id = ${process.ppid}; $parts = @()`,
    "for ($i = 0; $i -lt 6 -and $id -gt 0; $i++) {",
    '  $p = Get-CimInstance Win32_Process -Filter "ProcessId=$id"',
    "  if (-not $p) { break }",
    '  $parts += "pid$($p.ProcessId):$($p.Name)"',
    "  $id = $p.ParentProcessId",
    "}",
    "$parts -join ' > '",
  ].join("\n");
  try {
    const r = spawnSync("powershell.exe", ["-NoProfile", "-Command", script], {
      encoding: "utf8",
      timeout: 6000,
      windowsHide: true,
    });
    const out = String(r.stdout || "").trim();
    if (out) return out;
  } catch {
    /* 链拿不到就只留 ppid：留痕不能拖垮退出 */
  }
  return `ppid=${process.ppid}`;
}

/** 桥侧退出留痕：一行结构化事实（reason 可归因，读日志就能对上号）。 */
function logExitTrail(reason, extra = "") {
  const fields = [`reason=${reason}`, extra, `pid=${process.pid}`, `chain=${chainSnapshot || parentChain()}`].filter(Boolean);
  log(`留痕｜桥退出｜${fields.join(" ")}`);
}

// 父链等不到退出再查：SIGHUP 收尾只有几秒，spawnSync 一个 powershell 冷启动
// 常被掐断（2026-09-30 16:21 退出时查只剩 chain=ppid=<数字>；同日 17:22 部署
// 验证：listen 前同步查也失败——计划任务冷环境 powershell 起得慢）。父链不会变：
// 启动时同步快照一次（尽力），5s 后异步补查覆盖——此时系统已热，查询基本必成。
let chainSnapshot = null;

function backfillChain() {
  const chain = parentChain();
  if (chainSnapshot === chain || chain.startsWith("ppid=")) return;
  chainSnapshot = chain;
  log(`留痕｜父链补全｜chain=${chain}`);
}

/**
 * 桥的优雅退出唯一入口（spec 0019 起）：留痕 → 收隧道/托盘 → 退出。
 * 后续票（#188 自关＋临终通知、#189 重来）都把各自的 reason 挂在这里复用，别散落 process.exit。
 *
 * 退出码契约（#189，start-bridge.cmd 的重来引擎按它判定）：0＝干净停，人亲手停的
 * （external-signal＝Ctrl+C／托盘右键收它）不该被拉回来——不重来；非零＝可重来：
 * 自关（self-shutdown）传 75，崩溃天然非零。启动器见非零等 30s 重拉、最多 3 次。
 */
async function shutdown({ reason, extra = "", legacyNote = "", exitCode = 0 } = {}) {
  if (shuttingDown) return;
  shuttingDown = true;
  if (legacyNote) log(legacyNote);
  logExitTrail(reason, extra);
  if (trayWatchdogTimer) clearTimeout(trayWatchdogTimer);
  if (tunnelProbe) clearInterval(tunnelProbe);
  stopTunnelChild();
  if (trayStarted) stopTray();
  await codexControl?.stop();
  process.exit(exitCode);
}

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
// ZCode（票 #240）：app-server roster + 只读 model-io 尾部；--no-zcode 供测试隔离。
const wantZCode = !process.argv.includes("--no-zcode");
// DSH 插件推正文/等待事件；生产启动器另开本机只读名册对账，补桥重启漏帧与归档。
// --no-dsh 关闭该来源；BRIDGE_DSH_ROSTER=1 开名册对账（隔离实例默认不开）。
const wantDsh = !process.argv.includes("--no-dsh");
const wantDshRoster = wantDsh && process.env.BRIDGE_DSH_ROSTER === "1";

// 功能启用前已经出现的历史会话按已阅起算；此后的新回答才产生「空闲·没阅」（ADR 0018）。
const BRIDGE_STARTED_AT = Date.now();
let mirrorBootstrap = [];
let mirrorActivatedAt = BRIDGE_STARTED_AT;
try {
  const bootstrap = JSON.parse(readFileSync(process.env.BRIDGE_MIRROR_BOOTSTRAP || `${MIRROR_FILE}.bootstrap`, "utf8"));
  mirrorBootstrap = bootstrap.sessions || [];
  if (Number.isSafeInteger(bootstrap.activatedAt) && bootstrap.activatedAt > 0 && bootstrap.activatedAt <= BRIDGE_STARTED_AT) mirrorActivatedAt = bootstrap.activatedAt;
} catch { /* first installation without an old roster */ }
const mirrorRoster = new MirrorRoster(MIRROR_FILE, { now: mirrorActivatedAt, bootstrap: mirrorBootstrap });
const sessionLibrary = new SessionLibrary({ enabled: [
  ...(wantCodex ? ["codex"] : []), ...(wantClaude ? ["claude"] : []),
  ...(wantZCode ? ["zcode"] : []), ...(wantDshRoster ? ["dsh"] : []),
] });
await sessionLibrary.refresh();
const publishedMemberships = new Map();
const mirrorVisible = new Map();
const mirrorReplayBefore = new Map();
const wireEpoch = mirrorRoster.data.activatedAt * 1000;

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

/**
 * 事件环裁剪（issue #309）：先按字节封顶再从头部裁，后按条数封顶——重放体量（since=0）
 * 因此有硬上限。已入环的事件不因裁剪少发给「还在游标后面的手机」以外的读者：裁剪只丢最老的，
 * 手机游标落在其中时从环首续上（可能跳号，但不卡死、不再撑爆内存）。
 */
function trimEvents() {
  let bytes = 0;
  for (const ev of events) bytes += JSON.stringify(ev).length;
  while (events.length > 1 && bytes > EVENTS_RING_BYTES) {
    const [dropped] = events.splice(0, 1);
    bytes -= JSON.stringify(dropped).length;
  }
  if (events.length > MAX_EVENTS) events.splice(0, events.length - MAX_EVENTS);
}

/**
 * 单页取件（issue #309）：从 [since] 之后取事件，条数与字节双封顶，**至少给一条**
 * （单条超大事件不能把手机卡在原地）。返回本页与游标推进值（本页最大 id）。
 */
function eventPage(since, limit) {
  const cap = Number.isFinite(limit) && limit > 0 ? Math.floor(limit) : DEFAULT_EVENTS_PAGE;
  const rest = events.filter((e) => e.id > since);
  const batch = [];
  let bytes = 0;
  for (const ev of rest) {
    if (ev.kind !== "membership" && !mirrorAllows(ev)) continue;
    if (batch.length >= cap) break;
    const size = JSON.stringify(ev).length;
    if (batch.length > 0 && bytes + size > EVENTS_PAGE_BYTES) break;
    batch.push(ev);
    bytes += size;
  }
  const cursor = batch.length > 0 ? batch[batch.length - 1].id : (rest.at(-1)?.id || since);
  return { batch, cursor };
}
/** @type {Set<(v: any[]) => void>} */
const waiters = new Set();
/** 会话最新态（hooks 部分事件回填 workspace/reply 用；codex/claude 适配器与 /hooks 共享）。 */
const latestBySession = new Map();
const speechFacts = new SessionSpeechFacts();
const internalCodexSessions = new Set();
let visibleCodexSessions = wantCodex ? new Set() : null;
const codexRemoteCreated = new Set();
let visibleClaudeSessions = wantClaude ? new Set() : null;
let visibleDshSessions = wantDshRoster ? new Set() : null;
let archivedDshSessions = new Set();
const pendingDshEvents = new Map(); // bounded early content, replayed only after projection confirms a conversation

function holdDshEvent(patch) {
  const sessionId = patch.sessionId;
  if (!sessionId || patch.kind === "membership" || patch.membership) return false;
  const bytes = Buffer.byteLength(JSON.stringify(patch));
  if (bytes > 262144) return false;
  if (!pendingDshEvents.has(sessionId) && pendingDshEvents.size >= 32) pendingDshEvents.delete(pendingDshEvents.keys().next().value);
  const pending = pendingDshEvents.get(sessionId) || { events: [], bytes: 0, expiresAt: Date.now() + 120000 };
  pending.events.push({ patch: { ...patch, updatedAt: patch.updatedAt ?? Date.now() }, bytes });
  pending.bytes += bytes;
  while (pending.events.length > 32 || pending.bytes > 262144) pending.bytes -= pending.events.shift().bytes;
  pendingDshEvents.set(sessionId, pending);
  return true;
}

function flushDshEvents(sessionId) {
  const pending = pendingDshEvents.get(sessionId);
  pendingDshEvents.delete(sessionId);
  if (!pending || pending.expiresAt < Date.now()) return;
  for (const { patch } of pending.events) appendEvent(patch);
}
/** 最近活跃的 codex 会话（agent-turn-complete 载荷无 sessionId，回落到这里）。 */
let lastCodexSession = null;
/**
 * 每会话的问答流窗口（spec 0017 / 票 #169）：桥持有、事件里整份发出。
 * 适配器只给增量（userText/assistantText/assistantDelta），攒与裁剪都在这里，
 * 免得两个适配器各写一套窗口逻辑。
 */
const turnsBySession = new Map();
/** 最近一次助手输出时刻与最近已阅时刻（只用于判「空闲·没阅」，不单独上 wire）。 */
const replyAtBySession = new Map();
const readAtBySession = new Map();
/** 来源在册账本：旧代/迟到活动不能把已出册会话写回来（spec 0023 / 票 #236）。 */
const membershipLedger = new SourceMembershipLedger();

function mirrorRow(ev) {
  const row = { ...(sessionLibrary.get(ev.source, ev.sessionId) || {}), ...ev };
  // Adapter-disabled debug instances have no source metadata to establish birth.
  if (!sessionLibrary.enabled.has(ev.source) && !row.createdAt) row.createdAt = mirrorRoster.data.activatedAt;
  return row;
}
function codexSourceVisible(sessionId) {
  return visibleCodexSessions === null || visibleCodexSessions.has(sessionId) || codexRemoteCreated.has(sessionId);
}
function mirrorAllows(ev) {
  return ev && !internalCodexSessions.has(ev.sessionId) &&
    !(ev.source === "codex" && !codexSourceVisible(ev.sessionId)) &&
    !(ev.source === "claude" && visibleClaudeSessions !== null && !visibleClaudeSessions.has(ev.sessionId)) &&
    !(ev.source === "dsh" && visibleDshSessions !== null && !visibleDshSessions.has(ev.sessionId)) &&
    !membershipLedger.tombstone(ev.source, ev.sessionId) &&
    !sessionLibrary.get(ev.source, ev.sessionId)?.archived && !sessionLibrary.get(ev.source, ev.sessionId)?.internal && mirrorRoster.allows(mirrorRow(ev));
}
function enqueueWire(ev) {
  events.push(ev);
  trimEvents();
  try { writeFileSync(SEQ_FILE, String(seq)); } catch { /* existing cursor fallback */ }
  for (const wake of [...waiters]) wake();
}
function publishMembership(source, sessionId, enabled, sourceFact = {}) {
  if (!["codex", "claude", "dsh", "zcode"].includes(source)) return;
  const id = ++seq;
  const identity = {};
  if (enabled) for (const field of ["status", "workspace", "title", "readState", "updatedAt"]) {
    if (sourceFact[field] !== undefined) identity[field] = sourceFact[field];
  }
  const ev = { ...identity, kind: "membership", source, sourceSessionId: sessionId, sessionId,
    membership: enabled ? "PRESENT" : "ABSENT",
    archiveState: sourceFact.archiveState === "ARCHIVED" ? "ARCHIVED" : enabled ? (sourceFact.archiveState || "ACTIVE") : "UNKNOWN",
    reason: sourceFact.reason || (enabled ? "mirror-restored" : "mirror-removed"),
    generation: wireEpoch + id, revision: id, updatedAt: Date.now(), id };
  publishedMemberships.set(mirrorKey(source, sessionId), ev);
  mirrorVisible.set(mirrorKey(source, sessionId), enabled);
  enqueueWire(ev);
}
function reconcileMirrors() {
  for (const row of sessionLibrary.rows.values()) {
    const remembered = latestBySession.get(row.sessionId);
    const current = remembered?.source === row.source ? remembered : undefined;
    if (!current && !mirrorRoster.allows(row)) continue;
    const state = { ...(current || row), status: STATUSES.has(current?.status) ? current.status : "idle", readState: current?.readState || "read", updatedAt: current?.updatedAt || row.createdAt || 0 };
    const allowed = mirrorAllows(state);
    const key = mirrorKey(row.source, row.sessionId);
    if (mirrorVisible.get(key) === allowed) continue;
    publishMembership(row.source, row.sessionId, allowed);
    if (allowed) {
      mirrorReplayBefore.set(key, Date.now());
      const restored = { ...state, mirrorBaseline: true, voiceEvent: null, voiceReplay: false, id: ++seq, readState: "read" };
      for (const field of ["file", "available", "archived", "createdAt", "isArchived", "valid"]) delete restored[field];
      latestBySession.set(row.sessionId, restored);
      enqueueWire(restored);
    } else pendingActions.delete(row.sessionId);
  }
}

function mergeHistoryTurns(history, live) {
  if (!history?.length) return live || [];
  if (!live?.length) return history;
  const first = live[0];
  let split = -1;
  for (let i = history.length - 1; i >= 0; i--) {
    if (history[i].role === first.role && history[i].text === first.text) { split = i; break; }
  }
  if (split < 0) split = history.findIndex((turn) => turn.ts >= first.ts);
  if (split < 0) split = history.length;
  return [...history.slice(0, split), ...live];
}

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
  return applyTurnLogPatch(turnLogFor(sessionId), partial, ts);
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

function normalizedReadState(value) {
  if (typeof value === "boolean") return value ? "read" : "unread";
  const word = String(value || "").trim().toLowerCase();
  if (word === "unread" || word === "false") return "unread";
  return "read";
}

function appendReadStateEvent(sessionId, readState, updatedAt = Date.now()) {
  const remembered = latestBySession.get(sessionId);
  if (!remembered || !STATUSES.has(remembered.status)) return null;
  const normalized = normalizedReadState(readState);
  const previousReadAt = readAtBySession.get(sessionId) || BRIDGE_STARTED_AT;
  const replyAt = replyAtBySession.get(sessionId) || 0;
  if (normalized === "read") readAtBySession.set(sessionId, updatedAt);
  const unchanged = normalized === remembered.readState &&
    (normalized !== "read" || replyAt <= previousReadAt);
  if (unchanged) return remembered;
  const ev = {
    ...remembered,
    kind: "read-state",
    sessionId,
    readState: normalized,
    updatedAt,
    id: ++seq,
  };
  delete ev.actionExpired;
  latestBySession.set(sessionId, (() => {
    const rememberedCopy = { ...ev };
    delete rememberedCopy.kind;
    return rememberedCopy;
  })());
  events.push(ev);
  trimEvents();
  try {
    writeFileSync(SEQ_FILE, String(seq));
  } catch {
    /* 落盘失败只影响下次重启的续号，不阻塞事件 */
  }
  for (const wake of [...waiters]) wake();
  return ev;
}

/** 读身份表（首跑/坏文件 → 空表：没有身份可恢复，不影响启动）。 */
function loadIdentityTable() {
  try {
    const raw = JSON.parse(readFileSync(IDENTITY_FILE, "utf8"));
    const table = new Map();
    for (const [sessionId, value] of Object.entries(raw)) {
      if (!sessionId || !value || typeof value !== "object") continue;
      const entry = {};
      if (typeof value.workspace === "string" && value.workspace) entry.workspace = value.workspace;
      if (typeof value.title === "string" && value.title) entry.title = value.title;
      if (entry.workspace || entry.title) table.set(sessionId, entry);
    }
    return table;
  } catch {
    return new Map();
  }
}

const identityBySession = loadIdentityTable();

/** 身份落盘（写失败只影响下次重启的名字恢复，不阻塞事件）。 */
function persistIdentity() {
  try {
    writeFileSync(IDENTITY_FILE, JSON.stringify(Object.fromEntries(identityBySession)));
  } catch {
    /* 落盘失败不阻塞桥 */
  }
}

/** 身份记忆：workspace/title 一旦收到就记住——稀疏补丁（两个字段都没带）不许抹掉它。 */
function rememberIdentity(sessionId, ev) {
  const workspace = typeof ev.workspace === "string" && ev.workspace ? ev.workspace : null;
  const title = typeof ev.title === "string" && ev.title ? ev.title : null;
  if (!workspace && !title) return;
  const previous = identityBySession.get(sessionId);
  if (previous && previous.workspace === workspace && previous.title === title) return;
  identityBySession.delete(sessionId); // 重新插入：最近用到的身份留在表尾，超出上限先裁它
  identityBySession.set(sessionId, {
    ...(workspace ? { workspace } : {}),
    ...(title ? { title } : {}),
  });
  while (identityBySession.size > MAX_IDENTITY) {
    identityBySession.delete(identityBySession.keys().next().value);
  }
  persistIdentity();
}

/** 会话出册（归档/移除）：身份随墓碑一起忘掉，不给已归档会话留复活用的名字。 */
function forgetIdentity(sessionId) {
  if (identityBySession.delete(sessionId)) persistIdentity();
}

/** 合并基准：内存最新态优先，其次重启前的身份表；都没有给空对象。 */
function knownState(sessionId) {
  return latestBySession.get(sessionId) || identityBySession.get(sessionId) || {};
}

function appendEvent(partial, authority = null) {
  if (!partial || typeof partial !== "object") return null;
  if (partial.source === "codex" && internalCodexSessions.has(partial.sessionId || partial.sourceSessionId)) return null;
  // Desktop observations obey its visible roster; authenticated bridge-created conversations are separately admitted.
  // Absence facts still pass through so the phone receives removals.
  const codexId = partial.sessionId || partial.sourceSessionId;
  if (partial.source === "codex" && !codexSourceVisible(codexId) &&
      !(partial.kind === "membership" && partial.membership === "ABSENT")) return null;
  const claudeId = partial.sessionId || partial.sourceSessionId;
  if (partial.source === "claude" && visibleClaudeSessions !== null && !visibleClaudeSessions.has(claudeId) &&
      !(partial.kind === "membership" && partial.membership === "ABSENT")) return null;
  if (partial.source === "claude" && visibleClaudeSessions !== null && (partial.kind === "membership" || partial.membership) &&
      authority !== "claude-desktop") return null;
  const dshId = partial.sessionId || partial.sourceSessionId;
  if (partial.source === "dsh" && visibleDshSessions !== null) {
    if ((partial.kind === "membership" || partial.membership) && authority !== "dsh-roster") return null;
    if (!visibleDshSessions.has(dshId) && !(partial.kind === "membership" && partial.membership === "ABSENT")) return null;
  }
  if (partial.kind === "read-state" || partial.readStateOnly === true) {
    const sessionId = typeof partial.sessionId === "string" ? partial.sessionId : "";
    return sessionId ? appendReadStateEvent(sessionId, partial.readState) : null;
  }
  const membershipInput = partial.kind === "membership" || partial.membership;
  if (membershipInput) {
    const fact = membershipFact({
      ...partial,
      sourceSessionId: partial.sourceSessionId || partial.sessionId,
    });
    if (!fact) return null;
    const applied = membershipLedger.apply(fact);
    if (!applied?.accepted) return null;
    sessionLibrary.observe({ source: fact.source, sessionId: fact.sourceSessionId,
      ...(fact.title ? { title: fact.title } : {}), ...(fact.workspace ? { workspace: fact.workspace } : {}),
      archived: fact.archiveState === "ARCHIVED", available: fact.membership !== "ABSENT" });
    // 在册正事实（PRESENT/ACTIVE）**不清活动状态**（issue #306）：DSH 建会话连发两条
    // `session-added`（`session/created` 带 cwd，紧跟的 `agent/created` 不带），第二条若按
    // 稀疏事件整体替换，就把 workspace / title / readState / 会话窗口（turns）一起抹掉——
    // 背屏因此只剩「未命名会话」。墓碑与 UNKNOWN 观测不合并（它们不是活动事实）。
    const presence = fact.membership === "PRESENT" && fact.archiveState === "ACTIVE";
    const ev = {
      ...(presence ? knownState(fact.sourceSessionId) : {}),
      ...partial,
      ...fact,
      sessionId: fact.sourceSessionId,
      updatedAt: Number.isFinite(partial.updatedAt) ? partial.updatedAt : Date.now(),
      id: ++seq,
    };
    if (fact.membership === "ABSENT" || fact.archiveState === "ARCHIVED") {
      latestBySession.delete(fact.sourceSessionId);
      turnsBySession.delete(fact.sourceSessionId);
      forgetIdentity(fact.sourceSessionId);
    } else if (fact.archiveState === "UNKNOWN") {
      // 容错观测只上事件流，不改桥在册/问答流：坏 JSON、文件缺失都不是归档/恢复事实。
    } else {
      const rememberedCopy = { ...ev };
      for (const key of ["kind", "sourceSessionId", "membership", "archiveState", "reason", "generation", "revision"]) {
        delete rememberedCopy[key];
      }
      latestBySession.set(fact.sourceSessionId, rememberedCopy);
      // 在册事实里带到的目录名/真标题（DSH `session/created` 的 cwd）同样记进身份表。
      rememberIdentity(fact.sourceSessionId, ev);
    }
    publishMembership(fact.source, fact.sourceSessionId, (presence || fact.archiveState === "UNKNOWN") && mirrorAllows(ev), ev);
    return ev;
  }
  if (typeof partial.sessionId !== "string" || !partial.sessionId) return null;
  sessionLibrary.observe({ source: partial.source || null, sessionId: partial.sessionId,
    ...(partial.createdAt ? { createdAt: partial.createdAt } : {}),
    ...(partial.title ? { title: partial.title } : {}), ...(partial.workspace ? { workspace: partial.workspace } : {}) });
  const remembered = knownState(partial.sessionId);
  // 稀疏补丁（issue #306：DSH `session-summary` 只带标题、不动状态）按该会话最新态回填状态；
  // 未知会话仍必须自带状态，缺状态照旧 400——外部非法输入一律不入环。
  const status = STATUSES.has(partial.status) ? partial.status : remembered.status;
  if (!STATUSES.has(status)) return null;
  if (!membershipLedger.acceptsActivity(partial.source || null, partial.sessionId)) return null;
  const ts = Number.isFinite(partial.updatedAt) ? partial.updatedAt : Date.now();
  const incoming = latestReplyToAssistantText(partial);
  const firstSeen = !readAtBySession.has(partial.sessionId) && !replyAtBySession.has(partial.sessionId);
  if (firstSeen) readAtBySession.set(partial.sessionId, BRIDGE_STARTED_AT);
  const completedAnswer = incoming.assistantKind !== "progress" &&
    typeof incoming.assistantText === "string" && incoming.assistantText.trim();
  const completedStructuredAnswer = (Array.isArray(incoming.contentEntries) ? incoming.contentEntries : [])
    .some((entry) => entry?.kind === "answer" && entry.open !== true && String(entry.text || "").trim());
  const lastEntry = turnsBySession.get(partial.sessionId)?.all().at(-1);
  const completedStreamingAnswer = incoming.completeStream === true && incoming.assistantKind !== "progress" &&
    lastEntry?.kind === "answer" && lastEntry.open === true;
  if (completedAnswer || completedStructuredAnswer || completedStreamingAnswer) {
    replyAtBySession.set(partial.sessionId, ts);
  }
  // 问答流先攒后发：增量补丁在这里落进会话窗口（spec 0017 / 票 #169）。
  const contentAt = incoming.source === "codex" && Number.isFinite(incoming.sourceAt) ? incoming.sourceAt : ts;
  const touchedTurns = applyTurnPatch(partial.sessionId, incoming, contentAt);
  const turnLog = turnLogFor(partial.sessionId);
  const ev = {
    source: null,
    title: null,
    workspace: null,
    currentAction: null,
    latestReply: null,
    summary: null,
    readState: "read",
    ...remembered,
    ...incoming,
    updatedAt: partial.updatedAt ?? ts, // 回填链不能盖掉新鲜时间戳
    id: ++seq, // id 恒由桥分配（外部传入被忽略）
  };
  if (ev.source === "codex") {
    const controlled = codexControl?.questions.pending(partial.sessionId) || [];
    if (controlled.length) incoming.inputRequests = [...(incoming.inputRequests || []), ...controlled];
  }
  Object.assign(ev, speechFacts.apply(`${ev.source || "legacy"}:${partial.sessionId}`, incoming, ts));
  ev.voiceReplay = partial.replay === true;
  ev.mirrorBaseline = false;
  const replayBefore = mirrorReplayBefore.get(mirrorKey(ev.source, ev.sessionId)) || 0;
  if (ev.voiceEvent?.createdAt <= replayBefore) ev.voiceEvent = { ...ev.voiceEvent, replay: true };
  if (ev.pendingRequests.some((request) => request.kind === "approval")) ev.status = "waiting";
  ev.pendingQuestions = ev.pendingRequests.filter((request) => request.kind === "question")
    .map((request) => ({ id: request.id, title: request.title || request.text, options: request.options || [],
      ...(ev.source === "codex" ? codexControl?.questions.metadata(partial.sessionId, request.id) : {}) }));
  for (const key of ["taskStarted", "turnId", "completion", "completionText", "sourceAt", "replay", "inputRequests", "resolvedRequestIds", "resolvedRequestPrefix", "resolvedRequestPrefixes", "clearInputRequests", "requestId"]) delete ev[key];
  // 内部补丁字段不上线（手机端只认 turns / latestReply）；两者每帧按当前窗口重算。
  delete ev.userText;
  delete ev.assistantText;
  delete ev.assistantDetail;
  delete ev.assistantDelta;
  delete ev.assistantKind;
  delete ev.recoverableError;
  delete ev.thinkingText;
  delete ev.thinkingDelta;
  delete ev.toolSummary;
  delete ev.toolDetail;
  delete ev.toolResultSummary;
  delete ev.toolResultDetail;
  delete ev.errorText;
  delete ev.errorDetail;
  delete ev.approvalText;
  delete ev.approvalDetail;
  delete ev.usageText;
  delete ev.usageDetail;
  delete ev.noticeText;
  delete ev.noticeDetail;
  delete ev.contentEntries;
  delete ev.entries;
  delete ev.completeStream;
  delete ev.resetTurns;
  ev.turns = turnLog.list();
  // 身份随事件一起记住（issue #306）：真标题/目录名收到即落盘，桥重启后仍能恢复。
  rememberIdentity(partial.sessionId, ev);
  const derived = turnLog.latestReply();
  if (derived !== null) {
    ev.latestReply = derived;
  } else if (touchedTurns) {
    ev.latestReply = null; // 只有提问、还没有回答：旧字段不该留着上一轮的回答
  }
  const explicitReadState = partial.readState;
  const hasUnreadTurn = partial.hasUnreadTurn ?? partial.has_unread_turn;
  if (explicitReadState !== undefined || hasUnreadTurn !== undefined) {
    ev.readState = explicitReadState !== undefined
      ? normalizedReadState(explicitReadState)
      : (hasUnreadTurn ? "unread" : "read");
    if (ev.readState === "read") readAtBySession.set(partial.sessionId, ts);
  } else if (partial.status === "idle") {
    const replyAt = replyAtBySession.get(partial.sessionId) || 0;
    const readAt = readAtBySession.get(partial.sessionId) || BRIDGE_STARTED_AT;
    ev.readState = replyAt > readAt ? "unread" : (remembered.readState || "read");
    if (ev.readState === "read") readAtBySession.set(partial.sessionId, ts);
  } else {
    ev.readState = normalizedReadState(remembered.readState);
  }
  if (!READ_STATES.has(ev.readState)) ev.readState = "read";
  latestBySession.set(partial.sessionId, (() => {
    // actionExpired 是**单事件**终态标记（手机按它走失败提示链）：不上「最新态」，
    // 否则会被 ...remembered 粘连到该会话后续每一条事件上。
    const rememberedCopy = { ...ev };
    delete rememberedCopy.actionExpired;
    return rememberedCopy;
  })());
  // Snapshots expose this cursor even when activity is hidden; persist before returning it.
  try { writeFileSync(SEQ_FILE, String(seq)); } catch { /* existing cursor fallback */ }
  if (mirrorAllows(ev)) {
    if (mirrorVisible.get(mirrorKey(ev.source, ev.sessionId)) === false) publishMembership(ev.source, ev.sessionId, true);
    mirrorVisible.set(mirrorKey(ev.source, ev.sessionId), true);
    enqueueWire(ev);
  }
  return ev;
}

/** hooks 载荷 → 统一会话事件（部分补丁，缺字段由 appendEvent 回填）。导出供测试。 */
export function mapHookToPatch(source, body) {
  if (!body || typeof body !== "object") return null;
  const hookType = String(body.type || body.event || body.hook_event_name || "");
  const explicitReadState = body.readState;
  const hasUnreadTurn = body.hasUnreadTurn ?? body.has_unread_turn;
  const wireReadState = explicitReadState !== undefined
    ? normalizedReadState(explicitReadState)
    : (hasUnreadTurn === undefined ? "read" : (hasUnreadTurn ? "unread" : "read"));
  if (/^(read-state|read|mark-read|mark_read)$/i.test(hookType)) {
    const readSessionId = body.session_id || body.sessionId || body.thread_id || body.threadId;
    if (!readSessionId) return null;
    return {
      kind: "read-state",
      sessionId: readSessionId,
      source: source || null,
      readState: wireReadState,
    };
  }
  if (source === "claude") {
    const membership = membershipFromExplicitHook(source, body);
    if (membership) return membership;
    const event = body.hook_event_name || body.type;
    const sessionId = body.session_id || body.sessionId;
    if (!sessionId) return null;
    if (event === "Stop" || event === "stop") {
      const patch = { sessionId, source, status: "idle", currentAction: null, completion: "done", turnId: body.turn_id };
      if (typeof body.last_assistant_message === "string" && body.last_assistant_message.trim()) {
        patch.latestReply = body.last_assistant_message;
      }
      if (typeof body.cwd === "string" && body.cwd) patch.workspace = body.cwd;
      return patch;
    }
    if (event === "Notification" || event === "notification") {
      return { sessionId, source, status: "waiting", summary: body.message || body.title || "需要你确认" };
    }
    if (event === "StopFailure") return { sessionId, source, status: "error", completion: "error", summary: body.error_details || body.error };
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
      return { sessionId, source, status: "working", currentAction: null, clearInputRequests: true };
    }
    return null;
  }
  if (source === "codex") {
    const membership = membershipFromExplicitHook(source, body);
    if (membership) return membership;
    const type = String(body.type || body.event || "");
    const sessionId = body["thread-id"] || body.thread_id || body.threadId || body.session_id || body.sessionId || lastCodexSession;
    const turnId = body["turn-id"] || body.turn_id || body.turnId;
    if (!sessionId) return null;
    if (/turn-complete|task_complete|turn_complete/.test(type)) {
      const patch = { sessionId, source, status: "idle", currentAction: null, completion: "done", turnId };
      const reply = body["last-assistant-message"] ?? body.last_assistant_message;
      if (typeof reply === "string" && reply.trim()) {
        patch.latestReply = reply;
      }
      return patch;
    }
    if (/approval|waiting/.test(type)) return { sessionId, source, status: "waiting" };
    if (/turn-started|task_started|working/.test(type)) return { sessionId, source, status: "working", taskStarted: /turn-started|task_started/.test(type), turnId };
    // 出错词（spec 0018-4 票 #174 顺手项）：只认**明确字面** `error`（有明确信号才映射，
    // 不造词）；codex notify 的其他形态一律照旧跳过。
    if (type === "error") {
      const patch = { sessionId, source, status: "error", completion: "error", turnId };
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
 * 会话动作（Remote Approval 的请求面）：手机 `POST /action` 批准（同意/拒绝/选中选项）落这里，
 * PreToolUse 钩子经 `GET /action/pending` 取走**一次性**决定。发 prompt 只走 spec 0024 的
 * `/codex/*` 专用面；本契约仍不承载自由文字输入或按键。
 */
const ACTION_TTL_MS = Number(process.env.BRIDGE_ACTION_TTL_MS || 120_000);
/** @type {Map<string, {requestId: string, action: string, optionId: string|null, expiresAt: number}>} */
const pendingActions = new Map();
/** ZCode app-server server-request 的 adapter-local 回执缝（只回 approve/reject/select）。 */
/** Remote Codex Conversation（spec 0024）：官方 app-server 控制面与待决批准。 */
let codexControl = null;
const codexApprovals = new Map();
let zcodeActionSink = null;
let zcodeHistorySink = null;
let codexHistorySink = null;

/** 手机侧「在线看护」最近一次露面时刻（/events 长轮询或 /snapshot）：批准等待窗只对在线手机开，
 *  2026-09-30 起兼任托盘第三态（手机已连）的判据。BRIDGE_PHONE_WINDOW_MS 可覆盖（测试收窄用）。 */
let lastPhoneSeenAt = 0;
const PHONE_SEEN_WINDOW_MS = Number(process.env.BRIDGE_PHONE_WINDOW_MS || 90_000);

function phoneSeen() {
  lastPhoneSeenAt = Date.now();
  // 托盘第三态：手机露面即从未连翻已连（写状态文件，托盘每秒读）。只写翻转，不写每次露面。
  trayPhoneFlip();
}

function phoneWatching() {
  return lastPhoneSeenAt > 0 && Date.now() - lastPhoneSeenAt < PHONE_SEEN_WINDOW_MS;
}

/**
 * 手机在线事实写进托盘（第三态，2026-09-30 机主要求「托盘图标显示手机有没有连上」）：
 * 事实变了才写文件——手机每次长轮询都露面，照写会空转。已连→未连靠 [startPhoneTraySweep]
 * 的周期检查收口（手机停拨后最多 5s＋窗长翻回）。
 *
 * 写入闸门＝托盘真在跑（trayStarted）。`--no-tunnel` 调试实例没有托盘，照写只会用自家
 * （空的）phone 事实污染生产托盘状态文件——RCU_TRAY_STATE 指到隔离文件时放行（测试注入缝）。
 */
let trayPhoneLast = null;

function trayPhoneFlip() {
  if (!trayStarted && !process.env.RCU_TRAY_STATE) return;
  const watching = phoneWatching();
  if (watching === trayPhoneLast) return;
  trayPhoneLast = watching;
  syncTray();
}

function startPhoneTraySweep() {
  const timer = setInterval(trayPhoneFlip, 5000);
  timer.unref?.();
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

/** spec 0024 请求面的纯校验：项目、可用模型与 prompt 都在创建前收口，避免 provider/model 混搭。 */
export function codexRequestFor(body, projects, models) {
  const prompt = typeof body?.prompt === "string" ? body.prompt.trim() : "";
  if (!prompt) return { ok: false, receipt: "bad-request", reason: "prompt-required" };
  const project = (projects || []).find((candidate) => candidate?.id === body?.projectId);
  const workspace = project?.roots?.[0]?.path;
  if (!project || typeof workspace !== "string" || !workspace) {
    return { ok: false, receipt: "bad-request", reason: "project-unavailable" };
  }
  const rawModel = typeof body?.model === "string" ? body.model.trim() : "";
  const model = rawModel || null;
  if (model && !(models || []).some((candidate) => candidate?.id === model || candidate?.model === model)) {
    return { ok: false, receipt: "bad-request", reason: "model-unavailable" };
  }
  return { ok: true, receipt: "accepted", projectId: project.id, workspace, model, prompt };
}

/** 只有远程创建、失败且没有任何正常助手回复的空会话可真删除。 */
export function canDeleteCodexFailure(sessionId, latest, remoteCreated) {
  if (!remoteCreated.has(sessionId)) return false;
  if (!latest || latest.status !== "error") return false;
  const turns = Array.isArray(latest.turns) ? latest.turns : [];
  return !turns.some((turn) => turn?.role === "assistant" && String(turn?.text || "").trim()) &&
    !String(latest.latestReply || "").trim();
}

function consumePendingAction(sessionId) {
  const entry = pendingActions.get(sessionId);
  if (!entry) return null;
  pendingActions.delete(sessionId);
  if (entry.expiresAt <= Date.now()) return null;
  return entry;
}

/**
 * 过期未取走的会话动作判死（review 2026-09-30 / spec 0018-4 AC3 补遗）：已受理（accepted）
 * 的动作在 [ACTION_TTL_MS] 内没人取走——DSH 预算耗尽没决定、Claude 钩子超时回落、或压根
 * 没人点——就发一条 `actionExpired` 终态事件，手机据此一次失败提示（不悬挂、不重试）。
 * 只对**还在册**的会话发（不为已下册会话造事件）；导出供判例锁契约。
 */
export function sweepExpiredActions(now = Date.now()) {
  for (const [sessionId, entry] of [...pendingActions]) {
    if (entry.expiresAt > now) continue;
    pendingActions.delete(sessionId);
    const remembered = latestBySession.get(sessionId);
    if (!remembered) continue;
    appendEvent({ sessionId, status: remembered.status, actionExpired: true });
    log(`action expired session=${sessionId} requestId=${entry.requestId}`);
  }
}

/** DSH 工作区名册与回合边界对账：仅差异入环，保留插件带来的正文和等待语义。 */
function reconcileDshRoster({ sessions, archived }) {
  visibleDshSessions = new Set(sessions.map((row) => row.sessionId));
  archivedDshSessions = archived;
  for (const [id, pending] of pendingDshEvents) {
    if (archived.has(id) || pending.expiresAt < Date.now()) pendingDshEvents.delete(id);
  }
  const active = new Set();
  const facts = new Map(membershipLedger.snapshot()
    .filter((fact) => fact.source === "dsh")
    .map((fact) => [fact.sourceSessionId, fact]));
  const nextFact = (sessionId, membership, extra = {}) => {
    const previous = facts.get(sessionId);
    const fact = membershipFact({
      source: "dsh",
      sourceSessionId: sessionId,
      membership,
      generation: previous?.generation ?? 0,
      revision: Math.max(Date.now(), (previous?.revision ?? 0) + 1),
      reason: membership === "ACTIVE" ? "roster" : membership === "ARCHIVED" ? "archive" : "source-removed",
      ...extra,
    });
    if (fact) appendEvent(fact, "dsh-roster");
  };
  for (const row of sessions) {
    const sessionId = row?.sessionId;
    if (typeof sessionId !== "string" || !sessionId || active.has(sessionId)) continue;
    active.add(sessionId);
    const old = latestBySession.get(sessionId);
    const fact = facts.get(sessionId);
    const status = row.status === "working"
      ? old?.status === "waiting" ? "waiting" : "working"
      : row.status === "idle"
        ? old?.status === "error" ? "error" : "idle"
        : old?.status || "idle";
    if (!old || !fact || fact.membership !== "PRESENT" || fact.archiveState !== "ACTIVE") {
      nextFact(sessionId, "ACTIVE", {
        status,
        ...(row.workspace ? { workspace: row.workspace } : {}),
        ...(row.title ? { title: row.title } : {}),
      });
      flushDshEvents(sessionId);
      continue;
    }
    const patch = { sessionId, source: "dsh", status };
    if (row.workspace && row.workspace !== old.workspace) patch.workspace = row.workspace;
    if (row.title && row.title !== old.title) patch.title = row.title;
    if (status === "idle" && old.status !== "idle") {
      patch.currentAction = null;
      patch.pendingOptions = [];
    }
    if (status !== old.status || "workspace" in patch || "title" in patch) appendEvent(patch);
    flushDshEvents(sessionId);
  }
  for (const [sessionId, old] of latestBySession) {
    if (old.source !== "dsh" || active.has(sessionId)) continue;
    nextFact(sessionId, archived.has(sessionId) ? "ARCHIVED" : "ABSENT");
    pendingActions.delete(sessionId);
  }
}

/** 当前在册会话快照：只读、不带正文，按 latestBySession 的键集投影最小字段；附来源能力表。 */
function sessionSnapshot() {
  const sessions = [];
  for (const ev of latestBySession.values()) {
    if (!mirrorAllows(ev)) continue;
    if (!ev || typeof ev.sessionId !== "string" || !ev.sessionId) continue;
    sessions.push({
      sessionId: ev.sessionId,
      id: ev.id,
      source: typeof ev.source === "string" && ev.source ? ev.source : null,
      title: typeof ev.title === "string" && ev.title ? ev.title : null,
      workspace: typeof ev.workspace === "string" && ev.workspace ? ev.workspace : null,
      status: STATUSES.has(ev.status) ? ev.status : null,
      readState: READ_STATES.has(ev.readState) ? ev.readState : "read",
      id: Number.isFinite(ev.id) ? ev.id : 0,
      updatedAt: Number.isFinite(ev.updatedAt) ? ev.updatedAt : null,
      pendingRequests: ev.pendingRequests || [],
      pendingQuestions: ev.pendingQuestions || [],
    });
  }
  const memberships = [...publishedMemberships.values()];
  return memberships.length > 0
    ? { cursor: seq, sessions, memberships, capabilities: capabilitiesFor(dshPluginLive()) }
    : { cursor: seq, sessions, capabilities: capabilitiesFor(dshPluginLive()) };
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

function ensureCodexControl() {
  if (!wantCodex) return null;
  if (codexControl) return codexControl;
  codexControl = new CodexAppServerControl({ log, threadBusy(threadId) {
    return ["working", "waiting"].includes(latestBySession.get(threadId)?.status);
  } });
  codexControl.on("event", (patch) => appendEvent(patch));
  codexControl.on("approval", ({ serverRequestId, threadId, summary, requestId }) => {
    if (!threadId) return;
    codexApprovals.set(threadId, serverRequestId);
    appendEvent({
      sessionId: threadId,
      source: "codex",
      status: "waiting",
      summary,
      currentAction: null,
      inputRequests: [{ id: requestId, kind: "approval", text: summary }],
    });
  });
  codexControl.on("approvalResolved", ({ threadId, requestId }) => {
    codexApprovals.delete(threadId);
    appendEvent({ sessionId: threadId, source: "codex", status: "working", currentAction: null, resolvedRequestIds: [requestId] });
  });
  return codexControl;
}

function codexFailure(error) {
  const unknown = /timeout|exited/i.test(String(error?.message || ""));
  return {
    receipt: unknown ? "unknown" : "failed",
    error: String(error?.message || error),
    threadId: error?.threadId || null,
  };
}

async function handleEvents(req, res, since, holdMs, limit) {
  // 手机每次来取事件都顺手清一次过期动作（判死发 actionExpired 终态，见 sweepExpiredActions）。
  sweepExpiredActions();
  // 取件 = 「since 之后」+ 条数/字节封顶（issue #309）：last id > since 是恒等式，
  // 不必先 filter 再取头。
  const take = () => eventPage(since, limit);
  let { batch, cursor } = take();
  if (batch.length === 0 && holdMs > 0) {
    const waited = await new Promise((resolve) => {
      let done = false;
      const finish = (v) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        waiters.delete(onWake);
        resolve(v);
      };
      const onWake = () => finish(take());
      const timer = setTimeout(() => finish({ batch: [], cursor: since }), holdMs);
      waiters.add(onWake);
      const now = take();
      if (now.batch.length > 0) finish(now);
      req.on("close", () => finish({ batch: [], cursor: since })); // 手机端断开：立刻放行
    });
    batch = waited.batch;
    cursor = waited.cursor;
  }
  if (res.writableEnded || res.destroyed) return;
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
    if (!writeAuthorized(req, url)) {
      unauthorized(res);
      return;
    }
    if (url.pathname === "/codex/questions/answer" && req.method === "POST") {
      let body;
      try { body = JSON.parse(await readBody(req)); } catch { body = null; }
      if (!body || typeof body.sessionId !== "string" || typeof body.groupId !== "string") {
        res.writeHead(400, { "Content-Type": "application/json" }).end(JSON.stringify({ receipt: "bad-request" }));
        return;
      }
      const result = codexControl && mirrorAllows(latestBySession.get(body.sessionId)) ? await codexControl.questions.submit(body) : { receipt: "unsupported" };
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(result));
      return;
    }
    if (url.pathname === "/codex/questions/state" && req.method === "GET") {
      const sessionId = url.searchParams.get("sessionId");
      const result = mirrorAllows(latestBySession.get(sessionId)) ? codexControl?.questions.state(sessionId, url.searchParams.get("groupId")) || { receipt: "unsupported" } : { receipt: "unsupported" };
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(result));
      return;
    }
    if (req.method === "GET" && url.pathname === "/snapshot") {
      phoneSeen();
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(sessionSnapshot()));
      return;
    }
    // 完整历史（票 #177）：手机回看当前会话从头到尾。桥的问答流存量自本票起不裁剪
    // （turn-log 的窗口只是推流视图），这里给全量；未知/已下册会话回空列表（合法，
    // 手机侧把空结果当「没有更多」不抹现有内容）。
    if (req.method === "GET" && url.pathname === "/history") {
      phoneSeen();
      const sessionId = url.searchParams.get("sessionId") || "";
      const state = latestBySession.get(sessionId);
      if (!state) { res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ sessionId, turns: [] })); return; }
      if (!mirrorAllows(state)) { res.writeHead(404).end(); return; }
      const log = sessionId ? turnsBySession.get(sessionId) : undefined;
      const liveTurns = log ? log.all() : [];
      let modelIoTurns = [];
      const row = sessionLibrary.get(state.source, sessionId);
      let historyError = null;
      try {
        if (state.source === "codex" && codexHistorySink) modelIoTurns = codexHistorySink(sessionId);
        if (!modelIoTurns.length && row?.file) modelIoTurns = await sessionLibrary.history(row);
        else if (state.source === "zcode" && zcodeHistorySink) modelIoTurns = zcodeHistorySink(sessionId);
      } catch (error) { historyError = error.message; }
      const turns = state.source === "codex" ? mergeCodexHistory(modelIoTurns, liveTurns) : mergeHistoryTurns(modelIoTurns, liveTurns);
      if (!mirrorAllows(latestBySession.get(sessionId))) { res.writeHead(404).end(); return; }
      const offset = Math.max(0, Number(url.searchParams.get("offset")) || 0);
      const paged = url.searchParams.has("offset");
      const page = [];
      let bytes = 0;
      for (const turn of turns.slice(offset)) {
        const size = JSON.stringify(turn).length;
        if (paged && page.length && (bytes + size > 1_000_000 || page.length >= 100)) break;
        page.push(turn); bytes += size;
      }
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ sessionId, turns: page,
        nextOffset: offset + page.length < turns.length ? offset + page.length : null, total: turns.length,
        ...(historyError ? { error: historyError, complete: false } : { complete: true }) }));
      return;
    }
    // 会话已阅回执（ADR 0018）：任一端实际打开正文后共享给其他端。只改已阅事实，
    // 不碰会话状态、问答流或排序；未知会话不凭回执造出在册会话。
    if (req.method === "POST" && url.pathname === "/read") {
      phoneSeen();
      let body = {};
      try {
        body = JSON.parse(await readBody(req) || "{}");
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"ok":false,"receipt":"bad-request"}');
        return;
      }
      const requestedId = String(body.sessionId || body.sourceSessionId || "");
      const requestedSource = String(body.source || "").trim().toLowerCase();
      const candidates = [...new Set([
        requestedId,
        requestedId.startsWith("bridge:") ? requestedId.slice("bridge:".length).replace(/^[a-z]+:/, "") : requestedId,
      ].filter(Boolean))];
      const target = [...latestBySession.entries()].find(([sessionId, ev]) =>
        candidates.includes(sessionId) &&
        (!requestedSource || !ev.source || String(ev.source).toLowerCase() === requestedSource),
      );
      if (!target || !mirrorAllows(target[1])) {
        res.writeHead(404, { "Content-Type": "application/json" }).end('{"ok":false,"receipt":"unknown-session"}');
        return;
      }
      const hasUnreadTurn = body.hasUnreadTurn ?? body.has_unread_turn;
      const requestedReadState = body.readState !== undefined
        ? normalizedReadState(body.readState)
        : (hasUnreadTurn === undefined ? "read" : (hasUnreadTurn ? "unread" : "read"));
      const ev = appendReadStateEvent(target[0], requestedReadState);
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
        ok: true,
        receipt: "accepted",
        sessionId: target[0],
        readState: ev?.readState || "read",
      }));
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
      // 单页条数上限（issue #309）：手机按满页续取；缺省/非法走 DEFAULT_EVENTS_PAGE。
      const limitRaw = Number(url.searchParams.get("limit") ?? DEFAULT_EVENTS_PAGE);
      const limit = Number.isFinite(limitRaw) && limitRaw > 0 ? Math.floor(limitRaw) : DEFAULT_EVENTS_PAGE;
      await handleEvents(req, res, Math.floor(since), holdMs, limit);
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
      const ev = appendEvent({ ...body, createdAt: body.createdAt || Date.now() });
      if (!ev) {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"error":"invalid event"}');
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: true, id: ev.id }));
      return;
    }
    // Remote Codex Conversation（spec 0024）：项目/模型先读当前 app-server，provider/model
    // 不再跨配置盲拼；创建、追问、停止、删除均为 POST 写面，已由 writeAuthorized 验凭据。
    if (req.method === "GET" && url.pathname === "/codex/options") {
      const control = ensureCodexControl();
      if (!control) {
        res.writeHead(404, { "Content-Type": "application/json" }).end('{"ok":false,"error":"codex-disabled"}');
        return;
      }
      try {
        const [projects, models] = await Promise.all([control.listProjects(), control.listModels()]);
        res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
          ok: true,
          projects: projects.map((project) => ({
            id: project.id,
            name: project.name,
            roots: project.roots || [],
          })),
          models: models.map((model) => ({
            id: model.id || model.model,
            displayName: model.displayName || model.id || model.model,
            isDefault: Boolean(model.isDefault),
          })),
        }));
      } catch (error) {
        const failure = codexFailure(error);
        res.writeHead(failure.receipt === "unknown" ? 504 : 503, { "Content-Type": "application/json" })
          .end(JSON.stringify({ ok: false, ...failure }));
      }
      return;
    }

    const codexConversationMatch = url.pathname.match(/^\/codex\/conversations\/([^/]+)(\/messages|\/stop|\/delete)?$/);
    if (req.method === "POST" && url.pathname === "/codex/conversations") {
      const control = ensureCodexControl();
      if (!control) {
        res.writeHead(404, { "Content-Type": "application/json" }).end('{"ok":false,"error":"codex-disabled"}');
        return;
      }
      let body;
      try {
        body = JSON.parse(await readBody(req) || "{}");
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"ok":false,"receipt":"bad-request"}');
        return;
      }
      try {
        const [projects, models] = await Promise.all([control.listProjects(), control.listModels()]);
        const input = codexRequestFor(body, projects, models);
        if (!input.ok) {
          res.writeHead(400, { "Content-Type": "application/json" }).end(JSON.stringify(input));
          return;
        }
        const result = await control.startConversation(input);
        codexRemoteCreated.add(result.threadId);
        appendEvent({
          sessionId: result.threadId,
          source: "codex",
          workspace: input.workspace,
          status: "working",
          userText: input.prompt,
          currentAction: null,
          createdAt: Date.now(),
        });
        res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
          ok: true,
          receipt: "accepted",
          requestId: body.requestId || null,
          threadId: result.threadId,
          turnId: result.turnId,
        }));
      } catch (error) {
        if (error?.threadId) codexRemoteCreated.add(error.threadId);
        const failure = codexFailure(error);
        if (failure.threadId) {
          appendEvent({
            sessionId: failure.threadId,
            source: "codex",
            status: "error",
            summary: failure.error,
            currentAction: null,
            completion: failure.receipt === "failed" ? "error" : undefined,
          });
        }
        res.writeHead(failure.receipt === "unknown" ? 504 : 500, { "Content-Type": "application/json" })
          .end(JSON.stringify({ ok: false, requestId: body.requestId || null, ...failure }));
      }
      return;
    }

    if (req.method === "POST" && codexConversationMatch) {
      const control = ensureCodexControl();
      if (!control) {
        res.writeHead(404, { "Content-Type": "application/json" }).end('{"ok":false,"error":"codex-disabled"}');
        return;
      }
      const threadId = decodeURIComponent(codexConversationMatch[1]);
      if (!mirrorAllows(latestBySession.get(threadId))) { res.writeHead(404).end(); return; }
      const operation = codexConversationMatch[2] || "";
      let body = {};
      try {
        body = JSON.parse(await readBody(req) || "{}");
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" }).end('{"ok":false,"receipt":"bad-request"}');
        return;
      }
      try {
        if (operation === "/messages") {
          const latest = latestBySession.get(threadId);
          if (!latest || latest.source !== "codex") {
            res.writeHead(404, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: false, receipt: "unknown-session" }));
            return;
          }
          if (["working", "waiting"].includes(latest.status)) {
            res.writeHead(409, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: false, receipt: "busy", reason: "desktop-occupied" }));
            return;
          }
          const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
          const model = typeof body.model === "string" && body.model.trim() ? body.model.trim() : null;
          const models = await control.listModels();
          if (!prompt || (model && !models.some((candidate) => candidate.id === model || candidate.model === model))) {
            res.writeHead(400, { "Content-Type": "application/json" })
              .end(JSON.stringify({ ok: false, receipt: "bad-request", reason: prompt ? "model-unavailable" : "prompt-required" }));
            return;
          }
          const result = await control.sendTurn({ threadId, model, prompt, requestId: body.requestId || randomUUID() });
          if (result.managedBy !== "desktop") {
            appendEvent({ sessionId: threadId, source: "codex", status: "working", userText: prompt, currentAction: null });
          }
          res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
            ok: true,
            receipt: "accepted",
            requestId: body.requestId || null,
            threadId,
            turnId: result.turnId || null,
            managedBy: result.managedBy || "bridge",
          }));
          return;
        }
        if (operation === "/stop") {
          const stopped = await control.interrupt(threadId);
          if (!stopped) {
            res.writeHead(409, { "Content-Type": "application/json" }).end(JSON.stringify({
              ok: false, receipt: "unsupported", reason: "no-managed-turn", threadId,
            }));
            return;
          }
          if (stopped) appendEvent({ sessionId: threadId, source: "codex", status: "idle", currentAction: null });
          res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
            ok: true,
            receipt: "accepted",
            requestId: body.requestId || null,
            threadId,
            stopped,
          }));
          return;
        }
        if (operation === "/delete") {
          const latest = latestBySession.get(threadId);
          if (!canDeleteCodexFailure(threadId, latest, codexRemoteCreated)) {
            res.writeHead(403, { "Content-Type": "application/json" }).end(JSON.stringify({
              ok: false,
              receipt: "forbidden",
              requestId: body.requestId || null,
              reason: "only-failed-empty-remote-sessions",
            }));
            return;
          }
          await control.deleteThread(threadId);
          codexRemoteCreated.delete(threadId);
          latestBySession.delete(threadId);
          turnsBySession.delete(threadId);
          codexApprovals.delete(threadId);
          appendEvent({
            kind: "membership",
            source: "codex",
            sessionId: threadId,
            membership: "ABSENT",
            generation: Date.now(),
          });
          res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
            ok: true,
            receipt: "accepted",
            requestId: body.requestId || null,
            threadId,
            deleted: true,
          }));
          return;
        }
        res.writeHead(404).end();
      } catch (error) {
        const failure = codexFailure(error);
        res.writeHead(failure.receipt === "unknown" ? 504 : 500, { "Content-Type": "application/json" })
          .end(JSON.stringify({ ok: false, requestId: body.requestId || null, threadId, ...failure }));
      }
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
      if (sessionId && latestBySession.has(sessionId) && !mirrorAllows(latestBySession.get(sessionId))) {
        res.writeHead(404, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: false, receipt: "unknown-session", requestId: body.requestId || null })); return;
      }
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
      const source = (latestBySession.get(sessionId) || {}).source ?? null;
      if (source === "codex") {
        log(`codex action inspect session=${sessionId} pending=${codexApprovals.has(sessionId)} source=${source}`);
        const serverRequestId = codexApprovals.get(sessionId);
        if (action === "select" || serverRequestId == null || !codexControl?.resolveApproval(serverRequestId, action)) {
          res.writeHead(200, { "Content-Type": "application/json" })
            .end(JSON.stringify({ ok: false, receipt: "bad-request", requestId }));
          return;
        }
        log(`codex action resolved session=${sessionId} action=${action} requestId=${requestId}`);
        res.writeHead(200, { "Content-Type": "application/json" })
          .end(JSON.stringify({ ok: true, receipt: "accepted", requestId }));
        return;
      }
      if (source === "zcode" && zcodeActionSink?.({ sessionId, requestId, action, optionId })) {
        log(`zcode action resolved session=${sessionId} action=${action} requestId=${requestId}`);
        res.writeHead(200, { "Content-Type": "application/json" })
          .end(JSON.stringify({ ok: true, receipt: "accepted", requestId }));
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
          const ev = appendEvent(removal.fact);
          if (ev) {
            latestBySession.delete(removal.sessionId);
            turnsBySession.delete(removal.sessionId);
            res.writeHead(200, { "Content-Type": "application/json" })
              .end(JSON.stringify({ ok: true, removed: true, id: ev.id }));
          } else {
            res.writeHead(200, { "Content-Type": "application/json" })
              .end(JSON.stringify({ ok: true, removed: false, stale: true }));
          }
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
      if (source === "dsh" && visibleDshSessions !== null && !visibleDshSessions.has(patch.sessionId) &&
          patch.kind !== "membership" && !patch.membership) {
        if (archivedDshSessions.has(patch.sessionId)) {
          res.writeHead(202, { "Content-Type": "application/json" }).end('{"ok":true,"ignored":true}');
          return;
        }
        const buffered = holdDshEvent(patch);
        res.writeHead(202, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: true, buffered, ...buffered ? {} : { ignored: true } }));
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
const urlBeforeStart = redactBridgeUrl(readTrayState()?.url || "");
/** 当前隧道地址（拿到之前为 null：托盘显示"隧道未就绪"，探活不发请求）。 */
const tunnel = { child: null, ready: false, url: null, lastBalloon: null };

/** 把当前事实写进托盘状态文件（就绪与否 + 地址 + 手机在线）；失败不掀桌子。 */
function syncTray() {
  setTrayState({ url: tunnel.url, port: PORT, log: LOG_FILE, ready: tunnel.ready, phone: phoneWatching() }, log);
}

/**
 * 记录隧道 URL 并自动推给手机（桥重启/换 URL 时免手工重配；adb 不在则跳过）。
 * 地址**真的变了**才弹托盘气泡——同一地址的重启不吵人。
 */
let lastPushedUrl = null;
function publishTunnelUrl(rawUrl, log) {
  const url = redactBridgeUrl(rawUrl);
  const addressedUrl = bridgeUrlWithToken(rawUrl);
  // 比较基准是"桥启动前那个地址"（urlBeforeStart），不是"本进程上一次的地址"——
  // 否则桥一重启，第一个 URL 永远算"没变"，换了域名的气泡就丢了（无人值守下机主无从知道要改手机端）。
  const previous = redactBridgeUrl(tunnel.url || urlBeforeStart);
  log(`隧道 URL: ${url}`);
  tunnel.url = url;
  try {
    writeFileSync(URL_FILE, addressedUrl + "\n");
  } catch (e) {
    log(`bridge.url 写入失败 ${e?.message || e}`);
  }
  if (previous && previous !== url && tunnel.lastBalloon !== url) {
    tunnel.lastBalloon = url;
    balloon(`桥地址已更换，手机端需要新地址：${url}`, log);
  }
  syncTray();
  // 飞书通知独立于 adb 推送：首次可用/换址各一次，发送失败不阻塞桥、不补发。
  notifyFeishuBridgeUrl({
    addressedUrl,
    baseUrl: url,
    stateFile: FEISHU_STATE_FILE,
    log,
  });
  if (addressedUrl === lastPushedUrl) return;
  lastPushedUrl = addressedUrl;
  pushUrlToPhone(addressedUrl, log, 0);
}

/**
 * 逐个候选 adb 试推送；找不到 adb（ENOENT/非 0 退出）就换下一个。
 *
 * 多设备（USB 线 + 无线调试同时在线）时必须靠 `ADB_SERIAL` 指一台：不给的话 adb 直接
 * "more than one device" 失败，广播发不出去——2026-09-29 实测就是这样把自动推送打哑的，
 * 而那正是"手机端手填桥地址"要兜的坑（两条路都在，别让人只能靠手填）。
 */
function pushUrlToPhone(url, log, index) {
  const clearing = url == null; // 「清除桥地址」广播（#188 临终通知）：不带 --es url，措辞随之换
  // BRIDGE_ADB_PUSH=0：一刀关掉自动推送（隔离实测实例用——假地址绝不推给真手机）。
  if (process.env.BRIDGE_ADB_PUSH === "0") {
    log(clearing ? "adb 推送已关（BRIDGE_ADB_PUSH=0）：清除桥地址广播未发出" : "adb 推送已关（BRIDGE_ADB_PUSH=0）：URL 仅落 bridge.url");
    return;
  }
  const adb = adbCandidates()[index];
  if (!adb) {
    log(clearing ? "未找到可用的 adb：清除桥地址广播发不出（手机侧靠 #187 超时判停兜底）" : "未找到可用的 adb：URL 已落 bridge.url，手机端可手填这一条");
    return;
  }
  const args = adbArgs(url);
  if (!args.includes("-s")) {
    log(clearing ? "adb 目标不唯一（ADB_SERIAL 未设且连着一台以上设备）：清除桥地址广播未发出" : "adb 目标不唯一（ADB_SERIAL 未设且连着一台以上设备）：URL 已落 bridge.url，可在手机设置页手填");
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
      const via = `${adb}${args.includes("-s") ? ` -s ${args[args.indexOf("-s") + 1]}` : ""}`;
      log(clearing ? `已广播清除桥地址到手机（${via}）` : `已自动推送隧道 URL 到手机（${via}）`);
    } else {
      pushUrlToPhone(url, log, index + 1);
    }
  });
}

/**
 * 临终通知（#188）：桥自关前**尽力**广播一次「清除桥地址」——不带 `--es url` 的同一
 * 广播，手机既有语义＝立即 DISABLED（不另起新协议）。复用 pushUrlToPhone 的逐候选
 * 逻辑（url=null），`BRIDGE_ADB_PUSH=0` 同样把它拦下（隔离实测的断言锚）。
 * 发不出不重试、不阻塞退出：spawn 异步即发（Windows 上子进程不随父进程死），
 * 桥照常往下走 shutdown——手机侧还有 #187 超时判停兜底，这条只是「尽快」。
 */
function notifyPhoneDisabled(log) {
  pushUrlToPhone(null, log, 0);
}

/** 通用隧道子进程接线：逐行解析、出 URL 上报、退出交给看门狗重拉。 */
function spawnTunnelChild(bin, args, tag, log, onLine) {
  // .cmd/.bat 不能裸 spawn（Node ≥20 在 Windows 同步抛 EINVAL，桥直接死在 listen 回调里）：
  // 经 ComSpec /c 起，与 tray.mjs 拉 PowerShell 的先例一致；.exe 路径不受影响。
  const isBat = /\.(cmd|bat)$/i.test(bin);
  let child;
  try {
    child = spawn(
      isBat ? process.env.ComSpec || "cmd.exe" : bin,
      isBat ? ["/c", bin, ...args] : args,
      { stdio: ["ignore", "pipe", "pipe"], env: process.env },
    );
  } catch (e) {
    log(`${tag} 启动失败（${e?.code || e?.message}）`);
    handleTunnelExit(null, log); // 当作隧道退出：交给看门狗退避重拉，别让桥死在这
    return;
  }
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
 * 每 TUNNEL_PROBE_MS 探一次隧道 /health，结果写进托盘状态（图标就绪/未就绪的依据）。
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
      // BRIDGE_PROBE_BASE：探活目标注入缝（默认关）。隔离实测用——假隧道域名探不了活，
      // 没这条缝 ready 永远翻不了绿；生产不设它，行为分毫不差。
      const target = process.env.BRIDGE_PROBE_BASE || url;
      // URL 规范化后通常带结尾斜杠；直接再拼 `/health` 会变成 `//health` 并被桥判 404，
      // 手机明明在线托盘仍停在琥珀黄。相对 URL 解析统一落到唯一的 `/health`。
      const healthUrl = new URL("health", target.endsWith("/") ? target : `${target}/`).toString();
      const client = healthUrl.startsWith("https:") ? https : http;
      const req = client.request(healthUrl, { method: "HEAD", timeout: 8000 }, (res) => {
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

await startMirrorManager({
  file: MANAGER_FILE,
  async snapshot() {
    await sessionLibrary.refresh();
    reconcileMirrors();
    return { revision: mirrorRoster.data.revision, error: mirrorRoster.error,
      failures: sessionLibrary.failures,
      sessions: [...sessionLibrary.rows.values()].filter((row) => !row.internal && !internalCodexSessions.has(row.sessionId) && !row.archived &&
        !(row.source === "codex" && !codexSourceVisible(row.sessionId)) &&
        !(row.source === "claude" && visibleClaudeSessions !== null && !visibleClaudeSessions.has(row.sessionId)) &&
        !(row.source === "dsh" && visibleDshSessions !== null && !visibleDshSessions.has(row.sessionId)))
        .map((row) => ({ source: row.source, sessionId: row.sessionId, title: row.title || "未命名会话", workspace: row.workspace || "",
          available: row.available !== false && !membershipLedger.tombstone(row.source, row.sessionId),
          state: mirrorRoster.choice(row) === false ? "removed" : mirrorRoster.allows(mirrorRow(row)) ? "enabled" : "candidate" })) };
  },
  async apply(body) {
    await sessionLibrary.refresh();
    mirrorRoster.apply(body.changes, body.revision, (source, id) => {
      const row = sessionLibrary.get(source, id);
      if (!row) return null;
      return { ...row, available: row.available !== false && !membershipLedger.tombstone(source, id) &&
        !(source === "codex" && !codexSourceVisible(id)) &&
        !(source === "claude" && visibleClaudeSessions !== null && !visibleClaudeSessions.has(id)) &&
        !(source === "dsh" && visibleDshSessions !== null && !visibleDshSessions.has(id)) };
    });
    reconcileMirrors();
    return { ok: true, revision: mirrorRoster.data.revision };
  },
}).catch((error) => log(`会话管理入口不可用：${error.message}`));
const catalogueTimer = setInterval(() => sessionLibrary.refresh().then(reconcileMirrors).catch(() => {}), 1500);
catalogueTimer.unref();

server.listen(PORT, HOST, () => {
  chainSnapshot = parentChain();
  log(`留痕｜桥启动｜pid=${process.pid} chain=${chainSnapshot}`);
  setTimeout(backfillChain, 5000).unref?.();
  log(
    `监听 http://${HOST}:${PORT}（长轮询持有 ${HOLD_MS / 1000}s，环容量 ${MAX_EVENTS} 条 / ` +
      `${Math.round(EVENTS_RING_BYTES / 1_000_000)}MB，单页 ${DEFAULT_EVENTS_PAGE} 条 / ` +
      `${Math.round(EVENTS_PAGE_BYTES / 1_000_000)}MB）`,
  );
  if (wantDemo) startDemo();
  // 会话文件适配器（ADR 0006）：目录存在即自动挂载，--no-codex / --no-claude 可关。
  if (wantCodex) {
    const codex = startCodexAdapter(
      (ev) => {
        lastCodexSession = ev.sessionId || lastCodexSession;
        appendEvent(ev);
      },
      { log, onVisibleSessions(ids) { visibleCodexSessions = ids; }, onInternalSession(sessionId) {
        internalCodexSessions.add(sessionId);
        latestBySession.delete(sessionId);
        turnsBySession.delete(sessionId);
      } },
    );
    codexHistorySink = (sessionId) => codex.historyFor(sessionId);
  }
  if (wantClaude) startClaudeAdapter((event) => appendEvent(event, event.kind === "membership" ? "claude-desktop" : null), {
    log, onVisibleSessions(ids) { visibleClaudeSessions = ids; },
  });
  if (wantZCode) {
    const zcode = startZCodeAdapter(appendEvent, {
      log,
      onSessionMetadata: (row) => sessionLibrary.observe(row),
      ...(process.env.BRIDGE_ZCODE_HOME ? { taskIndexPath: join(process.env.BRIDGE_ZCODE_HOME, "tasks-index.sqlite") } : {}),
      onSessionRemoved(sessionId) {
        latestBySession.delete(sessionId);
        turnsBySession.delete(sessionId);
        pendingActions.delete(sessionId);
      },
    });
    zcodeActionSink = (body) => zcode.resolveAction(body);
    zcodeHistorySink = (sessionId) => zcode.historyFor(sessionId);
  }
  if (wantDshRoster) startDshRosterAdapter(reconcileDshRoster, { log });
  if (wantTunnel) {
    // 托盘先起：图标在＝桥在；地址一拿到就写进状态文件（图标同时从黄转绿）。
    // 补拉耗尽（#188）→ 临终通知 + 优雅自关。取舍：先补图标后关桥＝保手机优先
    // （ADR 0011 / spec 0019）——监护先尽力把图标补回来（补回即清零计数，整夜偶发退出
    // 每次都能自愈）；连续补不回才认输，认输后也先广播「清除桥地址」再关自己（复用
    // 不带 --es url 的既有语义，发不出不重试），手机立即 DISABLED、另有 #187 超时判停
    // 兜底——半死不活地留着只会让手机对着一个没人管的镜像。
    trayStarted = startTray(
      {
        port: PORT,
        log: LOG_FILE,
        managerFile: MANAGER_FILE,
        onGuardianExhausted: (n) => {
          log(`托盘监护耗尽（连续 ${n} 次补不回）——先尽力通知手机清除桥地址，再优雅自关`);
          notifyPhoneDisabled(log);
          // 退出码 75＝可重来（#189）：start-bridge.cmd 的重试引擎等 30s 重拉；
          // external-signal（人按 Ctrl+C）仍走缺省 0＝干净停，不重来。
          shutdown({
            reason: "self-shutdown",
            extra: `cause=tray-guardian-exhausted attempts=${n}`,
            exitCode: 75,
          });
        },
      },
      log,
    );
    if (trayStarted) {
      syncTray();
      balloon("RearCue PC 桥已启动，隧道连接中……", log);
    }
    startTunnel(log);
    startTunnelProbe(log);
    startPhoneTraySweep();
  } else {
    log("隧道关闭（--no-tunnel）：仅本机/LAN 可达（托盘图标不出现）");
  }
});

// 退出路径：收掉隧道与托盘，不留孤儿进程/孤儿图标；留痕走 shutdown()（spec 0019-1）。
// SIGBREAK＝控制台 Ctrl+Break（Windows 上少数真能投递进进程的信号之一），一并纳入。
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP", "SIGBREAK"]) {
  process.on(sig, () => {
    shutdown({ reason: "external-signal", extra: `signal=${sig}`, legacyNote: `收到 ${sig}，收尾退出` });
  });
}

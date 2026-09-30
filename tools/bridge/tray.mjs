/**
 * 托盘图标接线（票 #171）：桥没有窗口，任务栏托盘是它唯一的人机界面。
 *
 * 分工：本模块只管「起托盘进程 + 把状态写给它 + 让它弹气泡」，界面本身在 tray.ps1。
 * 两侧契约就一条状态文件（一行 JSON）加一条事件文件（追加一行 JSON），
 * 所以托盘进程崩了、或桥重启了，都不会有半死状态——桥死了图标自己消失（托盘轮询父进程）。
 *
 * 为什么不用 node 的托盘库：桥是零 npm 依赖的纯 Node 进程（ADR 0006），
 * 本机只有 Windows PowerShell 5.1（WinForms 可用），够用且不引依赖。
 */
import { spawn, spawnSync } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { trayIconDir, writeTrayIcons } from "./make-icons.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const IS_WINDOWS = process.platform === "win32";

/**
 * adb 候选（推送桥地址给手机用）：环境变量 → 本机工具目录 → PATH。
 * `ADB_SERIAL` 可指定目标机（USB 线 + 无线调试**同时在线**时必须给，否则 adb 回
 * "more than one device" 直接失败——2026-09-29 实测就是这条把自动推送打哑的）。
 */
export function adbCandidates() {
  return [
    process.env.ADB,
    join(process.env.LOCALAPPDATA || "", "RearCue-tools/android-sdk/platform-tools/adb.exe"),
    "adb",
  ].filter((p) => p && p !== "adb.exe");
}

/**
 * 推送用的完整参数：多设备时 adb 挑不出来，得显式 -s（否则广播发不出去且不报错）。
 * url 为空 → 不带 `--es url` 三段：**同一广播**的既有语义就是「清除桥地址」
 * （#188 临终通知复用它，手机立即 DISABLED，不另起新协议）。
 */
export function adbArgs(url) {
  const serial = resolveAdbSerial();
  return [
    ...(serial ? ["-s", serial] : []),
    "shell", "am", "broadcast",
    "-n", "com.rearcue.poc/.DebugCommandReceiver",
    "-a", "com.rearcue.poc.action.BRIDGE_URL",
    ...(url ? ["--es", "url", url] : []),
  ];
}

/**
 * 目标机序列号：`ADB_SERIAL` 明写 > 自动认（只连着一台时）> null（认不出，推送跳过）。
 *
 * 为什么要自动认：计划任务起桥时**带不进环境变量**，而手机常常同时挂着 USB 与无线调试
 * （`adb devices` 两行、实为同一台），此时不给 `-s` 会 "more than one device" 直接失败，
 * 无人值守下就没人去手填了。`ro.serialno` 是硬件序列号，USB 与无线两行取到同一个值，
 * 按它去重即可判定"是不是只有一台真机"。结果进程内缓存一次（设备插拔不频繁）。
 */
let cachedSerial;
export function resolveAdbSerial() {
  if (process.env.ADB_SERIAL) return process.env.ADB_SERIAL;
  if (cachedSerial !== undefined) return cachedSerial;
  cachedSerial = detectSingleDeviceSerial();
  return cachedSerial;
}

/** 只连着一台真机时返回其序列号；两台以上、或问不出来，返回 null。 */
export function detectSingleDeviceSerial() {
  const adb = adbCandidates()[0];
  if (!adb) return null;
  const run = (args) =>
    // 测试替身是 .cmd：Node 直接 spawn 批处理会 EINVAL，得经 cmd.exe 起（生产里是 adb.exe，走上面那条）。
    /\.(cmd|bat)$/i.test(adb)
      ? spawnSync(process.env.ComSpec || "cmd.exe", ["/c", adb, ...args], {
          encoding: "utf8",
          windowsHide: true,
          timeout: 10_000,
        })
      : spawnSync(adb, args, { encoding: "utf8", windowsHide: true, timeout: 10_000 });
  const listed = run(["devices"]);
  const ids = String(listed.stdout || "")
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("*"))
    .map((line) => line.split(/\s+/))
    .filter(([, state]) => state === "device")
    .map(([id]) => id);
  if (!ids.length) return null;
  const serials = new Set();
  for (const id of ids) {
    const prop = run(["-s", id, "shell", "getprop", "ro.serialno"]);
    const value = String(prop.stdout || "").trim();
    serials.add(value || id); // 取不到就退回传输 id（无线地址）
  }
  return serials.size === 1 ? ids[0] : null;
}

/** 托盘进程句柄与上次写下的状态（桥重启时靠状态文件里的旧 URL 判断要不要弹气泡）。 */
const state = {
  child: null,
  lastUrl: null,
  eventFile: null,
  ready: null,
  timer: null,
  // ── 托盘监护（spec 0019-2 / #186）──
  guardAttempts: 0, // 已连续补拉次数（托盘存活满一个间隔即清零）
  guardTimer: null, // 待触发的补拉定时器
  guardStopping: false, // 桥主动收尾中：不补拉
  spawnedAt: 0, // 当前托盘的拉起时刻（判「活满一个间隔＝健康一轮」）
  onGuardianExhausted: null, // 补拉耗尽回调（#188 起挂桥的自关；缺省只留日志锚）
  port: null,
  logFile: "",
  iconDir: "",
};

/**
 * 托盘监护参数（可注入，隔离实测用）：补拉上限与间隔。
 * 产品节奏＝3 次 × 10 秒（spec 0019-2）：托盘消失后 10s 内图标回来、桥服务不断。
 */
const GUARD_MAX = () => Number(process.env.RCU_TRAY_GUARD_MAX ?? 3);
const GUARD_INTERVAL_MS = () => Number(process.env.RCU_TRAY_GUARD_INTERVAL_MS ?? 10_000);

function stateFile() {
  return process.env.RCU_TRAY_STATE || join(trayIconDir(), "tray-state.json");
}
function eventFile() {
  return process.env.RCU_TRAY_EVENT || join(trayIconDir(), "tray-event.jsonl");
}

/**
 * 托盘状态写进状态文件：托盘每秒读一次，据此换色（ready）与显示地址（url）。
 * 只写文件不推事件——气泡由 balloon() 单独追加，读走即清空，不会重复弹。
 *
 * [keepUrl] 只在桥启动那一刻传（上一次的地址）：气泡要拿它判断"地址是不是真的换了"，
 * 但托盘面板**不该**显示它——桥刚起来、隧道还没连上时显示上一轮的地址，机主照着复制
 * 只会拿到一个已经失效的域名（无人值守下尤其坑）。所以 url 归零、只留比较用的旧值。
 */
export function setTrayState({ url, port, log, ready, keepUrl } = {}, logger = () => {}) {
  if (!IS_WINDOWS) return;
  const next = {
    v: 1,
    ready: !!ready,
    url: url || "",
    lastUrl: keepUrl || "",
    port: port || null,
    log: log || "",
    at: Date.now(),
  };
  try {
    mkdirSync(dirname(stateFile()), { recursive: true });
    writeFileSync(stateFile(), JSON.stringify(next) + "\n");
    state.ready = next.ready;
  } catch (e) {
    logger(`托盘状态写入失败 ${e?.message || e}`);
  }
}

/** 让托盘弹一句气泡（地址变化、隧道重拉等）。不响不震，只提示。 */
export function balloon(text, logger = () => {}) {
  if (!IS_WINDOWS || !text) return;
  try {
    if (!existsSync(eventFile())) writeFileSync(eventFile(), "");
    appendFileSync(eventFile(), JSON.stringify({ kind: "notice", text: String(text) }) + "\n");
  } catch (e) {
    logger(`托盘气泡写入失败 ${e?.message || e}`);
  }
}

/** 读托盘状态文件里的旧值（桥重启后据此判断地址是不是真的变了）。 */
export function readTrayState() {
  try {
    return JSON.parse(readFileSync(stateFile(), "utf8"));
  } catch {
    return null;
  }
}

/**
 * 托盘消失后的监护循环（spec 0019-2 / #186）：桥补拉图标，最多 RCU_TRAY_GUARD_MAX 次、
 * 间隔 RCU_TRAY_GUARD_INTERVAL_MS。托盘**存活满一个间隔**＝这一轮是健康的，连续失败计数
 * 清零——整夜里偶发退出后依然每次都能自愈（US18），只有「补了立刻又没」才累积到耗尽。
 * 耗尽交 onGuardianExhausted 处置（#188 起＝桥优雅自关；桥不主动收尾时它不应被调）。
 */
function onTrayGone(logger, why) {
  if (state.guardStopping) {
    logger(`托盘退出（${why}）：桥正在收尾，不补拉`);
    return;
  }
  const max = GUARD_MAX();
  const interval = GUARD_INTERVAL_MS();
  if (state.spawnedAt && Date.now() - state.spawnedAt >= interval) state.guardAttempts = 0;
  if (state.guardAttempts >= max) {
    logger(`托盘补拉 ${max} 次仍无图标（最后退出：${why}）——监护耗尽`);
    state.onGuardianExhausted?.(max);
    return;
  }
  state.guardAttempts += 1;
  logger(`托盘消失（${why}）：补拉 ${state.guardAttempts}/${max}，${Math.round(interval / 1000)}s 后重拉图标`);
  state.guardTimer = setTimeout(() => {
    state.guardTimer = null;
    if (state.guardStopping || state.child) return;
    spawnTrayOnce(logger);
  }, interval);
  state.guardTimer.unref?.();
}

/** 拉一个托盘进程（首次与补拉共用）：成败只影响监护计数，不抛给调用方。 */
function spawnTrayOnce(logger) {
  const args = [
    "-NoProfile",
    "-WindowStyle", "Hidden",
    "-ExecutionPolicy", "Bypass",
    "-File", join(HERE, "tray.ps1"),
    "-ParentPid", String(process.pid),
    "-Port", String(state.port),
    "-Log", state.logFile || "",
    "-StateFile", stateFile(),
    "-EventFile", eventFile(),
    "-IconDir", state.iconDir,
  ];
  let child;
  try {
    // RCU_TRAY_BIN 可换托盘程序（隔离实测注入「起不来」用）；生产就是 powershell.exe。
    child = spawn(process.env.RCU_TRAY_BIN || "powershell.exe", args, { stdio: "ignore", windowsHide: true });
  } catch (e) {
    logger(`托盘启动抛错（${e?.code || e?.message}）`);
    onTrayGone(logger, `spawn-throw ${e?.code || e?.message}`);
    return true;
  }
  state.child = child;
  state.spawnedAt = Date.now();
  const gone = (why) => {
    if (state.child !== child) return; // 已被接替/已收尾
    state.child = null;
    // 桥侧目击留痕（spec 0019-1）：托盘怎么没的，桥这头先记一笔；
    // 托盘自己那侧还会往 -Log 写更细的 reason。
    logger(`托盘进程退出 ${why}（图标随之消失；桥照常跑）`);
    onTrayGone(logger, why);
  };
  child.on("error", (e) => {
    logger(`托盘启动失败（${e?.code || e?.message}）——桥不受影响`);
    gone(`error=${e?.code || e?.message}`);
  });
  child.on("exit", (code, signal) => gone(`code=${code} signal=${signal ?? "-"}`));
  return true;
}

/** 起托盘（幂等；桥重启时若旧托盘还在，先让它随旧父进程自然退场）。 */
export function startTray({ port, log, onGuardianExhausted } = {}, logger = () => {}) {
  if (!IS_WINDOWS) {
    logger("非 Windows：托盘跳过（桥本身照常跑）");
    return false;
  }
  try {
    const icons = writeTrayIcons();
    // 启动即写一次状态：地址**留空**（隧道还没连上，上一轮的地址已经不成立了），
    // 旧值只作为 keepUrl 留给气泡判断"地址是不是真的换了"。
    setTrayState(
      { url: "", keepUrl: readTrayState()?.url || "", port, log, ready: false },
      logger,
    );
    state.port = port;
    state.logFile = log || "";
    state.iconDir = dirname(icons.ready);
    state.guardAttempts = 0;
    state.guardStopping = false;
    state.onGuardianExhausted = onGuardianExhausted;
    const ok = spawnTrayOnce(logger);
    logger(`托盘已起（图标 ${icons.ready} / ${icons.pending} / ${icons.light}）`);
    return ok;
  } catch (e) {
    logger(`托盘启动异常 ${e?.message || e}——桥不受影响`);
    return false;
  }
}

/** 桥退出时收掉托盘：图标不留孤儿（托盘也会因父进程消失自退，这里是快路径）。 */
export function stopTray() {
  state.guardStopping = true; // 主动收尾：exit 事件不得触发补拉
  if (state.guardTimer) {
    clearTimeout(state.guardTimer);
    state.guardTimer = null;
  }
  try {
    state.child?.kill();
  } catch {
    /* 已经没了就算了 */
  }
  state.child = null;
}

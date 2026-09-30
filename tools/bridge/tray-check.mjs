#!/usr/bin/env node
/**
 * 托盘 + 看门狗端到端实测（票 #171）：`node tools/bridge/tray-check.mjs`
 *
 * 验七件事（都在这台机器上真跑，不是单测里的桩）：
 *   ① 桥起来后托盘进程在（图标在＝桥在）；
 *   ② 隧道报到 URL → 状态文件 ready=true 且带上地址（图标转绿）；
 *   ③ 杀掉隧道进程 → 看门狗自动重拉一条，拿到**新地址**并写回状态（气泡的触发条件）；
 *   ⑤ 杀掉托盘进程 → 桥监护补拉，新托盘 ~10s 内回来，期间桥健康口全程通（spec 0019-2）；
 *   ④ 杀桥 → 托盘进程自己退（图标消失，不留孤儿）；
 *   ⑥ 注入补拉失败（RCU_TRAY_BIN 指向不存在的 exe）→ 桥先尽力广播「清除桥地址」
 *      （被 BRIDGE_ADB_PUSH=0 拦下，只留日志锚）再优雅自关（#188），退出码 75＝可重来；
 *   ⑦ 重来耗尽 → 彻底停（#189）：start-bridge.cmd 的重试引擎把退出码 75 的桥拉满
 *      3 次重来（共 4 次运行）后自己退出 1，之后没人再把桥拉起来。
 *
 * 全程隔离：独立端口、独立临时目录、假隧道、独立日志/seq/url 文件、关 adb 推送，
 * 绝不碰生产的 bridge.url / bridge.log / 计划任务 / 真手机。进程查找一律按隔离
 * 实例的完整路径匹配（state 文件、假隧道脚本、本 worktree 的 bridge.mjs），
 * 不按裸脚本名——裸名会同时命中生产实例，清理时会把生产托盘杀掉。
 * 只在 Windows 上有意义（托盘是 WinForms），其他平台直接跳过。
 */
import { spawn, spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import http from "node:http";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const BRIDGE_ENTRY = join(HERE, "bridge.mjs");
const DIR = process.env.RCU_TRAY_CHECK_DIR || join(ROOT, ".scratch", "tray-check");
const TRAY_STATE_FILE = join(DIR, "tray-state.json");
const FAKE_TUNNEL_FILE = join(DIR, "fake-tunnel.mjs");
const PORT = Number(process.env.RCU_TRAY_CHECK_PORT || 18793);
const WAIT = { tray: 12_000, url: 20_000, reurl: 25_000 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (msg) => console.log(`[tray-check] ${msg}`);

function readState() {
  try {
    return JSON.parse(readFileSync(TRAY_STATE_FILE, "utf8"));
  } catch {
    return null;
  }
}

async function waitFor(what, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await predicate();
    if (last) return last;
    await sleep(400);
  }
  throw new Error(`等不到：${what}（超时 ${timeoutMs}ms，最后看到 ${JSON.stringify(last)}）`);
}

/**
 * 按命令行找进程，永远排除 harness 自己（node）与查询进程自身（powershell——
 * 它的命令行里带着 pattern，不排除就会每次多算一个幻影 pid）。
 * pattern 必须是隔离实例独有的完整路径片段（见文件头注释），别传裸脚本名。
 */
function findPids(pattern) {
  const r = spawnSync(
    "powershell.exe",
    [
      "-NoProfile",
      "-Command",
      `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*${pattern}*' -and $_.ProcessId -ne $PID -and $_.ProcessId -ne ${process.pid} } | ForEach-Object { $_.ProcessId }`,
    ],
    { encoding: "utf8", windowsHide: true },
  );
  return String(r.stdout || "")
    .split(/\s+/)
    .filter(Boolean)
    .map(Number);
}

/** 探桥本机健康口（补拉期间必须一直通）。 */
import http2 from "node:http";
function probeHealth() {
  return new Promise((resolve) => {
    const req = http2.get(`http://127.0.0.1:${PORT}/health`, { timeout: 2000 }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on("timeout", () => { req.destroy(); resolve(false); });
    req.on("error", () => resolve(false));
  });
}

function killPids(pids) {
  for (const pid of pids) {
    try {
      process.kill(pid, "SIGKILL");
    } catch {
      /* 已经没了 */
    }
  }
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function main() {
  if (process.platform !== "win32") {
    log("非 Windows：托盘检查跳过");
    return;
  }
  rmSync(DIR, { recursive: true, force: true });
  mkdirSync(DIR, { recursive: true });
  // 假隧道：冒充 tunwg 打一行隧道 URL 就挂着；计数文件保证每次重拉换新地址。
  // URL 必须长得像 tunwg 真地址（<纯字母数字>.l.tunwg.com）——桥按这个正则解析，
  // trycloudflare 形状的假地址在 tunwg 模式下永远解析不出来。
  writeFileSync(
    FAKE_TUNNEL_FILE,
    [
      'import { appendFileSync, readFileSync } from "node:fs";',
      `const COUNT = ${JSON.stringify(join(DIR, "tunnel-count"))};`,
      "let n = 0;",
      'try { n = parseInt(readFileSync(COUNT, "utf8"), 10) || 0; } catch {}',
      "n += 1;",
      "appendFileSync(COUNT, String(n));",
      "process.stdout.write(`INF Your quick Tunnel has been created! https://fake${n}.l.tunwg.com\\n`);",
      "setInterval(() => {}, 1000);",
    ].join("\n"),
  );
  writeFileSync(
    join(DIR, "tunwg-fake.cmd"),
    `@echo off\r\nnode "%~dp0fake-tunnel.mjs"\r\n`,
  );
  // 假「隧道公网口」：探活打这里（真隧道域名是 https 假域名，探不活），
  // ready 的判定权就握在 harness 手里——想验「探不通转黄」就把它关掉。
  const probeServer = http.createServer((req, res) => {
    res.statusCode = 200;
    res.end();
  });
  await new Promise((resolve) => probeServer.listen(0, "127.0.0.1", resolve));
  const probeBase = `http://127.0.0.1:${probeServer.address().port}`;

  const env = {
    ...process.env,
    BRIDGE_PORT: String(PORT),
    BRIDGE_TUNNEL: "tunwg",
    TUNWG_BIN: join(DIR, "tunwg-fake.cmd"),
    BRIDGE_TUNNEL_RETRY_MS: "1500",
    BRIDGE_PROBE_MS: "3000",
    BRIDGE_PROBE_BASE: probeBase,
    BRIDGE_ADB_PUSH: "0",
    BRIDGE_LOG: join(DIR, "bridge.log"),
    BRIDGE_SEQ_FILE: join(DIR, "bridge.seq"),
    BRIDGE_URL_FILE: join(DIR, "bridge.url"),
    RCU_TRAY_ICON_DIR: join(DIR, "icons"),
    RCU_TRAY_STATE: TRAY_STATE_FILE,
    RCU_TRAY_EVENT: join(DIR, "tray-event.jsonl"),
  };
  const bridge = spawn(process.execPath, [BRIDGE_ENTRY, "--no-codex", "--no-claude"], {
    cwd: ROOT,
    env,
    stdio: "ignore",
    windowsHide: true,
  });

  const results = [];
  let trays = [];
  try {
    // ① 托盘进程在（按隔离实例的 state 文件路径匹配，生产托盘不算数）
    trays = await waitFor("托盘进程出现", () => {
      const pids = findPids(TRAY_STATE_FILE);
      return pids.length ? pids : null;
    }, WAIT.tray);
    results.push(`① 托盘进程在（pid ${trays.join(",")}）`);

    // ② 隧道报到地址 → ready + 地址写进状态
    const first = await waitFor("状态文件 ready=true 且带 URL", () => {
      const st = readState();
      return st && st.ready && st.url ? st : null;
    }, WAIT.url);
    results.push(`② 隧道就绪：${first.url}（ready=${first.ready}）`);

    // ③ 杀隧道 → 看门狗重拉 → 新地址
    const tunnelPids = findPids(FAKE_TUNNEL_FILE);
    if (!tunnelPids.length) throw new Error("找不到假隧道进程，无法验证看门狗");
    killPids(tunnelPids);
    log(`已杀隧道进程 ${tunnelPids.join(",")}，等看门狗重拉`);
    const second = await waitFor("看门狗重拉出新地址", () => {
      const st = readState();
      return st && st.ready && st.url && st.url !== first.url ? st : null;
    }, WAIT.reurl);
    results.push(`③ 看门狗自愈：${first.url} → ${second.url}`);

    // 托盘只有一份（重启隧道不该多出图标）
    const trays2 = findPids(TRAY_STATE_FILE);
    if (trays2.length !== trays.length) {
      throw new Error(`托盘进程数变了：${trays.length} → ${trays2.length}（应当恒为一份）`);
    }
    results.push(`③b 托盘仍是一份（pid ${trays2.join(",")}）`);

    // ⑤ 杀托盘 → 桥监护补拉（spec 0019-2/#186）：新托盘 ~10s 内回来，
    //    期间桥健康口每拍都通（补拉绝不能打断手机镜像的服务面）。
    const traysBefore = findPids(TRAY_STATE_FILE);
    killPids(traysBefore);
    log(`已杀托盘进程 ${traysBefore.join(",")}，等桥监护补拉`);
    let healthAlwaysOk = true;
    const respawned = await waitFor("桥补拉托盘（新 pid）", async () => {
      if (!(await probeHealth())) healthAlwaysOk = false;
      const pids = findPids(TRAY_STATE_FILE);
      return pids.length ? pids : null;
    }, 18_000);
    if (!healthAlwaysOk) throw new Error("补拉期间桥健康口断过（补拉不得打断服务）");
    const oldSet = new Set(traysBefore);
    if (respawned.every((p) => oldSet.has(p))) {
      throw new Error(`托盘 pid 没换（${respawned.join(",")}），疑似没真补拉`);
    }
    results.push(`⑤ 杀托盘→桥补拉回来（${traysBefore.join(",")} → ${respawned.join(",")}），健康口全程通`);
    trays = respawned; // ④ 继续用补拉回来的托盘验证「桥停 → 图标消失」
  } finally {
    // ④ 杀桥 → 托盘自退
    try {
      try {
        bridge.kill("SIGKILL");
      } catch {
        /* 已经没了 */
      }
      await sleep(3000);
      const still = trays.filter((p) => isAlive(p));
      if (still.length) {
        results.push(`④ 失败：桥已杀，托盘仍在（pid ${still.join(",")}）`);
        killPids(still);
      } else {
        results.push("④ 桥停 → 托盘自退（图标消失，不留孤儿）");
      }
    } catch (e) {
      results.push(`④ 检查异常：${e?.message || e}`);
    }
    // 兜底收尾：本轮起的东西一个不留（全按隔离路径匹配，生产实例碰不着）
    try {
      bridge.kill("SIGKILL");
    } catch {
      /* 已经没了 */
    }
    killPids(findPids(FAKE_TUNNEL_FILE));
    killPids(findPids(TRAY_STATE_FILE));
    killPids(findPids(BRIDGE_ENTRY));
    probeServer.close();
    if (existsSync(join(DIR, "tray-event.jsonl"))) {
      log(`气泡事件：${readFileSync(join(DIR, "tray-event.jsonl"), "utf8").trim() || "(无)"}`);
    }
  }

  // ⑥ 注入补拉失败 → 桥自关（spec 0019 / #188）：④ 的清理已收完上一实例，另起一个
  //    隔离桥（独立端口 + DIR 子目录），RCU_TRAY_BIN 指向不存在的 exe＝每次补拉必败，
  //    补拉间隔加速到 0.8s、关 adb 推送。等它自己退出（预算 ~15s，正常 3×0.8s＋启动
  //    约 4s），断言日志**依次**出现：补拉 3/3 → 监护耗尽 → 临终通知被隔离缝拦下 →
  //    优雅自关留痕；退出码必须是 75（可重来：#189 起自关走非零退出码，
  //    start-bridge.cmd 的重试引擎按它判定要不要 30s 重拉）。
  {
    const DIR6 = join(DIR, "self-shutdown");
    mkdirSync(DIR6, { recursive: true });
    const PORT6 = Number(process.env.RCU_TRAY_CHECK_PORT_6 || 18794);
    const LOG6 = join(DIR6, "bridge.log");
    const env6 = {
      ...process.env,
      BRIDGE_PORT: String(PORT6),
      BRIDGE_TUNNEL: "tunwg",
      TUNWG_BIN: join(DIR, "tunwg-fake.cmd"),
      BRIDGE_ADB_PUSH: "0",
      BRIDGE_LOG: LOG6,
      BRIDGE_SEQ_FILE: join(DIR6, "bridge.seq"),
      BRIDGE_URL_FILE: join(DIR6, "bridge.url"),
      RCU_TRAY_ICON_DIR: join(DIR6, "icons"),
      RCU_TRAY_STATE: join(DIR6, "tray-state.json"),
      RCU_TRAY_EVENT: join(DIR6, "tray-event.jsonl"),
      RCU_TRAY_BIN: join(DIR6, "definitely-no-tray.exe"), // 不存在：起托盘必败（ENOENT）
      RCU_TRAY_GUARD_MAX: "3",
      RCU_TRAY_GUARD_INTERVAL_MS: "800", // 加速耗尽：3×0.8s 走完全程
    };
    log("⑥ 另起隔离桥（RCU_TRAY_BIN 指向不存在的 exe）：等补拉耗尽 → 自关");
    const bridge6 = spawn(process.execPath, [BRIDGE_ENTRY, "--no-codex", "--no-claude"], {
      cwd: ROOT,
      env: env6,
      stdio: "ignore",
      windowsHide: true,
    });
    try {
      const code6 = await Promise.race([
        new Promise((resolve) => bridge6.on("exit", (code) => resolve(code))),
        sleep(15_000).then(() => null),
      ]);
      if (code6 === null) throw new Error("⑥ 超时：桥没有在 15s 内自关");
      if (code6 !== 75) throw new Error(`⑥ 桥退出码 ${code6}（自关＝可重来退出码 75）`);
      const text6 = readFileSync(LOG6, "utf8");
      // 顺序敏感：启动期的 URL 推送也会打「adb 推送已关」——只有耗尽**之后**那条
      // 清除变体才是临终通知被调到的证据。
      const order6 = [
        "补拉 3/3",
        "监护耗尽",
        "adb 推送已关（BRIDGE_ADB_PUSH=0）：清除桥地址广播未发出",
        "留痕｜桥退出｜reason=self-shutdown",
      ];
      let pos6 = -1;
      for (const anchor of order6) {
        const at = text6.indexOf(anchor);
        if (at < 0) throw new Error(`⑥ 日志缺锚点「${anchor}」`);
        if (at <= pos6) throw new Error(`⑥ 锚点顺序不对：「${anchor}」没出现在前一条之后`);
        pos6 = at;
      }
      if (!text6.includes("cause=tray-guardian-exhausted attempts=3")) {
        throw new Error("⑥ 退出留痕缺 cause=tray-guardian-exhausted attempts=3");
      }
      results.push("⑥ 补拉耗尽 → 临终通知（被隔离缝拦下）→ 桥自关（exit 75＝可重来，补拉 3/3）");
    } finally {
      // 兜底：shutdown 只杀隧道的 cmd 壳，node 假隧道孙进程可能漏——按隔离路径再收一遍。
      try {
        bridge6.kill("SIGKILL");
      } catch {
        /* 已经没了 */
      }
      killPids(findPids(FAKE_TUNNEL_FILE));
      killPids(findPids(BRIDGE_ENTRY));
    }
  }

  // ⑦ 重来耗尽 → 彻底停（spec 0019 / #189）：start-bridge.cmd 现在是重试启动器——桥
  //    退出码 0＝干净停（不重来）；非零（自关 75 / 崩溃）＝等 RCU_BRIDGE_RETRY_MS 再拉，
  //    最多 3 次重来（共 4 次运行），耗尽启动器退出 1、之后谁也不再把桥拉起来。
  //    隔离法：把启动器**复制**进 DIR 子目录，旁边放假 bridge.mjs（跑一次往计数文件
  //    追加一行再 exit 75——仓里的真 bridge.mjs 一字不动），RCU_BRIDGE_RETRY_MS=1000
  //    跑该副本；绝不碰真计划任务（生产 "RearCueBridge" 任务不受影响）。
  {
    const DIR7 = join(DIR, "retry-launcher");
    mkdirSync(DIR7, { recursive: true });
    const LAUNCHER7 = join(DIR7, "start-bridge.cmd");
    const COUNT7 = join(DIR7, "runs.log");
    copyFileSync(join(HERE, "start-bridge.cmd"), LAUNCHER7);
    writeFileSync(
      join(DIR7, "bridge.mjs"),
      [
        'import { appendFileSync } from "node:fs";',
        `const COUNT = ${JSON.stringify(COUNT7)};`,
        "appendFileSync(COUNT, `run pid=${process.pid}\\n`);",
        "process.exit(75); // 可重来退出码（#189 桥自关同款）",
      ].join("\n"),
    );
    const countRuns7 = () => {
      try {
        const text = readFileSync(COUNT7, "utf8").trim();
        return text ? text.split(/\r?\n/).length : 0;
      } catch {
        return 0;
      }
    };
    const wrapper = spawn("cmd.exe", ["/c", LAUNCHER7], {
      cwd: DIR7,
      env: { ...process.env, RCU_BRIDGE_RETRY_MS: "1000" },
      stdio: "ignore",
      windowsHide: true,
    });
    try {
      const code7 = await Promise.race([
        new Promise((resolve) => wrapper.on("exit", (code) => resolve(code))),
        sleep(30_000).then(() => null),
      ]);
      if (code7 === null) throw new Error("⑦ 超时：重试启动器没在 30s 内耗尽退出");
      if (code7 !== 1) throw new Error(`⑦ 启动器退出码 ${code7}（重来耗尽应为 1）`);
      const runs7 = countRuns7();
      if (runs7 !== 4) throw new Error(`⑦ 桥被拉起 ${runs7} 次（应恰 4 次＝初次＋3 次重来）`);
      await sleep(2000);
      if (countRuns7() !== runs7) throw new Error("⑦ 耗尽后计数仍在涨：还有人在拉起桥（没彻底停）");
      results.push("⑦ 重来耗尽→彻底停：启动器 exit 1，桥恰跑 4 次（1＋3 重来），停后 2s 无新增");
    } finally {
      try {
        wrapper.kill("SIGKILL");
      } catch {
        /* 已经没了 */
      }
      killPids(findPids(join(DIR7, "bridge.mjs"))); // 假桥/中间壳残留兜底（全按隔离路径匹配）
    }
  }

  for (const r of results) log(r);
  const failed = results.some((r) => r.includes("失败") || r.includes("异常"));
  log(failed ? "结论：有失败项（见上）" : "结论：七项全过");
  process.exitCode = failed ? 1 : 0;
}

main().catch((e) => {
  log(`异常终止：${e?.message || e}`);
  killPids(findPids(FAKE_TUNNEL_FILE));
  killPids(findPids(TRAY_STATE_FILE));
  killPids(findPids(BRIDGE_ENTRY));
  process.exitCode = 1;
});

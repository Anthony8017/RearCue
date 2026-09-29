#!/usr/bin/env node
/**
 * 托盘 + 看门狗端到端实测（票 #171）：`node tools/bridge/tray-check.mjs`
 *
 * 验四件事（都在这台机器上真跑，不是单测里的桩）：
 *   ① 桥起来后托盘进程在（图标在＝桥在）；
 *   ② 隧道报到 URL → 状态文件 ready=true 且带上地址（图标转绿）；
 *   ③ 杀掉隧道进程 → 看门狗自动重拉一条，拿到**新地址**并写回状态（气泡的触发条件）；
 *   ④ 杀桥 → 托盘进程自己退（图标消失，不留孤儿）。
 *
 * 全程隔离：独立端口、独立临时目录、假隧道，绝不碰生产的 bridge.url / 计划任务。
 * 只在 Windows 上有意义（托盘是 WinForms），其他平台直接跳过。
 */
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const DIR = process.env.RCU_TRAY_CHECK_DIR || join(ROOT, ".scratch", "tray-check");
const PORT = Number(process.env.RCU_TRAY_CHECK_PORT || 18793);
const WAIT = { tray: 12_000, url: 20_000, reurl: 25_000, gone: 12_000 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (msg) => console.log(`[tray-check] ${msg}`);

function readState() {
  try {
    return JSON.parse(readFileSync(join(DIR, "tray-state.json"), "utf8"));
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

/** 按命令行找进程（只在本脚本自己起的隔离实例里用，命中即杀）。 */
function findPids(pattern) {
  const r = spawnSync(
    "powershell.exe",
    [
      "-NoProfile",
      "-Command",
      `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*${pattern}*' } | ForEach-Object { $_.ProcessId }`,
    ],
    { encoding: "utf8", windowsHide: true },
  );
  return String(r.stdout || "")
    .split(/\s+/)
    .filter(Boolean)
    .map(Number);
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
  writeFileSync(
    join(DIR, "fake-tunnel.mjs"),
    [
      'import { appendFileSync, readFileSync } from "node:fs";',
      `const COUNT = ${JSON.stringify(join(DIR, "tunnel-count"))};`,
      "let n = 0;",
      'try { n = parseInt(readFileSync(COUNT, "utf8"), 10) || 0; } catch {}',
      "n += 1;",
      "appendFileSync(COUNT, String(n));",
      'process.stdout.write(`INF Your quick Tunnel has been created! https://fake-${n}.trycloudflare.com\\n`);',
      "setInterval(() => {}, 1000);",
    ].join("\n"),
  );
  writeFileSync(
    join(DIR, "tunwg-fake.cmd"),
    `@echo off\r\nnode "%~dp0fake-tunnel.mjs"\r\n`,
  );

  const env = {
    ...process.env,
    BRIDGE_PORT: String(PORT),
    BRIDGE_TUNNEL: "tunwg",
    TUNWG_BIN: join(DIR, "tunwg-fake.cmd"),
    BRIDGE_TUNNEL_RETRY_MS: "1500",
    BRIDGE_PROBE_MS: "3000",
    RCU_TRAY_ICON_DIR: join(DIR, "icons"),
    RCU_TRAY_STATE: join(DIR, "tray-state.json"),
    RCU_TRAY_EVENT: join(DIR, "tray-event.jsonl"),
  };
  const bridge = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-codex", "--no-claude"], {
    cwd: ROOT,
    env,
    stdio: "ignore",
    windowsHide: true,
  });

  const results = [];
  let trays = [];
  try {
    // ① 托盘进程在
    trays = await waitFor("托盘进程出现", () => {
      const pids = findPids("tray.ps1").filter((p) => p !== process.pid);
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
    const tunnelPids = findPids("fake-tunnel.mjs");
    if (!tunnelPids.length) throw new Error("找不到假隧道进程，无法验证看门狗");
    killPids(tunnelPids);
    log(`已杀隧道进程 ${tunnelPids.join(",")}，等看门狗重拉`);
    const second = await waitFor("看门狗重拉出新地址", () => {
      const st = readState();
      return st && st.ready && st.url && st.url !== first.url ? st : null;
    }, WAIT.reurl);
    results.push(`③ 看门狗自愈：${first.url} → ${second.url}`);

    // 托盘只有一份（重启隧道不该多出图标）
    const trays2 = findPids("tray.ps1").filter((p) => p !== process.pid);
    if (trays2.length !== trays.length) {
      throw new Error(`托盘进程数变了：${trays.length} → ${trays2.length}（应当恒为一份）`);
    }
    results.push(`③b 托盘仍是一份（pid ${trays2.join(",")}）`);
  } finally {
    // ④ 杀桥 → 托盘自退
    try {
      bridge.kill("SIGKILL");
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
    // 兜底收尾：本轮起的东西一个不留
    killPids(findPids("fake-tunnel.mjs"));
    killPids(findPids("tray-check"));
    killPids(findPids(`BRIDGE_PORT=${PORT}`));
    if (existsSync(join(DIR, "tray-event.jsonl"))) {
      log(`气泡事件：${readFileSync(join(DIR, "tray-event.jsonl"), "utf8").trim() || "(无)"}`);
    }
  }

  for (const r of results) log(r);
  const failed = results.some((r) => r.includes("失败") || r.includes("异常"));
  log(failed ? "结论：有失败项（见上）" : "结论：四项全过");
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  log(`异常终止：${e?.message || e}`);
  killPids(findPids("fake-tunnel.mjs"));
  killPids(findPids("tray.ps1"));
  process.exit(1);
});

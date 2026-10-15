import http from "node:http";
import https from "node:https";
import { spawn } from "node:child_process";
import { existsSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { quickTunnelUrl } from "./tunnel-url.mjs";

/**
 * Owns the tunnel process, health probe, URL publication, and phone handoff.
 * The bridge runtime keeps its one-process lifecycle; this module keeps tunnel
 * policy behind one small seam so HTTP/event code does not know process details.
 */
export function createTunnelLifecycle({
  here,
  port,
  urlFile,
  feishuStateFile,
  urlBeforeStart = "",
  retryMs = Number(process.env.BRIDGE_TUNNEL_RETRY_MS || 5000),
  probeMs = Number(process.env.BRIDGE_PROBE_MS || 15_000),
  wantTunnel = true,
  bridgeUrlWithToken,
  redactBridgeUrl,
  phoneWatching,
  setTrayState,
  balloon,
  notifyFeishuBridgeUrl,
  adbArgs,
  adbCandidates,
  log,
}) {
  const tunnel = { child: null, ready: false, url: null, lastBalloon: null };
  let lastPushedUrl = null;
  let watchdogTimer = null;
  let probeTimer = null;
  let stopping = false;

  function syncTray() {
    setTrayState({
      url: tunnel.url,
      copyUrlFile: urlFile,
      port,
      ready: tunnel.ready,
      phone: phoneWatching(),
    }, log);
  }

  function pushUrlToPhone(url, index = 0) {
    const clearing = url == null;
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
      pushUrlToPhone(url, index + 1);
      return;
    }
    child.on("error", () => pushUrlToPhone(url, index + 1));
    child.on("exit", (code) => {
      if (code === 0) {
        const via = `${adb}${args.includes("-s") ? ` -s ${args[args.indexOf("-s") + 1]}` : ""}`;
        log(clearing ? `已广播清除桥地址到手机（${via}）` : `已自动推送隧道 URL 到手机（${via}）`);
      } else {
        pushUrlToPhone(url, index + 1);
      }
    });
  }

  function publishTunnelUrl(rawUrl) {
    const url = redactBridgeUrl(rawUrl);
    const addressedUrl = bridgeUrlWithToken(rawUrl);
    const previous = redactBridgeUrl(tunnel.url || urlBeforeStart);
    log(`隧道 URL: ${url}`);
    tunnel.url = url;
    try {
      writeFileSync(urlFile, addressedUrl + "\n");
    } catch (e) {
      log(`bridge.url 写入失败 ${e?.message || e}`);
    }
    if (previous && previous !== url && tunnel.lastBalloon !== url) {
      tunnel.lastBalloon = url;
      balloon(`桥地址已更换，手机端需要新地址：${url}`, log);
    }
    syncTray();
    notifyFeishuBridgeUrl({ addressedUrl, baseUrl: url, stateFile: feishuStateFile, log });
    if (addressedUrl === lastPushedUrl) return;
    lastPushedUrl = addressedUrl;
    pushUrlToPhone(addressedUrl);
  }

  function notifyPhoneDisabled() {
    pushUrlToPhone(null);
  }

  function handleTunnelExit(code) {
    if (stopping || tunnel.child) return;
    tunnel.ready = false;
    syncTray();
    log(`隧道进程退出 code=${code}，${retryMs / 1000}s 后自动重拉一条`);
    watchdogTimer = setTimeout(() => {
      watchdogTimer = null;
      if (process.env.BRIDGE_NO_WATCHDOG === "1") {
        log("看门狗已关（BRIDGE_NO_WATCHDOG=1）：隧道不会自动重拉");
        return;
      }
      log("看门狗重拉隧道");
      startTunnel();
    }, retryMs);
  }

  function spawnTunnelChild(bin, args, tag, onLine) {
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
      handleTunnelExit(null);
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
      handleTunnelExit(code);
    });
  }

  function startCloudflared() {
    const localBin = join(here, "bin", "cloudflared.exe");
    const bin = process.env.CLOUDFLARED_BIN || (existsSync(localBin) ? localBin : "cloudflared");
    log(`拉起 cloudflared（${bin}）`);
    spawnTunnelChild(
      bin,
      ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${port}`],
      "cloudflared",
      (line) => {
        const url = quickTunnelUrl(line);
        if (url) publishTunnelUrl(url);
      },
    );
  }

  function startTunwg() {
    const localBin = join(here, "bin", "tunwg.exe");
    const bin = process.env.TUNWG_BIN || (existsSync(localBin) ? localBin : "tunwg");
    log(`拉起 tunwg（${bin}）`);
    spawnTunnelChild(bin, ["-p", String(port)], "tunwg", (line) => {
      const match = line.match(/https:\/\/[a-z0-9]+\.l\.tunwg\.com/);
      if (match) publishTunnelUrl(match[0].replace(/[.,)"]+$/, ""));
    });
  }

  function startTunnel() {
    const kind = (process.env.BRIDGE_TUNNEL || "cf").toLowerCase();
    if (kind === "tunwg") startTunwg();
    else startCloudflared();
  }

  function stop() {
    stopping = true;
    if (watchdogTimer) clearTimeout(watchdogTimer);
    if (probeTimer) clearInterval(probeTimer);
    watchdogTimer = null;
    probeTimer = null;
    const child = tunnel.child;
    tunnel.child = null;
    try {
      child?.kill();
    } catch {
      /* 已经没了 */
    }
  }

  function startProbe() {
    const probe = () => {
      if (!wantTunnel || stopping) return;
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
        const target = process.env.BRIDGE_PROBE_BASE || url;
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
    probeTimer = setInterval(probe, probeMs);
    probeTimer.unref?.();
    probe();
  }

  return {
    state: tunnel,
    syncTray,
    startTunnel,
    startProbe,
    stop,
    notifyPhoneDisabled,
  };
}

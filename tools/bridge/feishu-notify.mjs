/**
 * 桥地址变更的飞书通知（票 #303）。
 *
 * 产品契约：
 *   - 第一次拿到可用桥地址、以及后续每次地址真正变化时通知一次；
 *   - 消息正文只含完整 Bridge URL（含连接所需 fragment），方便直接复制；
 *   - 电脑装了飞书就先打开飞书，再通过「命命」bot 发给机主；
 *   - 未安装飞书、启动失败或发送失败都静默放弃，不排队、不补发、不影响桥。
 *
 * 本模块不写日志里的完整 URL：通知正文可以带连接凭据，bridge.log 仍只记脱敏地址。
 * 隔离测试必须设 `BRIDGE_FEISHU_NOTIFY=0`，或注入假 spawn / 假可执行文件。
 */
import { spawn } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { delimiter, join } from "node:path";

const PROFILE = "cli_aa8fc94bcf381bb7";
const OWNER_USER_ID = "ou_8c7b69bb80f740d1ebf4fa665163d0bc";

/** 去掉 URL fragment；通知去重只看桥地址，不看访问 token 是否轮换。 */
export function bridgeUrlBase(rawUrl) {
  try {
    const url = new URL(rawUrl);
    url.hash = "";
    return url.toString();
  } catch {
    return String(rawUrl || "");
  }
}

function firstExisting(candidates, existsSyncImpl) {
  for (const candidate of candidates) {
    if (candidate && existsSyncImpl(candidate)) return candidate;
  }
  return null;
}

/** 找飞书桌面端；找不到＝未安装，按产品约定直接跳过。 */
export function findFeishuExecutable(options = {}) {
  const env = options.env || process.env;
  const platform = options.platform || process.platform;
  const existsSyncImpl = options.existsSyncImpl || existsSync;
  if (env.FEISHU_BIN) return firstExisting([env.FEISHU_BIN], existsSyncImpl);
  if (platform !== "win32") return null;

  const localAppData = env.LOCALAPPDATA || (env.USERPROFILE ? join(env.USERPROFILE, "AppData", "Local") : "");
  const candidates = [
    localAppData && join(localAppData, "Feishu", "app", "Feishu.exe"),
    localAppData && join(localAppData, "Programs", "Feishu", "Feishu.exe"),
    env.PROGRAMFILES && join(env.PROGRAMFILES, "Feishu", "Feishu.exe"),
    env["PROGRAMFILES(X86)"] && join(env["PROGRAMFILES(X86)"], "Feishu", "Feishu.exe"),
  ];
  return firstExisting(candidates, existsSyncImpl);
}

/** 找 lark-cli。Windows 优先 npm 的 .cmd shim（Node 不能裸 spawn .ps1）。 */
export function findLarkCli(options = {}) {
  const env = options.env || process.env;
  const platform = options.platform || process.platform;
  const existsSyncImpl = options.existsSyncImpl || existsSync;
  if (env.LARK_CLI) return firstExisting([env.LARK_CLI], existsSyncImpl);

  const names = platform === "win32"
    ? ["lark-cli.cmd", "lark-cli.exe", "lark-cli.bat"]
    : ["lark-cli"];
  const candidates = [];
  if (platform === "win32" && env.APPDATA) candidates.push(join(env.APPDATA, "npm", "lark-cli.cmd"));
  for (const dir of String(env.PATH || "").split(delimiter)) {
    if (!dir) continue;
    for (const name of names) candidates.push(join(dir, name));
  }
  return firstExisting(candidates, existsSyncImpl);
}

/**
 * 认领一次地址通知：只有状态文件里的上一条地址不同才返回 true。
 * 先落状态再尝试发送，确保发送失败也不会对同一地址反复重试。
 */
export function claimFeishuBridgeUrl(baseUrl, stateFile, options = {}) {
  const readFileSyncImpl = options.readFileSyncImpl || readFileSync;
  const writeFileSyncImpl = options.writeFileSyncImpl || writeFileSync;
  const normalized = bridgeUrlBase(baseUrl);
  let previous = "";
  try {
    previous = String(readFileSyncImpl(stateFile, "utf8")).trim();
  } catch {
    // 首次运行或状态不可读：仍允许尝试一次。
  }
  if (previous && previous === normalized) return false;
  try {
    writeFileSyncImpl(stateFile, normalized + "\n");
  } catch {
    // 状态写失败只影响跨重启去重，不能掀桌子。
  }
  return true;
}

function commandForSpawn(bin, args, env, platform) {
  if (platform === "win32" && /\.(cmd|bat)$/i.test(bin)) {
    return { command: env.ComSpec || "cmd.exe", args: ["/c", bin, ...args] };
  }
  return { command: bin, args };
}

function launchFeishu(exe, log, spawnImpl, env) {
  try {
    const child = spawnImpl(exe, [], {
      detached: true,
      stdio: "ignore",
      windowsHide: false,
      env,
    });
    child?.unref?.();
    child?.on?.("error", (error) => log(`飞书启动失败（${error?.code || error?.message || error}）`));
  } catch (error) {
    log(`飞书启动失败（${error?.code || error?.message || error}）`);
  }
}

function sendUrl(exe, addressedUrl, log, spawnImpl, env, platform) {
  const args = [
    "--profile", PROFILE,
    "im", "+messages-send",
    "--as", "bot",
    "--user-id", OWNER_USER_ID,
    "--text", addressedUrl,
    "--format", "json",
  ];
  const launch = commandForSpawn(exe, args, env, platform);
  try {
    const child = spawnImpl(launch.command, launch.args, {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
      env: { ...env, LARKSUITE_CLI_NO_UPDATE_NOTIFIER: "1" },
    });
    let stdout = "";
    let stderr = "";
    child?.stdout?.on?.("data", (chunk) => { stdout += String(chunk); });
    child?.stderr?.on?.("data", (chunk) => { stderr += String(chunk); });
    child?.on?.("error", (error) => log(`飞书消息发送失败（${error?.code || error?.message || error}）`));
    child?.on?.("exit", (code) => {
      if (code === 0) log("飞书地址消息已发送");
      else log(`飞书消息发送失败（exit=${code}${stderr ? ` ${stderr.trim()}` : ""}）`);
      if (stdout.trim()) {
        try {
          const result = JSON.parse(stdout);
          const messageId = result?.message_id || result?.data?.message_id;
          if (result?.ok !== true || !messageId) {
            log(`飞书消息未创建（${stdout.trim()}）`);
          }
        } catch {
          log(`飞书返回不是成功回执（${stdout.trim()}）`);
        }
      }
    });
  } catch (error) {
    log(`飞书消息发送失败（${error?.code || error?.message || error}）`);
  }
}

/**
 * 发送一次桥地址变更通知。函数只做异步派发，永不抛错、永不等待飞书。
 *
 * @param {object} input
 * @param {string} input.addressedUrl 完整连接链接，作为飞书正文原样发送。
 * @param {string} input.baseUrl 脱敏地址，只用于跨重启去重。
 * @param {(message: string) => void} [input.log]
 */
export function notifyFeishuBridgeUrl(input) {
  const {
    addressedUrl,
    baseUrl,
    log = () => {},
    enabled = true,
    stateFile,
    env = process.env,
    platform = process.platform,
    existsSyncImpl = existsSync,
    readFileSyncImpl = readFileSync,
    writeFileSyncImpl = writeFileSync,
    spawnImpl = spawn,
    feishuExecutable,
    larkCli,
  } = input || {};
  if (!enabled || env.BRIDGE_FEISHU_NOTIFY === "0" || !addressedUrl || !stateFile) return false;

  const normalizedBase = bridgeUrlBase(baseUrl || addressedUrl);
  if (!claimFeishuBridgeUrl(normalizedBase, stateFile, { readFileSyncImpl, writeFileSyncImpl })) return false;

  const feishu = Object.hasOwn(input || {}, "feishuExecutable")
    ? feishuExecutable
    : findFeishuExecutable({ env, platform, existsSyncImpl });
  if (!feishu) {
    log("未找到飞书桌面端：桥地址消息跳过");
    return false;
  }

  launchFeishu(feishu, log, spawnImpl, env);
  const cli = Object.hasOwn(input || {}, "larkCli")
    ? larkCli
    : findLarkCli({ env, platform, existsSyncImpl });
  if (!cli) {
    log("未找到 lark-cli：飞书已打开，但地址消息跳过");
    return false;
  }
  sendUrl(cli, addressedUrl, log, spawnImpl, env, platform);
  return true;
}
/**
 * 飞书桥地址通知测试（票 #303）：只用临时状态文件与假进程，绝不碰真实飞书、
 * 真实 lark-cli 或生产 bridge.url。
 */
import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  bridgeUrlBase,
  claimFeishuBridgeUrl,
  findFeishuExecutable,
  findLarkCli,
  notifyFeishuBridgeUrl,
} from "./feishu-notify.mjs";

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "rcu-feishu-"));
  const localAppData = join(root, "local");
  const appData = join(root, "roaming");
  mkdirSync(join(localAppData, "Feishu", "app"), { recursive: true });
  mkdirSync(join(appData, "npm"), { recursive: true });
  writeFileSync(join(localAppData, "Feishu", "app", "Feishu.exe"), "fake");
  writeFileSync(join(appData, "npm", "lark-cli.cmd"), "fake");
  return {
    root,
    stateFile: join(root, "bridge.feishu-url"),
    env: {
      LOCALAPPDATA: localAppData,
      APPDATA: appData,
      PATH: "",
      ComSpec: "cmd.exe",
    },
  };
}

function fakeSpawn() {
  const calls = [];
  const spawnImpl = (command, args, options) => {
    calls.push({ command, args, options });
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.unref = () => {};
    queueMicrotask(() => child.emit("exit", 0));
    return child;
  };
  return { calls, spawnImpl };
}

test("地址去重按脱敏 URL；首次与换址发送，同址 token 轮换不重复", () => {
  const f = fixture();
  const state = f.stateFile;
  assert.equal(claimFeishuBridgeUrl("https://a.example/", state), true);
  assert.equal(claimFeishuBridgeUrl("https://a.example/#token=next", state), false);
  assert.equal(claimFeishuBridgeUrl("https://b.example/#token=next", state), true);
  assert.equal(String(readFileSync(state, "utf8")).trim(), "https://b.example/");
});

test("消息正文只有完整链接，并且安装飞书时先打开飞书", () => {
  const f = fixture();
  const { calls, spawnImpl } = fakeSpawn();
  const addressed = "https://a.trycloudflare.com/#token=secret";
  const started = notifyFeishuBridgeUrl({
    addressedUrl: addressed,
    baseUrl: bridgeUrlBase(addressed),
    stateFile: f.stateFile,
    env: f.env,
    spawnImpl,
    log: () => {},
  });
  assert.equal(started, true);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].command, join(f.env.LOCALAPPDATA, "Feishu", "app", "Feishu.exe"));
  assert.deepEqual(calls[0].args, []);
  assert.equal(calls[1].command, "cmd.exe");
  assert.deepEqual(calls[1].args.slice(0, 2), ["/c", join(f.env.APPDATA, "npm", "lark-cli.cmd")]);
  const textIndex = calls[1].args.indexOf("--text");
  assert.ok(textIndex > 0);
  assert.equal(calls[1].args[textIndex + 1], addressed);
  assert.equal(calls[1].args[textIndex + 2], "--format");
});

test("飞书未安装时静默跳过，同一地址不补发；下一次换址再尝试", () => {
  const f = fixture();
  const { calls, spawnImpl } = fakeSpawn();
  const feishu = join(f.env.LOCALAPPDATA, "Feishu", "app", "Feishu.exe");
  const lark = join(f.env.APPDATA, "npm", "lark-cli.cmd");
  // 让探测只认显式注入的“未安装”状态，避免断言依赖机器 PATH。
  const first = notifyFeishuBridgeUrl({
    addressedUrl: "https://a.example/#token=1",
    baseUrl: "https://a.example/",
    stateFile: f.stateFile,
    env: f.env,
    spawnImpl,
    feishuExecutable: null,
    larkCli: lark,
  });
  assert.equal(first, false);
  assert.equal(calls.length, 0);
  const second = notifyFeishuBridgeUrl({
    addressedUrl: "https://a.example/#token=2",
    baseUrl: "https://a.example/",
    stateFile: f.stateFile,
    env: f.env,
    spawnImpl,
    feishuExecutable: null,
    larkCli: lark,
  });
  assert.equal(second, false);
  assert.equal(calls.length, 0);
  const third = notifyFeishuBridgeUrl({
    addressedUrl: "https://b.example/#token=3",
    baseUrl: "https://b.example/",
    stateFile: f.stateFile,
    env: f.env,
    spawnImpl,
    feishuExecutable: feishu,
    larkCli: lark,
  });
  assert.equal(third, true, "下一次地址真正变化时应重新尝试");
  assert.equal(calls.length, 2);
  assert.equal(existsSync(feishu), true, "fixture should have installed Feishu for later assertions");
});

test("发送抛错不掀桌子，也不对同一地址重试", () => {
  const f = fixture();
  const calls = [];
  const spawnImpl = (command, args) => {
    calls.push({ command, args });
    throw new Error("boom");
  };
  const input = {
    addressedUrl: "https://a.example/#token=1",
    baseUrl: "https://a.example/",
    stateFile: f.stateFile,
    env: f.env,
    spawnImpl,
  };
  assert.doesNotThrow(() => notifyFeishuBridgeUrl(input));
  assert.equal(calls.length, 2, "应尝试启动飞书并发送一次");
  assert.doesNotThrow(() => notifyFeishuBridgeUrl({ ...input, addressedUrl: "https://a.example/#token=2" }));
  assert.equal(calls.length, 2, "同址不重试");
});

test("可执行文件探测认出 Windows npm shim 与飞书常见安装目录", () => {
  const f = fixture();
  assert.equal(findFeishuExecutable({ env: f.env, platform: "win32" }), join(f.env.LOCALAPPDATA, "Feishu", "app", "Feishu.exe"));
  assert.equal(findLarkCli({ env: f.env, platform: "win32" }), join(f.env.APPDATA, "npm", "lark-cli.cmd"));
});
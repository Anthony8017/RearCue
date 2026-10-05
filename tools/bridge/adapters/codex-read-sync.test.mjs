import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { appendFileSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startCodexAdapter } from "./codex.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const bridgeDir = mkdtempSync(join(tmpdir(), "rearcue-codex-read-bridge-"));
let child;
let base;

async function waitFor(predicate, message, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await predicate();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.fail(message);
}

before(async () => {
  const socket = createServer();
  await new Promise((resolve) => socket.listen(0, "127.0.0.1", resolve));
  const port = socket.address().port;
  await new Promise((resolve) => socket.close(resolve));
  base = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, [join(here, "..", "bridge.mjs"),
    "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode", "--no-dsh"], {
    stdio: "ignore",
    windowsHide: true,
    env: {
      ...process.env,
      BRIDGE_PORT: String(port),
      BRIDGE_LOG: join(bridgeDir, "bridge.log"),
      BRIDGE_SEQ_FILE: join(bridgeDir, "bridge.seq"),
      BRIDGE_IDENTITY_FILE: join(bridgeDir, "identity.json"),
      BRIDGE_ACCESS_TOKEN: "isolated-read-test",
      RCU_TRAY_STATE: join(bridgeDir, "tray-state.json"),
    },
  });
  await waitFor(async () => {
    try { return (await fetch(`${base}/health`)).ok; } catch { return false; }
  }, "隔离桥启动超时");
});

after(async () => {
  if (child && child.exitCode === null) {
    const stopped = new Promise((resolve) => child.once("exit", resolve));
    child.kill();
    await stopped;
  }
  rmSync(bridgeDir, { recursive: true, force: true });
});

function desktopState(unreadIds) {
  return JSON.stringify({ "electron-thread-read-state-v1": {
    version: 1, unreadByIdentity: { identity: { "local:host": unreadIds } },
  } });
}

function fixture(sessionId, rawState) {
  const dir = mkdtempSync(join(tmpdir(), "rearcue-codex-read-session-"));
  const root = join(dir, "sessions");
  const now = new Date();
  const day = join(root, String(now.getFullYear()),
    String(now.getMonth() + 1).padStart(2, "0"), String(now.getDate()).padStart(2, "0"));
  mkdirSync(day, { recursive: true });
  const file = join(day, "rollout-2026-10-05-00000000-0000-0000-0000-000000000042.jsonl");
  const stateFile = join(dir, ".codex-global-state.json");
  writeFileSync(file, JSON.stringify({ type: "session_meta", payload: {
    session_id: sessionId, cwd: "C:/read-sync-test",
  } }) + "\n" + JSON.stringify({ type: "event_msg", payload: { type: "task_started" } }) + "\n");
  writeFileSync(stateFile, rawState);
  let pending = Promise.resolve();
  const events = [];
  const adapter = startCodexAdapter((event) => {
    events.push(event);
    // session_meta 只是身份补丁；桥的 appendEvent 也会忽略尚无工作状态的事件。
    if (!event.status && event.kind !== "read-state" && event.kind !== "membership") return;
    pending = pending.then(async () => {
      const response = await fetch(`${base}/inject`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(event),
      });
      assert.equal(response.status, 200, `${await response.text()} ${JSON.stringify(event)}`);
    });
  }, {
    root, archivedRoot: join(dir, "archived_sessions"),
    titleIndexFile: join(dir, "session_index.jsonl"), globalStateFile: stateFile,
    pollMs: 20, debounceMs: 5,
  });
  return {
    events, stateFile,
    async flush() { await pending; },
    append(payload) {
      appendFileSync(file, JSON.stringify({ type: "event_msg", payload }) + "\n");
    },
    async close() {
      adapter.stop();
      await pending;
      rmSync(dir, { recursive: true, force: true });
    },
  };
}

async function session(sessionId) {
  const snapshot = await (await fetch(`${base}/snapshot`)).json();
  return snapshot.sessions.find((row) => row.sessionId === sessionId);
}

test("Codex 正文已打开：完成后未读集合不变，切走再切回也不能留下蓝点", async () => {
  const id = "codex-stays-open";
  const f = fixture(id, desktopState([]));
  try {
    await waitFor(() => f.events.some((e) => e.sessionId === id), "缺少初始会话");
    await f.flush();
    f.append({ type: "task_started" });
    await waitFor(() => f.events.some((e) => e.taskStarted), "未开始任务");
    await f.flush();
    f.append({ type: "task_complete", last_agent_message: "已完成" });
    await waitFor(() => f.events.some((e) => e.completion === "done"), "未完成任务");
    await f.flush();
    assert.equal((await session(id)).readState, "read", "停留在正文时完成即已阅");
    // 切换两个已阅会话时，Codex 的未读集合始终为空。
    writeFileSync(f.stateFile, JSON.stringify({ switchedTo: "another", ...JSON.parse(desktopState([])) }));
    await new Promise((resolve) => setTimeout(resolve, 60));
    writeFileSync(f.stateFile, JSON.stringify({ switchedTo: id, ...JSON.parse(desktopState([])) }));
    await new Promise((resolve) => setTimeout(resolve, 60));
    await f.flush();
    const row = await session(id);
    assert.equal(row.status, "idle");
    assert.equal(row.readState, "read", "PC 一直已阅且切回正文，列表应消除蓝点");
  } finally {
    await f.close();
  }
});

test("后台未阅回答保留蓝点，PC 真正打开后清除，无关写入不清除", async () => {
  const id = "codex-background-unread";
  const f = fixture(id, desktopState([id]));
  try {
    await waitFor(() => f.events.some((e) => e.readState === "unread"), "缺少来源未阅事实");
    await f.flush();
    f.append({ type: "task_complete", last_agent_message: "后台完成" });
    await waitFor(() => f.events.some((e) => e.completion === "done"), "未完成任务");
    await f.flush();
    assert.equal((await session(id)).readState, "unread");
    writeFileSync(f.stateFile, JSON.stringify({ unrelated: true, ...JSON.parse(desktopState([id])) }));
    await new Promise((resolve) => setTimeout(resolve, 60));
    await f.flush();
    assert.equal((await session(id)).readState, "unread");
    writeFileSync(f.stateFile, desktopState([]));
    await waitFor(() => f.events.some((e) => e.kind === "read-state" && e.readState === "read"), "PC 打开后未同步已阅");
    await f.flush();
    assert.equal((await session(id)).readState, "read");
  } finally {
    await f.close();
  }
});

test("完成与 PC 已阅落在同一扫描，完成事件不能重新点亮蓝点", async () => {
  const id = "codex-read-at-completion";
  const f = fixture(id, desktopState([id]));
  try {
    await waitFor(() => f.events.some((e) => e.readState === "unread"), "缺少来源未阅事实");
    await f.flush();
    f.append({ type: "task_complete", last_agent_message: "切回时完成" });
    writeFileSync(f.stateFile, desktopState([]));
    await waitFor(() => f.events.some((e) => e.completion === "done"), "未完成任务");
    await f.flush();
    assert.equal((await session(id)).readState, "read");
  } finally {
    await f.close();
  }
});

test("Codex 状态坏读不清蓝点，修复文件后继续同步", async () => {
  const id = "codex-bad-read-state";
  const f = fixture(id, desktopState([id]));
  try {
    await waitFor(() => f.events.some((e) => e.readState === "unread"), "缺少来源未阅事实");
    await f.flush();
    const badStates = ["{broken", "{}", JSON.stringify({
      "electron-thread-read-state-v1": { unreadByIdentity: { identity: { "local:host": "bad" } } },
    })];
    for (const [index, raw] of badStates.entries()) {
      writeFileSync(f.stateFile, raw);
      const reply = `坏读期间完成 ${index}`;
      f.append({ type: "task_complete", last_agent_message: reply });
      await waitFor(() => f.events.some((e) => e.assistantText === reply), "坏读期间未完成任务");
      await f.flush();
      assert.equal((await session(id)).readState, "unread", "坏读必须保留未阅");
    }
    writeFileSync(f.stateFile, desktopState([]));
    await waitFor(() => f.events.some((e) => e.kind === "read-state" && e.readState === "read"), "恢复后未同步");
    await f.flush();
    assert.equal((await session(id)).readState, "read");
  } finally {
    await f.close();
  }
});

test("完成时来源状态不可读，不能沿用旧的已阅事实消掉新回答", async () => {
  const id = "codex-missing-read-state";
  const f = fixture(id, desktopState([]));
  try {
    await waitFor(() => f.events.some((e) => e.status === "working"), "缺少初始会话");
    await f.flush();
    rmSync(f.stateFile);
    f.append({ type: "task_complete", last_agent_message: "来源状态缺失时完成" });
    await waitFor(() => f.events.some((e) => e.completion === "done"), "未完成任务");
    await f.flush();
    assert.equal((await session(id)).readState, "unread");
    writeFileSync(f.stateFile, desktopState([]));
    await waitFor(() => f.events.some((e) => e.kind === "read-state" && e.readState === "read"), "旧已阅事实恢复后仍须补齐完成回执");
    await f.flush();
    assert.equal((await session(id)).readState, "read");
  } finally {
    await f.close();
  }
});

test("启动时无可信的 Codex 状态，完成后首次读到已阅也补回执", async () => {
  const id = "codex-first-valid-read-state";
  const f = fixture(id, "{}");
  try {
    await waitFor(() => f.events.some((e) => e.status === "working"), "缺少初始会话");
    await f.flush();
    f.append({ type: "task_complete", last_agent_message: "首次读取前完成" });
    await waitFor(() => f.events.some((e) => e.completion === "done"), "未完成任务");
    await f.flush();
    assert.equal((await session(id)).readState, "unread");
    writeFileSync(f.stateFile, desktopState([]));
    await waitFor(() => f.events.some((e) => e.kind === "read-state" && e.readState === "read"), "首次可信已阅未补回执");
    await f.flush();
    assert.equal((await session(id)).readState, "read");
  } finally {
    await f.close();
  }
});

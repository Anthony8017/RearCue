// Claude authoritative local-record membership scanner tests (spec 0023 / ticket #238).
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import {
  CLAUDE_MEMBERSHIP_POLL_MS,
  claudeMembershipFact,
  defaultClaudeUserDataRoot,
  parseClaudeMembershipRecord,
  startClaudeMembershipScanner,
} from "./claude-membership.mjs";
import { membershipFact, SourceMembershipLedger } from "./source-membership.mjs";
import { startClaudeAdapter } from "./claude.mjs";

async function waitForEvent(events, predicate, timeoutMs = 2500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = events.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("claude membership event timeout");
}

function fixture() {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-claude-membership-"));
  const root = join(temp, "local-agent-mode-sessions");
  const dir = join(root, "account-1", "org-1");
  mkdirSync(dir, { recursive: true });
  return { temp, root, file: join(dir, "session-1.json") };
}

test("Windows：自动识别 Claude-3p 与 Store 数据目录，支持显式覆盖", () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-claude-root-"));
  const env = { APPDATA: join(temp, "Roaming"), LOCALAPPDATA: join(temp, "Local") };
  const store = join(env.LOCALAPPDATA, "Packages", "Claude_pzs8sxrjxfjjc", "LocalCache", "Roaming", "Claude");
  const custom = join(env.LOCALAPPDATA, "Claude-3p");
  try {
    mkdirSync(join(env.APPDATA, "Claude"), { recursive: true }); // 空旧目录不能遮住真实会话
    mkdirSync(join(store, "claude-code-sessions"), { recursive: true });
    assert.equal(defaultClaudeUserDataRoot({ platform: "win32", env, home: temp }), store);
    mkdirSync(join(custom, "claude-code-sessions"), { recursive: true });
    assert.equal(defaultClaudeUserDataRoot({ platform: "win32", env, home: temp }), custom);
    assert.equal(defaultClaudeUserDataRoot({ platform: "win32", env: { ...env, CLAUDE_DESKTOP_USER_DATA: temp }, home: temp }), temp);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});

test("Desktop JSON：按 cliSessionId 产生归档墓碑，携带当前标题与目录", () => {
  const record = parseClaudeMembershipRecord(JSON.stringify({
    sessionId: "local_desktop-1", cliSessionId: "cli-1", isArchived: true,
    title: "手雷中途爆炸不伤害玩家逻辑", cwd: "C:/Ant_Nest/.claude/worktrees/test", originCwd: "C:/Ant_Nest",
  }), "local_desktop-1");
  const fact = claudeMembershipFact({ record, generation: 1, revision: 1 });
  assert.equal(fact.sourceSessionId, "cli-1");
  assert.equal(fact.title, "手雷中途爆炸不伤害玩家逻辑");
  assert.equal(fact.workspace, "C:/Ant_Nest");
  const ledger = new SourceMembershipLedger();
  ledger.apply(fact);
  assert.equal(ledger.acceptsActivity("claude", "cli-1"), false, "归档必须拦住 transcript/hooks 使用的同一 CLI ID");
});

test("Code/Cowork 两目录扫描：坏文件沿用 CLI ID，取消归档解除同一墓碑", () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-claude-layout-"));
  const roots = [join(temp, "claude-code-sessions"), join(temp, "local-agent-mode-sessions")];
  const files = roots.map((root, index) => {
    const dir = join(root, "account", "org");
    mkdirSync(dir, { recursive: true });
    return join(dir, `local_${index}.json`);
  });
  const writeRecord = (index, isArchived) => writeFileSync(files[index], JSON.stringify({
    sessionId: `local_${index}`, cliSessionId: `cli-${index}`, isArchived,
  }));
  const events = [];
  let scanner;
  try {
    writeRecord(0, true);
    writeRecord(1, false);
    scanner = startClaudeMembershipScanner((event) => events.push(event), { roots, autoStart: false });
    assert.equal(events.find((e) => e.sourceSessionId === "cli-0")?.archiveState, "ARCHIVED");
    assert.equal(events.find((e) => e.sourceSessionId === "cli-1")?.archiveState, "ACTIVE");
    writeFileSync(files[0], "{broken");
    scanner.scan();
    assert.equal(events.at(-1).sourceSessionId, "cli-0");
    assert.equal(events.at(-1).archiveState, "UNKNOWN");
    assert.equal(scanner.ledger.acceptsActivity("claude", "cli-0"), false);
    writeRecord(0, false);
    scanner.scan();
    assert.equal(events.at(-1).reason, "unarchive");
    assert.equal(events.at(-1).sourceSessionId, "cli-0");
    assert.equal(scanner.ledger.acceptsActivity("claude", "cli-0"), true);
  } finally {
    scanner?.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("Claude adapter 默认目录布局：归档 Code 会话不被冷启动 transcript 或迟到消息复活", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-claude-code-archive-"));
  const root = join(temp, "projects");
  const project = join(root, "project");
  const dir = join(temp, "claude-code-sessions", "account", "org");
  mkdirSync(project, { recursive: true });
  mkdirSync(dir, { recursive: true });
  const file = join(dir, "local_desktop.json");
  const writeRecord = (isArchived) => writeFileSync(file, JSON.stringify({
    sessionId: "local_desktop", cliSessionId: "cli-replay", isArchived, title: "真实 Code 会话", cwd: "C:/repo",
  }));
  writeRecord(true);
  writeFileSync(join(project, "cli-replay.jsonl"), JSON.stringify({
    cwd: "C:/repo", type: "assistant", message: { content: [{ type: "text", text: "旧回答" }] },
  }) + "\n");
  const ledger = new SourceMembershipLedger();
  const roster = new Map();
  const events = [];
  const adapter = startClaudeAdapter((event) => {
    events.push(event);
    if (event.kind === "membership") {
      ledger.apply(event);
      if (event.archiveState === "ARCHIVED") roster.delete(event.sessionId);
      else if (event.archiveState === "ACTIVE") roster.set(event.sessionId, event);
    } else if (ledger.acceptsActivity(event.source, event.sessionId)) roster.set(event.sessionId, event);
  }, { root, userDataRoot: temp, pollMs: 20, debounceMs: 50 });
  try {
    await waitForEvent(events, (e) => e.sourceSessionId === "cli-replay" && e.archiveState === "ARCHIVED");
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(roster.has("cli-replay"), false);
    assert.equal(roster.has("local_desktop"), false, "Desktop ID 不应建立另一条在册会话");
    writeRecord(false);
    const restored = await waitForEvent(events, (e) => e.reason === "unarchive");
    assert.equal(restored.title, "真实 Code 会话");
    assert.equal(restored.workspace, "C:/repo");
    assert.equal(roster.has("cli-replay"), true);
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("真实桥 HTTP：Code 归档移出快照、迟到 hook 不复活、重启保留墓碑、取消归档回册", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-claude-http-"));
  const dir = join(temp, "claude-code-sessions", "account", "org");
  mkdirSync(dir, { recursive: true });
  const file = join(dir, "local_http.json");
  const writeRecord = (isArchived) => writeFileSync(file, JSON.stringify({
    sessionId: "local_http", cliSessionId: "cli-http-archive", isArchived, title: "HTTP 归档回归", cwd: "C:/repo",
  }));
  const socket = createServer();
  await new Promise((resolve) => socket.listen(0, "127.0.0.1", resolve));
  const port = socket.address().port;
  await new Promise((resolve) => socket.close(resolve));
  const base = `http://127.0.0.1:${port}`;
  let child;
  let exited;
  const start = () => {
    child = spawn(process.execPath, [fileURLToPath(new URL("../bridge.mjs", import.meta.url)),
      "--no-tunnel", "--no-codex", "--no-zcode", "--no-dsh"], {
      stdio: "ignore",
      env: { ...process.env, CLAUDE_DESKTOP_USER_DATA: temp, BRIDGE_PORT: String(port),
        BRIDGE_LOG: join(temp, "bridge.log"), BRIDGE_SEQ_FILE: join(temp, "bridge.seq"),
        BRIDGE_IDENTITY_FILE: join(temp, "identity.json"), BRIDGE_URL_FILE: join(temp, "bridge.url"),
        RCU_TRAY_STATE: join(temp, "tray-state.json"), BRIDGE_ADB_PUSH: "0", BRIDGE_ACCESS_TOKEN: "claude-test-token" },
    });
    exited = new Promise((resolve) => child.once("exit", resolve));
  };
  const stop = async () => { if (child.exitCode === null) child.kill(); await exited; };
  const snapshotWhen = async (predicate, timeoutMs = 2000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const snapshot = await (await fetch(`${base}/snapshot`)).json();
        if (predicate(snapshot)) return snapshot;
      } catch { /* isolated bridge starting */ }
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    throw new Error("Claude HTTP 归档同步未在预算内完成");
  };
  const active = (s) => s.sessions.find((row) => row.sessionId === "cli-http-archive");
  const archived = (s) => !active(s) && s.memberships?.some((fact) =>
    fact.sourceSessionId === "cli-http-archive" && fact.archiveState === "ARCHIVED");
  const lateHook = async (expectedStatus = 400) => {
    const response = await fetch(`${base}/hooks/claude`, { method: "POST",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({
        hook_event_name: "MessageDisplay", session_id: "cli-http-archive", delta: "迟到回答", cwd: "C:/repo",
      }) });
    await response.arrayBuffer();
    assert.equal(response.status, expectedStatus, "同一 hook 归档时被墓碑拒绝，取消归档后正常接受");
  };
  try {
    writeRecord(false);
    start();
    const initial = await snapshotWhen(active, 5000);
    assert.equal(active(initial).title, "HTTP 归档回归");
    assert.equal(initial.sessions.some((s) => s.sessionId === "local_http"), false);
    writeRecord(true);
    await snapshotWhen(archived);
    await lateHook();
    assert.equal(active(await (await fetch(`${base}/snapshot`)).json()), undefined);
    await stop();
    start();
    await snapshotWhen(archived, 5000);
    await lateHook();
    assert.equal(active(await (await fetch(`${base}/snapshot`)).json()), undefined);
    writeRecord(false);
    const restored = await snapshotWhen(active);
    assert.equal(active(restored).title, "HTTP 归档回归");
    assert.equal(active(restored).workspace, "C:/repo");
    await lateHook(200);
    assert.equal(active(await (await fetch(`${base}/snapshot`)).json()).status, "working");
  } finally {
    if (child) await stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("Claude JSON records：ACTIVE -> ARCHIVED -> UNKNOWN -> UNARCHIVE，缺失文件只给 UNKNOWN", async () => {
  const { temp, root, file } = fixture();
  writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false, title: "first" }), "utf8");
  const events = [];
  const scanner = startClaudeMembershipScanner((event) => events.push(event), { root, pollMs: 20 });
  try {
    const active = await waitForEvent(events, (e) => e.sourceSessionId === "session-1" && e.archiveState === "ACTIVE");
    assert.equal(active.membership, "PRESENT");
    assert.equal(active.reason, "authoritative-snapshot");

    writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: true }), "utf8");
    const archived = await waitForEvent(events, (e) => e.sourceSessionId === "session-1" && e.archiveState === "ARCHIVED");
    assert.equal(archived.membership, "ABSENT");
    assert.equal(archived.reason, "archive");

    writeFileSync(file, "{not-json", "utf8");
    const unknown = await waitForEvent(events, (e) => e.sourceSessionId === "session-1" && e.archiveState === "UNKNOWN");
    assert.equal(unknown.membership, "PRESENT");
    assert.equal(unknown.reason, "unknown");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), true, "UNKNOWN 不得清除归档墓碑");
    assert.equal(scanner.ledger.acceptsActivity("claude", "session-1"), false);

    writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false }), "utf8");
    const restored = await waitForEvent(
      events,
      (e) => e.sourceSessionId === "session-1" && e.archiveState === "ACTIVE" && e.reason === "unarchive",
    );
    assert.equal(restored.membership, "PRESENT");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), false);

    rmSync(file, { force: true });
    const absentUnknown = await waitForEvent(
      events,
      (e) => e.sourceSessionId === "session-1" && e.archiveState === "UNKNOWN" && e !== unknown,
    );
    assert.equal(absentUnknown.reason, "unknown");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), false);
    assert.equal(scanner.ledger.acceptsActivity("claude", "session-1"), true, "缺失文件不是归档");
  } finally {
    scanner.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("Claude JSON records：坏形状回 UNKNOWN；扫描代数保护拒绝旧记录覆盖新墓碑", async () => {
  assert.ok(CLAUDE_MEMBERSHIP_POLL_MS <= 2000, "Claude membership 必须留在 2s 同步预算内");
  assert.deepEqual(parseClaudeMembershipRecord("{broken", "fallback"), {
    valid: false,
    sessionId: "fallback",
    reason: "unknown",
  });
  assert.equal(parseClaudeMembershipRecord(JSON.stringify({ sessionId: "x" }), "fallback").valid, false);
  assert.equal(
    claudeMembershipFact({
      record: { valid: false, sessionId: "fallback" },
      generation: 1,
      revision: 1,
    }).archiveState,
    "UNKNOWN",
  );

  const { temp, root, file } = fixture();
  writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false }), "utf8");
  const events = [];
  const scanner = startClaudeMembershipScanner((event) => events.push(event), { root, pollMs: 20 });
  try {
    const first = await waitForEvent(events, (e) => e.archiveState === "ACTIVE");
    const newerTombstone = membershipFact({
      source: "claude",
      sourceSessionId: "session-1",
      membership: "ARCHIVED",
      generation: first.generation + 1_000_000,
      revision: first.revision + 1_000_000,
      reason: "archive",
    });
    assert.equal(scanner.ledger.apply(newerTombstone).accepted, true);

    const before = events.length;
    writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false, changed: true }), "utf8");
    scanner.scan();
    assert.equal(events.length, before, "旧 generation 的记录不得覆盖更新墓碑");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), true);
    assert.equal(scanner.ledger.acceptsActivity("claude", "session-1"), false);
  } finally {
    scanner.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

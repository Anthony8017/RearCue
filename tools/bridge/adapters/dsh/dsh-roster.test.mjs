import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { readDshRoster, startDshRosterAdapter } from "./dsh-roster.mjs";

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "rearcue-dsh-roster-"));
  const storage = join(root, "storages"); mkdirSync(storage);
  const workspace = (ids, archived = []) => writeFileSync(join(storage, "workspace.json"), JSON.stringify({
    global: { workspaceIds: ["workspace"], archivedSessionIds: archived },
    tables: { workspaces: { workspace: { path: "C:/repo", sessionIds: ids } } },
  }));
  const projection = (id, rows, { domain = "session_projcache_archive_manager_v2", hashed = true, formatVersion = 4, recordId = id } = {}) => {
    const dir = join(storage, domain, "sessions"); mkdirSync(dir, { recursive: true });
    const key = hashed ? `session_${createHash("sha256").update(id).digest("base64url")}` : id;
    const file = join(dir, `${key}.json`);
    writeFileSync(file, JSON.stringify({ version: 2, record: { sessionId: recordId,
      identity: { formatVersion, createdAt: 1 }, rows } })); return file;
  };
  return { root, storage, workspace, projection, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

const row = (val, seq = 1) => ({ ver: 1, seq, val });

test("DSH 哈希缓存提供可见性、标题与回合边界；空白和未确认记录不入册", () => {
  const f = fixture(); f.workspace(["blank", "normal", "missing", "archived"], ["archived"]);
  f.projection("blank", { sessionListMetadata: row({ blank: true }), sessionStats: row({ turns: 0 }) });
  f.projection("normal", { sessionListMetadata: row({ blank: false }), title: row("正常对话"), turnBoundary: row({ openTurnStartSeq: 3 }) });
  try { assert.deepEqual(readDshRoster(f.root).sessions, [{ sessionId: "normal", workspace: "C:/repo", title: "正常对话", status: "working" }]); }
  finally { f.cleanup(); }
});

test("空白占位开始真正对话后回册，全部归档后名单为零", () => {
  const f = fixture(); f.workspace(["conversation"]);
  f.projection("conversation", { sessionListMetadata: row({ blank: true }) });
  try {
    assert.equal(readDshRoster(f.root).sessions.length, 0);
    f.projection("conversation", { sessionListMetadata: row({ blank: false }, 2), turnBoundary: row({ openTurnStartSeq: null }, 2) });
    assert.equal(readDshRoster(f.root).sessions[0].status, "idle");
    f.workspace(["conversation"], ["conversation"]);
    assert.equal(readDshRoster(f.root).sessions.length, 0);
  } finally { f.cleanup(); }
});

test("按每行序号合并缓存，错误 sessionId 不使用，显式 blank 优先于标题/统计", () => {
  const f = fixture(); f.workspace(["mixed", "wrong", "blank"]);
  f.projection("mixed", { sessionListMetadata: row({ blank: false }, 4), title: row("新标题", 5) });
  f.projection("mixed", { sessionListMetadata: row({ blank: true }, 1), title: row("旧标题", 2), turnBoundary: row({ openTurnStartSeq: null }, 6) }, { domain: "session_projcache", hashed: false });
  f.projection("wrong", { sessionListMetadata: row({ blank: false }) }, { recordId: "somebody-else" });
  f.projection("blank", { sessionListMetadata: row({ blank: true }), title: row("标题不能证明已有对话"), sessionStats: row({ turns: 10 }) });
  try { assert.deepEqual(readDshRoster(f.root).sessions, [{ sessionId: "mixed", workspace: "C:/repo", title: "新标题", status: "idle" }]); }
  finally { f.cleanup(); }
});

test("旧版缓存的真实对话统计兼容；热读取坏缓存保留最后可信投影", async () => {
  const f = fixture(); f.workspace(["legacy"]);
  const file = f.projection("legacy", { sessionStats: row({ turns: 1 }), title: row("旧版正常对话") }, { domain: "session_projcache", hashed: false, formatVersion: 3 });
  const rosters = []; const stop = startDshRosterAdapter((r) => rosters.push(r), { dshHome: f.root, intervalMs: 20 });
  try {
    assert.equal(rosters[0].sessions.length, 1);
    writeFileSync(file, "{torn"); await new Promise((r) => setTimeout(r, 60));
    assert.equal(rosters.at(-1).sessions.length, 1);
    assert.equal(rosters.at(-1).sessions[0].title, "旧版正常对话");
  } finally { stop(); f.cleanup(); }
});

test("真实桥：空白/迟到 hooks 不增员；开始对话保留早到正文；归档与重启均不复活", async () => {
  const f = fixture(); f.workspace(["conversation"]);
  f.projection("conversation", { sessionListMetadata: row({ blank: true }) });
  const socket = createServer(); await new Promise((resolve) => socket.listen(0, "127.0.0.1", resolve));
  const port = socket.address().port; await new Promise((resolve) => socket.close(resolve));
  const base = `http://127.0.0.1:${port}`;
  let child, exited;
  const start = () => {
    child = spawn(process.execPath, [fileURLToPath(new URL("../../bridge.mjs", import.meta.url)),
      "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode"], { stdio: "ignore", env: {
        ...process.env, BRIDGE_DSH_ROSTER: "1", BRIDGE_DSH_HOME: f.root, BRIDGE_PORT: String(port),
        BRIDGE_LOG: join(f.root, "bridge.log"), BRIDGE_SEQ_FILE: join(f.root, "bridge.seq"),
        BRIDGE_IDENTITY_FILE: join(f.root, "identity.json"), BRIDGE_URL_FILE: join(f.root, "bridge.url"),
        RCU_TRAY_STATE: join(f.root, "tray-state.json"), BRIDGE_ADB_PUSH: "0", BRIDGE_ACCESS_TOKEN: "dsh-fixture-token",
      } });
    exited = new Promise((resolve) => child.once("exit", resolve));
  };
  const stop = async () => { if (child.exitCode === null) child.kill(); await exited; };
  const waitFor = async (predicate, timeout = 2000) => {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      try { const s = await (await fetch(`${base}/snapshot`)).json(); if (predicate(s)) return s; } catch {}
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    throw new Error("DSH 名册未在预算内同步");
  };
  const post = async (body) => {
    const response = await fetch(`${base}/hooks/dsh`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const result = await response.json(); return { status: response.status, result };
  };
  try {
    start(); await waitFor((s) => s.sessions.length === 0, 5000);
    assert.equal((await post({ event: "session-added", sessionId: "conversation", workspace: "C:/repo" })).status, 400);
    const early = await post({ event: "user-message", sessionId: "conversation", userText: "第一条真实提问" });
    assert.equal(early.result.buffered, true);
    const oversized = await post({ event: "assistant-message", sessionId: "unlisted-large", assistantText: "x".repeat(262144) });
    assert.equal(oversized.result.buffered, false, "超过暂存护栏不能误报已保存");
    assert.equal(oversized.result.ignored, true);
    assert.equal((await (await fetch(`${base}/snapshot`)).json()).sessions.length, 0);
    f.projection("conversation", { sessionListMetadata: row({ blank: false }, 2), title: row("开始对话", 2), turnBoundary: row({ openTurnStartSeq: 2 }, 2) });
    const active = await waitFor((s) => s.sessions.some((r) => r.sessionId === "conversation"));
    assert.equal(active.sessions[0].title, "开始对话");
    const history = await (await fetch(`${base}/history?sessionId=conversation`)).json();
    assert.ok(history.turns.some((r) => r.text === "第一条真实提问"), "不能因准入延迟丢掉第一条提问");
    f.workspace(["conversation"], ["conversation"]);
    await waitFor((s) => s.sessions.length === 0);
    assert.equal((await post({ event: "assistant-message", sessionId: "conversation", assistantText: "迟到旧回答" })).result.ignored, true);
    await stop(); start();
    await waitFor((s) => s.sessions.length === 0, 5000);
    assert.equal((await post({ event: "session-added", sessionId: "conversation" })).status, 400);
    f.workspace(["conversation"]);
    await waitFor((s) => s.sessions.some((r) => r.sessionId === "conversation"));
    const restored = await (await fetch(`${base}/history?sessionId=conversation`)).json();
    assert.equal(restored.turns.some((r) => r.text === "迟到旧回答"), false);
  } finally { if (child) await stop(); f.cleanup(); }
});

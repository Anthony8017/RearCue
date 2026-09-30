// 适配器行解析测试（票 #118/#119）：node --test tools/bridge/adapters/adapters.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, renameSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { startCodexAdapter, parseCodexLine, CODEX_POLL_MS } from "./codex.mjs";
import { startClaudeAdapter, parseClaudeLine } from "./claude.mjs";

async function waitForEvent(events, predicate, timeoutMs = 2500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = events.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("adapter event timeout");
}

function utcDayDir(root) {
  const now = new Date();
  return join(
    root,
    String(now.getUTCFullYear()),
    String(now.getUTCMonth() + 1).padStart(2, "0"),
    String(now.getUTCDate()).padStart(2, "0"),
  );
}

test("codex：session_meta → sessionId/workspace", () => {
  const p = parseCodexLine(
    JSON.stringify({ type: "session_meta", payload: { session_id: "s1", cwd: "C:/repo" } }),
  );
  assert.equal(p.sessionId, "s1");
  assert.equal(p.workspace, "C:/repo");
});

test("codex：assistant message → assistantText+working；user message → userText；坏行 null", () => {
  const p = parseCodexLine(
    JSON.stringify({
      type: "response_item",
      payload: { type: "message", role: "assistant", content: [{ type: "text", text: "你好" }] },
    }),
  );
  // spec 0017：正文按「一条完整助手输出」交给桥（`assistantText`），不再拼整段尾巴。
  assert.equal(p.assistantText, "你好");
  assert.equal(p.latestReply, undefined);
  assert.equal(p.status, "working");

  // 机主提问（spec 0017 / 票 #169）：此前被整行丢弃。
  const ask = parseCodexLine(
    JSON.stringify({
      type: "response_item",
      payload: { type: "message", role: "user", content: [{ type: "input_text", text: "把背屏改成靠左" }] },
    }),
  );
  assert.equal(ask.userText, "把背屏改成靠左");
  // 空正文的 user 行不产生提问（例如只有附件的行）。
  assert.equal(
    parseCodexLine(JSON.stringify({ type: "response_item", payload: { type: "message", role: "user", content: [] } })),
    null,
  );

  assert.equal(parseCodexLine("not json"), null);
  assert.equal(parseCodexLine(JSON.stringify({ type: "world_state", payload: {} })), null);
});

test("codex：task_started→working、task_complete→idle+last_agent_message、tool_call→action", () => {
  assert.equal(parseCodexLine(JSON.stringify({ type: "event_msg", payload: { type: "task_started" } })).status, "working");
  const done = parseCodexLine(
    JSON.stringify({ type: "event_msg", payload: { type: "task_complete", last_agent_message: "完成" } }),
  );
  assert.equal(done.status, "idle");
  assert.equal(done.assistantText, "完成");
  const tool = parseCodexLine(
    JSON.stringify({ type: "response_item", payload: { type: "custom_tool_call", name: "exec", input: "npm test" } }),
  );
  assert.match(tool.currentAction, /^exec npm test/);
  assert.equal(tool.status, "working");
});

test("codex：sessions 与 archived_sessions 移动产生 ARCHIVED / unarchive，并恢复最后状态", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-archive-"));
  const root = join(temp, "sessions");
  const archivedRoot = join(temp, "archived_sessions");
  const day = utcDayDir(root);
  mkdirSync(day, { recursive: true });
  mkdirSync(archivedRoot, { recursive: true });
  const name = "rollout-2026-10-01T00-00-00-019f3104-232e-7642-82f3-5512a3050389.jsonl";
  const activeFile = join(day, name);
  const archivedFile = join(archivedRoot, name);
  writeFileSync(
    activeFile,
    [
      JSON.stringify({ type: "session_meta", payload: { session_id: "codex-mv", cwd: "C:/codex-mv" } }),
      JSON.stringify({ type: "event_msg", payload: { type: "task_started" } }),
    ].join("\n") + "\n",
    "utf8",
  );

  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), {
    root,
    archivedRoot,
    pollMs: 20,
    debounceMs: 1,
  });
  try {
    await waitForEvent(events, (e) => e.sessionId === "codex-mv" && e.status === "working");
    assert.equal(
      events.some((e) => e.kind === "membership" && e.membership === "PRESENT"),
      false,
      "首次发现活跃文件只靠活动露面，不伪造 ACTIVE 生命周期事实",
    );

    renameSync(activeFile, archivedFile);
    const archived = await waitForEvent(
      events,
      (e) => e.kind === "membership" && e.sourceSessionId === "codex-mv" && e.membership === "ABSENT",
    );
    assert.equal(archived.archiveState, "ARCHIVED");
    assert.equal(archived.reason, "archive");

    renameSync(archivedFile, activeFile);
    const restored = await waitForEvent(
      events,
      (e) => e.kind === "membership" && e.sourceSessionId === "codex-mv" && e.membership === "PRESENT",
    );
    assert.equal(restored.archiveState, "ACTIVE");
    assert.equal(restored.reason, "unarchive");
    assert.equal(restored.status, "working", "取消归档恢复归档前最后状态");
    assert.equal(restored.workspace, "C:/codex-mv");
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("codex：启动时 archived_sessions 即墓碑；文件缺失/消失不冒充归档", async () => {
  assert.ok(CODEX_POLL_MS <= 2000, "Codex 生命周期轮询必须留在 2s 同步预算内");
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-missing-"));
  const root = join(temp, "sessions");
  const archivedRoot = join(temp, "archived_sessions");
  const day = utcDayDir(root);
  mkdirSync(day, { recursive: true });
  mkdirSync(archivedRoot, { recursive: true });
  const archivedFile = join(archivedRoot, "rollout-2026-10-01T00-00-00-019f3104-232e-7642-82f3-5512a3050389.jsonl");
  writeFileSync(archivedFile, JSON.stringify({ type: "event_msg", payload: { type: "task_complete" } }) + "\n", "utf8");
  const activeFile = join(day, "rollout-2026-10-01T00-00-01-019f3104-f459-76f3-8bfa-7bf43bf86caf.jsonl");
  writeFileSync(activeFile, JSON.stringify({ type: "event_msg", payload: { type: "task_started" } }) + "\n", "utf8");

  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), {
    root,
    archivedRoot,
    pollMs: 20,
    debounceMs: 1,
  });
  try {
    const tombstone = await waitForEvent(events, (e) => e.kind === "membership" && e.membership === "ABSENT");
    assert.equal(tombstone.reason, "archive");
    assert.equal(tombstone.archiveState, "ARCHIVED");
    await waitForEvent(events, (e) => e.sessionId === "019f3104-f459-76f3-8bfa-7bf43bf86caf" && e.status === "working");
    rmSync(activeFile, { force: true });
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(
      events.some((e) => e.sourceSessionId === "019f3104-f459-76f3-8bfa-7bf43bf86caf" && e.membership === "ABSENT"),
      false,
      "文件缺失不是归档/移除事实",
    );
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("claude：assistant text/tool_use/user 提问与 tool_result 映射", () => {
  const text = parseClaudeLine(
    JSON.stringify({ cwd: "C:/p", type: "assistant", message: { content: [{ type: "text", text: "回复" }] } }),
  );
  assert.equal(text.assistantText, "回复");
  assert.equal(text.latestReply, undefined);
  assert.equal(text.status, "working");
  assert.equal(text.workspace, "C:/p");

  const tool = parseClaudeLine(
    JSON.stringify({ type: "assistant", message: { content: [{ type: "tool_use", name: "Bash", input: { command: "ls" } }] } }),
  );
  assert.match(tool.currentAction, /^Bash/);

  // 机主提问：`content` 是**纯字符串**（本机 transcript 实测形态）——spec 0010 时代因
  // `Array.isArray` 判定失败被整体丢弃。
  const ask = parseClaudeLine(
    JSON.stringify({ type: "user", message: { content: "玩家释放暗影飞痕时被抓住了" } }),
  );
  assert.equal(ask.userText, "玩家释放暗影飞痕时被抓住了");
  assert.equal(ask.status, undefined, "纯提问不带状态判定（回合是否进行中由别的事件说）");

  // 数组形态的纯文本 user 行同样算提问。
  const askBlocks = parseClaudeLine(
    JSON.stringify({ type: "user", message: { content: [{ type: "text", text: "数组形态的提问" }] } }),
  );
  assert.equal(askBlocks.userText, "数组形态的提问");

  // tool_result 行只当状态信号，不算提问。
  const result = parseClaudeLine(
    JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", content: "ok" }] } }),
  );
  assert.equal(result.status, "working");
  assert.equal(result.userText, undefined);

  assert.equal(parseClaudeLine("{broken"), null);
  assert.equal(parseClaudeLine(JSON.stringify({ type: "system", subtype: "init" })), null);
});

test("codex adapter：统一事件填 source=codex", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-codex-"));
  const day = utcDayDir(root);
  mkdirSync(day, { recursive: true });
  const file = join(day, "rollout-2026-09-29-00000000-0000-0000-0000-000000000000.jsonl");
  writeFileSync(
    file,
    [
      JSON.stringify({ type: "session_meta", payload: { session_id: "codex-src", cwd: "C:/codex" } }),
      JSON.stringify({ type: "event_msg", payload: { type: "task_started" } }),
    ].join("\n") + "\n",
    "utf8",
  );

  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), { root });
  try {
    const event = await waitForEvent(events, (e) => e.sessionId === "codex-src");
    assert.equal(event.source, "codex");
    assert.equal(event.workspace, "C:/codex");
    assert.equal(event.status, "working");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("claude adapter：统一事件填 source=claude", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-claude-"));
  const project = join(root, "project-a");
  mkdirSync(project, { recursive: true });
  const file = join(project, "claude-src.jsonl");
  writeFileSync(
    file,
    JSON.stringify({
      cwd: "C:/claude",
      type: "assistant",
      message: { content: [{ type: "text", text: "hello" }] },
    }) + "\n",
    "utf8",
  );

  const events = [];
  const adapter = startClaudeAdapter((event) => events.push(event), { root });
  try {
    const event = await waitForEvent(events, (e) => e.sessionId === "claude-src");
    assert.equal(event.source, "claude");
    assert.equal(event.workspace, "C:/claude");
    assert.equal(event.status, "working");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("codex/claude 适配器：显式 membership 行进出册；task_complete/Stop 不是归档", () => {
  const archived = parseCodexLine(JSON.stringify({
    type: "membership",
    sessionId: "m-c",
    membership: "ARCHIVED",
    generation: 2,
    revision: 2,
  }));
  assert.equal(archived.kind, "membership");
  assert.equal(archived.source, "codex");
  assert.equal(archived.membership, "ABSENT");
  assert.equal(archived.archiveState, "ARCHIVED");
  assert.equal(parseCodexLine(JSON.stringify({ type: "task_complete", payload: {} })), null);

  const absent = parseClaudeLine(JSON.stringify({
    type: "membership",
    session_id: "m-cl",
    membership: "ABSENT",
    generation: 3,
  }));
  assert.equal(absent.kind, "membership");
  assert.equal(absent.source, "claude");
  assert.equal(absent.membership, "ABSENT");
  assert.equal(absent.archiveState, "UNKNOWN");
  assert.equal(parseClaudeLine(JSON.stringify({ type: "Stop", session_id: "m-cl" })), null);
});

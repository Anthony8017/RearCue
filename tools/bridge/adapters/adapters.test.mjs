// 适配器行解析测试（票 #118/#119）：node --test tools/bridge/adapters/adapters.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, appendFileSync, renameSync, rmSync, utimesSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import {
  startCodexAdapter as startIndexedCodexAdapter,
  parseCodexLine,
  parseCodexTitleRecord,
  loadCodexTitleIndex,
  CODEX_POLL_MS,
} from "./codex.mjs";
import { startClaudeAdapter, parseClaudeLine } from "./claude.mjs";
import { parseCodexReadState } from "./codex-read-state.mjs";

// These isolated file-parser fixtures intentionally have no desktop state database.
const startCodexAdapter = (emit, options) => startIndexedCodexAdapter(emit, { threadIndexFile: null, ...options });

async function waitForEvent(events, predicate, timeoutMs = 2500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = events.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("adapter event timeout");
}

function localDayDir(root) {
  const now = new Date();
  return join(
    root,
    String(now.getFullYear()),
    String(now.getMonth() + 1).padStart(2, "0"),
    String(now.getDate()).padStart(2, "0"),
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

test("codex adapter：电脑端手动终止后转空闲，重启保持空闲，继续提问可恢复工作", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-abort-"));
  const root = join(temp, "sessions");
  mkdirSync(root);
  const file = join(root, "rollout-2026-10-04-00000000-0000-0000-0000-000000000002.jsonl");
  const line = (type, payload) => JSON.stringify({ type, payload }) + "\n";
  writeFileSync(file,
    line("session_meta", { id: "codex-abort", cwd: "C:/RearCue" }) +
    line("event_msg", { type: "task_started", turn_id: "turn-1" }) +
    line("response_item", { type: "function_call", name: "exec_command", arguments: "npm test" }),
    "utf8",
  );
  let events = [];
  const start = () => startCodexAdapter((event) => events.push(event), {
    root,
    archivedRoot: join(temp, "archived_sessions"),
    titleIndexFile: join(temp, "session_index.jsonl"),
    globalStateFile: join(temp, ".codex-global-state.json"),
    pollMs: 20,
    debounceMs: 1,
  });
  let adapter = start();
  try {
    const working = await waitForEvent(events, (e) => e.sessionId === "codex-abort" && e.status === "working" && /^exec_command/.test(e.currentAction || ""));
    assert.match(working.currentAction, /^exec_command/);
    events = [];
    // 本机 Codex Desktop 手动停止的真实 rollout 事件形状；不会另发 task_complete。
    appendFileSync(file, line("event_msg", {
      type: "turn_aborted", turn_id: "turn-1", reason: "interrupted",
    }), "utf8");
    const stopped = await waitForEvent(events, (e) => e.sessionId === "codex-abort", 1000);
    assert.equal(stopped.status, "idle", "停止后列表不应继续显示工作中");
    assert.equal(stopped.currentAction, null, "停止后清除正在执行的工具摘要");
    assert.equal(stopped.assistantText, undefined, "终止不制造完整回答");
    assert.equal(events.some((e) => e.kind === "membership"), false, "停止回合不代表归档会话");

    adapter.stop();
    events = [];
    adapter = start();
    const restored = await waitForEvent(events, (e) => e.sessionId === "codex-abort" && e.status === "idle");
    assert.equal(restored.status, "idle", "冷启动重放终止事件也必须为空闲");
    assert.equal(restored.currentAction, null);

    events = [];
    appendFileSync(file, line("event_msg", { type: "task_started", turn_id: "turn-2" }), "utf8");
    await waitForEvent(events, (e) => e.sessionId === "codex-abort" && e.status === "working");
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("codex：session_index 读取当前标题，追加改名实时替换", () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-title-index-"));
  const file = join(temp, "session_index.jsonl");
  writeFileSync(
    file,
    [
      JSON.stringify({ id: "c-title", thread_name: "旧名", updated_at: "2026-10-02T00:00:00Z" }),
      JSON.stringify({ id: "c-title", thread_name: "会话名对不上", updated_at: "2026-10-02T00:01:00Z" }),
      "{broken",
    ].join("\n") + "\n",
    "utf8",
  );
  try {
    assert.equal(parseCodexTitleRecord(JSON.stringify({ id: "x", thread_name: "  新名  " })).title, "新名");
    assert.equal(loadCodexTitleIndex(file).get("c-title").title, "会话名对不上");
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});

test("codex adapter：标题改名独立出事件，不复活已归档/未露面会话", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-title-"));
  const root = join(temp, "sessions");
  const archivedRoot = join(temp, "archived_sessions");
  const day = localDayDir(root);
  const titleIndexFile = join(temp, "session_index.jsonl");
  mkdirSync(day, { recursive: true });
  mkdirSync(archivedRoot, { recursive: true });
  const file = join(day, "rollout-2026-10-02-00000000-0000-0000-0000-000000000001.jsonl");
  writeFileSync(
    file,
    [
      JSON.stringify({ type: "session_meta", payload: { session_id: "codex-title", cwd: "C:/RearCue" } }),
      JSON.stringify({ type: "event_msg", payload: { type: "task_started" } }),
    ].join("\n") + "\n",
    "utf8",
  );
  writeFileSync(
    titleIndexFile,
    JSON.stringify({ id: "codex-title", thread_name: "初始名", updated_at: "2026-10-02T00:00:00Z" }) + "\n",
    "utf8",
  );

  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), {
    root,
    archivedRoot,
    titleIndexFile,
    pollMs: 20,
    debounceMs: 1,
  });
  try {
    const initial = await waitForEvent(events, (e) => e.sessionId === "codex-title" && e.title === "初始名");
    assert.equal(initial.source, "codex");

    appendFileSync(
      titleIndexFile,
      JSON.stringify({ id: "codex-title", thread_name: "会话名对不上", updated_at: "2026-10-02T00:01:00Z" }) + "\n",
      "utf8",
    );
    const renamed = await waitForEvent(events, (e) => e.sessionId === "codex-title" && e.title === "会话名对不上");
    assert.equal(renamed.status, "working", "改名保持最近状态，不把工作中会话打回空闲");

    appendFileSync(
      file,
      JSON.stringify({
        type: "response_item",
        payload: { type: "message", role: "assistant", content: [{ type: "text", text: "改名后的输出" }] },
      }) + "\n",
      "utf8",
    );
    const afterRename = await waitForEvent(
      events,
      (e) => e.sessionId === "codex-title" && e.assistantText === "改名后的输出",
    );
    assert.equal(afterRename.title, "会话名对不上", "后续活动不得把刚同步的标题冲掉");

    appendFileSync(
      titleIndexFile,
      JSON.stringify({ id: "never-seen", thread_name: "历史会话", updated_at: "2026-10-02T00:02:00Z" }) + "\n",
      "utf8",
    );
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(events.some((e) => e.sessionId === "never-seen"), false, "title index 不得单独复活会话");
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("codex：sessions 与 archived_sessions 移动产生 ARCHIVED / unarchive，并恢复最后状态", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-archive-"));
  const root = join(temp, "sessions");
  const archivedRoot = join(temp, "archived_sessions");
  const day = localDayDir(root);
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
  const day = localDayDir(root);
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
  assert.equal(tool.toolName, "Bash");
  assert.equal(tool.toolSummary, tool.currentAction);
  assert.equal(tool.toolDetail, '{"command":"ls"}');

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
  assert.equal(result.toolResultSummary, "工具完成");
  assert.equal(result.toolResultDetail, "ok");

  assert.equal(parseClaudeLine("{broken"), null);
  assert.equal(parseClaudeLine(JSON.stringify({ type: "system", subtype: "init" })), null);
});

test("codex adapter：统一事件填 source=codex", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-codex-"));
  const day = localDayDir(root);
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
    const event = await waitForEvent(events, (e) => e.sessionId === "codex-src" && e.status === "working");
    assert.equal(event.source, "codex");
    assert.equal(event.workspace, "C:/codex");
    assert.equal(event.status, "working");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});


test("codex adapter：冷启动补上近30分钟外的活跃会话为 idle", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-cold-idle-"));
  const root = join(temp, "sessions");
  const archivedRoot = join(temp, "archived_sessions");
  const titleIndexFile = join(temp, "session_index.jsonl");
  const day = localDayDir(root);
  mkdirSync(day, { recursive: true });
  mkdirSync(archivedRoot, { recursive: true });
  const file = join(day, "rollout-2026-10-01-00000000-0000-0000-0000-000000000009.jsonl");
  writeFileSync(
    file,
    [
      JSON.stringify({ type: "session_meta", payload: { session_id: "codex-old-idle", cwd: "C:/OldRearCue" } }),
      JSON.stringify({
        type: "response_item",
        payload: { type: "message", role: "assistant", content: [{ type: "text", text: "旧正文不得重放" }] },
      }),
      JSON.stringify({ type: "event_msg", payload: { type: "task_complete", last_agent_message: "旧完成文本" } }),
    ].join("\n") + "\n",
    "utf8",
  );
  writeFileSync(
    titleIndexFile,
    JSON.stringify({ id: "codex-old-idle", thread_name: "旧的在册会话", updated_at: "2026-10-01T00:00:00Z" }) + "\n",
    "utf8",
  );
  const old = Date.now() - 60 * 60 * 1000;
  utimesSync(file, old / 1000, old / 1000);

  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), {
    root,
    archivedRoot,
    titleIndexFile,
    pollMs: 20,
    debounceMs: 1,
  });
  try {
    const event = await waitForEvent(events, (e) => e.sessionId === "codex-old-idle");
    assert.equal(event.status, "idle");
    assert.equal(event.workspace, "C:/OldRearCue");
    assert.equal(event.title, "旧的在册会话");
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(
      events.some((e) => e.sessionId === "codex-old-idle" && (e.assistantText || e.latestReply)),
      false,
      "冷启动只补当前态，不重放旧正文",
    );
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
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
  const adapter = startClaudeAdapter((event) => events.push(event), { root, userDataRoot: join(root, "desktop"), strictDesktopRoster: false });
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

test("codex：可识别的注入上下文不当机主提问；相似普通文本保留", () => {
  const injected = [
    JSON.stringify({
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "# AGENTS.md instructions for C:/repo\n\n<INSTRUCTIONS>\nhidden\n</INSTRUCTIONS>" }],
      },
    }),
    JSON.stringify({
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: '<external_codex_apps_open_page>{"page_id":null}</external_codex_apps_open_page>' }],
      },
    }),
    JSON.stringify({
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: '<subagent_notification>{"agent_path":"a","status":"completed"}</subagent_notification>' }],
      },
    }),
  ];
  for (const line of injected) assert.equal(parseCodexLine(line), null);

  const ordinary = parseCodexLine(
    JSON.stringify({
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "<subagent_notification>这是我真的打的一句话</subagent_notification>" }],
      },
    }),
  );
  assert.equal(ordinary.userText, "<subagent_notification>这是我真的打的一句话</subagent_notification>");
});

test("codex read-state：合并多身份未读集合并兼容 legacyMigration", () => {
  const unread = parseCodexReadState(JSON.stringify({
    "electron-thread-read-state-v1": {
      unreadByIdentity: {
        one: { "local:a": ["thread-a", "shared"] },
        two: { "local:b": ["thread-b", "shared", ""] },
      },
      legacyMigration: { unreadThreadIdsByHostId: { local: ["thread-legacy"] } },
    },
  }));
  assert.deepEqual(unread, new Set(["thread-a", "thread-b", "shared", "thread-legacy"]));
  assert.deepEqual(parseCodexReadState("{broken"), new Set());
});

test("codex adapter：桌面 thread read-state 跨端回执——点开会话后绿点事实转已阅", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-codex-read-state-"));
  const root = join(temp, "sessions");
  const archivedRoot = join(temp, "archived_sessions");
  const day = localDayDir(root);
  mkdirSync(day, { recursive: true });
  mkdirSync(archivedRoot, { recursive: true });
  const file = join(day, "rollout-2026-10-03-00000000-0000-0000-0000-000000000042.jsonl");
  const globalStateFile = join(temp, ".codex-global-state.json");
  const sessionId = "codex-desktop-read";
  writeFileSync(
    file,
    [
      JSON.stringify({ type: "session_meta", payload: { session_id: sessionId, cwd: "C:/RearCue" } }),
      JSON.stringify({ type: "event_msg", payload: { type: "task_complete", last_agent_message: "完成" } }),
    ].join("\n") + "\n",
    "utf8",
  );
  const stateFor = (unreadThreadIds) => JSON.stringify({
    "electron-thread-read-state-v1": {
      version: 1,
      unreadByIdentity: {
        identity: {
          "local:host": unreadThreadIds,
        },
      },
    },
  });
  writeFileSync(globalStateFile, stateFor([sessionId]), "utf8");

  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), {
    root,
    archivedRoot,
    titleIndexFile: join(temp, "session_index.jsonl"),
    globalStateFile,
    pollMs: 20,
    debounceMs: 1,
  });
  try {
    await waitForEvent(
      events,
      (e) => e.kind === "read-state" && e.sessionId === sessionId && e.readState === "unread",
    );

    const beforeUnrelatedWrite = events.filter(
      (e) => e.kind === "read-state" && e.sessionId === sessionId && e.readState === "read",
    ).length;
    writeFileSync(globalStateFile, JSON.stringify({
      unrelatedCodexState: { touched: true },
      ...JSON.parse(stateFor([sessionId])),
    }), "utf8");
    await new Promise((resolve) => setTimeout(resolve, 80));
    assert.equal(
      events.filter((e) => e.kind === "read-state" && e.sessionId === sessionId && e.readState === "read").length,
      beforeUnrelatedWrite,
      "全局状态的无关写入不得把没阅误判成已阅",
    );

    // Codex 电脑端点开会话：官方 Electron 状态把该 thread 从未读集合移走。
    writeFileSync(globalStateFile, stateFor([]), "utf8");
    await waitForEvent(
      events,
      (e) => e.kind === "read-state" && e.sessionId === sessionId && e.readState === "read",
    );
  } finally {
    adapter.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

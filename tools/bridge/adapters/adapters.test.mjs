// 适配器行解析测试（票 #118/#119）：node --test tools/bridge/adapters/adapters.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { startCodexAdapter, parseCodexLine } from "./codex.mjs";
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

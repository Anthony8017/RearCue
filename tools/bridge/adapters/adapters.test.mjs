// 适配器行解析测试（票 #118/#119）：node --test tools/bridge/adapters/adapters.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseCodexLine } from "./codex.mjs";
import { parseClaudeLine } from "./claude.mjs";

test("codex：session_meta → sessionId/workspace", () => {
  const p = parseCodexLine(
    JSON.stringify({ type: "session_meta", payload: { session_id: "s1", cwd: "C:/repo" } }),
  );
  assert.equal(p.sessionId, "s1");
  assert.equal(p.workspace, "C:/repo");
});

test("codex：assistant message → latestReply+working；坏行 null", () => {
  const p = parseCodexLine(
    JSON.stringify({
      type: "response_item",
      payload: { type: "message", role: "assistant", content: [{ type: "text", text: "你好" }] },
    }),
  );
  assert.equal(p.latestReply, "你好");
  assert.equal(p.status, "working");
  assert.equal(parseCodexLine("not json"), null);
  assert.equal(parseCodexLine(JSON.stringify({ type: "world_state", payload: {} })), null);
});

test("codex：task_started→working、task_complete→idle+last_agent_message、tool_call→action", () => {
  assert.equal(parseCodexLine(JSON.stringify({ type: "event_msg", payload: { type: "task_started" } })).status, "working");
  const done = parseCodexLine(
    JSON.stringify({ type: "event_msg", payload: { type: "task_complete", last_agent_message: "完成" } }),
  );
  assert.equal(done.status, "idle");
  assert.equal(done.latestReply, "完成");
  const tool = parseCodexLine(
    JSON.stringify({ type: "response_item", payload: { type: "custom_tool_call", name: "exec", input: "npm test" } }),
  );
  assert.match(tool.currentAction, /^exec npm test/);
  assert.equal(tool.status, "working");
});

test("claude：assistant text/tool_use/user tool_result 映射", () => {
  const text = parseClaudeLine(
    JSON.stringify({ cwd: "C:/p", type: "assistant", message: { content: [{ type: "text", text: "回复" }] } }),
  );
  assert.equal(text.latestReply, "回复");
  assert.equal(text.status, "working");
  assert.equal(text.workspace, "C:/p");

  const tool = parseClaudeLine(
    JSON.stringify({ type: "assistant", message: { content: [{ type: "tool_use", name: "Bash", input: { command: "ls" } }] } }),
  );
  assert.match(tool.currentAction, /^Bash/);

  const result = parseClaudeLine(
    JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", content: "ok" }] } }),
  );
  assert.equal(result.status, "working");

  assert.equal(parseClaudeLine("{broken"), null);
  assert.equal(parseClaudeLine(JSON.stringify({ type: "system", subtype: "init" })), null);
});

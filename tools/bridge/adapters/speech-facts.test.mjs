import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionSpeechFacts, questionRequests } from "./speech-facts.mjs";
import { parseCodexLine, startCodexAdapter } from "./codex.mjs";
import { createDebouncedEmitter } from "./tail-util.mjs";
import { parseClaudeLine } from "./claude.mjs";
import { mapZCodeEventToPatch, mapZCodeSessionToPatch } from "./zcode.mjs";
import { hookBodiesFor } from "./dsh/dsh-events.mjs";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

test("a child rollout never enters either membership or activity, even when session_id names its parent", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-child-"));
  const events = [];
  const meta = { type: "session_meta", payload: { id: "child", session_id: "parent", source: { subagent: { thread_spawn: { parent_thread_id: "parent" } } } } };
  assert.equal(parseCodexLine(JSON.stringify(meta)), null);
  writeFileSync(join(root, "rollout-child.jsonl"), [meta, { type: "event_msg", payload: { type: "task_complete", last_agent_message: "internal result" } }].map(JSON.stringify).join("\n") + "\n");
  const adapter = startCodexAdapter((event) => events.push(event), { root, archivedRoot: join(root, "archived"), pollMs: 20, debounceMs: 0 });
  try { await new Promise((resolve) => setTimeout(resolve, 60)); assert.deepEqual(events, []); }
  finally { adapter.stop(); rmSync(root, { recursive: true, force: true }); }
});

test("stop, ordinary idle and local tool errors are silent; genuine completion uses only this turn", () => {
  const facts = new SessionSpeechFacts();
  facts.apply("main", { taskStarted: true, turnId: "old", assistantText: "old answer" }, 1);
  assert.equal(facts.apply("main", { completion: "done", turnId: "old" }, 2).voiceEvent.text, "old answer");
  facts.apply("main", { taskStarted: true, turnId: "new" }, 3);
  assert.equal(facts.apply("main", { completion: "cancelled" }, 4).voiceEvent, null);
  assert.equal(facts.apply("main", { status: "idle" }, 5).voiceEvent, null);
  assert.equal(facts.apply("main", { status: "error", errorText: "recoverable" }, 6).voiceEvent, null);
  facts.apply("main", { taskStarted: true, turnId: "empty" }, 7);
  assert.equal(facts.apply("main", { completion: "done", turnId: "empty" }, 8).voiceEvent.text, "");
  assert.equal(facts.apply("main", { completion: "done", turnId: "empty" }, 9).voiceEvent, null);
});

test("async acceptance and continuing work retain every question; reply resolves only its own request", () => {
  const facts = new SessionSpeechFacts();
  const patch = parseCodexLine(JSON.stringify({ type: "response_item", payload: { type: "function_call", name: "functions.request_user_input_async", call_id: "call", arguments: JSON.stringify({ questions: [{ title: "first", options: ["A", "B"] }, { title: "second" }] }) } }));
  const first = facts.apply("main", patch, 1);
  assert.equal(first.pendingRequests.length, 2);
  assert.match(first.pendingRequests[0].text, /A/);
  assert.equal(facts.apply("main", parseCodexLine(JSON.stringify({ type: "response_item", payload: { type: "function_call_output", call_id: "call", output: '{"accepted":true}' } })), 2).pendingRequests.length, 2);
  const reply = '<send_user_message_question_reply>' + JSON.stringify([{ questionItemId: JSON.stringify(["request_user_input_async", "call", 0]), answer: "A" }]) + '</send_user_message_question_reply>';
  assert.deepEqual(facts.apply("main", { userText: reply, status: "working" }, 3).pendingRequests.map((request) => request.id), ["call:1"]);
  assert.equal(facts.apply("main", { resolvedRequestPrefix: "call" }, 4).pendingRequests.length, 0);
});

test("historical completion retains original occurrence time and cannot become a fresh result", () => {
  const facts = new SessionSpeechFacts();
  facts.apply("main", { taskStarted: true, turnId: "turn" }, 100);
  const event = facts.apply("main", { completion: "done", sourceAt: 1, replay: true }, 999).voiceEvent;
  assert.equal(event.createdAt, 1);
  assert.equal(event.replay, true);
  assert.equal(facts.apply("main", { status: "working" }, 1000).voiceEvent, null);
});

test("debouncing preserves completion followed immediately by the next turn", () => {
  const facts = new SessionSpeechFacts();
  const emitted = [];
  const emitter = createDebouncedEmitter((patch) => emitted.push(facts.apply("main", patch, 1)), 1000);
  emitter.schedule("main", { taskStarted: true, turnId: "one" });
  emitter.schedule("main", { assistantText: "answer one" });
  emitter.schedule("main", { completion: "done", turnId: "one" });
  emitter.schedule("main", { taskStarted: true, turnId: "two" });
  emitter.schedule("main", { completion: "cancelled", turnId: "two" });
  emitter.dispose();
  assert.deepEqual(emitted.filter((entry) => entry.voiceEvent).map((entry) => entry.voiceEvent.text), ["answer one"]);
});

test("a late duplicate completion cannot consume the next turn or borrow its answer", () => {
  const facts = new SessionSpeechFacts();
  facts.apply("main", { taskStarted: true, turnId: "one", assistantText: "one" }, 1);
  facts.apply("main", { completion: "done", turnId: "one" }, 2);
  facts.apply("main", { taskStarted: true, turnId: "two", assistantText: "two" }, 3);
  assert.equal(facts.apply("main", { completion: "done", turnId: "one", assistantText: "one" }, 4).voiceEvent, null);
  assert.equal(facts.apply("main", { completion: "done", turnId: "two" }, 5).voiceEvent.text, "two");
});

test("explicit waiting is not answered by ordinary tool activity", () => {
  const facts = new SessionSpeechFacts();
  facts.apply("main", { status: "waiting", requestId: "native", summary: "approve?" }, 1);
  assert.equal(facts.apply("main", { status: "working", toolSummary: "Read" }, 2).pendingRequests.length, 1);
  assert.equal(facts.apply("main", { resolvedRequestIds: ["native"], status: "working" }, 3).pendingRequests.length, 0);
});

test("pending question UI snapshots use the same IDs and clear only questions, not approvals", () => {
  const facts = new SessionSpeechFacts();
  facts.apply("main", { inputRequests: [{ id: "permission", kind: "approval", text: "modify file" }] }, 1);
  const state = facts.apply("main", { pendingQuestions: [{ id: "call:0", title: "question", options: ["yes", "no"] }] }, 2);
  assert.equal(state.pendingRequests.length, 2);
  assert.match(state.pendingRequests[1].text, /yes/);
  assert.deepEqual(facts.apply("main", { pendingQuestions: [] }, 3).pendingRequests.map((request) => request.id), ["permission"]);
});

test("other sources exclude explicit internal identities while keeping ordinary forks", () => {
  assert.equal(parseClaudeLine(JSON.stringify({ type: "assistant", isSidechain: true, message: { content: [{ type: "text", text: "internal" }] } })), null);
  assert.equal(mapZCodeSessionToPatch({ sessionId: "child", sessionKind: "subagent_child", status: "idle" }), null);
  assert.ok(mapZCodeSessionToPatch({ sessionId: "fork", sessionKind: "fork", parentSessionId: "parent", status: "idle" }));
  assert.deepEqual(hookBodiesFor("agent/status", { agent: { id: "child", session: { header: { origin: "subagent" } } }, status: "idle" }), []);
  assert.equal(mapZCodeEventToPatch({ type: "turn.completed", sessionId: "main", payload: { resultType: "cancelled" } }).completion, "cancelled");
  assert.equal(mapZCodeEventToPatch({ type: "tool.updated", sessionId: "main", payload: { kind: "error" } }).status, "working");
});

test("an incomplete child header is retried rather than permanently cached as a main session", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-child-race-"));
  const file = join(root, "rollout-child.jsonl");
  writeFileSync(file, '{"type":"session_meta"');
  const events = [];
  const adapter = startCodexAdapter((event) => events.push(event), { root, archivedRoot: join(root, "archived"), pollMs: 10, debounceMs: 0 });
  try {
    await new Promise((resolve) => setTimeout(resolve, 25));
    writeFileSync(file, JSON.stringify({ type: "session_meta", payload: { id: "child", source: { subagent: {} } } }) + '\n' + JSON.stringify({ type: "event_msg", payload: { type: "task_started" } }) + '\n');
    await new Promise((resolve) => setTimeout(resolve, 35));
    assert.deepEqual(events, []);
  } finally { adapter.stop(); rmSync(root, { recursive: true, force: true }); }
});

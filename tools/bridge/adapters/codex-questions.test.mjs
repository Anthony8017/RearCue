import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, appendFileSync, utimesSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname, basename } from "node:path";
import { CodexQuestionTracker } from "./codex-questions.mjs";
import { startCodexAdapter, parseCodexLine } from "./codex.mjs";

const event = (type, fields = {}) => ({ type: "event_msg", payload: { type, ...fields } });
const start = (turn = "t1") => event("task_started", { turn_id: turn });
const question = (call = "call_1", turn = "t1") => event("item_completed", {
  turn_id: turn, item: { type: "AgentMessage", delivery: "async", id: call,
    questions: [{ title: "选哪个？", options: ["A", { label: "B", description: "说明" }] }, { title: "还继续吗？" }] },
});
const reply = (call = "call_1", index = 0) => ({ type: "response_item", payload: {
  type: "message", role: "user", content: [{ type: "input_text", text:
    `<send_user_message_question_reply>\n${JSON.stringify([{ questionItemId: JSON.stringify(["request_user_input_async", call, index]), question: "选哪个？", answer: "A" }])}\n</send_user_message_question_reply>` }],
} });

test("async accepted 和继续工作不清题；逐题回答关联，重复问题不复活已答题", () => {
  const tracker = new CodexQuestionTracker();
  tracker.apply(start());
  assert.equal(tracker.apply(question()), true);
  assert.deepEqual(tracker.pendingQuestions[0], { id: "call_1:0", title: "选哪个？", options: ["A", "B"] });
  tracker.apply({ type: "response_item", payload: { type: "function_call_output", call_id: "call_1", output: '{"accepted":true}' } });
  tracker.apply({ type: "response_item", payload: { type: "message", role: "user", content: [{ text: "别的追问" }] } });
  assert.equal(tracker.pendingQuestions.length, 2);
  assert.equal(tracker.status, "working");
  tracker.apply(reply());
  assert.equal(tracker.pendingQuestions.length, 1);
  tracker.apply(question());
  assert.equal(tracker.pendingQuestions.length, 1);
  tracker.apply(reply("call_1", 1));
  assert.equal(tracker.pendingQuestions.length, 0);
  assert.equal(parseCodexLine(JSON.stringify(reply())).userText, "选哪个？\nA");
});

test("回合完成/中断/更换解除；迟到的旧回合完成不清新题", () => {
  for (const type of ["task_complete", "turn_aborted"]) {
    const tracker = new CodexQuestionTracker();
    tracker.apply(start()); tracker.apply(question());
    tracker.apply(event(type, { turn_id: "t1" }));
    assert.equal(tracker.pendingQuestions.length, 0);
    tracker.apply(question());
    assert.equal(tracker.pendingQuestions.length, 0);
  }
  const tracker = new CodexQuestionTracker();
  tracker.apply(start()); tracker.apply(question());
  tracker.apply(start("t2")); tracker.apply(question("call_2", "t2"));
  tracker.apply(event("task_complete", { turn_id: "t1" }));
  assert.equal(tracker.pendingQuestions[0].id, "call_2:0");
});

test("坏字段/普通引用不造答复；工具调用未真正展示时不造题", () => {
  const tracker = new CodexQuestionTracker();
  tracker.apply(start()); tracker.apply(question());
  tracker.apply({ type: "response_item", payload: { type: "function_call", name: "request_user_input_async", arguments: "{}" } });
  tracker.apply({ type: "response_item", payload: { type: "message", role: "user", content: [{ text: "示例：" + reply().payload.content[0].text }] } });
  tracker.apply(event("item_completed", { turn_id: "t1", item: { type: "AgentMessage", delivery: "async", id: "bad", questions: [null, { options: [] }] } }));
  assert.equal(tracker.pendingQuestions.length, 2);
});

test("适配器端到端：旧文件重建待答题，重启/普通输出保留，答完发显式空集", async () => {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-questions-"));
  const root = join(temp, "sessions"); mkdirSync(root);
  const file = join(root, "rollout-2026-10-04-00000000-0000-0000-0000-000000000009.jsonl");
  const line = (record) => JSON.stringify(record) + "\n";
  writeFileSync(file, line({ type: "session_meta", payload: { id: "questions", cwd: "C:/repo" } }) + line(start()) + line(question()));
  utimesSync(file, new Date(Date.now() - 3600000), new Date(Date.now() - 3600000));
  const events = [];
  let adapter;
  const launch = () => startCodexAdapter((e) => events.push(e), { root, archivedRoot: join(temp, "archive"), titleIndexFile: join(temp, "titles"), globalStateFile: join(temp, "state"), pollMs: 20, debounceMs: 10 });
  const wait = async (predicate) => {
    const end = Date.now() + 2500;
    while (Date.now() < end) {
      if (events.some(predicate)) return;
      await new Promise((r) => setTimeout(r, 20));
    }
    assert.fail("adapter timeout");
  };
  try {
    adapter = launch();
    await wait((e) => e.pendingQuestions?.length === 2 && e.status === "working");
    adapter.stop(); events.length = 0; adapter = launch();
    await wait((e) => e.pendingQuestions?.length === 2);
    events.length = 0;
    appendFileSync(file, line({ type: "response_item", payload: { type: "function_call_output", output: '{"accepted":true}' } }));
    await wait((e) => e.pendingQuestions?.length === 2);
    events.length = 0; appendFileSync(file, line(reply()) + line(reply("call_1", 1)));
    await wait((e) => e.pendingQuestions?.length === 0 && e.userText?.includes("A"));
    events.length = 0;
    appendFileSync(file, line(question("next_call")) + line({ type: "response_item", payload: { type: "function_call_output", output: "普通工具输出" } }));
    await wait((e) => e.pendingQuestions?.length === 2 && e.contentEntries?.length === 2);
    assert.equal(events.find((e) => e.contentEntries?.length).contentEntries[0].kind, "question");
  } finally {
    adapter?.stop();
    assert.equal(dirname(resolve(temp)), resolve(tmpdir()));
    assert.ok(basename(temp).startsWith("rearcue-questions-"));
    rmSync(temp, { recursive: true, force: true });
  }
});

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync, utimesSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseCodexLine, readCodexHistory, mergeCodexHistory, startCodexAdapter } from "./codex.mjs";
import { codexEventPatch } from "./codex-control.mjs";
import { createTurnLog, applyTurnLogPatch } from "./turn-log.mjs";
import { SessionSpeechFacts } from "./speech-facts.mjs";
import { createDebouncedEmitter } from "./tail-util.mjs";

const message = (id, phase, text) => ({ type: "response_item", payload: {
  type: "message", role: "assistant", id, phase, content: [{ type: "output_text", text }],
} });
const event = (type, extras = {}) => ({ type: "event_msg", payload: { type, turn_id: "r", ...extras } });

test("six progress messages stay out of the final reply; source history preserves full final text", () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-final-"));
  const file = join(root, "rollout-sample.jsonl");
  const final = "已更新并重启桌面版。\n\n安装结果与验证边界也属于最终答复。";
  const records = [{ type: "session_meta", payload: { id: "sample", cwd: root } }, event("task_started"),
    { type: "response_item", payload: { type: "message", role: "user", id: "user", content: [{ text: "请更新" }] } },
    ...Array.from({ length: 6 }, (_, i) => message(`progress-${i}`, "commentary", `进度 ${i}`)),
    message("final", "final_answer", final + "\n\n<oai-mem-citation>source metadata</oai-mem-citation>"),
    event("task_complete", { last_agent_message: final })];
  writeFileSync(file, records.map((record, i) => JSON.stringify({ timestamp: new Date(1000 + i).toISOString(), ...record })).join("\n") + "\n");
  try {
    const turns = readCodexHistory(file);
    assert.equal(turns.filter((entry) => entry.kind === "progress").length, 6);
    assert.deepEqual(turns.filter((entry) => entry.kind === "answer").map((entry) => entry.text), [final]);
    assert.ok(turns.find((entry) => entry.kind === "answer").detail.includes("<oai-mem-citation>"));
    assert.ok(turns.every((entry) => entry.roundId === "r" && entry.roundComplete === true));
    const oldLive = turns.slice(-3).map((entry) => ({ ...entry, kind: "answer", ts: entry.ts + 10000 }));
    assert.deepEqual(mergeCodexHistory(turns, oldLive), turns);
    const pending = { entryId: "new-delta", role: "assistant", kind: "progress", text: "新内容", ts: 20000, open: true };
    assert.deepEqual(mergeCodexHistory(turns, [...oldLive, pending]), [...turns, pending]);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test("old visible file history is available without replaying completion events", () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-old-final-"));
  const file = join(root, "rollout-old.jsonl");
  writeFileSync(file, [{ type: "session_meta", payload: { id: "old", cwd: root } }, event("task_started"),
    message("p", "commentary", "正在检查"), message("f", "final_answer", "完成"),
    event("task_complete", { last_agent_message: "完成" })].map(JSON.stringify).join("\n") + "\n");
  utimesSync(file, new Date(0), new Date(0));
  const events = [];
  const adapter = startCodexAdapter((entry) => events.push(entry), {
    root, archivedRoot: join(root, "archive"), threadIndexFile: null, debounceMs: 0,
    titleIndexFile: join(root, "titles"), globalStateFile: join(root, "global"),
  });
  try {
    assert.deepEqual(adapter.historyFor("old").map((entry) => entry.kind), ["progress", "answer"]);
    assert.deepEqual(adapter.historyFor("unknown"), []);
    assert.ok(events.every((entry) => !entry.completion && !entry.assistantText));
  } finally { adapter.stop(); rmSync(root, { recursive: true, force: true }); }
});

test("phase-less delta uses item metadata and final completion reclassifies the same item", () => {
  const phases = new Map();
  const log = createTurnLog();
  log.beginRound(1, "r");
  const patch = (method, params) => codexEventPatch({ method, params: { threadId: "t", turnId: "r", ...params } }, phases);
  applyTurnLogPatch(log, patch("item/created", { item: { id: "p", type: "agentMessage", phase: "commentary" } }), 2);
  applyTurnLogPatch(log, patch("item/agentMessage/delta", { itemId: "p", delta: "检查中" }), 3);
  applyTurnLogPatch(log, patch("item/completed", { item: { id: "p", type: "agentMessage", phase: "commentary", text: "检查完成" } }), 4);
  applyTurnLogPatch(log, patch("item/agentMessage/delta", { itemId: "f", delta: "已完成" }), 5);
  assert.equal(log.latestReply(), null);
  // A tool between delta and completion must not produce a second copy of the answer.
  log.tool("验证", "原文", {}, 6);
  applyTurnLogPatch(log, patch("item/completed", { item: { id: "f", type: "agentMessage", phase: "final_answer", text: "已完成\n\n验证通过" } }), 7);
  assert.equal(log.all().filter((entry) => entry.entryId === "f").length, 1);
  assert.equal(log.all()[0].kind, "progress");
  assert.equal(log.latestReply(), "已完成\n\n验证通过");
  log.delta("迟到", 8, "f", "answer");
  assert.equal(log.latestReply(), "已完成\n\n验证通过");
});

test("unknown phase is not an answer; explicit completion text can promote it", () => {
  const log = createTurnLog();
  log.beginRound(1, "r");
  const unknown = parseCodexLine(JSON.stringify(message("legacy", undefined, "结果")));
  assert.equal(unknown.assistantKind, "progress");
  applyTurnLogPatch(log, unknown, 2);
  assert.equal(log.latestReply(), null);
  applyTurnLogPatch(log, parseCodexLine(JSON.stringify(event("task_complete", { last_agent_message: "结果" }))), 3);
  assert.equal(log.all().length, 1);
  assert.equal(log.latestReply(), "结果");
});

test("resolved tool errors fold only on successful completion; final failures remain errors", () => {
  for (const completion of ["done", "error", "cancelled"]) {
    const log = createTurnLog();
    applyTurnLogPatch(log, { taskStarted: true, turnId: "r" }, 1);
    applyTurnLogPatch(log, { errorText: "工具失败", recoverableError: true }, 2);
    applyTurnLogPatch(log, { errorText: "最终失败" }, 3);
    applyTurnLogPatch(log, { turnId: "r", completion }, 4);
    assert.equal(log.all()[0].resolved === true, completion === "done");
    assert.equal(log.all()[1].resolved, undefined);
  }
});

test("progress completion is silent and debounce preserves every distinct content item", () => {
  const facts = new SessionSpeechFacts();
  facts.apply("t", { taskStarted: true, turnId: "r" }, 1);
  facts.apply("t", { assistantText: "检查中", assistantKind: "progress" }, 2);
  assert.equal(facts.apply("t", { completion: "done", turnId: "r" }, 3).voiceEvent.text, "");
  const events = [];
  const emitter = createDebouncedEmitter((entry) => events.push(entry), 400);
  emitter.schedule("t", { assistantText: "进度1", assistantKind: "progress" });
  emitter.schedule("t", { assistantText: "进度2", assistantKind: "progress" });
  emitter.schedule("t", { toolSummary: "读取" });
  emitter.schedule("t", { toolSummary: "修改" });
  emitter.dispose();
  assert.deepEqual(events.map((entry) => entry.assistantText || entry.toolSummary), ["进度1", "进度2", "读取", "修改"]);
});

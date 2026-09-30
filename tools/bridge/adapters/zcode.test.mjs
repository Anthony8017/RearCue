// ZCode 桥适配器契约测试（票 #240）：node --test tools/bridge/adapters/zcode.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { EventEmitter } from "node:events";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  createZCodeAdapter,
  createZCodeProtocolClient,
  mapZCodeEventToPatch,
  mapZCodeModelIoRecordToPatch,
  mapZCodeSessionToPatch,
  startZCodeAdapter,
  zcodeActionResponseFor,
  zcodeInteractionFromServerRequest,
} from "./zcode.mjs";

async function waitForEvent(events, predicate, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = events.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("zcode adapter event timeout");
}

function fakeClient(sessions = []) {
  const messageHandlers = new Set();
  const responses = [];
  return {
    responses,
    async request(method) {
      if (method === "session/list") return { sessions };
      if (method === "session/subscribe") {
        throw Object.assign(new Error("Session is not active"), { code: -32004 });
      }
      throw new Error(`unexpected request ${method}`);
    },
    respond(id, result) {
      responses.push({ id, result });
      return true;
    },
    onMessage(handler) {
      messageHandlers.add(handler);
      return () => messageHandlers.delete(handler);
    },
    send(message) {
      for (const handler of [...messageHandlers]) handler(message);
    },
    stop() {},
  };
}

test("zcode app-server：换行 JSON 无 jsonrpc，server request 能回同 id result", async () => {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  const written = [];
  child.stdin = { write: (line) => written.push(line) };
  child.kill = () => child.emit("exit", 0, null);
  const client = createZCodeProtocolClient({ spawnImpl: () => child, log: () => {} });
  const messages = [];
  client.onMessage((message) => messages.push(message));
  const request = client.request("session/list", {});
  const outbound = JSON.parse(written.at(-1));
  assert.deepEqual(outbound, { id: 1, method: "session/list", params: {} });
  child.stdout.emit("data", JSON.stringify({ id: 1, result: { sessions: [] } }) + "\n");
  assert.deepEqual(await request, { sessions: [] });

  child.stdout.emit(
    "data",
    JSON.stringify({
      id: 9,
      method: "interaction/requestPermission",
      params: { requestId: "r", sessionId: "s", options: [] },
    }) + "\n",
  );
  assert.equal(messages.at(-1).method, "interaction/requestPermission");
  assert.equal(client.respond(9, { decision: "allow" }), true);
  assert.deepEqual(JSON.parse(written.at(-1)), { id: 9, result: { decision: "allow" } });
  client.stop();
});

test("zcode：session/list 单条 → source/title/workspace/status/updatedAt", () => {
  const patch = mapZCodeSessionToPatch({
    sessionId: "sess_z1",
    title: "修背屏标题",
    titleSource: "custom",
    status: "running",
    updatedAt: 1790794225222,
    workspace: { workspacePath: "C:\\work\\RearCue" },
    archivedAt: null,
  });
  assert.deepEqual(patch, {
    sessionId: "sess_z1",
    source: "zcode",
    status: "working",
    title: "修背屏标题",
    workspace: "C:\\work\\RearCue",
    updatedAt: 1790794225222,
  });
  assert.equal(mapZCodeSessionToPatch({ sessionId: "x", status: "mystery" }), null);
});

test("zcode：session/event fixture → 提问、回复增量、完成/错误/等待状态", () => {
  const started = mapZCodeEventToPatch({
    eventId: "e1",
    sessionId: "sess_z1",
    timestamp: 100,
    type: "turn.started",
    payload: { input: "把标题接进桥" },
  });
  assert.equal(started.userText, "把标题接进桥");
  assert.equal(started.status, "working");

  const delta = mapZCodeEventToPatch({
    sessionId: "sess_z1",
    timestamp: 101,
    type: "part.delta",
    payload: { messageId: "m1", partId: "p1", field: "text", delta: "正在接" },
  });
  assert.equal(delta.assistantDelta, "正在接");
  assert.equal(delta.status, "working");

  const done = mapZCodeEventToPatch({
    sessionId: "sess_z1",
    timestamp: 102,
    type: "turn.completed",
    payload: { response: "接好了", resultType: "success" },
  });
  assert.equal(done.assistantText, "接好了");
  assert.equal(done.status, "idle");

  const waiting = mapZCodeEventToPatch({
    sessionId: "sess_z1",
    timestamp: 103,
    type: "permission.requested",
    payload: {
      requestId: "req-1",
      reason: "允许删除临时文件？",
      options: [
        { optionId: "allowOnce", name: "Allow once" },
        { optionId: "deny", name: "Deny" },
      ],
    },
  });
  assert.equal(waiting.status, "waiting");
  assert.equal(waiting.summary, "允许删除临时文件？");
  assert.equal(waiting.pendingOptions, undefined, "只有同意/拒绝时交给手机内置批准按钮");
});

test("zcode：model_io fixture → 最后提问 + 回复原文/工具动作", () => {
  const patch = mapZCodeModelIoRecordToPatch({
    sessionId: "sess_z1",
    turnId: "turn_1",
    startedAt: "2026-10-01T00:00:00.000Z",
    completedAt: "2026-10-01T00:00:03.000Z",
    request: {
      messages: [
        { role: "user", content: [{ type: "text", text: "旧问题" }] },
        { role: "assistant", content: [{ type: "text", text: "旧回答" }] },
        { role: "user", content: "这次改哪里" },
      ],
    },
    response: {
      text: "先改 bridge.mjs",
      toolCalls: [{ name: "Read", input: { file_path: "tools/bridge/bridge.mjs" } }],
    },
  });
  assert.equal(patch.userText, "这次改哪里");
  assert.equal(patch.assistantText, "先改 bridge.mjs");
  assert.match(patch.currentAction, /^Read tools\/bridge\/bridge.mjs/);
  assert.equal(patch.status, "working");
});

test("zcode：model_io 的 system-reminder user 行不当机主提问", () => {
  const patch = mapZCodeModelIoRecordToPatch({
    sessionId: "sess_z1",
    turnId: "turn_2",
    completedAt: "2026-10-01T00:01:00.000Z",
    request: {
      messages: [
        { role: "user", content: [{ type: "text", text: "<system-reminder>\nTodoWrite reminder\n</system-reminder>" }] },
      ],
    },
    response: { text: "继续", toolCalls: [] },
  });
  assert.equal(patch.userText, undefined);
  assert.equal(patch.assistantText, "继续");
});

test("zcode：Remote Approval 只回 approve/reject/select，不接受自由文字", () => {
  const permission = zcodeInteractionFromServerRequest({
    id: 71,
    method: "interaction/requestPermission",
    params: {
      requestId: "req-perm",
      sessionId: "sess_z1",
      toolName: "Bash",
      reason: "允许删除？",
      options: [
        { optionId: "allowOnce", name: "Allow once", response: { decision: "allow", reason: "Approved once" } },
        { optionId: "deny", name: "Deny", response: { decision: "deny", reason: "denied" } },
      ],
    },
  });
  assert.deepEqual(zcodeActionResponseFor(permission, "approve"), {
    decision: "allow",
    reason: "Approved once",
  });
  assert.deepEqual(zcodeActionResponseFor(permission, "reject"), {
    decision: "deny",
    reason: "denied",
  });
  assert.equal(zcodeActionResponseFor(permission, "free-text", "hello"), null);

  const userInput = zcodeInteractionFromServerRequest({
    id: 72,
    method: "interaction/requestUserInput",
    params: {
      requestId: "req-select",
      sessionId: "sess_z1",
      prompt: "选一个",
      questions: [{ question: "走哪条", options: [{ label: "甲", value: "a" }, { label: "乙", value: "b" }] }],
    },
  });
  assert.deepEqual(userInput.pendingOptions, [
    { id: "a", label: "甲" },
    { id: "b", label: "乙" },
  ]);
  assert.deepEqual(zcodeActionResponseFor(userInput, "select", "b"), {
    action: "accept",
    content: { answers: { 走哪条: "b" } },
  });
});

test("zcode adapter：session/list + model-io 尾部产出统一事件；subscribe -32004 只降级", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-"));
  const client = fakeClient([
    {
      sessionId: "sess_z1",
      title: "ZCode 标题",
      status: "running",
      updatedAt: 200,
      workspace: { workspacePath: "C:\\zcode\\RearCue" },
    },
  ]);
  writeFileSync(
    join(root, "model-io-sess_z1.jsonl"),
    JSON.stringify({
      sessionId: "sess_z1",
      turnId: "turn_1",
      requestId: "model-1",
      completedAt: "2026-10-01T00:00:03.000Z",
      request: { messages: [{ role: "user", content: "看标题" }] },
      response: { text: "标题已映射", toolCalls: [] },
    }) + "\n",
    "utf8",
  );
  const events = [];
  const logs = [];
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    modelIoRoot: root,
    pollMs: 0,
    log: (line) => logs.push(line),
  });
  try {
    await adapter.scanNow();
    const roster = await waitForEvent(events, (e) => e.title === "ZCode 标题");
    assert.equal(roster.source, "zcode");
    assert.equal(roster.workspace, "C:\\zcode\\RearCue");
    assert.equal(roster.status, "working");
    const turn = await waitForEvent(events, (e) => e.userText === "看标题");
    assert.equal(turn.assistantText, "标题已映射");
    assert.equal(turn.source, "zcode");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("zcode adapter：server request → waiting/pendingOptions；手机 select 回 protocol result；waiting 不被 error 盖过", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-approval-"));
  const client = fakeClient([
    { sessionId: "sess_z1", title: "批准", status: "running", updatedAt: 1, workspace: { workspacePath: "C:\\z" } },
  ]);
  const events = [];
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    modelIoRoot: root,
    modelIoDiscovery: false,
    pollMs: 0,
  });
  try {
    await adapter.scanNow();
    client.send({
      id: 91,
      method: "interaction/requestUserInput",
      params: {
        requestId: "req-q",
        sessionId: "sess_z1",
        prompt: "选方案",
        questions: [{ question: "方案", options: [{ label: "甲", value: "a" }, { label: "乙", value: "b" }] }],
      },
    });
    const waiting = await waitForEvent(events, (e) => e.status === "waiting" && e.pendingOptions?.length === 2);
    assert.equal(waiting.summary, "选方案");
    client.send({
      method: "session/event",
      params: {
        sessionId: "sess_z1",
        eventSeq: 1,
        events: [{ eventId: "e-fail", sessionId: "sess_z1", type: "turn.failed", payload: { error: { message: "boom" } } }],
      },
    });
    const errorAttempt = await waitForEvent(events, (e) => e.summary === "boom");
    assert.equal(errorAttempt.status, "waiting", "Waiting-for-Approval 优先，error 不得盖过");
    assert.equal(adapter.resolveAction({ sessionId: "sess_z1", requestId: "req-q", action: "select", optionId: "b" }), true);
    assert.deepEqual(client.responses.at(-1), {
      id: 91,
      result: { action: "accept", content: { answers: { 方案: "b" } } },
    });
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("zcode app-server 缺失：只记日志并保留只读降级，不拖垮桥", () => {
  const logs = [];
  const zcode = startZCodeAdapter(() => {}, {
    appServerPath: join(tmpdir(), "missing-zcode.cjs"),
    modelIoDiscovery: false,
    pollMs: 0,
    restart: false,
    log: (line) => logs.push(line),
  });
  try {
    assert.ok(logs.some((line) => line.includes("app-server 不存在")));
  } finally {
    zcode.stop();
  }
});

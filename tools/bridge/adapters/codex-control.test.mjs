import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter, once } from "node:events";
import {
  approvalResponse,
  approvalSummary,
  CodexAppServerControl,
  codexEventPatch,
  resolveCodexCommand,
} from "./codex-control.mjs";

class FakeCodexChild extends EventEmitter {
  constructor() {
    super();
    this.methods = [];
    this.killed = false;
    this.stdout = new EventEmitter();
    this.stderr = new EventEmitter();
    this.stdout.setEncoding = () => {};
    this.stderr.setEncoding = () => {};
    this.stdin = {
      write: (payload) => {
        const request = JSON.parse(String(payload));
        this.methods.push(request.method);
        const result = {
          initialize: {},
          "thread/start": { thread: { id: "t1" } },
          "thread/resume": {},
          "turn/start": { turnId: request.params?.threadId === "t1" ? "turn1" : "turn2" },
        }[request.method] || {};
        queueMicrotask(() => {
          this.stdout.emit("data", `${JSON.stringify({ id: request.id, result })}\n`);
        });
      },
    };
  }

  kill() {
    this.killed = true;
    queueMicrotask(() => {
      this.emit("exit", 0, null);
      this.emit("close", 0, null);
    });
  }
}

test("批准只映射为一次性 accept/approved，拒绝不扩大权限", () => {
  assert.deepEqual(approvalResponse("item/fileChange/requestApproval", "approve"), { decision: "accept" });
  assert.deepEqual(approvalResponse("item/fileChange/requestApproval", "reject"), { decision: "decline" });
  assert.deepEqual(approvalResponse("execCommandApproval", "approve"), { decision: "approved" });
  assert.deepEqual(
    approvalResponse("execCommandApproval", "reject"),
    { decision: { denied: { rejection: "RearCue 手机端拒绝" } } },
  );
  assert.deepEqual(
    approvalResponse("item/permissions/requestApproval", "reject", { permissions: { fileSystem: { write: ["C:\\x"] } } }),
    { permissions: {}, scope: "turn" },
  );
});

test("批准摘要不回显过长命令", () => {
  const summary = approvalSummary("execCommandApproval", { command: ["powershell", "x".repeat(200)] });
  assert.ok(summary.startsWith("想执行命令：powershell"));
  assert.ok(summary.length <= 160);
  assert.equal(approvalSummary("item/fileChange/requestApproval"), "想修改文件");
});

test("app-server 事件映射保留 Codex 来源并区分回合终态", () => {
  assert.deepEqual(
    codexEventPatch({
      method: "thread/started",
      params: { thread: { id: "t1", cwd: "C:\\ws" } },
    }),
    { sessionId: "t1", source: "codex", workspace: "C:\\ws", status: "idle" },
  );
  assert.deepEqual(
    codexEventPatch({
      method: "item/completed",
      params: {
        threadId: "t1",
        item: { type: "agentMessage", text: "OK" },
      },
    }),
    {
      sessionId: "t1",
      source: "codex",
      assistantText: "OK",
      status: "working",
      completeStream: true,
    },
  );
  assert.deepEqual(
    codexEventPatch({
      method: "turn/completed",
      params: {
        threadId: "t1",
        turn: { id: "turn1", error: { message: "model unavailable" } },
      },
    }),
    {
      sessionId: "t1",
      source: "codex",
      status: "error",
      currentAction: null,
      summary: "model unavailable",
      completeStream: true,
      errorText: "model unavailable",
      turnId: "turn1",
      completion: "error",
    },
  );
});

test("Codex 可执行文件在计划任务环境中显式解析", () => {
  assert.equal(
    resolveCodexCommand({ CODEX_BIN: "custom-codex" }, () => { throw new Error("unused"); }),
    "custom-codex",
  );
  assert.equal(
    resolveCodexCommand({}, () => ({ stdout: "C:\\bin\\codex.exe\r\nC:\\other\\codex.exe" })),
    "C:\\bin\\codex.exe",
  );
});

test("failed without error detail remains failure; interrupted never means done", () => {
  const failed = codexEventPatch({ method: "turn/completed", params: { threadId: "main", turn: { id: "failed", status: "failed" } } });
  assert.equal(failed.status, "error");
  assert.equal(failed.completion, "error");
  assert.equal(codexEventPatch({ method: "turn/completed", params: { threadId: "main", turn: { id: "stop", status: "interrupted" } } }).completion, "cancelled");
});


test("Codex delta 与工具全文进入结构化补丁", () => {
  assert.deepEqual(
    codexEventPatch({
      method: "item/agentMessage/delta",
      params: { threadId: "t-stream", itemId: "m1", delta: "正在" },
    }),
    {
      sessionId: "t-stream",
      source: "codex",
      status: "working",
      assistantDelta: "正在",
      entryId: "m1",
    },
  );
  assert.deepEqual(
    codexEventPatch({
      method: "item/reasoningSummaryDelta",
      params: { threadId: "t-stream", itemId: "r1", delta: "先检查" },
    }),
    {
      sessionId: "t-stream",
      source: "codex",
      status: "working",
      thinkingDelta: "先检查",
      entryId: "r1",
    },
  );
  assert.deepEqual(
    codexEventPatch({
      method: "item/completed",
      params: {
        threadId: "t-stream",
        item: {
          id: "tool1",
          type: "commandExecution",
          name: "shell",
          command: ["powershell", "Get-ChildItem"],
          output: "file-a\nfile-b",
        },
      },
    }),
    {
      sessionId: "t-stream",
      source: "codex",
      status: "working",
      toolName: "shell",
      command: "powershell Get-ChildItem",
      toolResultSummary: "shell powershell Get-ChildItem",
      toolResultDetail: "file-a\nfile-b",
    },
  );
});

test("回合结束后释放 writer，下一轮可重新接管", async () => {
  const children = [];
  const control = new CodexAppServerControl({
    command: "codex",
    spawnProcess: () => {
      const child = new FakeCodexChild();
      children.push(child);
      return child;
    },
    requestTimeoutMs: 1_000,
  });

  const created = await control.startConversation({
    workspace: "C:\\ws",
    prompt: "first",
  });
  assert.equal(created.threadId, "t1");
  assert.equal(children[0].killed, false);

  const released = once(control, "exit");
  children[0].stdout.emit("data", `${JSON.stringify({
    method: "turn/completed",
    params: { threadId: "t1", turn: { id: created.turnId } },
  })}\n`);
  await released;
  assert.equal(children[0].killed, true);
  assert.equal(control.child, null);

  await control.sendTurn({ threadId: "t1", prompt: "second" });
  assert.deepEqual(
    children[1].methods.filter((method) => method !== "initialize"),
    ["thread/resume", "turn/start"],
  );
  await control.stop();
});

test("internal app-server threads remain hidden after their metadata; required approval belongs to the parent", async () => {
  const child = new FakeCodexChild();
  const control = new CodexAppServerControl({ command: "codex", spawnProcess: () => child });
  await control.startConversation({ workspace: "C:\\ws", prompt: "main" });
  const events = [], approvals = [];
  control.on("event", (event) => events.push(event));
  control.on("approval", (approval) => approvals.push(approval));
  const deliver = (message) => child.stdout.emit("data", JSON.stringify(message) + "\n");
  try {
    deliver({ method: "thread/started", params: { threadId: "t1", thread: { id: "internal", source: { subagent: { thread_spawn: { parent_thread_id: "t1" } } } } } });
    deliver({ method: "turn/started", params: { threadId: "internal", turn: { id: "child-turn" } } });
    deliver({ method: "turn/completed", params: { threadId: "internal", turn: { id: "child-turn" } } });
    assert.deepEqual(events, []);
    deliver({ id: 900, method: "item/fileChange/requestApproval", params: { threadId: "internal", turnId: "child-turn" } });
    assert.equal(approvals.length, 1);
    assert.equal(approvals[0].threadId, "t1");
    assert.equal(control.resolveApproval(900, "reject"), true);
  } finally { await control.stop(); }
});

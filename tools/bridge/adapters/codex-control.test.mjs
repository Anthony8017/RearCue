import test from "node:test";
import assert from "node:assert/strict";
import {
  approvalResponse,
  approvalSummary,
  codexEventPatch,
} from "./codex-control.mjs";

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
    { sessionId: "t1", source: "codex", assistantText: "OK", status: "working" },
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
    },
  );
});

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readZCodeHistory } from "./zcode-history.mjs";

test("ZCode model-io 按需重建完整历史，不受 2 MiB 尾部窗口限制", (t) => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-history-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const earlyUser = "早先的问题";
  const filler = "x".repeat(2 * 1024 * 1024);
  const lines = [
    {
      sessionId: "sess_full",
      turnId: "turn_1",
      requestId: "r1",
      startedAt: "2026-10-01T00:00:00.000Z",
      completedAt: "2026-10-01T00:00:01.000Z",
      request: { messages: [{ role: "user", content: earlyUser }, { role: "tool", content: filler }] },
      response: { text: "早先的回答" },
    },
    {
      sessionId: "sess_full",
      turnId: "turn_2",
      requestId: "r2",
      startedAt: "2026-10-01T00:01:00.000Z",
      completedAt: "2026-10-01T00:01:02.000Z",
      request: { messages: [{ role: "user", content: "后来的问题" }] },
      response: { text: "后来的回答" },
    },
    {
      sessionId: "sess_full",
      turnId: "turn_3",
      requestId: "r3",
      startedAt: "2026-10-01T00:02:00.000Z",
      completedAt: "2026-10-01T00:02:01.000Z",
      request: {
        messages: [
          { role: "user", content: "<system-reminder>\nignore\n</system-reminder>" },
          { role: "user", content: "最后一问" },
        ],
      },
      response: { text: "最后一答" },
    },
  ];
  writeFileSync(join(root, "model-io-sess_full.jsonl"), lines.map((line) => JSON.stringify(line)).join("\n") + "\npartial", "utf8");
  assert.deepEqual(
    readZCodeHistory(root, "sess_full").map((turn) => [turn.role, turn.text]),
    [
      ["user", earlyUser],
      ["assistant", "早先的回答"],
      ["user", "后来的问题"],
      ["assistant", "后来的回答"],
      ["user", "最后一问"],
      ["assistant", "最后一答"],
    ],
  );
});

test("ZCode model-io 同 turn 多次模型调用不重复插入同一提问", () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-history-turn-"));
  try {
    const records = [
      {
        sessionId: "sess_loop",
        turnId: "turn_loop",
        requestId: "r1",
        completedAt: 1,
        request: { messages: [{ role: "user", content: "做一次" }] },
        response: { text: "先查文件" },
      },
      {
        sessionId: "sess_loop",
        turnId: "turn_loop",
        requestId: "r2",
        completedAt: 2,
        request: { messages: [{ role: "user", content: "做一次" }] },
        response: { text: "已改完" },
      },
    ];
    writeFileSync(join(root, "model-io-sess_loop.jsonl"), records.map((line) => JSON.stringify(line)).join("\n"), "utf8");
    assert.deepEqual(
      readZCodeHistory(root, "sess_loop").map((turn) => [turn.role, turn.text]),
      [
        ["user", "做一次"],
        ["assistant", "先查文件"],
        ["assistant", "已改完"],
      ],
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

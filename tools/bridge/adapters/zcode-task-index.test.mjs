import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync, appendFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { createZCodeAdapter } from "./zcode.mjs";

function makeTaskDb(path) {
  const db = new DatabaseSync(path);
  db.exec(`create table tasks (
    workspace_key text not null,
    workspace_path text,
    task_id text not null,
    title text,
    task_status text,
    archived integer not null default 0,
    deleted integer not null default 0,
    updated_at integer not null,
    primary key (workspace_key, task_id)
  )`);
  const insert = db.prepare(`insert into tasks
    (workspace_key, workspace_path, task_id, title, task_status, archived, deleted, updated_at)
    values (?, ?, ?, ?, ?, ?, ?, ?)`);
  insert.run("w1", "C:/work", "sess-active", "A", "completed", 0, 0, 10);
  insert.run("w1", "C:/work", "sess-archived", "B", "completed", 1, 0, 20);
  insert.run("w1", "C:/work", "sess-duplicate", "C", "completed", 1, 0, 30);
  insert.run("w2", "C:/other", "sess-duplicate", "C", "completed", 0, 0, 31);
  db.close();
}

function setArchived(path, sessionId, archived) {
  const db = new DatabaseSync(path);
  db.prepare("update tasks set archived = ? where task_id = ?").run(archived ? 1 : 0, sessionId);
  db.close();
}

function setTaskStatus(path, sessionId, status, updatedAt = null) {
  const db = new DatabaseSync(path);
  if (updatedAt === null) {
    db.prepare("update tasks set task_status = ?, updated_at = updated_at + 1 where task_id = ?")
      .run(status, sessionId);
  } else {
    db.prepare("update tasks set task_status = ?, updated_at = ? where task_id = ?")
      .run(status, updatedAt, sessionId);
  }
  db.close();
}

const sessions = [
  { sessionId: "sess-active", title: "A", status: "idle", updatedAt: 10, workspace: { workspacePath: "C:/work" } },
  { sessionId: "sess-archived", title: "B", status: "idle", updatedAt: 20, workspace: { workspacePath: "C:/work" } },
  { sessionId: "sess-duplicate", title: "C", status: "idle", updatedAt: 31, workspace: { workspacePath: "C:/other" } },
  { sessionId: "sess-old", title: "old", status: "idle", updatedAt: 1, workspace: { workspacePath: "C:/old" } },
];

test("zcode adapter: task index archive truth filters session/list history", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-task-index-"));
  const taskIndexPath = join(root, "tasks-index.sqlite");
  makeTaskDb(taskIndexPath);
  const events = [];
  const client = {
    async request(method) {
      assert.equal(method, "session/list");
      return { sessions };
    },
  };
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    taskIndexPath,
    modelIoDiscovery: false,
    pollMs: 0,
  });
  try {
    await adapter.scanNow();
    const seen = events.filter((event) => event.kind !== "membership").map((event) => event.sessionId);
    assert.deepEqual(seen.sort(), ["sess-active", "sess-duplicate"]);
    assert.ok(!seen.includes("sess-archived"));
    assert.ok(!seen.includes("sess-old"));
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("zcode adapter: task index drives live archive and unarchive membership", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-task-index-lifecycle-"));
  const taskIndexPath = join(root, "tasks-index.sqlite");
  makeTaskDb(taskIndexPath);
  setArchived(taskIndexPath, "sess-active", true);
  setArchived(taskIndexPath, "sess-archived", false);
  const events = [];
  const client = {
    async request(method) {
      assert.equal(method, "session/list");
      return { sessions };
    },
  };
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    taskIndexPath,
    modelIoDiscovery: false,
    pollMs: 0,
  });
  try {
    await adapter.scanNow();
    assert.deepEqual(
      events.filter((event) => event.kind !== "membership").map((event) => event.sessionId).sort(),
      ["sess-archived", "sess-duplicate"],
    );

    setArchived(taskIndexPath, "sess-archived", true);
    await adapter.scanNow();
    const archived = events.find((event) =>
      event.kind === "membership" && event.sourceSessionId === "sess-archived" && event.membership === "ARCHIVED",
    );
    assert.equal(archived?.reason, "archive");

    setArchived(taskIndexPath, "sess-archived", false);
    await adapter.scanNow();
    const restored = events.filter((event) =>
      event.kind === "membership" && event.sourceSessionId === "sess-archived" && event.membership === "ACTIVE",
    );
    assert.equal(restored.length, 1);
    assert.equal(restored[0]?.reason, "unarchive");

    await adapter.scanNow();
    await adapter.scanNow();
    assert.equal(
      events.filter((event) => event.kind === "membership" && event.sourceSessionId === "sess-archived").length,
      2,
      "稳定在册不得每轮重发 unarchive",
    );
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("zcode adapter: task_status 覆盖 session/list 的持久化 idle（运行中→working）", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-task-status-"));
  const taskIndexPath = join(root, "tasks-index.sqlite");
  makeTaskDb(taskIndexPath);
  setTaskStatus(taskIndexPath, "sess-active", "running");
  const events = [];
  const client = {
    async request(method) {
      assert.equal(method, "session/list");
      return { sessions }; // session/list 恒回 idle——桌面运行态它看不见
    },
  };
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    taskIndexPath,
    modelIoDiscovery: false,
    pollMs: 0,
  });
  try {
    await adapter.scanNow();
    const active = events.find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(active?.status, "working", "task_status=running 必须顶掉 session/list 的 idle");

    setTaskStatus(taskIndexPath, "sess-active", "completed");
    events.length = 0;
    await adapter.scanNow();
    const completed = events.find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(completed?.status, "idle", "task_status=completed 收口回 idle");

    setTaskStatus(taskIndexPath, "sess-active", "weird-word");
    events.length = 0;
    await adapter.scanNow();
    const weird = events.find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.ok(!weird || weird.status === "idle", "词表外的 task_status 不造状态：不发或保底 session/list 的 idle");

    setTaskStatus(taskIndexPath, "sess-active", "running");
    events.length = 0;
    await adapter.scanNow();
    const back = events.find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(back?.status, "working", "坏值一轮后 running 仍恢复 working");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("zcode adapter: model-io 工作证据在保鲜窗内顶住 roster 的 stale idle", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-working-evidence-"));
  const taskIndexPath = join(root, "tasks-index.sqlite");
  const modelIoRoot = join(root, "rollout");
  mkdirSync(modelIoRoot);
  makeTaskDb(taskIndexPath);
  setTaskStatus(taskIndexPath, "sess-active", "completed"); // 后续回合不翻 running——持久化视角
  const modelIoFile = join(modelIoRoot, "model-io-sess-active.jsonl");
  writeFileSync(
    modelIoFile,
    `${JSON.stringify({ sessionId: "sess-active", turnId: "t1", completedAt: "2026-10-03T00:00:01Z", response: { text: "", toolCalls: [{ name: "Bash" }] } })}\n`,
  );
  const listSessions = [
    { sessionId: "sess-active", title: "A", status: "idle", updatedAt: 10, workspace: { workspacePath: "C:/work" } },
  ];
  const events = [];
  const client = {
    async request(method) {
      assert.equal(method, "session/list");
      return { sessions: listSessions };
    },
  };
  let clock = 1_000_000;
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    taskIndexPath,
    modelIoRoot,
    pollMs: 0,
    now: () => clock,
    workingGraceMs: 5_000,
  });
  try {
    await adapter.scanNow();
    const held = [...events].reverse().find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(held?.status, "working", "task_status=completed 但 model-io 正在出工具调用：必须保持 working");

    // 回合收口：纯文本记录清掉证据，roster 的 idle 正常放行。
    appendFileSync(
      modelIoFile,
      `${JSON.stringify({ sessionId: "sess-active", turnId: "t1-end", completedAt: "2026-10-03T00:00:02Z", response: { text: "done" } })}\n`,
    );
    events.length = 0;
    clock += 1_000;
    await adapter.scanNow();
    const settled = [...events].reverse().find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(settled?.status, "idle", "纯文本收口记录后必须回落 idle");

    // 新回合再来一条 toolCalls 记录 → 证据刷新；保鲜窗外无新记录则回落 idle（长工具静默/被打断）。
    appendFileSync(
      modelIoFile,
      `${JSON.stringify({ sessionId: "sess-active", turnId: "t2", completedAt: "2026-10-03T00:00:03Z", response: { text: "", toolCalls: [{ name: "Read" }] } })}\n`,
    );
    events.length = 0;
    clock += 1_000;
    await adapter.scanNow();
    const renewed = [...events].reverse().find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(renewed?.status, "working", "新回合的 toolCalls 记录重新点亮 working");

    events.length = 0;
    clock += 6_000; // 越过保鲜窗，且无新记录
    await adapter.scanNow();
    const expired = [...events].reverse().find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(expired?.status, "idle", "保鲜窗外无新证据：回落 idle，不得卡死 working");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

test("zcode adapter: 电脑端手动停止后立即清掉 model-io working 残留", async () => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-zcode-manual-stop-"));
  const taskIndexPath = join(root, "tasks-index.sqlite");
  const modelIoRoot = join(root, "rollout");
  mkdirSync(modelIoRoot);
  makeTaskDb(taskIndexPath);
  const stoppedAt = Date.parse("2026-10-03T00:00:02Z");
  setTaskStatus(taskIndexPath, "sess-active", "completed", stoppedAt);
  const modelIoFile = join(modelIoRoot, "model-io-sess-active.jsonl");
  writeFileSync(
    modelIoFile,
    `${JSON.stringify({
      sessionId: "sess-active",
      turnId: "t1",
      completedAt: "2026-10-03T00:00:01Z",
      response: { text: "", toolCalls: [{ name: "Bash" }] },
    })}\n`,
  );
  const events = [];
  const client = {
    async request(method) {
      assert.equal(method, "session/list");
      return {
        sessions: [
          { sessionId: "sess-active", title: "A", status: "idle", updatedAt: 10, workspace: { workspacePath: "C:/work" } },
        ],
      };
    },
  };
  const adapter = createZCodeAdapter((event) => events.push(event), client, {
    taskIndexPath,
    modelIoRoot,
    modelIoDiscovery: false,
    pollMs: 0,
    now: () => Date.parse("2026-10-03T00:00:03Z"),
    workingGraceMs: 300_000,
  });
  try {
    await adapter.scanNow();
    const latest = [...events].reverse().find((event) => event.sessionId === "sess-active" && event.kind !== "membership");
    assert.equal(latest?.status, "idle", "停止时间晚于 model-io 工作证据时，不得继续显示运行中");
  } finally {
    adapter.stop();
    rmSync(root, { recursive: true, force: true });
  }
});

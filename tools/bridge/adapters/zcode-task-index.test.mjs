import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
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


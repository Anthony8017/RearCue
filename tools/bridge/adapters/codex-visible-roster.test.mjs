import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, mkdirSync, writeFileSync, appendFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { startCodexAdapter } from "./codex.mjs";

const pause = (ms = 100) => new Promise((resolve) => setTimeout(resolve, ms));
async function waitFor(predicate) {
  for (let n = 0; n < 100; n++) {
    if (predicate()) return;
    await pause(10);
  }
  assert.fail("Codex visible roster did not converge");
}

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), "rearcue-visible-codex-"));
  const root = join(dir, "sessions");
  mkdirSync(root);
  const index = join(dir, "state_5.sqlite");
  const db = new DatabaseSync(index);
  db.exec("CREATE TABLE threads (id TEXT PRIMARY KEY, source TEXT, archived INTEGER, preview TEXT, cwd TEXT, rollout_path TEXT, model_provider TEXT DEFAULT 'openai', is_pinned INTEGER DEFAULT 0)");
  const line = (type, payload) => JSON.stringify({ type, payload }) + "\n";
  const files = new Map();
  const add = (id, source = "vscode", preview = "User message", archived = 0) => {
    const file = join(root, `rollout-${id}.jsonl`);
    writeFileSync(file, line("session_meta", { id, source, cwd: "C:/visible-test" }) +
      line("event_msg", { type: "task_started" }));
    files.set(id, file);
    db.prepare("INSERT INTO threads (id,source,archived,preview,cwd,rollout_path) VALUES (?, ?, ?, ?, ?, ?)")
      .run(id, source, archived, preview, "C:/visible-test", file);
    return file;
  };
  const events = [];
  const roster = new Map();
  let adapter;
  return {
    dir, root, index, db, add, events, roster, files, line,
    start() {
      adapter = startCodexAdapter((event) => {
        events.push(event);
        if (event.kind === "membership" && event.membership === "ABSENT") roster.delete(event.sessionId);
        else if (event.status) roster.set(event.sessionId, event);
      }, {
        root, archivedRoot: join(dir, "archived_sessions"), threadIndexFile: index,
        titleIndexFile: join(dir, "titles"), globalStateFile: join(dir, "global-state"),
        pollMs: 20, debounceMs: 5,
      });
    },
    close() {
      adapter?.stop();
      db.close();
      rmSync(dir, { recursive: true, force: true });
    },
  };
}

test("Codex roster matches visible interactive index rows, excluding exec, empty and orphan rollouts", async () => {
  const f = fixture();
  try {
    f.add("desktop");
    f.add("cli", "cli");
    f.add("exec-test", "exec");
    f.add("empty", "vscode", "");
    f.add("archived", "vscode", "User message", 1);
    f.add("subagent", JSON.stringify({ subagent: { other: "guardian" } }));
    writeFileSync(join(f.root, "rollout-clicktest.jsonl"), f.line("event_msg", { type: "task_started" }));
    f.start();
    await waitFor(() => f.roster.has("desktop") && f.roster.has("cli"));
    await pause();
    assert.deepEqual([...f.roster.keys()].sort(), ["cli", "desktop"]);
  } finally { f.close(); }
});

test("Codex index archive wins over stale active files; unarchive and first message restore visibility", async () => {
  const f = fixture();
  try {
    const file = f.add("desktop");
    f.add("empty", "vscode", "");
    f.start();
    await waitFor(() => f.roster.has("desktop"));
    f.db.prepare("UPDATE threads SET archived=1 WHERE id='desktop'").run();
    appendFileSync(file, f.line("event_msg", { type: "task_started" }));
    await waitFor(() => !f.roster.has("desktop"));
    await pause();
    assert.equal(f.roster.has("desktop"), false, "late activity must not revive an archived desktop thread");
    f.db.prepare("UPDATE threads SET archived=0 WHERE id='desktop'").run();
    f.db.prepare("UPDATE threads SET preview='First user message' WHERE id='empty'").run();
    await waitFor(() => f.roster.has("desktop") && f.roster.has("empty"));
    assert.deepEqual([...f.roster.keys()].sort(), ["desktop", "empty"]);
  } finally { f.close(); }
});

test("Unreadable Codex index never broadens registration to raw rollout files", async () => {
  const f = fixture();
  try {
    f.add("desktop");
    f.add("exec-test", "exec");
    f.db.exec("ALTER TABLE threads RENAME TO saved_threads");
    f.start();
    await pause();
    assert.equal(f.roster.size, 0);
    f.db.exec("ALTER TABLE saved_threads RENAME TO threads");
    await waitFor(() => f.roster.has("desktop"));
    f.db.exec("ALTER TABLE threads RENAME TO saved_threads");
    await pause();
    assert.deepEqual([...f.roster.keys()], ["desktop"], "preserve last trusted roster during transient read failure");
  } finally { f.close(); }
});

test("Codex provider filtering preserves cross-provider pins and follows unpinning and provider switching", async () => {
  const f = fixture();
  try {
    f.add("official");
    f.add("custom");
    f.add("custom-pin");
    f.db.exec("UPDATE threads SET model_provider='custom' WHERE id IN ('custom','custom-pin'); UPDATE threads SET is_pinned=1 WHERE id='custom-pin'");
    f.start();
    await waitFor(() => f.roster.has("official") && f.roster.has("custom-pin"));
    await pause();
    assert.deepEqual([...f.roster.keys()].sort(), ["custom-pin", "official"]);
    f.db.exec("UPDATE threads SET is_pinned=0 WHERE id='custom-pin'");
    await waitFor(() => !f.roster.has("custom-pin"));
    writeFileSync(join(f.dir,"config.toml"), 'model_provider = "custom"\n[model_providers.custom]\nname="Custom"\n');
    await waitFor(() => f.roster.has("custom") && f.roster.has("custom-pin") && !f.roster.has("official"));
    assert.deepEqual([...f.roster.keys()].sort(), ["custom", "custom-pin"]);
  } finally { f.close(); }
});

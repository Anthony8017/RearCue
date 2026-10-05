import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { zstdCompressSync } from "node:zlib";
import { MirrorRoster } from "./mirror-roster.mjs";
import { SessionLibrary, zstdFrameSize } from "./session-library.mjs";

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "rearcue-mirror-"));
  t.after(() => { assert.ok(resolve(root).startsWith(join(tmpdir(), "rearcue-mirror-"))); rmSync(root, { recursive: true }); });
  return root;
}
test("old candidates remain optional, bootstrapped sessions and new births join automatically", (t) => {
  const file = join(fixture(t), "roster.json");
  const roster = new MirrorRoster(file, { now: 1000, bootstrap: [{ source: "codex", sessionId: "existing" }] });
  assert.equal(roster.allows({ source: "codex", sessionId: "existing", createdAt: 1 }), true);
  assert.equal(roster.allows({ source: "codex", sessionId: "old", createdAt: 1 }), false);
  assert.equal(roster.allows({ source: "codex", sessionId: "new", createdAt: 1001 }), true);
  assert.equal(roster.allows({ source: "codex", sessionId: "unknown" }), false);
});
test("removal persists across restart, rename and subsequent activity; source keys stay separate", (t) => {
  const file = join(fixture(t), "roster.json");
  const row = { source: "codex", sessionId: "same", createdAt: 1001, available: true };
  const roster = new MirrorRoster(file, { now: 1000 });
  roster.apply([{ ...row, enabled: false }], 0, () => row);
  const rebooted = new MirrorRoster(file, { now: 2000 });
  assert.equal(rebooted.allows({ ...row, title: "renamed", createdAt: 9999 }), false);
  assert.equal(rebooted.allows({ ...row, source: "claude" }), true);
  rebooted.apply([{ ...row, enabled: true }], 1, () => row);
  assert.equal(new MirrorRoster(file).allows(row), true);
});
test("invalid/archived selections and write failure leave the whole applied roster unchanged", (t) => {
  const file = join(fixture(t), "roster.json");
  const roster = new MirrorRoster(file, { now: 1000 });
  const before = readFileSync(file, "utf8");
  assert.throws(() => roster.apply([{ source: "codex", sessionId: "archived", enabled: true }], 0,
    () => ({ source: "codex", sessionId: "archived", archived: true, available: true })));
  assert.equal(readFileSync(file, "utf8"), before);
  const save = roster.save;
  roster.save = () => { throw new Error("disk full"); };
  assert.throws(() => roster.apply([{ source: "codex", sessionId: "a", enabled: false }], 0,
    () => ({ source: "codex", sessionId: "a", available: true })), /disk full/);
  roster.save = save;
  assert.equal(roster.data.revision, 0);
  assert.equal(readFileSync(file, "utf8"), before);
  assert.throws(() => roster.apply([], 99, () => null), /刷新/);
});
test("damaged registry fails closed rather than forgetting explicit removals", (t) => {
  const file = join(fixture(t), "roster.json");
  writeFileSync(file, "invalid");
  const roster = new MirrorRoster(file);
  assert.ok(roster.error);
  assert.equal(roster.allows({ source: "codex", sessionId: "a", createdAt: Date.now() + 1 }), false);
  assert.equal(readFileSync(file, "utf8"), "invalid");
});
test("DSH concatenated frames retain over 40 full messages and exclude injected/child conversations", async (t) => {
  const home = fixture(t);
  const root = join(home, ".dsh");
  mkdirSync(join(root, "storages"), { recursive: true });
  mkdirSync(join(root, "sessions", "project", "old"), { recursive: true });
  writeFileSync(join(root, "storages", "workspace.json"), JSON.stringify({ global: { workspaceIds: ["w"], archivedSessionIds: [] },
    tables: { workspaces: { w: { path: "C:/workspace", sessionIds: ["old", "child"] } } } }));
  const header = { type: "session", version: 4, id: "old", createdAt: 1, cwd: "C:/workspace" };
  const entries = Array.from({ length: 60 }, (_, i) => ({ type: "assistant/message", seq: i, time: i,
    data: { message: { content: [{ type: "text", text: `answer${i}${"x".repeat(9000)}` }] } } }));
  entries.push({ type: "user/message", data: { content: [{ type: "text", text: "injected" }], source: { kind: "context" } } });
  entries.push({ type: "user/message", data: { content: [{ type: "text", text: "real prompt" }], source: { kind: "user" } } });
  const frames = [zstdCompressSync(Buffer.from(JSON.stringify(header) + "\n")), zstdCompressSync(Buffer.from(entries.map(JSON.stringify).join("\n") + "\n"))];
  assert.equal(zstdFrameSize(frames[0]), frames[0].length);
  writeFileSync(join(root, "sessions", "project", "old", "session.v4.jsonl.zstd"), Buffer.concat(frames));
  mkdirSync(join(root, "sessions", "project", "child"), { recursive: true });
  writeFileSync(join(root, "sessions", "project", "child", "session.v4.jsonl"), JSON.stringify({ ...header, id: "child", origin: "subagent" }) + "\n");
  const library = new SessionLibrary({ home, enabled: ["dsh"] });
  await library.refresh();
  assert.equal(library.failures.dsh, undefined);
  assert.equal(library.get("dsh", "child")?.internal, true);
  const row = library.get("dsh", "old");
  assert.ok(row);
  const turns = await library.history(row);
  assert.equal(turns.length, 61);
  assert.ok(turns[59].text.length > 9000);
  assert.equal(turns.at(-1).text, "real prompt");
});

test("Codex/Claude old saved transcripts are read completely and internal candidates stay excluded", async (t) => {
  const home = fixture(t);
  const codex = join(home, ".codex", "sessions");
  const claude = join(home, ".claude", "projects", "p");
  const desktop = join(home, "desktop");
  mkdirSync(codex, { recursive: true }); mkdirSync(claude, { recursive: true });
  const metadata = join(desktop, "claude-code-sessions", "a", "o"); mkdirSync(metadata, { recursive: true });
  const oldRoot = process.env.CLAUDE_DESKTOP_USER_DATA;
  process.env.CLAUDE_DESKTOP_USER_DATA = desktop;
  t.after(() => { if (oldRoot === undefined) delete process.env.CLAUDE_DESKTOP_USER_DATA; else process.env.CLAUDE_DESKTOP_USER_DATA = oldRoot; });
  const at = "2020-01-01T00:00:00Z";
  const codexRows = [
    { type: "session_meta", timestamp: at, payload: { id: "c", cwd: "C:/p" } },
    { type: "response_item", timestamp: at, payload: { type: "message", role: "user", content: [{ type: "input_text", text: "old prompt" }] } },
    { type: "response_item", timestamp: at, payload: { type: "message", role: "assistant", content: [{ type: "output_text", text: "old answer" }] } },
    { type: "event_msg", timestamp: at, payload: { type: "task_complete", last_agent_message: "old answer" } },
  ];
  writeFileSync(join(codex, "c.jsonl"), codexRows.map(JSON.stringify).join("\n"));
  writeFileSync(join(codex, "child.jsonl"), JSON.stringify({ type: "session_meta", payload: { id: "child", source: { subagent: {} } } }) + "\n");
  writeFileSync(join(claude, "cl.jsonl"), [
    { type: "user", sessionId: "cl", timestamp: at, message: { content: "old question" } },
    { type: "assistant", sessionId: "cl", timestamp: at, message: { content: [{ type: "text", text: "old reply" }] } },
  ].map(JSON.stringify).join("\n"));
  writeFileSync(join(claude, "child.jsonl"), JSON.stringify({ sessionId: "child", type: "user", isSidechain: true }) + "\n");
  for (const id of ["cl", "child"]) writeFileSync(join(metadata, `${id}.json`), JSON.stringify({ cliSessionId: id, isArchived: false, title: id }));
  const library = new SessionLibrary({ home, enabled: ["codex", "claude"] });
  await library.refresh();
  assert.deepEqual(library.failures, {});
  assert.deepEqual((await library.history(library.get("codex", "c"))).map((row) => row.text), ["old prompt", "old answer"]);
  assert.deepEqual((await library.history(library.get("claude", "cl"))).map((row) => row.text), ["old question", "old reply"]);
  assert.equal(library.get("codex", "child")?.internal, true);
  assert.equal(library.get("claude", "child")?.internal, true);
});

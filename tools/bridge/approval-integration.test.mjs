import { DatabaseSync } from "node:sqlite";
import { fixtureFetch as fetch } from "./fixture-http.mjs";
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

test("同一 Codex 会话的两项待批准逐项处理，第二次点击不误报 bad-request", async (t) => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-approval-http-"));
  mkdirSync(join(root, "source", "sessions"), { recursive: true });
  const db = new DatabaseSync(join(root, "source", "state_5.sqlite"));
  db.exec("CREATE TABLE threads (id TEXT, source TEXT, archived INTEGER, preview TEXT, cwd TEXT, rollout_path TEXT, model_provider TEXT, is_pinned INTEGER)");
  db.close();
  const socket = createServer();
  await new Promise(r => socket.listen(0, "127.0.0.1", r));
  const port = socket.address().port;
  await new Promise(r => socket.close(r));
  const base = `http://127.0.0.1:${port}`;
  const results = join(root, "results.jsonl");
  const stop = join(root, "stop");
  const child = spawn(process.execPath, ["--import", new URL("./fixtures/codex-approvals-loader.mjs", import.meta.url).href,
    fileURLToPath(new URL("bridge.mjs", import.meta.url)), "--no-tunnel", "--no-claude", "--no-dsh", "--no-zcode"], {
    windowsHide: true, stdio: "ignore", env: { ...process.env, BRIDGE_PORT: String(port), BRIDGE_ACCESS_TOKEN: "fixture-token",
      CODEX_BIN: "rearcue-approval-fixture", BRIDGE_CODEX_ALLOW_LEGACY: "1", BRIDGE_LOG: join(root, "bridge.log"), BRIDGE_SEQ_FILE: join(root, "seq"),
      BRIDGE_IDENTITY_FILE: join(root, "identity.json"), BRIDGE_URL_FILE: join(root, "bridge.url"),
      BRIDGE_CODEX_HOME: join(root, "source"), REARCUE_TEST_APPROVAL_RESULTS: results, REARCUE_TEST_APPROVAL_STOP: stop },
  });
  t.after(async () => {
    if (child.exitCode === null) {
      await new Promise(r => { const timer = setTimeout(() => child.kill(), 4000); child.once("exit", () => { clearTimeout(timer); r(); }); writeFileSync(stop, "planned isolated test exit"); });
    }
    assert.ok(resolve(root).startsWith(join(tmpdir(), "rearcue-approval-http-")));
    rmSync(root, { recursive: true });
  });
  const waitFor = async (read, check) => {
    for (let n = 0; n < 160; n++) {
      try { const value = await read(); if (check(value)) return value; } catch { /* startup */ }
      await new Promise(r => setTimeout(r, 25));
    }
    throw new Error("isolated approval bridge did not reach expected state");
  };
  const api = async (route, body) => (await fetch(base + route, { method: body ? "POST" : "GET", headers: {
    Authorization: "Bearer fixture-token", "Content-Type": "application/json",
  }, ...(body ? { body: JSON.stringify(body) } : {}) })).json();
  const state = async () => (await api("/snapshot")).sessions.find(row => row.sessionId === "approval-fixture");
  await waitFor(() => fetch(base + "/health"), r => r.ok);
  const created = await api("/codex/conversations", { projectId: "fixture", model: "mock", prompt: "isolated approval fixture", requestId: "create" });
  assert.equal(created.receipt, "accepted");
  await waitFor(state, s => s?.pendingRequests?.length === 2);
  const shown = async () => (await api("/events?since=0&wait=0")).events.filter(row => row.sessionId === "approval-fixture").at(-1);
  assert.match((await shown()).summary, /approval-9001/, "the displayed latest approval is the one answered first");

  assert.equal((await api("/action", { sessionId: "approval-fixture", action: "approve", requestId: "first" })).receipt, "accepted");
  assert.equal((await state()).status, "waiting", "the other approval must remain waiting");
  assert.match((await shown()).summary, /approval-9000/, "restore the summary of the remaining live approval");
  assert.equal((await api("/action", { sessionId: "approval-fixture", action: "reject", requestId: "second" })).receipt, "accepted",
    "after the first accepted approval, the second live request must still be actionable");
  assert.deepEqual((await state()).pendingRequests, []);
  assert.equal((await state()).status, "working");
  const replies = readFileSync(results, "utf8").trim().split("\n").map(line => JSON.parse(line));
  assert.deepEqual(replies.map(reply => reply.id), [9001, 9000]);
  assert.deepEqual(replies.map(reply => reply.result.decision), ["accept", { denied: { rejection: "RearCue 手机端拒绝" } }]);
});

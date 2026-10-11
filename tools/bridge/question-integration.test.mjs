import { DatabaseSync } from "node:sqlite";
import { fixtureFetch as fetch } from "./fixture-http.mjs";
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtempSync, mkdirSync, existsSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

test("phone-created Codex choices stay visible with the desktop roster enabled and obey mirror removal", async (t) => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-choice-http-"));
  mkdirSync(join(root, "source", "sessions"), { recursive: true });
  const sourceDb = new DatabaseSync(join(root, "source", "state_5.sqlite"));
  sourceDb.exec("CREATE TABLE threads (id TEXT, source TEXT, archived INTEGER, preview TEXT, cwd TEXT, rollout_path TEXT, model_provider TEXT, is_pinned INTEGER)");
  sourceDb.close();
  const socket = createServer();
  await new Promise(r => socket.listen(0, "127.0.0.1", r));
  const port = socket.address().port;
  await new Promise(r => socket.close(r));
  const base = "http://127.0.0.1:" + port;
  const resultFile = join(root, "reply.json");
  const stopFile = join(root, "stop");
  const child = spawn(process.execPath, ["--import", new URL("../../docs/poc-logs/20261010-rear-question-choice/smoke-loader.mjs", import.meta.url).href,
    fileURLToPath(new URL("bridge.mjs", import.meta.url)), "--no-tunnel", "--no-claude", "--no-dsh", "--no-zcode"], {
    windowsHide: true, stdio: "ignore", env: { ...process.env, BRIDGE_PORT: String(port), BRIDGE_ACCESS_TOKEN: "fixture-token",
      CODEX_BIN: "isolated-mock-codex", BRIDGE_LOG: join(root, "bridge.log"), BRIDGE_SEQ_FILE: join(root, "seq"),
      BRIDGE_IDENTITY_FILE: join(root, "identity.json"), BRIDGE_URL_FILE: join(root, "bridge.url"),
      BRIDGE_CODEX_HOME: join(root, "source"), RCU_SMOKE_RESULT: resultFile, RCU_SMOKE_STOP: stopFile },
  });
  t.after(async () => {
    if (child.exitCode === null) {
      await new Promise(r => { const timer = setTimeout(() => child.kill(), 4000); child.once("exit", () => { clearTimeout(timer); r(); }); writeFileSync(stopFile, "planned test exit"); });
    }
    assert.ok(resolve(root).startsWith(join(tmpdir(), "rearcue-choice-http-")));
    rmSync(root, { recursive: true });
  });
  const waitFor = async (read, check) => {
    for (let n = 0; n < 160; n++) {
      try { const value = await read(); if (check(value)) return value; } catch { /* bridge startup */ }
      await new Promise(r => setTimeout(r, 50));
    }
    throw new Error("isolated bridge did not reach the expected state");
  };
  const api = async (route, body) => (await fetch(base + route, { method: body ? "POST" : "GET", headers: { Authorization: "Bearer fixture-token", "Content-Type": "application/json" }, ...(body ? { body: JSON.stringify(body) } : {}) })).json();
  await waitFor(() => fetch(base + "/health"), r => r.ok);
  const created = await api("/codex/conversations", { projectId: "smoke", model: "mock", prompt: "隔离测试", requestId: "create" });
  assert.equal(created.receipt, "accepted");
  const threadId = created.threadId;
  const state = await waitFor(() => api("/snapshot"), s => s.sessions.some(row => row.sessionId === threadId && row.pendingQuestions?.length === 2));
  const questions = state.sessions.find(row => row.sessionId === threadId).pendingQuestions;
  assert.ok(questions.every(q => q.canAnswer));
  const connection = JSON.parse(readFileSync(join(root, "seq.manager.json"), "utf8"));
  const admin = async (route, body) => (await fetch("http://127.0.0.1:" + connection.port + route, { method: body ? "POST" : "GET", headers: { Authorization: "Bearer " + connection.token, "Content-Type": "application/json" }, ...(body ? { body: JSON.stringify(body) } : {}) })).json();
  let manager = await admin("/sessions");
  assert.equal(manager.sessions.find(s => s.sessionId === threadId)?.state, "enabled");
  const answer = { sessionId: threadId, groupId: questions[0].groupId, turnId: questions[0].turnId, requestId: "reply", answers: Object.fromEntries(questions.map(q => [q.id, [q.optionValues[0]]])) };
  const partial = { ...answer, requestId: "partial", answers: { [questions[0].id]: [questions[0].optionValues[0]] } };
  assert.equal((await api("/codex/questions/answer", partial)).receipt, "bad-request");
  assert.equal(existsSync(resultFile), false);
  const removed = await admin("/apply", { revision: manager.revision, changes: [{ source: "codex", sessionId: threadId, enabled: false }] });
  assert.equal(removed.ok, true, JSON.stringify(removed));
  assert.ok((await api("/snapshot")).sessions.every(row => row.sessionId !== threadId));
  assert.equal((await api("/codex/questions/answer", answer)).receipt, "unsupported");
  assert.equal(existsSync(resultFile), false);
  manager = await admin("/sessions");
  const restored = await admin("/apply", { revision: manager.revision, changes: [{ source: "codex", sessionId: threadId, enabled: true }] });
  assert.equal(restored.ok, true, JSON.stringify(restored));
  assert.equal((await api("/codex/questions/answer", answer)).receipt, "accepted");
  const native = JSON.parse(readFileSync(resultFile, "utf8"));
  assert.equal(native.id, 9000);
  assert.deepEqual(JSON.parse(native.result.contentItems[0].text), { answers: { q1: { answers: ["方案甲"] }, q2: { answers: ["先测试"] } } });
  await waitFor(() => api("/snapshot"), s => s.sessions.find(row => row.sessionId === threadId)?.pendingQuestions?.length === 0);
});

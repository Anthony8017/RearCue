import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:net";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { fixtureFetch as fetch } from "./fixture-http.mjs";

// Real HTTP handlers + app-server event mapping + rollout adapter + history merge.
// Only the app-server transport is faked; no model or production bridge is touched.
test("Codex prompt projection has one owner across creation, live events and history", async (t) => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-prompt-"));
  mkdirSync(join(root, ".codex", "sessions"), { recursive: true });
  const db = new DatabaseSync(join(root, ".codex", "state_5.sqlite"));
  db.exec("CREATE TABLE threads (id TEXT, source TEXT, archived INTEGER, preview TEXT, cwd TEXT, rollout_path TEXT, model_provider TEXT, is_pinned INTEGER)");
  db.close();
  const socket = createServer();
  await new Promise((resolve, reject) => { socket.once("error", reject); socket.listen(0, "127.0.0.1", resolve); });
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve));
  const base = `http://127.0.0.1:${port}`;
  const child = spawn(process.execPath, ["--import", new URL("fixtures/codex-prompt-loader.mjs", import.meta.url).href,
    fileURLToPath(new URL("bridge.mjs", import.meta.url)), "--no-tunnel", "--no-claude", "--no-dsh", "--no-zcode"], {
    windowsHide: true, stdio: ["ignore", "pipe", "pipe"], env: {
      ...process.env, BRIDGE_PORT: String(port), BRIDGE_ACCESS_TOKEN: "prompt-fixture-token",
      CODEX_HOME: join(root, ".codex"), BRIDGE_CODEX_HOME: join(root, ".codex"), RCU_PROMPT_FIXTURE: root,
      BRIDGE_CODEX_RUNTIME_FILE: join(root, "no-shared-runtime.json"),
      CODEX_BIN: "isolated-prompt-codex", BRIDGE_CODEX_ALLOW_LEGACY: "1",
      BRIDGE_LOG: join(root, "bridge.log"), BRIDGE_SEQ_FILE: join(root, "seq"),
      BRIDGE_IDENTITY_FILE: join(root, "identity.json"), BRIDGE_URL_FILE: join(root, "bridge.url"),
      RCU_TRAY_STATE: join(root, "tray.json"), BRIDGE_FEISHU_STATE_FILE: join(root, "feishu.json"),
    },
  });
  let output = "";
  child.stdout.on("data", chunk => { output = (output + chunk).slice(-8000); });
  child.stderr.on("data", chunk => { output = (output + chunk).slice(-8000); });
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = once(child, "exit");
      writeFileSync(join(root, "stop"), "planned isolated test exit");
      const timeout = setTimeout(() => child.kill(), 4000);
      try { await exited; } finally { clearTimeout(timeout); }
    }
    assert.ok(resolve(root).startsWith(join(tmpdir(), "rearcue-prompt-")), "cleanup must stay inside the fixture root");
    rmSync(root, { recursive: true, force: true });
  });
  const waitFor = async (read, check) => {
    const deadline = Date.now() + 10000;
    let lastValue;
    while (Date.now() < deadline) {
      if (child.exitCode !== null) throw new Error(`Fixture bridge exited: ${output}`);
      try { const value = await read(); lastValue = value; if (check(value)) return value; } catch { /* wait for startup/source polling */ }
      await new Promise(resolve => setTimeout(resolve, 40));
    }
    throw new Error(`Fixture bridge did not reach the expected state: ${JSON.stringify(lastValue)}\n${output}`);
  };
  const api = async (route, body) => {
    const response = await fetch(base + route, {
      method: body ? "POST" : "GET",
      headers: { Authorization: "Bearer prompt-fixture-token", "Content-Type": "application/json" },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    const value = await response.json();
    assert.equal(response.status, 200, JSON.stringify(value));
    return value;
  };
  const rows = page => page.events.findLast(row => row.sessionId === threadId && row.turns)?.turns || [];
  let commandId = 0;
  const synchronize = async turnId => {
    writeFileSync(join(root, "command.json"), JSON.stringify({ id: ++commandId, turnId }));
    const page = await waitFor(() => api("/events?since=0&wait=0"), value => rows(value).some(row => row.text === `disk:${turnId}`));
    assert.ok(rows(page).some(row => row.text === `live:${turnId}`), "live assistant output must still stream");
    return rows(page);
  };
  await waitFor(() => fetch(base + "/health"), response => response.ok);
  const prompt = "同一条提示词";
  const created = await api("/codex/conversations", {
    projectId: "fixture-project", model: "fixture-model", prompt, requestId: "create-prompt",
  });
  assert.equal(created.receipt, "accepted");
  const threadId = created.threadId;

  await t.test("new conversation: one actual submission displays one prompt, not three", async () => {
    const turns = await synchronize(created.turnId);
    assert.equal(turns.filter(row => row.role === "user" && row.text === prompt).length, 1);
    const history = await api(`/history?sessionId=${encodeURIComponent(threadId)}`);
    assert.equal(history.turns.filter(row => row.role === "user" && row.text === prompt).length, 1);
  });

  await t.test("a genuine follow-up with identical text remains a separate prompt", async () => {
    const sent = await api(`/codex/conversations/${encodeURIComponent(threadId)}/messages`, {
      prompt, requestId: "follow-up-prompt",
    });
    assert.equal(sent.receipt, "accepted");
    const turns = await synchronize(sent.turnId);
    const prompts = turns.filter(row => row.role === "user" && row.text === prompt);
    assert.equal(prompts.length, 2);
    assert.equal(new Set(prompts.map(row => row.roundId)).size, 2);
    const history = await api(`/history?sessionId=${encodeURIComponent(threadId)}`);
    assert.equal(history.turns.filter(row => row.role === "user" && row.text === prompt).length, 2);
    assert.equal(rows(await api("/events?since=0&wait=0")).filter(row => row.role === "user" && row.text === prompt).length, 2);
  });

  const requests = readFileSync(join(root, "requests.jsonl"), "utf8").trim().split("\n").map(JSON.parse);
  assert.equal(requests.filter(request => request.method === "thread/start").length, 1);
  assert.equal(requests.filter(request => request.method === "turn/start").length, 2, "the projection must not resubmit prompts");
});

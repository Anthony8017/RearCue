// Explicit app-server simulator for the prompt projection integration test only.
// It cannot launch Codex or make model requests.
import cp from "node:child_process";
import os from "node:os";
import { syncBuiltinESMExports } from "node:module";
import { EventEmitter } from "node:events";
import { DatabaseSync } from "node:sqlite";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = process.env.RCU_PROMPT_FIXTURE;
if (!root) throw new Error("Missing isolated prompt fixture directory");
const source = join(root, ".codex");
// Isolate all default source readers, including rollout tailing and the roster.
os.homedir = () => root;
const threads = new Map();
const pending = new Map();
let nextThread = 1;
let nextTurn = 1;

class FakeCodex extends EventEmitter {
  constructor() {
    super();
    this.stdout = new EventEmitter();
    this.stderr = new EventEmitter();
    this.stdout.setEncoding = this.stderr.setEncoding = () => {};
    this.stdin = { write: (text, callback) => {
      const request = JSON.parse(text);
      appendFileSync(join(root, "requests.jsonl"), JSON.stringify(request) + "\n");
      let result = {};
      if (request.method === "project/list") {
        result = { data: [{ id: "fixture-project", name: "Prompt fixture", roots: [{ path: root }] }] };
      } else if (request.method === "model/list") {
        result = { data: [{ id: "fixture-model", model: "fixture-model", isDefault: true }] };
      } else if (request.method === "thread/start") {
        const id = `prompt-fixture-${nextThread++}`;
        const file = join(source, "sessions", `rollout-${id}.jsonl`);
        writeFileSync(file, JSON.stringify({ type: "session_meta", payload: { id, cwd: root } }) + "\n");
        threads.set(id, file);
        const db = new DatabaseSync(join(source, "state_5.sqlite"));
        try {
          db.prepare("INSERT INTO threads VALUES (?, 'cli', 0, 'prompt fixture', ?, ?, 'openai', 0)").run(id, root, file);
        } finally { db.close(); }
        result = { thread: { id, cwd: root } };
      } else if (request.method === "turn/start") {
        const threadId = request.params.threadId;
        if (!threads.has(threadId)) throw new Error("Unknown fixture thread");
        const turnId = `fixture-turn-${nextTurn++}`;
        pending.set(turnId, { child: this, threadId, turnId, prompt: request.params.input[0].text });
        result = { turn: { id: turnId } };
      }
      if (request.id != null) queueMicrotask(() => this.output({ id: request.id, result }));
      callback?.();
    }};
  }

  output(message) { this.stdout.emit("data", JSON.stringify(message) + "\n"); }
  kill() { queueMicrotask(() => { this.emit("exit", 0, null); this.emit("close", 0, null); }); }
}

const realSpawn = cp.spawn;
cp.spawn = (command, args, options) => {
  if (args?.[0] !== "app-server") return realSpawn(command, args, options);
  if (command !== "isolated-prompt-codex") throw new Error("Refusing a real app-server in this fixture");
  return new FakeCodex();
};
syncBuiltinESMExports();

function publish(turnId) {
  const entry = pending.get(turnId);
  if (!entry) throw new Error(`Missing pending fixture turn: ${turnId}`);
  pending.delete(turnId);
  const { child, threadId, prompt } = entry;
  const params = { threadId, turnId };
  child.output({ method: "turn/started", params: { ...params, turn: { id: turnId } } });
  child.output({ method: "item/completed", params: { ...params, item: {
    type: "userMessage", id: `app-user-${turnId}`, content: [{ type: "text", text: prompt }],
  } } });
  child.output({ method: "item/agentMessage/delta", params: {
    ...params, itemId: `live-${turnId}`, delta: `live:${turnId}`, phase: "commentary",
  } });

  // The on-disk user item has no app-server item ID, as in real Codex rollouts.
  const records = [
    { type: "event_msg", payload: { type: "task_started", turn_id: turnId } },
    { type: "response_item", payload: { type: "message", role: "user", content: [{ type: "input_text", text: prompt }] } },
    { type: "response_item", payload: { type: "message", role: "assistant", phase: "final_answer",
      id: `disk-${turnId}`, content: [{ type: "output_text", text: `disk:${turnId}` }] } },
    { type: "event_msg", payload: { type: "task_complete", turn_id: turnId, last_agent_message: `disk:${turnId}` } },
  ];
  const now = Date.now();
  appendFileSync(threads.get(threadId), records.map((record, i) => JSON.stringify({
    timestamp: new Date(now + i).toISOString(), ...record,
  })).join("\n") + "\n");
  child.output({ method: "turn/completed", params: { ...params, turn: { id: turnId, status: "completed" } } });
}

let lastCommand = 0;
const timer = setInterval(() => {
  if (existsSync(join(root, "stop"))) {
    clearInterval(timer);
    writeFileSync(process.env.BRIDGE_LOG + ".stopflag", "planned isolated test exit");
    process.emit("SIGINT");
    return;
  }
  let command;
  try { command = JSON.parse(readFileSync(join(root, "command.json"), "utf8")); } catch { return; }
  if (command.id <= lastCommand) return;
  lastCommand = command.id;
  publish(command.turnId);
}, 20);

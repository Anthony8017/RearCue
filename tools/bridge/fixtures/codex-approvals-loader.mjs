// Protocol-only simulator for approval-integration.test.mjs. Never starts a real agent.
import cp from "node:child_process";
import { syncBuiltinESMExports } from "node:module";
import { EventEmitter } from "node:events";
import { appendFileSync, existsSync } from "node:fs";

const realSpawn = cp.spawn;
class ApprovalCodex extends EventEmitter {
  constructor() {
    super();
    this.stdout = new EventEmitter();
    this.stderr = new EventEmitter();
    this.stdout.setEncoding = this.stderr.setEncoding = () => {};
    this.stdin = { write: (text, callback) => {
      const request = JSON.parse(text);
      if (request.result) {
        appendFileSync(process.env.REARCUE_TEST_APPROVAL_RESULTS, JSON.stringify(request) + "\n");
        callback?.();
        return;
      }
      const result = {
        initialize: {},
        "project/list": { data: [{ id: "fixture", title: "approval fixture", roots: [{ path: process.cwd() }] }] },
        "model/list": { data: [{ id: "mock", model: "mock", isDefault: true }] },
        "thread/start": { thread: { id: "approval-fixture" } },
        "turn/start": { turn: { id: "turn-fixture" } },
      }[request.method] || {};
      queueMicrotask(() => {
        this.output({ id: request.id, result });
        if (request.method === "turn/start") setTimeout(() => {
          this.output({ method: "turn/started", params: { threadId: "approval-fixture", turn: { id: "turn-fixture" } } });
          for (const id of [9000, 9001]) {
            this.output({ id, method: "item/commandExecution/requestApproval", params: {
              threadId: "approval-fixture", turnId: "turn-fixture", itemId: `command-${id}`, command: ["echo", `approval-${id}`],
            } });
          }
        }, 50);
      });
    } };
  }
  output(message) { this.stdout.emit("data", JSON.stringify(message) + "\n"); }
  kill() { queueMicrotask(() => { this.emit("exit", 0, null); this.emit("close", 0, null); }); }
}
cp.spawn = (command, args, options) => command === "rearcue-approval-fixture" && args?.[0] === "app-server"
  ? new ApprovalCodex() : realSpawn(command, args, options);
syncBuiltinESMExports();
const timer = setInterval(() => {
  if (existsSync(process.env.REARCUE_TEST_APPROVAL_STOP)) {
    clearInterval(timer);
    process.emit("SIGINT");
  }
}, 25);

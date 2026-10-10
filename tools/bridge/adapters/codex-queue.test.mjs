import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter, once } from "node:events";
import { CodexAppServerControl } from "./codex-control.mjs";

class QueueChild extends EventEmitter {
  constructor({ consume = true, busy = () => false, failRead = false, delayedReceipt = false } = {}) {
    super();
    this.methods = [];
    this.killed = false;
    this.reads = 0;
    this.item = null;
    this.consumedId = null;
    this.stdout = new EventEmitter(); this.stdout.setEncoding = () => {};
    this.stderr = new EventEmitter(); this.stderr.setEncoding = () => {};
    this.stdin = { write: (line) => {
      const req = JSON.parse(line);
      this.methods.push(req.method);
      let result = {};
      let error;
      if (req.method === "thread/resume") error = { message: "thread-store conflict: thread t1 already has an active writer" };
      if (req.method === "thread/queue/add") {
        this.item = { id: "queued-1", clientUserMessageId: req.params.clientUserMessageId, input: req.params.input };
        result = { queuedSubmission: this.item };
        this.emit("queued");
      }
      if (req.method === "thread/read") {
        if (failRead) error = { message: "thread read unavailable" };
        this.reads++;
        if (delayedReceipt && this.item && !this.pendingClientId) {
          this.pendingClientId = this.item.clientUserMessageId; this.item = null;
        }
        if (delayedReceipt && this.pendingClientId && this.reads > 3) this.consumedId = this.pendingClientId;
        if (!delayedReceipt && consume && this.item && !busy() && this.reads > 1) {
          this.consumedId = this.item.clientUserMessageId; this.item = null;
        }
        result = { thread: { turns: [{ id: "desktop-turn", status: "interrupted", items: [
          { type: "userMessage", id: "old", clientId: "other-request", content: [{ type: "text", text: "same prompt" }] },
          ...(this.consumedId ? [{ type: "userMessage", id: "message", clientId: this.consumedId, content: [] }] : []),
        ] }] } };
      }
      if (req.method === "thread/queue/list") result = { data: this.item ? [this.item] : [], nextCursor: null };
      if (req.method === "thread/queue/delete") { result = { deleted: Boolean(this.item) }; this.item = null; }
      queueMicrotask(() => this.stdout.emit("data", JSON.stringify({ id: req.id, ...(error ? { error } : { result }) }) + "\n"));
    } };
  }
  kill() { this.killed = true; queueMicrotask(() => this.emit("exit", 0, null)); }
}

function control(child, options = {}) {
  return new CodexAppServerControl({ command: "fixture-codex", spawnProcess: () => child, queuePollMs: 1, queueDeliveryTimeoutMs: 25, ...options });
}

test("idle desktop writer gets one message through its own queue; accepted requires matching clientId", async () => {
  const child = new QueueChild();
  const client = control(child);
  const result = await client.sendTurn({ threadId: "t1", prompt: "same prompt", requestId: "phone-1" });
  assert.equal(result.turnId, "desktop-turn");
  assert.equal(result.managedBy, "desktop");
  assert.equal(child.consumedId, "phone-1");
  assert.equal(child.methods.filter(x => x === "thread/queue/add").length, 1);
  assert.ok(!child.methods.includes("turn/start"));
  assert.ok(child.reads > 1, "another request with identical text is not our receipt");
  await client.stop();
});

test("unconsumed message times out with a definite failure only after deletion", async () => {
  const child = new QueueChild({ consume: false });
  const client = control(child);
  await assert.rejects(client.sendTurn({ threadId: "t1", prompt: "prompt", requestId: "phone-2" }), /已撤回/);
  assert.ok(child.methods.includes("thread/queue/delete"));
  assert.equal(child.item, null);
  await client.stop();
});

test("explicit model is not silently ignored by desktop queue fallback", async () => {
  const child = new QueueChild();
  const client = control(child);
  await assert.rejects(client.sendTurn({ threadId: "t1", prompt: "prompt", model: "selected-model" }), /自动/);
  assert.ok(!child.methods.includes("thread/queue/add"));
  await client.stop();
});

test("working thread never leaves a message queued for later", async () => {
  const child = new QueueChild();
  const client = control(child, { threadBusy: () => true });
  await assert.rejects(client.sendTurn({ threadId: "t1", prompt: "prompt" }), /正在回答/);
  assert.ok(!child.methods.includes("thread/queue/add"));
  await client.stop();
});

test("closing the controller cancels pending delivery without restarting a child", async () => {
  const child = new QueueChild({ consume: false });
  let spawns = 0;
  const client = control(child, { spawnProcess: () => { spawns++; return child; }, queueDeliveryTimeoutMs: 1000 });
  const added = once(child, "queued");
  const outcome = client.sendTurn({ threadId: "t1", prompt: "prompt", requestId: "phone-close" }).catch(error => error);
  await added;
  await client.stop();
  assert.match((await outcome).message, /已撤回/);
  assert.equal(child.item, null);
  assert.equal(spawns, 1);
});

test("failed history reads still cancel unconsumed delivery", async () => {
  const child = new QueueChild({ consume: false, failRead: true });
  const client = control(child);
  await assert.rejects(client.sendTurn({ threadId: "t1", prompt: "prompt", requestId: "phone-read-error" }), /已撤回/);
  assert.ok(child.methods.includes("thread/queue/delete"));
  assert.equal(child.item, null);
  await client.stop();
});

test("consumed delivery may start working before its clientId is persisted; wait for the receipt", async () => {
  const child = new QueueChild({ delayedReceipt: true });
  const client = control(child, { threadBusy: () => Boolean(child.pendingClientId), queueDeliveryTimeoutMs: 100 });
  const result = await client.sendTurn({ threadId: "t1", prompt: "prompt", requestId: "phone-late-receipt" });
  assert.equal(result.managedBy, "desktop");
  assert.equal(child.consumedId, "phone-late-receipt");
  assert.ok(child.methods.includes("thread/queue/list"));
  await client.stop();
});

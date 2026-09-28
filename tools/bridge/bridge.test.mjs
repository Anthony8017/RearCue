// PC 桥行为测试（票 #116）：node --test tools/bridge/bridge.test.mjs
// 起真服务器（随机端口）断言统一会话事件契约：inject→长轮询投递、游标语义、非法输入 400。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PORT = 18787;
const BASE = `http://127.0.0.1:${PORT}`;
let child;

async function waitForHealth(timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${BASE}/health`);
      if (r.ok) return;
    } catch {
      /* 还没起来 */
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("bridge 起动超时");
}

before(async () => {
  child = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-tunnel"], {
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, BRIDGE_PORT: String(PORT) },
  });
  await waitForHealth();
});

after(() => {
  child?.kill();
});

test("health 存活", async () => {
  const r = await fetch(`${BASE}/health`);
  assert.equal(r.status, 200);
});

test("inject → since 游标投递（增量语义）", async () => {
  const post = await fetch(`${BASE}/inject`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      sessionId: "t1",
      status: "working",
      workspace: "C:/t",
      currentAction: "act",
      latestReply: "hello",
    }),
  });
  assert.equal(post.status, 200);
  const { id } = await post.json();
  assert.ok(id >= 1);

  const r = await fetch(`${BASE}/events?since=0`);
  const page = await r.json();
  assert.ok(page.events.some((e) => e.sessionId === "t1" && e.id === id));
  assert.ok(page.cursor >= id);

  // 已消费游标之后无新事件：wait=0 立即空页（不等长轮询持有）
  const r2 = await fetch(`${BASE}/events?since=${page.cursor}&wait=0`);
  const page2 = await r2.json();
  assert.equal(page2.events.length, 0);
  assert.equal(page2.cursor, page.cursor);
});

test("非法事件 400（未知 status / 缺 sessionId / 坏 JSON）", async () => {
  const bad1 = await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "x", status: "mystery" }),
  });
  assert.equal(bad1.status, 400);
  const bad2 = await fetch(`${BASE}/inject`, { method: "POST", body: JSON.stringify({ status: "idle" }) });
  assert.equal(bad2.status, 400);
  const bad3 = await fetch(`${BASE}/inject`, { method: "POST", body: "not json" });
  assert.equal(bad3.status, 400);
});

test("长轮询：新事件立即唤醒持有中的请求", async () => {
  // 先取当前游标，挂起持有请求（无新事件），随后注入——断言在 HOLD 内被唤醒并带回新事件。
  const head = await (await fetch(`${BASE}/events?since=0`)).json();
  const poll = fetch(`${BASE}/events?since=${head.cursor}`);
  await new Promise((r) => setTimeout(r, 150));
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "t2", status: "idle" }),
  });
  const r = await poll;
  const page = await r.json();
  assert.ok(page.events.some((e) => e.sessionId === "t2"));
});

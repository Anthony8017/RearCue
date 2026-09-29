// PC 桥行为测试（票 #116）：node --test tools/bridge/bridge.test.mjs
// 起真服务器（随机端口）断言统一会话事件契约：inject→长轮询投递、游标语义、非法输入 400。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PORT = 18799; // 独立端口：不与生产桥（18787）串扰
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
  child = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-tunnel", "--no-codex", "--no-claude"], {
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      BRIDGE_PORT: String(PORT),
      BRIDGE_SEQ_FILE: join(HERE, "bridge.test.seq"),
    },
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

test("snapshot：空表可读，坏请求不崩桥", async () => {
  const r = await fetch(`${BASE}/snapshot`);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { sessions: [] });

  const odd = await fetch(`${BASE}/snapshot?since=not-a-number`);
  assert.equal(odd.status, 200);
  const badMethod = await fetch(`${BASE}/snapshot`, { method: "POST" });
  assert.equal(badMethod.status, 404);

  const after = await fetch(`${BASE}/health`);
  assert.equal(after.status, 200);
});

test("inject → since 游标投递（增量语义）", async () => {
  const post = await fetch(`${BASE}/inject`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      sessionId: "t1",
      source: "codex",
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
  const event = page.events.find((e) => e.sessionId === "t1" && e.id === id);
  assert.ok(event);
  assert.equal(event.source, "codex");
  assert.ok(page.cursor >= id);

  // 已消费游标之后无新事件：wait=0 立即空页（不等长轮询持有）
  const r2 = await fetch(`${BASE}/events?since=${page.cursor}&wait=0`);
  const page2 = await r2.json();
  assert.equal(page2.events.length, 0);
  assert.equal(page2.cursor, page.cursor);
});

test("inject/旧事件缺 source：兼容为 null", async () => {
  const post = await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "t-no-source", status: "idle" }),
  });
  assert.equal(post.status, 200);
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const event = page.events.find((e) => e.sessionId === "t-no-source");
  assert.ok(event);
  assert.equal(event.source, null);
});

test("snapshot：在册键集 + 最小字段，重复更新不重复", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "snap-1", source: "claude", workspace: "C:/snap", status: "working" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: "snap-1",
      source: "claude",
      workspace: "C:/snap",
      status: "idle",
      latestReply: "snapshot must not leak reply",
    }),
  });

  const page = await (await fetch(`${BASE}/snapshot`)).json();
  const rows = page.sessions.filter((s) => s.sessionId === "snap-1");
  assert.equal(rows.length, 1);
  assert.deepEqual(Object.keys(rows[0]).sort(), ["sessionId", "source", "status", "updatedAt", "workspace"]);
  assert.equal(rows[0].source, "claude");
  assert.equal(rows[0].workspace, "C:/snap");
  assert.equal(rows[0].status, "idle");
  assert.equal(typeof rows[0].updatedAt, "number");
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

// ---- hooks 映射与端点（票 #118/#119） ----

test("hooks/claude：Stop → idle；正文不覆盖滚动尾巴（含则丢弃、缺则追加）", async () => {
  // 先建会话基线（适配器事件），再发部分补丁（Stop 只带状态与 last_assistant_message）
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "h1", status: "working", workspace: "C:/w", latestReply: "上一轮正文" }),
  });
  // 情形一：尾巴未含该文（适配器尚未扫到）→ 追加，不覆盖丢历史。
  let r = await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({
      hook_event_name: "Stop",
      session_id: "h1",
      cwd: "C:/w",
      last_assistant_message: "最终回复",
    }),
  });
  assert.equal(r.status, 200);
  let page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  let last = [...page.events].reverse().find((e) => e.sessionId === "h1");
  assert.equal(last.status, "idle");
  assert.equal(last.source, "claude");
  assert.ok(last.latestReply.includes("上一轮正文"));
  assert.ok(last.latestReply.includes("最终回复"));
  assert.equal(last.workspace, "C:/w");

  // 情形二：尾巴已含该文（transcript 先落盘、hook 后到）→ 丢弃补丁字段，尾巴原样保留。
  const tail = last.latestReply;
  r = await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({ hook_event_name: "Stop", session_id: "h1", last_assistant_message: "最终回复" }),
  });
  assert.equal(r.status, 200);
  page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  last = [...page.events].reverse().find((e) => e.sessionId === "h1");
  assert.equal(last.latestReply, tail); // 不重复追加
  assert.equal(last.status, "idle");
});

test("hooks/claude：Notification → waiting（回填保正文）", async () => {
  const r = await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({ hook_event_name: "Notification", session_id: "h1" }),
  });
  assert.equal(r.status, 200);
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const last = [...page.events].reverse().find((e) => e.sessionId === "h1");
  assert.equal(last.status, "waiting");
  assert.ok(last.latestReply.includes("上一轮正文")); // 部分补丁不丢正文
  assert.ok(last.latestReply.includes("最终回复"));
});

test("hooks/codex：turn-complete → idle；approval → waiting；未知载荷 202", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "c1", status: "working", workspace: "C:/c", latestReply: "codex 正文" }),
  });
  const done = await fetch(`${BASE}/hooks/codex`, {
    method: "POST",
    body: JSON.stringify({ type: "agent-turn-complete", session_id: "c1", "turn-id": "t1" }),
  });
  assert.equal(done.status, 200);
  let page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  let last = [...page.events].reverse().find((e) => e.sessionId === "c1");
  assert.equal(last.status, "idle");
  assert.equal(last.source, "codex");

  // 无 sessionId 且不知最近 codex 会话（适配器未挂载）：202 空操作不猜会话。
  const orphan = await fetch(`${BASE}/hooks/codex`, {
    method: "POST",
    body: JSON.stringify({ type: "agent-turn-complete", "turn-id": "t0" }),
  });
  assert.equal(orphan.status, 202);

  const wait = await fetch(`${BASE}/hooks/codex`, {
    method: "POST",
    body: JSON.stringify({ type: "approval-required", session_id: "c1" }),
  });
  assert.equal(wait.status, 200);
  page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  last = [...page.events].reverse().find((e) => e.sessionId === "c1");
  assert.equal(last.status, "waiting");
  assert.equal(last.latestReply, "codex 正文");

  const junk = await fetch(`${BASE}/hooks/codex`, { method: "POST", body: JSON.stringify({ type: "who-knows" }) });
  assert.equal(junk.status, 202);
});

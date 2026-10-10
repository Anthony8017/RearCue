// PC 桥行为测试（票 #116）：node --test tools/bridge/bridge.test.mjs
// 起真服务器（随机端口）断言统一会话事件契约：inject→长轮询投递、游标语义、非法输入 400。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "node:net";
import { capabilitiesFor } from "./capabilities.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
async function unusedPort() {
  const socket = createServer();
  await new Promise((resolve, reject) => { socket.once("error", reject); socket.listen(0, "127.0.0.1", resolve); });
  const port = socket.address().port;
  await new Promise((resolve) => socket.close(resolve));
  return port;
}
const PORT = await unusedPort(); // isolate parallel worktrees and the production bridge
const BASE = `http://127.0.0.1:${PORT}`;
const TRAY_STATE = join(HERE, "bridge.test.tray-state.json"); // 托盘状态隔离文件（同时是写入门的测试注入缝）
const IDENTITY_FILE = join(HERE, "bridge.test.identity.json"); // 会话身份表隔离文件（issue #306）
const ACCESS_TOKEN = "test-bridge-token";
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
  rmSync(TRAY_STATE, { force: true }); // 上一轮残留会把「状态文件已写」误判成通过
  rmSync(IDENTITY_FILE, { force: true }); // 身份表同理：残留会让「重启恢复」用例假通过
  child = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode"], {
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      BRIDGE_PORT: String(PORT),
      BRIDGE_SEQ_FILE: join(HERE, "bridge.test.seq"),
      BRIDGE_IDENTITY_FILE: IDENTITY_FILE,
      RCU_TRAY_STATE: TRAY_STATE,
      BRIDGE_ACCESS_TOKEN: ACCESS_TOKEN,
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
  // HEAD 也认（票 #171）：隧道探活用 HEAD，Cloudflare 对不支持的方法回 404——
  // 只认 GET 时探活恒 404、托盘图标永远停在琥珀黄（实测踩过）。
  const head = await fetch(`${BASE}/health`, { method: "HEAD" });
  assert.equal(head.status, 200);
});

test("snapshot：空表可读，坏请求不崩桥", async () => {
  const r = await fetch(`${BASE}/snapshot`);
  assert.equal(r.status, 200);
  // 契约（票 #172 → #174/#176 实测声明）：快照附来源能力表——claude 带 PreToolUse 本地批准
  // 通道故声明 approve；dsh 的 approve **随插件活性打折**（本测试先于任何 /hooks/dsh 接触，
  // 插件未露面 ⇒ 只 waiting）；codex 恒不声明（缺省保守，批准入口不开）。
  const snapshot = await r.json();
  assert.equal(typeof snapshot.cursor, "number");
  delete snapshot.cursor;
  assert.deepEqual(snapshot, {
    sessions: [],
    capabilities: {
      codex: ["waiting", "approve"],
      claude: ["waiting", "approve"],
      dsh: ["waiting"],
      zcode: ["waiting", "approve"],
    },
  });

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
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${ACCESS_TOKEN}` },
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

test("title 可选字段端到端保留：/events 与 /snapshot 都原样下发", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "z-title", source: "zcode", status: "working", title: "ZCode 标题索引" }),
  });
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const ev = page.events.find((e) => e.sessionId === "z-title");
  assert.equal(ev.title, "ZCode 标题索引");
  const snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(snap.sessions.find((e) => e.sessionId === "z-title")?.title, "ZCode 标题索引");
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
  assert.deepEqual(Object.keys(rows[0]).sort(), ["id", "pendingQuestions", "pendingRequests", "readState", "sessionId", "source", "status", "title", "updatedAt", "workspace"]);
  assert.equal(rows[0].source, "claude");
  assert.equal(rows[0].workspace, "C:/snap");
  assert.equal(rows[0].status, "idle");
  assert.equal(typeof rows[0].updatedAt, "number");
});

test("待答问题：快照与事件同源，普通 working 不抹题，显式空集解除，题目不当回答", async () => {
  const questions = [{ id: "call-q:0", title: "选谁？", options: ["A", "B"] }];
  await inject({ sessionId: "pending-question", source: "codex", status: "working", pendingQuestions: questions,
    contentEntries: [{ kind: "question", entryId: "call-q:0", text: "选谁？\n- A\n- B" }] });
  await inject({ sessionId: "pending-question", status: "working", currentAction: "仍在工作" });
  const snapshot = await (await fetch(`${BASE}/snapshot`)).json();
  const row = snapshot.sessions.find((s) => s.sessionId === "pending-question");
  assert.deepEqual(row.pendingQuestions, questions);
  assert.equal(row.status, "working");
  const history = await (await fetch(`${BASE}/history?sessionId=pending-question`)).json();
  assert.equal(history.turns[0].kind, "question");
  await inject({ sessionId: "pending-question", status: "working", pendingQuestions: [] });
  const cleared = await (await fetch(`${BASE}/snapshot`)).json();
  assert.deepEqual(cleared.sessions.find((s) => s.sessionId === "pending-question").pendingQuestions, []);
});

test("会话已阅：完整新回答→没阅，打开正文回执→已阅且跨事件共享", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "read-state-1", source: "codex", status: "working" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: "read-state-1",
      source: "codex",
      status: "idle",
      latestReply: "完整回答",
    }),
  });

  let page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  let latest = page.events.filter((e) => e.sessionId === "read-state-1").at(-1);
  assert.equal(latest.status, "idle");
  assert.equal(latest.readState, "unread");

  const read = await fetch(`${BASE}/read`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId: "read-state-1", source: "codex", readState: "read" }),
  });
  assert.equal(read.status, 200);
  assert.deepEqual(await read.json(), {
    ok: true,
    receipt: "accepted",
    sessionId: "read-state-1",
    readState: "read",
  });

  page = await (await fetch(`${BASE}/events?since=${latest.id}&wait=0`)).json();
  latest = page.events.filter((e) => e.sessionId === "read-state-1").at(-1);
  assert.equal(latest.kind, "read-state");
  assert.equal(latest.readState, "read");

  const snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(snap.sessions.find((e) => e.sessionId === "read-state-1")?.readState, "read");

  // 已阅后没有新回答的状态往返保持已阅，不制造第二条没阅。
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "read-state-1", source: "codex", status: "working" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "read-state-1", source: "codex", status: "idle" }),
  });
  page = await (await fetch(`${BASE}/events?since=${latest.id}&wait=0`)).json();
  latest = page.events.filter((e) => e.sessionId === "read-state-1").at(-1);
  assert.equal(latest.status, "idle");
  assert.equal(latest.readState, "read");
});

test("会话已阅：来源 read-state hook 与 has_unread_turn=false 都映射为已阅", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "source-read-1", source: "codex", status: "working" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "source-read-1", source: "codex", status: "idle", latestReply: "来源回答" }),
  });
  await fetch(`${BASE}/hooks/codex`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type: "read", session_id: "source-read-1", has_unread_turn: false }),
  });

  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const latest = page.events.filter((e) => e.sessionId === "source-read-1").at(-1);
  assert.equal(latest.kind, "read-state");
  assert.equal(latest.readState, "read");
});
test("会话已阅：未知会话不凭回执造在册事实", async () => {
  const read = await fetch(`${BASE}/read`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId: "not-registered", readState: "read" }),
  });
  assert.equal(read.status, 404);
  assert.deepEqual(await read.json(), { ok: false, receipt: "unknown-session" });
});
test("会话已阅：功能启用前的历史回答不制造旧会话没阅", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: "historical-read",
      source: "claude",
      status: "idle",
      latestReply: "历史回答",
      updatedAt: 1,
    }),
  });
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const latest = page.events.filter((e) => e.sessionId === "historical-read").at(-1);
  assert.equal(latest.readState, "read");
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

test("hooks/claude：Stop → idle；正文不覆盖问答流（含则去重、缺则追加）", async () => {
  // 先建会话基线（适配器事件），再发部分补丁（Stop 只带状态与 last_assistant_message）。
  // spec 0017 / 票 #169 起：正文不再拼成一根尾巴字符串，而是进**问答流** `turns`；
  // 「不覆盖丢历史」的等价事实 = 上一轮正文仍在流里。
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "h1", status: "working", workspace: "C:/w", latestReply: "上一轮正文" }),
  });
  // 情形一：流里未含该文（适配器尚未扫到）→ 追加成新的一条，不覆盖丢历史。
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
  assert.deepEqual(
    last.turns.map((t) => t.text),
    ["上一轮正文", "最终回复"],
    "问答流要留住上一轮、并把新回复接在后面",
  );
  assert.equal(last.latestReply, "最终回复", "旧字段取末尾助手输出");
  assert.equal(last.workspace, "C:/w");

  // 情形二：流里已含该文（transcript 先落盘、hook 后到）→ 去重不再追加一条。
  const turns = last.turns.length;
  r = await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({ hook_event_name: "Stop", session_id: "h1", last_assistant_message: "最终回复" }),
  });
  assert.equal(r.status, 200);
  page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  last = [...page.events].reverse().find((e) => e.sessionId === "h1");
  assert.equal(last.turns.length, turns, "同文复读不得在流里留第二条");
  assert.equal(last.latestReply, "最终回复");
  assert.equal(last.status, "idle");
});

test("hooks/claude：Notification → waiting（回填保问答流）", async () => {
  const r = await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({ hook_event_name: "Notification", session_id: "h1" }),
  });
  assert.equal(r.status, 200);
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const last = [...page.events].reverse().find((e) => e.sessionId === "h1");
  assert.equal(last.status, "waiting");
  // 部分补丁不丢正文：问答流原样带回。
  assert.deepEqual(last.turns.map((t) => t.text), ["上一轮正文", "最终回复"]);
  assert.equal(last.latestReply, "最终回复");
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

// ---- 问答流契约（spec 0017 / 票 #169） ----

test("问答流：提问与回答同流、按时间顺序", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q1", status: "working", userText: "帮我把背屏文字靠左对齐" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q1", status: "working", assistantText: "好，先改版心。" }),
  });
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const last = [...page.events].reverse().find((e) => e.sessionId === "q1");
  assert.deepEqual(
    last.turns.map((t) => [t.role, t.text]),
    [
      ["user", "帮我把背屏文字靠左对齐"],
      ["assistant", "好，先改版心。"],
    ],
  );
  // 内部补丁字段不上线。
  assert.equal(last.userText, undefined);
  assert.equal(last.assistantText, undefined);
});

test("问答流：逐字增量攒进同一条开放条", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q2", status: "working", assistantDelta: "正在读" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q2", status: "working", assistantDelta: "文件…" }),
  });
  let page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  let last = [...page.events].reverse().find((e) => e.sessionId === "q2");
  assert.equal(last.turns.length, 1, "增量追加同一条，不新开");
  assert.equal(last.turns[0].text, "正在读文件…");
  assert.equal(last.turns[0].open, true, "未收口的条目标成 open");

  // transcript 落盘后给完整文本：以完整文本收口，同一段不出现两次。
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q2", status: "idle", assistantText: "正在读文件…读完了。" }),
  });
  page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  last = [...page.events].reverse().find((e) => e.sessionId === "q2");
  assert.equal(last.turns.length, 1);
  assert.equal(last.turns[0].text, "正在读文件…读完了。");
  assert.equal(last.turns[0].open, undefined, "收口后不再是开放条");
});

test("全信息问答流：思考、工具摘要与原文详情同事件保留", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: "full-info-1",
      source: "codex",
      status: "working",
      contentEntries: [
        { kind: "thinking", text: "先确认边界", entryId: "f1" },
        {
          kind: "tool",
          text: "执行 Get-ChildItem",
          detail: "file-a\nfile-b",
          entryId: "f2",
          toolName: "shell",
          command: "Get-ChildItem",
        },
      ],
    }),
  });
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const last = [...page.events].reverse().find((e) => e.sessionId === "full-info-1");
  assert.deepEqual(last.turns.map((t) => [t.kind, t.text]), [
    ["thinking", "先确认边界"],
    ["tool", "执行 Get-ChildItem"],
  ]);
  assert.equal(last.turns[1].detail, "file-a\nfile-b");
  assert.equal(last.thinkingText, undefined);
  assert.equal(last.toolSummary, undefined);
});

test("问答流：只有提问时旧字段 latestReply 清空（不留上一轮的回答）", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q3", status: "working", assistantText: "上一轮的回答" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q3", status: "working", userText: "新一轮的提问" }),
  });
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const last = [...page.events].reverse().find((e) => e.sessionId === "q3");
  assert.equal(last.latestReply, "上一轮的回答", "旧字段取末尾**助手**输出，跳过提问");
  assert.deepEqual(last.turns.map((t) => t.role), ["assistant", "user"]);
});

test("问答流：会话之间互不串流", async () => {
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q4a", status: "working", userText: "A 会话的提问" }),
  });
  await fetch(`${BASE}/inject`, {
    method: "POST",
    body: JSON.stringify({ sessionId: "q4b", status: "working", userText: "B 会话的提问" }),
  });
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const a = [...page.events].reverse().find((e) => e.sessionId === "q4a");
  const b = [...page.events].reverse().find((e) => e.sessionId === "q4b");
  assert.deepEqual(a.turns.map((t) => t.text), ["A 会话的提问"]);
  assert.deepEqual(b.turns.map((t) => t.text), ["B 会话的提问"]);
});

test("hooks/claude MessageDisplay：中间批当增量攒，Stop 的完整正文收口同一条", async () => {
  // 中间批（回合进行中，按新完成的整行分批）。
  await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({
      hook_event_name: "MessageDisplay",
      session_id: "m1",
      message_id: "msg_1",
      index: 0,
      final: false,
      delta: "第一行\n",
    }),
  });
  await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({
      hook_event_name: "MessageDisplay",
      session_id: "m1",
      message_id: "msg_1",
      index: 1,
      final: true,
      delta: "第二行\n",
    }),
  });
  let page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  let last = [...page.events].reverse().find((e) => e.sessionId === "m1");
  assert.equal(last.source, "claude");
  assert.equal(last.status, "working");
  assert.equal(last.turns.length, 1, "同一段的多个批次攒成一条");
  assert.equal(last.turns[0].text, "第一行\n第二行\n");
  assert.equal(last.turns[0].open, true);

  // 最后一次 flush 的 delta 可能为空（正文以换行收尾）：不产生事件，不新开空条。
  const empty = await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({ hook_event_name: "MessageDisplay", session_id: "m1", index: 2, final: true, delta: "" }),
  });
  assert.equal(empty.status, 202);
  page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  last = [...page.events].reverse().find((e) => e.sessionId === "m1");
  assert.equal(last.turns.length, 1);

  // 回合结束的 Stop 带完整正文：以完整文本收口同一条，不出现重复段。
  await fetch(`${BASE}/hooks/claude`, {
    method: "POST",
    body: JSON.stringify({
      hook_event_name: "Stop",
      session_id: "m1",
      last_assistant_message: "第一行\n第二行\n第三行",
    }),
  });
  page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  last = [...page.events].reverse().find((e) => e.sessionId === "m1");
  assert.equal(last.status, "idle");
  assert.equal(last.turns.length, 1);
  assert.equal(last.turns[0].text, "第一行\n第二行\n第三行");
  assert.equal(last.turns[0].open, undefined);
});

// ---- DSH 只读插件入口（ADR 0010 / spec 0018-1，票 #171） ----

async function dshPost(body) {
  return fetch(`${BASE}/hooks/dsh`, { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) });
}

async function lastDshEvent(sessionId) {
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  return [...page.events].reverse().find((e) => e.sessionId === sessionId);
}

test("hooks/dsh：session-added 注册在册（source=dsh），状态词表归一", async () => {
  const r = await dshPost({ event: "session-added", sessionId: "d1", workspace: "C:/w/dsh-repo" });
  assert.equal(r.status, 200);
  let last = await lastDshEvent("d1");
  assert.equal(last.source, "dsh");
  assert.equal(last.status, "idle");
  assert.equal(last.workspace, "C:/w/dsh-repo");

  const snap = await (await fetch(`${BASE}/snapshot`)).json();
  const entry = snap.sessions.find((s) => s.sessionId === "d1");
  assert.ok(entry, "注册即入在册快照");
  assert.equal(entry.source, "dsh");

  // 状态归一（bridge 侧 normalizeDshStatus）：running→working、needs_input→waiting、done→idle。
  for (const [word, want] of [["running", "working"], ["needs_input", "waiting"], ["done", "idle"]]) {
    assert.equal((await dshPost({ event: "session-status", sessionId: `d1-${word}`, status: word })).status, 200);
    last = await lastDshEvent(`d1-${word}`);
    assert.equal(last.status, want, word);
  }
  const unknown = await dshPost({ event: "session-status", sessionId: "d1", status: "dancing" });
  assert.equal(unknown.status, 202, "未知状态词跳过");
});

test("hooks/dsh：重报不抹活动状态、稀疏标题补丁进 title（issue #306）", async () => {
  // ① session/created：带 header.cwd 的第一条 session-added。
  assert.equal((await dshPost({ event: "session-added", sessionId: "d4", workspace: "C:/w/dsh-naming" })).status, 200);
  // ② agent/created：紧跟的第二条（不带 cwd）——此前按稀疏事件整体替换，workspace 被抹成 null。
  assert.equal((await dshPost({ event: "session-added", sessionId: "d4" })).status, 200);
  let snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(
    snap.sessions.find((s) => s.sessionId === "d4").workspace,
    "C:/w/dsh-naming",
    "在册重报不清目录名",
  );

  // ③ session/title：稀疏补丁（只带标题、无 status）此前被 400 拒收；现按最新态回填状态。
  await dshPost({ event: "user-message", sessionId: "d4", userText: "跑一轮" });
  const titled = await dshPost({ event: "session-summary", sessionId: "d4", summary: "DSH背屏未命名会话问题" });
  assert.equal(titled.status, 200, "稀疏标题补丁必须被接受");
  const last = await lastDshEvent("d4");
  assert.equal(last.title, "DSH背屏未命名会话问题", "真标题进 wire title（手机主行链只认它）");
  assert.equal(last.summary, "DSH背屏未命名会话问题", "摘要留位照旧（#173/#174）");
  assert.equal(last.status, "working", "状态按该会话最新态回填");
  assert.equal(last.workspace, "C:/w/dsh-naming");
  assert.deepEqual(last.turns.map((t) => [t.role, t.text]), [["user", "跑一轮"]], "会话窗口不丢");
  snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(snap.sessions.find((s) => s.sessionId === "d4").title, "DSH背屏未命名会话问题");

  // 未知会话的稀疏补丁照旧拒收：缺状态的非法输入不入环。
  assert.equal((await dshPost({ event: "session-summary", sessionId: "d-unknown", summary: "无主标题" })).status, 400);
});

test("会话身份跨桥重启保留：新进程只见到一条状态事件也能拿回名字（issue #306）", async () => {
  // DSH 的标题只在开场发一次、目录名只在 session/created 带一次；桥重启后若不落盘身份，
  // 这两个字段就永久丢失（背屏退回「未命名会话」）。这里用第二个进程实例模拟重启。
  await dshPost({ event: "session-added", sessionId: "d5", workspace: "C:/w/dsh-restart" });
  await dshPost({ event: "session-summary", sessionId: "d5", summary: "桥重启也要记住的名字" });
  const table = JSON.parse(readFileSync(IDENTITY_FILE, "utf8"));
  assert.deepEqual(table.d5, { workspace: "C:/w/dsh-restart", title: "桥重启也要记住的名字" });

  const restartedPort = await unusedPort();
  const restartedBase = `http://127.0.0.1:${restartedPort}`;
  const restarted = spawn(
    process.execPath,
    [join(HERE, "bridge.mjs"), "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode"],
    {
      stdio: ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        BRIDGE_PORT: String(restartedPort),
        BRIDGE_SEQ_FILE: join(HERE, "bridge.test.seq.restart"),
        BRIDGE_IDENTITY_FILE: IDENTITY_FILE,
        RCU_TRAY_STATE: join(HERE, "bridge.test.tray-state.restart.json"),
        BRIDGE_ACCESS_TOKEN: ACCESS_TOKEN,
      },
    },
  );
  try {
    const deadline = Date.now() + 8000;
    for (;;) {
      try {
        if ((await fetch(`${restartedBase}/health`)).ok) break;
      } catch {
        /* 还没起来 */
      }
      if (Date.now() > deadline) throw new Error("重启实例起动超时");
      await new Promise((r) => setTimeout(r, 100));
    }
    const posted = await fetch(`${restartedBase}/hooks/dsh`, {
      method: "POST",
      body: JSON.stringify({ event: "session-status", sessionId: "d5", status: "working" }),
    });
    assert.equal(posted.status, 200);
    const page = await (await fetch(`${restartedBase}/events?since=0&wait=0`)).json();
    const last = [...page.events].reverse().find((e) => e.sessionId === "d5");
    assert.equal(last.workspace, "C:/w/dsh-restart", "目录名跨重启恢复");
    assert.equal(last.title, "桥重启也要记住的名字", "真标题跨重启恢复");

    // 出册即忘：身份不留在表里给已归档会话复活用。
    await dshPost({ event: "session-removed", sessionId: "d5" });
    assert.equal("d5" in JSON.parse(readFileSync(IDENTITY_FILE, "utf8")), false);
  } finally {
    restarted.kill();
  }
});

test("hooks/dsh：问答流提问/增量/整段回答同流，summary 留位", async () => {
  await dshPost({ event: "user-message", sessionId: "d2", userText: "帮我跑测试" });
  await dshPost({ event: "assistant-delta", sessionId: "d2", assistantDelta: "开始跑\n" });
  await dshPost({ event: "assistant-delta", sessionId: "d2", assistantDelta: "跑完了\n" });
  let last = await lastDshEvent("d2");
  assert.equal(last.status, "working");
  assert.deepEqual(last.turns.map((t) => [t.role, t.text]), [
    ["user", "帮我跑测试"],
    ["assistant", "开始跑\n跑完了\n"],
  ]);

  await dshPost({ event: "session-activity", sessionId: "d2", currentAction: "edit src/App.kt", summary: "想修改 xx 文件" });
  last = await lastDshEvent("d2");
  assert.equal(last.currentAction, "edit src/App.kt");
  assert.equal(last.summary, "想修改 xx 文件", "摘要字段留位（#173/#174 用）");

  // 摘要随会话粘住（回填链与 currentAction 同语义）：后续无摘要的事件不清掉上一条摘要，
  // 等确认的上下文不会闪没（#173/#174 消费；补丁层缺省不造空键）。
  await dshPost({ event: "session-status", sessionId: "d2", status: "idle" });
  last = await lastDshEvent("d2");
  assert.equal(last.status, "idle");
  assert.equal(last.summary, "想修改 xx 文件");
});

test("hooks/dsh：approval-request/question-request → waiting（只归一状态）", async () => {
  for (const event of ["approval-request", "question-request"]) {
    const r = await dshPost({ event, sessionId: "d2", summary: "等你拍板" });
    assert.equal(r.status, 200, event);
    const last = await lastDshEvent("d2");
    assert.equal(last.status, "waiting", event);
    assert.equal(last.summary, "等你拍板");
    assert.equal(last.source, "dsh");
  }
});

test("hooks/dsh：等待摘要退化用提问正文；快照带来源能力表（票 #172）", async () => {
  // 没发 summary 的提问：用提问正文当一句话摘要（供 #173 提醒摘要消费）。
  const r = await dshPost({ event: "question-request", sessionId: "d2", question: "选哪个方案" });
  assert.equal(r.status, 200);
  let last = await lastDshEvent("d2");
  assert.equal(last.status, "waiting");
  assert.equal(last.summary, "选哪个方案", "提问正文退化为摘要");

  // 显式 summary 优先于提问正文。
  await dshPost({ event: "question-request", sessionId: "d2", question: "正文", summary: "显式摘要" });
  last = await lastDshEvent("d2");
  assert.equal(last.summary, "显式摘要");

  // 来源能力表：插件已露面（/hooks/dsh 转发即活性心跳，票 #176）⇒ dsh 声明 approve。
  const snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.deepEqual(snap.capabilities.dsh, ["waiting", "approve"]);
});

test("hooks/dsh：session-removed 摘出在册（手机对账清锁的桥侧前提）", async () => {
  await dshPost({ event: "session-added", sessionId: "d3" });
  let snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.ok(snap.sessions.some((s) => s.sessionId === "d3"));

  const before = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const r = await dshPost({ event: "session-removed", sessionId: "d3" });
  assert.equal(r.status, 200);
  const receipt = await r.json();
  assert.equal(receipt.ok, true);
  assert.equal(receipt.removed, true);
  assert.ok(Number.isFinite(receipt.id), "实时出册必须进入事件流");
  snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(
    snap.sessions.some((s) => s.sessionId === "d3"),
    false,
    "退出即不在册快照",
  );

  const live = await (await fetch(`${BASE}/events?since=${before.cursor}&wait=0`)).json();
  const removal = live.events.find((e) => e.sessionId === "d3" && e.kind === "membership");
  assert.ok(removal, "session/disposed 必须给手机一条实时 membership 出册事实");
  assert.equal(removal.membership, "ABSENT");
  assert.equal(removal.archiveState, "UNKNOWN");
  assert.equal(removal.reason, "source-removed");

  const late = await dshPost({ event: "session-status", sessionId: "d3", status: "working" });
  assert.equal(late.status, 400, "出册后的迟到活动不得复活");
  snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(snap.sessions.some((s) => s.sessionId === "d3"), false);
});

test("hooks/dsh：通知行进问答流（issue #307）——低强调条目、不动状态、不算没阅", async () => {
  await dshPost({ event: "session-added", sessionId: "d7" });
  await dshPost({ event: "user-message", sessionId: "d7", userText: "跑一下测试" });
  await dshPost({ event: "assistant-message", sessionId: "d7", assistantText: "测试过了" });
  const before = await lastDshEvent("d7");
  assert.equal(before.status, "working");
  await dshPost({ event: "read-state", sessionId: "d7", readState: "read" });
  assert.equal((await lastDshEvent("d7")).readState, "read", "机主已阅（前置）");

  const r = await dshPost({
    event: "session-notice",
    sessionId: "d7",
    noticeText: "后台任务状态更新 · pwsh-5 [status: completed]",
    noticeDetail: "background job pwsh-5 finished [status: completed].",
  });
  assert.equal(r.status, 200);
  const last = await lastDshEvent("d7");
  assert.equal(last.status, "working", "通知不动会话状态（桥按最新态回填）");
  assert.equal(last.noticeText, undefined, "内部补丁字段不上线");
  assert.equal(last.turns.at(-1).kind, "notice");
  assert.equal(last.turns.at(-1).role, "assistant");
  assert.equal(last.turns.at(-1).text, "后台任务状态更新 · pwsh-5 [status: completed]");
  assert.equal(last.turns.at(-1).detail, "background job pwsh-5 finished [status: completed].");
  assert.equal(last.latestReply, "测试过了", "通知不冒充回答");
  assert.equal(last.readState, "read", "通知不算新回答，不点亮「空闲·没阅」");
});

test("hooks/dsh：source.kind 分类——注入不上背屏、通知只留一行（issue #307）", async () => {
  // 插件侧已按 source.kind 分类：注入压根不转发；这里钉住桥的两种入口语义（通知正文缺失＝无事发生）。
  await dshPost({ event: "session-added", sessionId: "d8" });
  const ok = await dshPost({
    event: "session-notice",
    sessionId: "d8",
    noticeText: "子任务状态更新 · 盘点完成",
  });
  assert.equal(ok.status, 200);
  assert.equal((await dshPost({ event: "session-notice", sessionId: "d8" })).status, 202, "没有通知正文＝无事发生");
  const last = await lastDshEvent("d8");
  assert.equal(last.status, "idle", "通知不动状态：回填 session-added 的 idle");
  assert.deepEqual(last.turns.map((t) => [t.kind, t.text]), [["notice", "子任务状态更新 · 盘点完成"]]);
});

test("hooks/dsh：坏 JSON 400、未映射事件 202；session-error 归一为 error（#174）", async () => {
  assert.equal((await dshPost("{oops")).status, 400);
  assert.equal((await dshPost({ event: "who-knows", sessionId: "d2" })).status, 202);
  // 出错词（spec 0018-4 票 #174）：api-session/error 是明确信号——error 状态进环，
  // 摘要照带（出错提醒 #173 的来源语义）。
  const r = await dshPost({ event: "session-error", sessionId: "d2", summary: "会话崩了" });
  assert.equal(r.status, 200);
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const ev = page.events.filter((e) => e.sessionId === "d2").at(-1);
  assert.equal(ev.status, "error");
  assert.equal(ev.summary, "会话崩了");
});

// ---------- 会话动作契约（spec 0018-4 / 票 #174：Remote Approval 的唯一写方向） ----------

/** 灌一条统一会话事件（/inject）：动作测试造在册会话用。 */
async function inject(body) {
  return fetch(`${BASE}/inject`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${ACCESS_TOKEN}` },
    body: JSON.stringify(body),
  });
}

async function actionPost(body) {
  const res = await fetch(`${BASE}/action`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${ACCESS_TOKEN}` },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
  return { status: res.status, body: await res.json() };
}

async function pendingGet(sessionId) {
  return (await fetch(`${BASE}/action/pending?sessionId=${encodeURIComponent(sessionId)}`)).json();
}

test("主动写面缺访问凭据恒 401（spec 0024 安全底线）", async () => {
  const action = await fetch(`${BASE}/action`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId: "a1", requestId: "r-no-auth", action: "approve" }),
  });
  const create = await fetch(`${BASE}/codex/conversations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ prompt: "x" }),
  });
  assert.equal(action.status, 401);
  assert.equal(create.status, 401);
});
test("action 契约：accepted → 决定一次性取走（approve=allow），再取为空", async () => {
  await inject({ sessionId: "a1", source: "claude", status: "waiting" });
  const r = await actionPost({ sessionId: "a1", requestId: "r-a1", action: "approve" });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, { ok: true, receipt: "accepted", requestId: "r-a1" });

  const first = await pendingGet("a1");
  assert.equal(first.decision, "allow");
  assert.equal(first.requestId, "r-a1");
  const second = await pendingGet("a1");
  assert.equal(second.decision, undefined, "一次性：取走即清");
});

test("action 契约：reject → deny；select 带选项原样回传", async () => {
  await inject({ sessionId: "a2", source: "claude", status: "waiting" });
  await actionPost({ sessionId: "a2", requestId: "r-a2", action: "reject" });
  assert.equal((await pendingGet("a2")).decision, "deny");

  await inject({ sessionId: "a3", source: "claude", status: "waiting" });
  const r = await actionPost({ sessionId: "a3", requestId: "r-a3", action: "select", optionId: "opt-2" });
  assert.equal(r.body.receipt, "accepted");
  const pending = await pendingGet("a3");
  assert.equal(pending.decision, "allow");
  assert.equal(pending.optionId, "opt-2");
});

test("action 契约：回执恒定不悬挂——unknown-session / unsupported / bad-request", async () => {
  // 不在册
  assert.deepEqual((await actionPost({ sessionId: "nope", requestId: "r1", action: "approve" })).body, {
    ok: false,
    receipt: "unknown-session",
    requestId: "r1",
  });
  // Codex 可批准，但当前没有待决 app-server request：不悬挂，明确 bad-request
  await inject({ sessionId: "a4", source: "codex", status: "waiting" });
  assert.equal((await actionPost({ sessionId: "a4", requestId: "r2", action: "approve" })).body.receipt, "bad-request");
  // 动作词不认识 / select 缺选项 / 缺会话键：bad-request
  await inject({ sessionId: "a5", source: "claude", status: "waiting" });
  assert.equal((await actionPost({ sessionId: "a5", requestId: "r3", action: "free-text" })).body.receipt, "bad-request");
  assert.equal((await actionPost({ sessionId: "a5", requestId: "r4", action: "select" })).body.receipt, "bad-request");
  assert.equal((await actionPost({ requestId: "r5", action: "approve" })).body.receipt, "bad-request");
  assert.equal((await actionPost("{oops")).body.receipt, "bad-request");
});

test("action 能力表：claude/codex/zcode 声明 approve、dsh 随插件活性（spec 0024）", async () => {
  const snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.ok(snap.capabilities.claude.includes("approve"), "PreToolUse 本地批准通道在，claude=approve");
  assert.ok(snap.capabilities.codex.includes("approve"), "app-server server-request 可回批准");
  assert.ok(snap.capabilities.zcode.includes("approve"), "ZCode interaction server-request 可回 approve/reject/select");
  assert.ok(snap.capabilities.dsh.includes("approve"), "插件在线（/hooks/dsh 已露面）⇒ dsh=approve");
  // 活性折扣（纯函数判例）：插件失联 ⇒ dsh 收回 approve，只剩 waiting；在线才带 approve。
  assert.deepEqual(capabilitiesFor(false).dsh, ["waiting"], "失联收回 approve（快照收紧、动作回 unsupported）");
  assert.deepEqual(capabilitiesFor(true).dsh, ["waiting", "approve"]);
  assert.deepEqual(capabilitiesFor(false).claude, ["waiting", "approve"], "折扣只作用于 dsh");
  assert.deepEqual(capabilitiesFor(false).zcode, ["waiting", "approve"], "ZCode interaction 数据路径声明 approve");
});

test("action 契约：dsh 会话批准 accepted（插件在线），&plugin=dsh 心跳取决定", async () => {
  await dshPost({ event: "session-added", sessionId: "d9" });
  const r = await actionPost({ sessionId: "d9", requestId: "r-d9", action: "approve" });
  assert.equal(r.body.receipt, "accepted", "dsh 声明 approve 且插件在线 ⇒ accepted");
  // 插件取决定（&plugin=dsh 兼作活性心跳）：一次性取走。
  const first = await (await fetch(`${BASE}/action/pending?sessionId=d9&plugin=dsh`)).json();
  assert.equal(first.decision, "allow");
  const second = await (await fetch(`${BASE}/action/pending?sessionId=d9&plugin=dsh`)).json();
  assert.equal(second.decision, undefined, "一次性：取走即清");
});

test("action 契约：dsh 选择题 select 选项回传（pendingOptions 消费面）", async () => {
  await dshPost({
    event: "question-request",
    sessionId: "d9",
    question: "选哪个",
    options: [{ id: "a", label: "方案 A" }, { id: "b", label: "方案 B" }],
  });
  // 选项进统一事件（手机按选项点选——自由文字永不存在）
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const ev = page.events.filter((e) => e.sessionId === "d9").at(-1);
  assert.deepEqual(ev.pendingOptions, [
    { id: "a", label: "方案 A" },
    { id: "b", label: "方案 B" },
  ]);

  const r = await actionPost({ sessionId: "d9", requestId: "r-q9", action: "select", optionId: "b" });
  assert.equal(r.body.receipt, "accepted");
  const pending = await (await fetch(`${BASE}/action/pending?sessionId=d9&plugin=dsh`)).json();
  assert.equal(pending.optionId, "b");
  assert.equal(pending.decision, "allow");
});

test("hooks/codex：明确 error 字面 → error 状态（#174 顺手项，其余词形不造）", async () => {
  const r = await fetch(`${BASE}/hooks/codex`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${ACCESS_TOKEN}` },
    body: JSON.stringify({ type: "error", session_id: "c9", summary: "task failed" }),
  });
  assert.equal(r.status, 200);
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const ev = page.events.filter((e) => e.sessionId === "c9").at(-1);
  assert.equal(ev.status, "error");
});

// ---- 完整历史契约（spec 0018-7 / 票 #177） ----

test("history：窗口之外更早的条目也能取到，实时推流仍是窗口（票 #177）", async () => {
  for (let i = 1; i <= 25; i++) {
    await inject({ sessionId: "hist1", source: "codex", status: "working", userText: `第 ${i} 问`, updatedAt: i });
  }
  const page = await (await fetch(`${BASE}/events?since=0&wait=0`)).json();
  const last = [...page.events].reverse().find((e) => e.sessionId === "hist1");
  assert.equal(last.turns.length, 20, "实时推流口径不变：尾部窗口 20 条");

  const hist = await (await fetch(`${BASE}/history?sessionId=hist1`)).json();
  assert.equal(hist.sessionId, "hist1");
  assert.equal(hist.turns.length, 25, "全量 25 条");
  assert.equal(hist.turns[0].text, "第 1 问", "窗口截掉的第 1 条也在");
});

test("history：未知/缺参会话回空列表合法，不崩桥（票 #177）", async () => {
  const unknown = await (await fetch(`${BASE}/history?sessionId=nope`)).json();
  assert.deepEqual(unknown.turns, []);
  const missing = await (await fetch(`${BASE}/history`)).json();
  assert.deepEqual(missing.turns, []);
  const health = await fetch(`${BASE}/health`);
  assert.equal(health.status, 200);
});

// ---- 托盘第三态（2026-09-30 机主要求「托盘图标显示手机有没有连上」）：手机露面 → 状态文件 phone:true ----

test("托盘第三态：手机露面（/events 长轮询）→ 状态文件翻 phone:true（桥→托盘契约）", async () => {
  await fetch(`${BASE}/events?wait=0&since=0`);
  const deadline = Date.now() + 2000;
  let st = null;
  while (Date.now() < deadline) {
    try {
      st = JSON.parse(readFileSync(TRAY_STATE, "utf8"));
      break;
    } catch {
      /* 还没写：再等一拍 */
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  assert.ok(st, "手机露面后状态文件应已写出");
  assert.equal(st.v, 1, "契约版本");
  assert.equal(st.phone, true, "手机在场＝第三态亮起的依据");
});

// ---- 批准无果判死（review 2026-09-30 / spec 0018-4 AC3 补遗）：accepted 之后无人取走/过期 → actionExpired 终态 ----

test("过期未取走的会话动作发 actionExpired 终态；被取走的不发", async () => {
  const port = await unusedPort();
  const base = `http://127.0.0.1:${port}`;
  const child2 = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode"], {
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      BRIDGE_PORT: String(port),
      BRIDGE_SEQ_FILE: join(HERE, "bridge.test.seq"),
      BRIDGE_ACTION_TTL_MS: "120",
      BRIDGE_ACCESS_TOKEN: ACCESS_TOKEN,
    },
  });
  const waitHealth = async () => {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      try {
        const r = await fetch(`${base}/health`);
        if (r.ok) return;
      } catch {
        /* 还没起来 */
      }
      await new Promise((r) => setTimeout(r, 50));
    }
    throw new Error("子桥起动超时");
  };
  const post = (pathname, body) =>
    fetch(`${base}${pathname}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${ACCESS_TOKEN}` },
      body: JSON.stringify(body),
    });
  try {
    await waitHealth();
    await post("/inject", { sessionId: "exp-1", source: "claude", status: "waiting" });
    await post("/inject", { sessionId: "ok-1", source: "claude", status: "waiting" });
    const r1 = await (await post("/action", { sessionId: "exp-1", requestId: "r1", action: "approve" })).json();
    const r2 = await (await post("/action", { sessionId: "ok-1", requestId: "r2", action: "approve" })).json();
    assert.equal(r1.receipt, "accepted");
    assert.equal(r2.receipt, "accepted");
    // ok-1 的决定被钩子取走（有下文）；exp-1 没人取走（TTL 过期即死）
    const taken = await (await fetch(`${base}/action/pending?sessionId=ok-1&plugin=dsh`)).json();
    assert.equal(taken.decision, "allow");
    await new Promise((r) => setTimeout(r, 300));
    const page = await (await fetch(`${base}/events?since=0&wait=0`)).json();
    const expired = page.events.filter((e) => e.actionExpired === true);
    assert.deepEqual(
      expired.map((e) => e.sessionId),
      ["exp-1"],
      "只有过期未取走的动作判死；被取走的（有下文）与未受理的不发终态",
    );
    // 终态不粘连：同会话后续普通事件无标记（appendEvent 的 remembered 剥离）
    const later = await post("/inject", { sessionId: "exp-1", source: "claude", status: "idle" });
    assert.equal(later.status, 200);
    const page2 = await (await fetch(`${base}/events?since=0&wait=0`)).json();
    const tail = page2.events.filter((e) => e.sessionId === "exp-1").at(-1);
    assert.notEqual(tail.actionExpired, true, "actionExpired 不得粘连后续事件");
  } finally {
    child2.kill();
  }
});

// ---------- 事件分页（issue #309：手机 bridge-poll OOM 崩进程）----------

test("分页：limit 满页续取，游标推进且不漏不重（手机 catch-up 口径）", async () => {
  const start = (await (await fetch(`${BASE}/events?since=0&wait=0`)).json()).cursor;
  for (let i = 1; i <= 12; i++) {
    const r = await fetch(`${BASE}/inject`, {
      method: "POST",
      body: JSON.stringify({ sessionId: "page-s", source: "codex", status: "working", currentAction: `step-${i}` }),
    });
    assert.equal(r.status, 200);
  }
  const seen = [];
  let cursor = start;
  for (let round = 1; round <= 4; round++) {
    const page = await (await fetch(`${BASE}/events?since=${cursor}&limit=5&wait=0`)).json();
    assert.ok(page.events.length <= 5, `每页不超过 limit（第 ${round} 轮拿到 ${page.events.length}）`);
    assert.ok(page.cursor >= cursor, "游标单调不倒退");
    seen.push(...page.events.map((e) => `${e.sessionId}:${e.currentAction}`));
    cursor = page.cursor;
    if (page.events.length === 0) break;
  }
  const ids = seen.filter((k) => k.startsWith("page-s:"));
  assert.deepEqual(
    ids,
    Array.from({ length: 12 }, (_, i) => `page-s:step-${i + 1}`),
    "12 条按页取全，不漏（游标取本页最大 id，不再取环尾而跳号）",
  );
  assert.equal(new Set(ids).size, ids.length, "不重");
});

test("分页：字节封顶与单条超大保底 + 环按字节裁剪（issue #309 根因）", async () => {
  const port = await unusedPort();
  const base = `http://127.0.0.1:${port}`;
  const child2 = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode"], {
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      BRIDGE_PORT: String(port),
      BRIDGE_SEQ_FILE: join(HERE, "bridge.test.seq.restart"),
      BRIDGE_EVENTS_RING_BYTES: "1000",
      BRIDGE_EVENTS_PAGE_BYTES: "1000",
    },
  });
  try {
    await waitForHealthFor(base);
    const inject = (sessionId, latestReply) =>
      fetch(`${base}/inject`, { method: "POST", body: JSON.stringify({ sessionId, status: "working", latestReply }) });
    // 环按字节裁剪：一大两小 → 大事件（最老）被裁掉，环里只剩能装下的两条。
    await inject("big", "x".repeat(4000));
    await inject("small-1", "甲");
    await inject("small-2", "乙");
    const ring = await (await fetch(`${base}/events?since=0&limit=100&wait=0`)).json();
    assert.deepEqual(
      new Set(ring.events.map((e) => e.sessionId)),
      new Set(["small-1", "small-2"]),
      "环按字节封顶：最老的事件先被裁，重放体量有硬上限",
    );
    // 单页字节封顶：单条就超页限时仍要给出（至少一条）——否则手机游标停在原地空转。
    await inject("big-2", "y".repeat(4000));
    const page = await (await fetch(`${base}/events?since=${ring.cursor}&limit=100&wait=0`)).json();
    assert.equal(page.events.length, 1, "超大单条独占一页，不被页限卡住");
    assert.equal(page.events[0].sessionId, "big-2");
    assert.equal(page.cursor, page.events[0].id, "游标推进到本页最大 id（下轮从这里续）");
  } finally {
    child2.kill();
  }
});

test("history：ZCode GET /history 按需读 model-io 重建完整会话", async () => {  const port = await unusedPort();
  const base = `http://127.0.0.1:${port}`;
  const root = join(HERE, "bridge.test.zcode-history");
  rmSync(root, { recursive: true, force: true });
  mkdirSync(root, { recursive: true });
  writeFileSync(
    join(root, "model-io-sess_history.jsonl"),
    [
      {
        sessionId: "sess_history",
        turnId: "turn_1",
        requestId: "r1",
        startedAt: 1,
        completedAt: 2,
        request: { messages: [{ role: "user", content: "第一问" }] },
        response: { text: "第一答" },
      },
      {
        sessionId: "sess_history",
        turnId: "turn_2",
        requestId: "r2",
        startedAt: 3,
        completedAt: 4,
        request: { messages: [{ role: "user", content: "第二问" }] },
        response: { text: "第二答" },
      },
    ].map((line) => JSON.stringify(line)).join("\n"),
    "utf8",
  );
  const child2 = spawn(process.execPath, [join(HERE, "bridge.mjs"), "--no-tunnel", "--no-codex", "--no-claude"], {
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      BRIDGE_PORT: String(port),
      BRIDGE_SEQ_FILE: join(HERE, "bridge.test.seq"),
      ZCODE_MODEL_IO_DIR: root,
    },
  });
  try {
    await waitForHealthFor(base);
    const history = await (await fetch(`${base}/history?sessionId=sess_history`)).json();
    assert.deepEqual(history.turns.map((turn) => [turn.role, turn.text]), [
      ["user", "第一问"],
      ["assistant", "第一答"],
      ["user", "第二问"],
      ["assistant", "第二答"],
    ]);
  } finally {
    child2.kill();
    rmSync(root, { recursive: true, force: true });
  }
});

async function waitForHealthFor(base, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${base}/health`);
      if (r.ok) return;
    } catch {
      /* 还没起来 */
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("history 子桥起动超时");
}

test("voice protocol: only a genuine new terminal fact speaks, never stop or subsequent metadata", async () => {
  const emit = async (patch) => {
    const receipt = await (await fetch(`${BASE}/inject`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId: "speech-facts-e2e", source: "codex", ...patch }) })).json();
    const page = await (await fetch(`${BASE}/events?since=${receipt.id - 1}`)).json();
    return page.events.find((event) => event.id === receipt.id);
  };
  await emit({ status: "working", taskStarted: true, turnId: "one", userText: "first" });
  await emit({ status: "working", assistantText: "first result" });
  const done = await emit({ status: "idle", completion: "done", turnId: "one" });
  assert.equal(done.voiceEvent.text, "first result");
  assert.equal((await emit({ status: "idle", title: "renamed" })).voiceEvent, null);
  await emit({ status: "working", taskStarted: true, turnId: "two", userText: "next" });
  assert.equal((await emit({ status: "idle", completion: "cancelled", turnId: "two" })).voiceEvent, null);
});

test("voice protocol: pending questions survive work, share UI IDs, and resolve individually", async () => {
  const emit = async (patch) => {
    const receipt = await (await fetch(`${BASE}/inject`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId: "speech-questions-e2e", source: "codex", status: "working", ...patch }) })).json();
    const page = await (await fetch(`${BASE}/events?since=${receipt.id - 1}`)).json();
    return page.events.find((event) => event.id === receipt.id);
  };
  await emit({ pendingQuestions: [{ id: "a:0", title: "first?", options: ["yes", "no"] }, { id: "a:1", title: "second?", options: [] }] });
  assert.equal((await emit({ currentAction: "Read" })).pendingRequests.length, 2);
  const resolved = await emit({ resolvedRequestIds: ["a:0"] });
  assert.deepEqual(resolved.pendingRequests.map((request) => request.id), ["a:1"]);
  const snapshot = await (await fetch(`${BASE}/snapshot`)).json();
  assert.deepEqual(snapshot.sessions.find((session) => session.sessionId === "speech-questions-e2e").pendingQuestions.map((question) => question.id), ["a:1"]);
  const replay = await emit({ completion: "done", sourceAt: 1, replay: true });
  assert.equal(replay.voiceReplay, true);
  assert.equal(replay.voiceEvent.createdAt, 1);
});

test("Codex notify identity uses thread-id and turn-id without falling back to another conversation", async () => {
  const notify = { type: "agent-turn-complete", "thread-id": "native-notify", "turn-id": "native-turn", "last-assistant-message": "notify result" };
  const send = async () => {
    const boundary = (await (await fetch(`${BASE}/snapshot`)).json()).cursor;
    assert.equal((await fetch(`${BASE}/hooks/codex`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(notify) })).status, 200);
    const page = await (await fetch(`${BASE}/events?since=${boundary}`)).json();
    return page.events.filter((event) => event.sessionId === "native-notify").at(-1);
  };
  const first = await send();
  assert.equal(first.voiceEvent.text, "notify result");
  assert.match(first.voiceEvent.id, /native-turn/);
  assert.equal((await send()).voiceEvent, null);
});

test("过程折叠回合元数据：等待不结束，停止收口，问卷答复不另开轮", async () => {
  await inject({ sessionId: "process-fold", source: "codex", status: "working", taskStarted: true, turnId: "r1", userText: "问题", updatedAt: 10 });
  await inject({ sessionId: "process-fold", status: "working", thinkingDelta: "思考", updatedAt: 11 });
  await inject({ sessionId: "process-fold", status: "waiting", updatedAt: 12 });
  let history = await (await fetch(`${BASE}/history?sessionId=process-fold`)).json();
  assert.ok(history.turns.every((entry) => entry.roundComplete !== true));
  await inject({ sessionId: "process-fold", status: "working", userText: "选择 A", resolvedRequestIds: ["q:0"], updatedAt: 13 });
  await inject({ sessionId: "process-fold", status: "idle", completion: "cancelled", updatedAt: 14 });
  history = await (await fetch(`${BASE}/history?sessionId=process-fold`)).json();
  assert.deepEqual([...new Set(history.turns.map((entry) => entry.roundId))], ["r1"]);
  assert.ok(history.turns.every((entry) => entry.roundComplete === true));
  await inject({ sessionId: "process-fold", status: "working", taskStarted: true, turnId: "r2", userText: "新轮", updatedAt: 20 });
  const next = await (await fetch(`${BASE}/history?sessionId=process-fold`)).json();
  assert.equal(next.turns.at(-1).roundId, "r2");
  assert.notEqual(next.turns.at(-1).roundComplete, true);
});


test("Codex答题写面必须鉴权，Desktop题不能绕过到普通prompt", async () => {
  const body = JSON.stringify({ sessionId: "desktop-only", groupId: "old", turnId: "t", requestId: "r", answers: {} });
  const rejected = await fetch(BASE + "/codex/questions/answer", { method: "POST", headers: { "Content-Type": "application/json" }, body });
  assert.equal(rejected.status, 401);
  const allowed = await fetch(BASE + "/codex/questions/answer", { method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer " + ACCESS_TOKEN }, body });
  assert.equal((await allowed.json()).receipt, "unsupported");
});

test("答题只读核对不产生写请求", async () => {
  const response = await fetch(BASE + "/codex/questions/state?sessionId=missing&groupId=old");
  assert.equal(response.status, 200);
  assert.equal((await response.json()).receipt, "unsupported");
});

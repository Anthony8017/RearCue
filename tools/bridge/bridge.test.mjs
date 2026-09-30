// PC 桥行为测试（票 #116）：node --test tools/bridge/bridge.test.mjs
// 起真服务器（随机端口）断言统一会话事件契约：inject→长轮询投递、游标语义、非法输入 400。
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { capabilitiesFor } from "./capabilities.mjs";

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
  assert.deepEqual(await r.json(), {
    sessions: [],
    capabilities: { codex: ["waiting"], claude: ["waiting", "approve"], dsh: ["waiting"] },
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
    assert.equal((await dshPost({ event: "session-status", sessionId: "d1", status: word })).status, 200);
    last = await lastDshEvent("d1");
    assert.equal(last.status, want, word);
  }
  const unknown = await dshPost({ event: "session-status", sessionId: "d1", status: "dancing" });
  assert.equal(unknown.status, 202, "未知状态词跳过");
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

  const r = await dshPost({ event: "session-removed", sessionId: "d3" });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, removed: true });
  snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.equal(
    snap.sessions.some((s) => s.sessionId === "d3"),
    false,
    "退出即不在册快照",
  );
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
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function actionPost(body) {
  const res = await fetch(`${BASE}/action`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
  return { status: res.status, body: await res.json() };
}

async function pendingGet(sessionId) {
  return (await fetch(`${BASE}/action/pending?sessionId=${encodeURIComponent(sessionId)}`)).json();
}

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
  // 在册但来源没声明 approve（codex 只提醒）：unsupported
  await inject({ sessionId: "a4", source: "codex", status: "waiting" });
  assert.equal((await actionPost({ sessionId: "a4", requestId: "r2", action: "approve" })).body.receipt, "unsupported");
  // 动作词不认识 / select 缺选项 / 缺会话键：bad-request
  await inject({ sessionId: "a5", source: "claude", status: "waiting" });
  assert.equal((await actionPost({ sessionId: "a5", requestId: "r3", action: "free-text" })).body.receipt, "bad-request");
  assert.equal((await actionPost({ sessionId: "a5", requestId: "r4", action: "select" })).body.receipt, "bad-request");
  assert.equal((await actionPost({ requestId: "r5", action: "approve" })).body.receipt, "bad-request");
  assert.equal((await actionPost("{oops")).body.receipt, "bad-request");
});

test("action 能力表：claude 声明 approve、codex 不声明、dsh 随插件活性（票 #176）", async () => {
  const snap = await (await fetch(`${BASE}/snapshot`)).json();
  assert.ok(snap.capabilities.claude.includes("approve"), "PreToolUse 本地批准通道在，claude=approve");
  assert.ok(!snap.capabilities.codex.includes("approve"), "codex 无程序化批准通道，不声明");
  assert.ok(snap.capabilities.dsh.includes("approve"), "插件在线（/hooks/dsh 已露面）⇒ dsh=approve");
  // 活性折扣（纯函数判例）：插件失联 ⇒ dsh 收回 approve，只剩 waiting；在线才带 approve。
  assert.deepEqual(capabilitiesFor(false).dsh, ["waiting"], "失联收回 approve（快照收紧、动作回 unsupported）");
  assert.deepEqual(capabilitiesFor(true).dsh, ["waiting", "approve"]);
  assert.deepEqual(capabilitiesFor(false).claude, ["waiting", "approve"], "折扣只作用于 dsh");
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
    headers: { "Content-Type": "application/json" },
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

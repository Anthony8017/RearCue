/**
 * DSH 只读插件判例（票 #171 订阅/转发 ＋ 票 #176 批准应答 ＋ 票 #181 宿主侧重写）：
 * 跑法：node --test tools/bridge/adapters/dsh/dsh-plugin.test.mjs
 * 行为面：宿主侧订阅接线（`ctx.on`）＋ 事件转发 ＋ 正文增量合并 ＋ 两条 waterfall 应答流；
 * 红线面：**只读保证**——源码扫描锁死「只用 ctx.on 订阅 ＋ 只对本机回环两口外发 ＋
 * 应答只经 dsh-answers 的规范值（自由文字不存在）」（ADR 0010 / ADR 0009）。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  apply,
  createDeltaCoalescer,
  createDshBridgePlugin,
  DSH_MIN_VERSION,
  runApprovalFlow,
  runQuestionFlow,
  versionAtLeast,
} from "./dsh-bridge-plugin.mjs";
import { SUBSCRIBED_EVENTS } from "./dsh-events.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN_SOURCE = readFileSync(join(HERE, "dsh-bridge-plugin.mjs"), "utf8");
const EVENTS_SOURCE = readFileSync(join(HERE, "dsh-events.mjs"), "utf8");
const ANSWERS_SOURCE = readFileSync(join(HERE, "dsh-answers.mjs"), "utf8");

/** 只去掉注释后的源码（红线扫描用；注释里会提到 ctx.remote 之类的背景，不算调用面）。 */
function codeOnly(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((line) => line.replace(/\/\/.*$/, ""))
    .join("\n");
}

/** 假 DSH 宿主 ctx：只提供 on 订阅面（真实插件也只被允许用这一个口）。 */
function fakeCtx() {
  const handlers = new Map();
  return {
    on(name, handler) {
      handlers.set(name, handler);
    },
    handlers,
  };
}

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

test("apply：宿主事件全订阅面接上，一个不漏", () => {
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: () => {} }).apply(ctx);
  for (const name of SUBSCRIBED_EVENTS) {
    assert.ok(ctx.handlers.has(name), `缺订阅：${name}`);
  }
  assert.equal(ctx.handlers.size, SUBSCRIBED_EVENTS.length, "多订了订阅表以外的事件");
});

test("会话事件 → 转发钩子体到桥（端口随配置）", () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ port: 19999, post: (port, body) => posted.push({ port, body }) }).apply(ctx);
  ctx.handlers.get("agent/status")({ agent: { id: "s-1" }, status: "running" });
  assert.deepEqual(posted, [
    { port: 19999, body: { event: "session-status", sessionId: "s-1", status: "working" } },
  ]);
});

test("正文事件（session/event）转发给桥，问答流拿得到内容", () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: (_p, body) => posted.push(body) }).apply(ctx);
  ctx.handlers.get("session/event")({ id: "s-1", header: {} }, {
    type: "user/message",
    data: { role: "user", content: [{ type: "text", text: "把测试跑一遍" }] },
  });
  assert.deepEqual(posted, [{ event: "user-message", sessionId: "s-1", userText: "把测试跑一遍" }]);
});

test("流式增量：逐字 delta 先攒后发（合并窗内只发一条）", async () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: (_p, body) => posted.push(body), deltaGapMs: 20 }).apply(ctx);
  const stream = ctx.handlers.get("agent/assistant-stream");
  for (const text of ["你", "好", "呀"]) {
    stream({ agent: { id: "s-2" }, frame: { type: "chunk", chunk: { type: "text-delta", text } } });
  }
  assert.equal(posted.length, 0, "合并窗内不立即发");
  await tick(40);
  assert.deepEqual(posted, [{ event: "assistant-delta", sessionId: "s-2", assistantDelta: "你好呀" }]);
});

test("增量合并器：flush 立刻冲掉攒着的文本，空缓冲不发", () => {
  const posted = [];
  const coalescer = createDeltaCoalescer((_p, body) => posted.push(body), 18787, 1000);
  coalescer.push("s-3", "甲");
  coalescer.push("s-3", "乙");
  coalescer.flush();
  coalescer.flush();
  assert.deepEqual(posted, [{ event: "assistant-delta", sessionId: "s-3", assistantDelta: "甲乙" }]);
});

// ---- 两条 waterfall（真机应答值） ----

test("批准流：手机同意 → 'allowed-once'；拒绝 → 'rejected'", async () => {
  const allow = await runApprovalFlow({
    sessionId: "s-1",
    poll: async () => ({ decision: "allow" }),
    waitMs: 50,
    intervalMs: 1,
  });
  assert.equal(allow, "allowed-once");
  const deny = await runApprovalFlow({
    sessionId: "s-1",
    poll: async () => ({ decision: "deny" }),
    waitMs: 50,
    intervalMs: 1,
  });
  assert.equal(deny, "rejected");
});

test("批准流：等不到决定 → 委派 next()（绝不猜应答值）", async () => {
  let delegated = 0;
  const outcome = await runApprovalFlow({
    sessionId: "s-1",
    poll: async () => null,
    waitMs: 0,
    intervalMs: 1,
    next: async () => {
      delegated += 1;
      return "unavailable";
    },
  });
  assert.equal(delegated, 1);
  assert.equal(outcome, "unavailable");
});

test("提问流：手机点选 label → {answers:[{id, selected:[label]}]}", async () => {
  const answer = await runQuestionFlow({
    sessionId: "s-1",
    ask: { questionId: "q-1", options: [{ id: "甲案", label: "甲案" }, { id: "乙案", label: "乙案" }] },
    poll: async () => ({ decision: "allow", optionId: "乙案" }),
    waitMs: 50,
    intervalMs: 1,
  });
  assert.deepEqual(answer, { answers: [{ id: "q-1", selected: ["乙案"] }] });
});

test("提问流：手机拒绝（提问无拒绝形态）→ 委派 next()", async () => {
  let delegated = 0;
  await runQuestionFlow({
    sessionId: "s-1",
    ask: { questionId: "q-1", options: [{ id: "甲案", label: "甲案" }] },
    poll: async () => ({ decision: "deny" }),
    waitMs: 0,
    intervalMs: 1,
    next: async () => {
      delegated += 1;
      return { answers: [] };
    },
  });
  assert.equal(delegated, 1);
});

test("waterfall 接线：approval/request 认领先转发再等决定；无 agent 直接委派", async () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: (_p, body) => posted.push(body), poll: async () => ({ decision: "allow" }), waitMs: 40, intervalMs: 1 }).apply(ctx);
  const outcome = await ctx.handlers.get("approval/request")(
    { agent: { id: "s-9" }, toolName: "bash", reason: "要删目录" },
    async () => "unavailable",
  );
  assert.equal(outcome, "allowed-once");
  assert.deepEqual(posted, [{ event: "approval-request", sessionId: "s-9", summary: "要删目录" }]);

  let delegated = 0;
  const orphan = await ctx.handlers.get("approval/request")({ toolName: "bash" }, async () => {
    delegated += 1;
    return "unavailable";
  });
  assert.equal(delegated, 1);
  assert.equal(orphan, "unavailable");
});

test("waterfall 接线：user-questions/request 选项以 label 进钩子体，点选原样回", async () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({
    post: (_p, body) => posted.push(body),
    poll: async () => ({ decision: "allow", optionId: "甲案" }),
    waitMs: 40,
    intervalMs: 1,
  }).apply(ctx);
  const answer = await ctx.handlers.get("user-questions/request")(
    {
      agent: { id: "s-10" },
      questions: [{ id: "q-1", question: "走哪条？", options: [{ label: "甲案" }, { label: "乙案" }] }],
    },
    async () => ({ answers: [] }),
  );
  assert.deepEqual(answer, { answers: [{ id: "q-1", selected: ["甲案"] }] });
  assert.deepEqual(posted, [
    {
      event: "question-request",
      sessionId: "s-10",
      summary: "走哪条？",
      options: [
        { id: "甲案", label: "甲案" },
        { id: "乙案", label: "乙案" },
      ],
    },
  ]);
});

// ---- 版本门槛 ----

test("版本门槛：引擎明确低于门槛 → 零订阅零转发（DSH 来源不出现）", () => {
  for (const v of ["0.1.9", "0.2.0-rc.1", "0.2.0-rc.0"]) {
    const ctx = fakeCtx();
    const posted = [];
    createDshBridgePlugin({ engineVersion: v, post: (_p, b) => posted.push(b), waitMs: 0 }).apply(ctx);
    assert.equal(ctx.handlers.size, 0, `低于门槛竟订阅了：${v}`);
    assert.equal(posted.length, 0, `低于门槛竟转发了：${v}`);
  }
});

test("版本门槛：达到门槛与认不出（fail-open）都放行", () => {
  for (const v of ["0.2.0-rc.2", "0.2.0", "0.3.1", null, "看不懂"]) {
    const ctx = fakeCtx();
    createDshBridgePlugin({ engineVersion: v, post: () => {} }).apply(ctx);
    assert.equal(ctx.handlers.size, SUBSCRIBED_EVENTS.length, `该放行却拦了：${v}`);
  }
  assert.equal(versionAtLeast("0.2.0-rc.10"), true, "数字段按数值比：rc.10 > rc.2");
});

// ---- 只读保证（判例锁红线） ----

test("只读保证：ctx 调用面只有 on 订阅", () => {
  const code = codeOnly(PLUGIN_SOURCE);
  const calls = [...code.matchAll(/ctx\.([A-Za-z_$][\w$]*)/g)].map((m) => m[1]);
  assert.ok(calls.length > 0, "应有订阅调用");
  assert.deepEqual([...new Set(calls)].sort(), ["on"], "除 on 外不得出现任何 ctx 接口面");
});

test("只读保证：外发只有本机回环两口（/hooks/dsh 转发 ＋ /action/pending 取决定）", () => {
  const urls = PLUGIN_SOURCE.match(/https?:\/\/[^\s"'`]+/g) || [];
  assert.ok(urls.length > 0, "应有转发出口");
  for (const u of urls) {
    assert.ok(u.startsWith("http://127.0.0.1:"), `外发地址必须是本机回环：${u}`);
    assert.ok(u.includes("/hooks/dsh") || u.includes("/action/pending"), `外发路径只许两口之一：${u}`);
  }
});

test("只读保证：三份源码不含写方法调用面，问答应答只走规范值", () => {
  const writeCall = /\.(respond|resolve|reject|approve|create|update|remove|delete|send\w*|upload\w*|install\w*)\s*\(/;
  for (const [name, src] of [
    ["dsh-bridge-plugin.mjs", PLUGIN_SOURCE],
    ["dsh-events.mjs", EVENTS_SOURCE],
    ["dsh-answers.mjs", ANSWERS_SOURCE],
  ]) {
    assert.equal(writeCall.test(codeOnly(src)), false, `${name} 出现写方法调用面`);
  }
  // 应答形状只许由 dsh-answers 产出：插件不得自造 outcome / answers 字面量。
  assert.ok(PLUGIN_SOURCE.includes("approvalOutcomeFor("), "批准应答必须经 approvalOutcomeFor");
  assert.ok(PLUGIN_SOURCE.includes("questionAnswerFor("), "提问应答必须经 questionAnswerFor");
  assert.ok(PLUGIN_SOURCE.includes("typeof next === \"function\" ? next()"), "取不到决定必须委派 next()");
});

test("版本门槛声明：DSH >= 0.2.0-rc.2（package.json 同步）", () => {
  assert.equal(DSH_MIN_VERSION, "0.2.0-rc.2");
  const manifest = JSON.parse(readFileSync(join(HERE, "package.json"), "utf8"));
  assert.equal(manifest.dsh.engines.dsh, `>=${DSH_MIN_VERSION}`);
});

// ---- 宿主装载预演（票 #181：装载器调 apply(ctx, config)） ----

test("宿主装载入口：命名导出 apply 可用，config 可注入替身（不打真网络）", async () => {
  const posted = [];
  const ctx = fakeCtx();
  const dispose = apply(ctx, {
    post: (_p, body) => posted.push(body),
    poll: async () => null,
    waitMs: 0,
    deltaGapMs: 0,
  });
  assert.equal(ctx.handlers.size, SUBSCRIBED_EVENTS.length);
  ctx.handlers.get("agent/status")({ agent: { id: "s-1" }, status: "idle" });
  assert.deepEqual(posted, [{ event: "session-status", sessionId: "s-1", status: "idle" }]);
  assert.equal(typeof dispose, "function", "应返回清理函数（冲掉攒着的增量）");
  dispose();
});

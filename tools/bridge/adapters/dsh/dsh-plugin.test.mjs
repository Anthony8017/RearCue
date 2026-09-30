// DSH 只读插件判例（票 #171 订阅/转发 ＋ 票 #176 批准应答通道）：
// node --test tools/bridge/adapters/dsh/dsh-plugin.test.mjs
// 行为面：订阅接线 + 事件转发 + 批准应答流（fake ctx / 注入 poll）；
// 红线面：**只读保证**——源码扫描锁死「只订阅官方事件 ＋ 只对 waterfall 作批准类应答
// （经 dshAnswerFor 规范应答，自由文字不存在），无其余写面；唯一外发是本机回环两口」（ADR 0010）。
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createDshBridgePlugin, DSH_MIN_VERSION, runAnswerFlow } from "./dsh-bridge-plugin.mjs";
import { SUBSCRIBED_EVENTS } from "./dsh-events.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN_SOURCE = readFileSync(join(HERE, "dsh-bridge-plugin.mjs"), "utf8");
const EVENTS_SOURCE = readFileSync(join(HERE, "dsh-events.mjs"), "utf8");
const ANSWERS_SOURCE = readFileSync(join(HERE, "dsh-answers.mjs"), "utf8");

/** 假 DSH ctx：只提供 remote.$on 订阅面（真实插件也只被允许用这一个口）。 */
function fakeCtx() {
  const handlers = new Map();
  return {
    remote: {
      $on(name, handler) {
        handlers.set(name, handler);
      },
    },
    handlers,
  };
}

test("apply：官方事件全订阅面接上，一个不漏", () => {
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: () => {} }).apply(ctx);
  for (const name of SUBSCRIBED_EVENTS) {
    assert.ok(ctx.handlers.has(name), `缺订阅：${name}`);
  }
});

test("收到官方事件 → 转发一条钩子体到桥（端口随配置）", () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ port: 19001, post: (port, body) => posted.push({ port, body }), waitMs: 0 }).apply(ctx);
  ctx.handlers.get("api-session/status")({ sessionId: "s-1", status: "running" });
  assert.equal(posted.length, 1);
  assert.equal(posted[0].port, 19001);
  assert.deepEqual(posted[0].body, { event: "session-status", sessionId: "s-1", status: "running" });
});

test("正文流事件转发钩子体；空正文/坏载荷不转发", () => {
  const posted = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({ post: (_port, body) => posted.push(body), waitMs: 0 }).apply(ctx);
  ctx.handlers.get("assistant/message")({ sessionId: "s-1", delta: "逐批" });
  assert.deepEqual(posted[0], { event: "assistant-delta", sessionId: "s-1", assistantDelta: "逐批" });
  ctx.handlers.get("user/message")({ sessionId: "s-1" }); // 空正文：不转发
  ctx.handlers.get("api-session/added")(null); // 坏载荷：不转发不抛
  assert.equal(posted.length, 1);
});

test("转发器抛错不影响 DSH（红线：插件绝不影响会话）", () => {
  const ctx = fakeCtx();
  createDshBridgePlugin({
    post: () => {
      throw new Error("桥未启动");
    },
    waitMs: 0,
  }).apply(ctx);
  assert.doesNotThrow(() => ctx.handlers.get("approval/request")({ sessionId: "s-1", title: "拍板" }));
});

// ---- 批准应答通道（票 #176） ----

function flowArgs(overrides = {}) {
  return {
    sessionId: "s-1",
    poll: async () => null,
    port: 19001,
    waitMs: 30,
    intervalMs: 5,
    ...overrides,
  };
}

test("approval/request：桥上已有决定 allow → 回规范应答 {decision:approve}", async () => {
  const answers = [];
  const done = runAnswerFlow(
    flowArgs({
      kind: "approval",
      payload: { next: (v) => answers.push(v) },
      poll: async () => ({ decision: "allow", requestId: "r1" }),
    }),
  );
  assert.equal(await done, true);
  assert.deepEqual(answers, [{ decision: "approve" }]);
});

test("approval/request：决定 deny → 回 {decision:reject}", async () => {
  const answers = [];
  await runAnswerFlow(
    flowArgs({
      kind: "approval",
      payload: { next: (v) => answers.push(v) },
      poll: async () => ({ decision: "deny" }),
    }),
  );
  assert.deepEqual(answers, [{ decision: "reject" }]);
});

test("question/request：select 决定 → 回 {optionId}（选项来自请求载荷）", async () => {
  const answers = [];
  await runAnswerFlow(
    flowArgs({
      kind: "question",
      payload: { options: [{ id: "a" }, { id: "b", label: "方案 B" }], next: (v) => answers.push(v) },
      poll: async () => ({ decision: "allow", optionId: "b" }),
    }),
  );
  assert.deepEqual(answers, [{ optionId: "b" }]);
});

test("question/request：选项表外的 id 不答（绝不造答案）", async () => {
  const answers = [];
  const result = await runAnswerFlow(
    flowArgs({
      kind: "question",
      payload: { options: [{ id: "a" }], next: (v) => answers.push(v) },
      poll: async () => ({ decision: "allow", optionId: "z" }),
    }),
  );
  assert.equal(result, false);
  assert.deepEqual(answers, []);
});

test("预算内没决定 → 不答（官方请求保持等待）；应答口抛错吞掉", async () => {
  const answers = [];
  const result = await runAnswerFlow(
    flowArgs({
      kind: "approval",
      payload: { next: (v) => answers.push(v) },
      poll: async () => ({ armed: true, waitMs: 0 }),
    }),
  );
  assert.equal(result, false);
  assert.deepEqual(answers, [], "没有决定绝不自由发挥");

  const thrown = await runAnswerFlow(
    flowArgs({
      kind: "approval",
      payload: {
        next: () => {
          throw new Error("waterfall 已关闭");
        },
      },
      poll: async () => ({ decision: "allow" }),
    }),
  );
  assert.equal(thrown, false, "应答口抛错吞掉，不外抛");
});

test("取决定器抛错当没决定；没有应答口（载荷坏）连轮询都不开", async () => {
  const answers = [];
  const result = await runAnswerFlow(
    flowArgs({
      kind: "approval",
      payload: { next: (v) => answers.push(v) },
      poll: async () => {
        throw new Error("桥未启动");
      },
    }),
  );
  assert.equal(result, false);
  assert.deepEqual(answers, []);

  let polls = 0;
  const noResponder = await runAnswerFlow(
    flowArgs({
      kind: "approval",
      payload: { title: "没有应答口" },
      poll: async () => {
        polls += 1;
        return { decision: "allow" };
      },
    }),
  );
  assert.equal(noResponder, false);
  assert.equal(polls, 0);
});

test("插件内联接线：审批/提问事件开应答流，其余事件不开", async () => {
  const answers = [];
  const ctx = fakeCtx();
  createDshBridgePlugin({
    post: () => {},
    poll: async () => ({ decision: "allow" }),
    waitMs: 40,
    intervalMs: 5,
  }).apply(ctx);
  ctx.handlers.get("approval/request")({ sessionId: "s-9", next: (v) => answers.push(v) });
  await new Promise((r) => setTimeout(r, 60));
  assert.deepEqual(answers, [{ decision: "approve" }]);

  const answers2 = [];
  ctx.handlers.get("api-session/status")({ sessionId: "s-9", status: "running", next: (v) => answers2.push(v) });
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(answers2, [], "非等待事件不开应答流");
});

// ---- 只读保证（判例锁红线；票 #176 修订措辞：新增的是批准类应答通道，不是写面） ----

test("只读保证：ctx.remote 只出现 $on 订阅调用", () => {
  const calls = [...PLUGIN_SOURCE.matchAll(/ctx\.remote\.([A-Za-z_$][\w$]*)\s*\(/g)].map((m) => m[1]);
  assert.ok(calls.length > 0, "应有订阅调用");
  assert.deepEqual([...new Set(calls)], ["$on"], "除 $on 外不得出现任何远端调用面");
});

test("只读保证：三份源码不含写方法调用面（应答只走 dshAnswerFor 规范形状）", () => {
  // 写方法 token 面（副作用调用）：出现即判例红。应答口经 bracket 访问（dsh-answers.mjs）
  // 取用、只回 dshAnswerFor 的规范应答值——不在此列；其余写面一概不许。
  const writeCall = /\.(respond|resolve|reject|approve|create|update|remove|delete|send\w*|upload\w*|install\w*)\s*\(/;
  for (const [name, src] of [
    ["dsh-bridge-plugin.mjs", PLUGIN_SOURCE],
    ["dsh-events.mjs", EVENTS_SOURCE],
    ["dsh-answers.mjs", ANSWERS_SOURCE],
  ]) {
    assert.equal(writeCall.test(src), false, `${name} 出现写方法调用面`);
  }
  // 插件不得自造应答值：应答形状只许由 dshAnswerFor 产出（自由文字永不存在）。
  assert.equal(/\{\s*decision\s*:/.test(PLUGIN_SOURCE), false, "插件不得自造 decision 应答");
  assert.ok(PLUGIN_SOURCE.includes("dshAnswerFor("), "应答必须经 dshAnswerFor");
  assert.ok(PLUGIN_SOURCE.includes("answerVia("), "应答必须经 answerVia");
});

test("只读保证：唯一外发是本机回环两口（/hooks/dsh 转发＋/action/pending 取决定）", () => {
  const urls = PLUGIN_SOURCE.match(/https?:\/\/[^\s"'`]+/g) || [];
  assert.ok(urls.length > 0, "应有转发出口");
  for (const u of urls) {
    assert.ok(u.startsWith("http://127.0.0.1:"), `外发地址必须是本机回环：${u}`);
    assert.ok(u.includes("/hooks/dsh") || u.includes("/action/pending"), `外发路径只许 /hooks/dsh 或 /action/pending：${u}`);
  }
});

test("版本门槛声明：DSH >= 0.2.0-rc.2（package.json 同步）", () => {
  assert.equal(DSH_MIN_VERSION, "0.2.0-rc.2");
  const manifest = JSON.parse(readFileSync(join(HERE, "package.json"), "utf8"));
  assert.equal(manifest.dsh.engines.dsh, `>=${DSH_MIN_VERSION}`);
});

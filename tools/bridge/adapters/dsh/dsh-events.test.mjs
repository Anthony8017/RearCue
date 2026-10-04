/**
 * 宿主侧事件 → 钩子体 映射判例（票 #181）：形状全部按真机 DSH 0.2.0-rc.2 的证据写，
 * 不是按猜的字段。覆盖 session/created（含历史回放）、disposed、agent/*、session/event
 * 的正文与活动、agent/assistant-stream 的正文增量、两条 waterfall 的摘要与选项，
 * 以及桥侧 mapDshHookToPatch 的新增 session-summary 分支。
 * 跑法：node --test tools/bridge/adapters/dsh/dsh-events.test.mjs
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  dshRemovalFromHook,
  errorText,
  hookBodiesFor,
  mapDshHookToPatch,
  normalizeDshStatus,
  questionAskOf,
  sanitizeOptions,
  SUBSCRIBED_EVENTS,
  textOfContent,
} from "./dsh-events.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));

/** 真机 Session 替身：id/header.cwd/deriveMessages 三件套（只读面）。 */
function fakeSession({ id = "s-1", cwd = "C:\\work", messages = [] } = {}) {
  return { id, header: { id, cwd, createdAt: 1 }, deriveMessages: () => messages };
}

const textBlock = (text) => ({ type: "text", text });
const userMessage = (content) => ({ id: "m1", role: "user", content, source: { kind: "user" } });
const assistantMessage = (content) => ({ id: "m2", role: "assistant", content, source: { kind: "model" } });

// ---- 会话生命周期 ----

test("session/created：注册在册＋工作区，并从 deriveMessages 回放历史", () => {
  const bodies = hookBodiesFor(
    "session/created",
    fakeSession({
      messages: [
        userMessage([textBlock("帮我看看构建")]),
        assistantMessage([{ type: "reasoning", text: "思考不该上屏" }, textBlock("已经修好了")]),
      ],
    }),
  );
  assert.deepEqual(bodies, [
    { event: "session-added", sessionId: "s-1", workspace: "C:\\work" },
    { event: "user-message", sessionId: "s-1", userText: "帮我看看构建" },
    { event: "assistant-message", sessionId: "s-1", assistantText: "已经修好了" },
  ]);
});

test("session/created：deriveMessages 抛错/缺 id 时不炸（容错契约）", () => {
  const broken = {
    id: "s-2",
    header: {},
    deriveMessages: () => {
      throw new Error("boom");
    },
  };
  assert.deepEqual(hookBodiesFor("session/created", broken), [{ event: "session-added", sessionId: "s-2" }]);
  assert.deepEqual(hookBodiesFor("session/created", { header: {} }), []);
});

test("session/disposed：session → session-removed；无 id 跳过", () => {
  assert.deepEqual(hookBodiesFor("session/disposed", fakeSession({ id: "s-3" })), [
    { event: "session-removed", sessionId: "s-3" },
  ]);
  assert.deepEqual(hookBodiesFor("session/disposed", {}), []);
});

test("agent/created：agent.id 露面为 session-added", () => {
  assert.deepEqual(hookBodiesFor("agent/created", { agent: { id: "s-4" } }), [
    { event: "session-added", sessionId: "s-4" },
  ]);
});

test("agent/status：真机两值 running|idle → working|idle；未知词跳过", () => {
  assert.deepEqual(hookBodiesFor("agent/status", { agent: { id: "s-5" }, status: "running" }), [
    { event: "session-status", sessionId: "s-5", status: "working" },
  ]);
  assert.deepEqual(hookBodiesFor("agent/status", { agent: { id: "s-5" }, status: "idle" }), [
    { event: "session-status", sessionId: "s-5", status: "idle" },
  ]);
  assert.deepEqual(hookBodiesFor("agent/status", { agent: { id: "s-5" }, status: "waiting" }), []);
});

test("agent/error：错误链文本进摘要（cause 追加、截 200）", () => {
  const cause = new Error("connection reset");
  const error = new Error("请求失败", { cause });
  assert.deepEqual(hookBodiesFor("agent/error", { agent: { id: "s-6" }, error }), [
    { event: "session-error", sessionId: "s-6", summary: "请求失败: connection reset" },
  ]);
  const long = new Error("x".repeat(500));
  const body = hookBodiesFor("agent/error", { agent: { id: "s-6" }, error: long })[0];
  assert.equal(body.summary.length, 200);
});

// ---- 会话事件（正文与活动） ----

test("session/event user/message：data 就是 UserMessage，取文本块", () => {
  const bodies = hookBodiesFor("session/event", fakeSession(), {
    type: "user/message",
    seq: 7,
    time: 1,
    data: userMessage([textBlock("跑一下测试")]),
  });
  assert.deepEqual(bodies, [{ event: "user-message", sessionId: "s-1", userText: "跑一下测试" }]);
});

test("session/event assistant/message：data.message.content 取文本（推理块不上屏）", () => {
  const bodies = hookBodiesFor("session/event", fakeSession(), {
    type: "assistant/message",
    seq: 8,
    time: 2,
    data: {
      turn: 1,
      step: 1,
      message: assistantMessage([
        { type: "reasoning", text: "内心戏" },
        textBlock("改完了"),
        { type: "tool-call", id: "c1", name: "read", arguments: "{}" },
        textBlock("，请过目"),
      ]),
    },
  });
  assert.deepEqual(bodies, [{ event: "assistant-message", sessionId: "s-1", assistantText: "改完了，请过目" }]);
});

test("session/event tool/call → 活动行；turn/start → working；session/title → 摘要", () => {
  assert.deepEqual(hookBodiesFor("session/event", fakeSession(), { type: "tool/call", data: { name: "grep" } }), [
    {
      event: "session-activity",
      sessionId: "s-1",
      currentAction: "工具 grep",
      toolName: "grep",
      toolSummary: "工具 grep",
      toolDetail: '{"name":"grep"}',
    },
  ]);
  assert.deepEqual(hookBodiesFor("session/event", fakeSession(), { type: "turn/start", data: { turn: 1 } }), [
    { event: "session-status", sessionId: "s-1", status: "working" },
  ]);
  assert.deepEqual(hookBodiesFor("session/event", fakeSession(), { type: "session/title", data: { title: "修构建" } }), [
    { event: "session-summary", sessionId: "s-1", summary: "修构建" },
  ]);
});

test("session/event：未知类型、缺 session、空文本一律空数组", () => {
  assert.deepEqual(hookBodiesFor("session/event", fakeSession(), { type: "todo/write", data: {} }), []);
  assert.deepEqual(
    hookBodiesFor("session/event", {}, { type: "user/message", data: userMessage([textBlock("x")]) }),
    [],
  );
  assert.deepEqual(
    hookBodiesFor("session/event", fakeSession(), { type: "user/message", data: userMessage([{ type: "image" }]) }),
    [],
  );
});

test("agent/assistant-stream：只认 chunk 的 text-delta 帧", () => {
  const args = (frame) => [{ agent: { id: "s-9" }, frame }];
  assert.deepEqual(hookBodiesFor("agent/assistant-stream", ...args({ type: "start" })), []);
  assert.deepEqual(
    hookBodiesFor("agent/assistant-stream", ...args({ type: "chunk", chunk: { type: "reasoning-delta", text: "想" } })),
    [],
  );
  assert.deepEqual(
    hookBodiesFor("agent/assistant-stream", ...args({ type: "chunk", chunk: { type: "text-delta", text: "你" } })),
    [{ event: "assistant-delta", sessionId: "s-9", assistantDelta: "你" }],
  );
  assert.deepEqual(hookBodiesFor("agent/assistant-stream", ...args({ type: "end" })), []);
});

// ---- 两条 waterfall ----

test("approval/request：摘要按 reason → displayReason → 工具名 退化（真机 req 无工具参数）", () => {
  assert.deepEqual(
    hookBodiesFor("approval/request", { agent: { id: "s-7" }, toolName: "bash", reason: "要删临时目录" }),
    [{ event: "approval-request", sessionId: "s-7", summary: "要删临时目录" }],
  );
  assert.deepEqual(
    hookBodiesFor("approval/request", { agent: { id: "s-7" }, toolName: "bash", displayReason: { en: "Run rm" } }),
    [{ event: "approval-request", sessionId: "s-7", summary: "Run rm" }],
  );
  assert.deepEqual(hookBodiesFor("approval/request", { agent: { id: "s-7" }, toolName: "bash" }), [
    { event: "approval-request", sessionId: "s-7", summary: "工具 bash" },
  ]);
  assert.deepEqual(hookBodiesFor("approval/request", { toolName: "bash" }), []);
});

test("user-questions/request：摘要＝问题，选项以 label 当 id（真机选项无 id）", () => {
  const req = {
    agent: { id: "s-8" },
    questions: [
      {
        id: "q-1",
        question: "走哪条路？",
        header: "路线",
        options: [{ label: "甲案", description: "快" }, { label: "乙案" }],
      },
    ],
  };
  const [body] = hookBodiesFor("user-questions/request", req);
  assert.equal(body.event, "question-request");
  assert.equal(body.sessionId, "s-8");
  assert.equal(body.summary, "走哪条路？");
  assert.deepEqual(body.options, [
    { id: "甲案", label: "甲案", description: "快" },
    { id: "乙案", label: "乙案" },
  ]);
  const ask = questionAskOf(req);
  assert.equal(ask.questionId, "q-1");
  assert.equal(ask.multiSelect, false);
});

test("questionAskOf：计划评审的 intent.approve 留在 ask 上（同意＝选该 label）", () => {
  const ask = questionAskOf({
    agent: { id: "s-8" },
    questions: [
      {
        id: "q-2",
        question: "批准这个计划？",
        options: [{ label: "批准" }, { label: "再改" }],
        intent: { kind: "plan-review", approve: "批准" },
      },
    ],
  });
  assert.equal(ask.planApproveLabel, "批准");
});

// ---- 正文与状态工具 ----

test("textOfContent：多文本块拼接、空块跳过、超长截断", () => {
  assert.equal(textOfContent([textBlock("甲"), { type: "reasoning", text: "乙" }, textBlock("丙")]), "甲丙");
  assert.equal(textOfContent([{ type: "image" }]), null);
  assert.equal(textOfContent("不是数组"), null);
  assert.equal(textOfContent([textBlock("a".repeat(9000))]).length, 8000);
});

test("errorText：cause 链、非 Error 的 message 字段、环", () => {
  const a = new Error("外层");
  a.cause = new Error("内层");
  assert.equal(errorText(a), "外层: 内层");
  assert.equal(errorText({ message: "朴素对象" }), "朴素对象");
  const cyclic = new Error("环");
  cyclic.cause = cyclic;
  // 与宿主 errorChain 同一条口径：自指 cause 渲染为 '<circular cause>' 而不是丢弃整条链。
  assert.equal(errorText(cyclic), "环: <circular cause>");
  assert.equal(errorText(null), null);
});

test("normalizeDshStatus：词表归一与未知词 null", () => {
  assert.equal(normalizeDshStatus("running"), "working");
  assert.equal(normalizeDshStatus("needs-input"), "waiting");
  assert.equal(normalizeDshStatus("done"), "idle");
  assert.equal(normalizeDshStatus("crashed"), "error");
  assert.equal(normalizeDshStatus("???"), null);
});

test("sanitizeOptions：字符串与对象都收，坏条目跳过，上限 8", () => {
  assert.deepEqual(sanitizeOptions(["甲", { label: "乙" }, {}, 1, null]), [
    { id: "甲", label: "甲" },
    { id: "乙", label: "乙" },
  ]);
  assert.equal(sanitizeOptions(Array.from({ length: 12 }, (_, i) => `o${i}`)).length, 8);
  assert.deepEqual(sanitizeOptions("不是数组"), []);
});

// ---- 桥侧钩子体 → 统一补丁 ----

test("mapDshHookToPatch：session-summary 只更新标题/摘要、不动状态", () => {
  // 标题同时进 `title`（issue #306）：手机侧主行链只认 title，只填 summary 会退「未命名会话」。
  assert.deepEqual(mapDshHookToPatch({ event: "session-summary", sessionId: "s-1", summary: "修构建" }), {
    sessionId: "s-1",
    source: "dsh",
    summary: "修构建",
    title: "修构建",
  });
  assert.equal(mapDshHookToPatch({ event: "session-summary", sessionId: "s-1" }), null);
});

test("mapDshHookToPatch：提问带 label 选项进 pendingOptions；移除事件不走这里", () => {
  const patch = mapDshHookToPatch({
    event: "question-request",
    sessionId: "s-1",
    summary: "走哪条路？",
    options: [{ id: "甲案", label: "甲案" }],
  });
  assert.equal(patch.status, "waiting");
  assert.deepEqual(patch.pendingOptions, [{ id: "甲案", label: "甲案" }]);
  assert.equal(mapDshHookToPatch({ event: "session-removed", sessionId: "s-1" }), null);
  const removal = dshRemovalFromHook({ event: "session-removed", sessionId: "s-1", generation: 4, revision: 9 });
  assert.equal(removal.sessionId, "s-1");
  assert.equal(removal.fact.kind, "membership");
  assert.equal(removal.fact.membership, "ABSENT");
  assert.equal(removal.fact.reason, "source-removed");
});

test("订阅面与真机一致：9 个宿主事件名，不含客户端转发面", () => {
  assert.deepEqual(SUBSCRIBED_EVENTS, [
    "session/created",
    "session/disposed",
    "agent/created",
    "agent/status",
    "agent/error",
    "session/event",
    "agent/assistant-stream",
    "approval/request",
    "user-questions/request",
  ]);
  const source = readFileSync(join(HERE, "dsh-events.mjs"), "utf8");
  // 只看代码行（注释里会提到客户端转发面作为背景说明，不算依赖）。
  // 先去掉行尾 \r：Windows 检出（autocrlf）会带 CRLF，`.` 不匹配 \r 会让行注释剥不干净。
  const code = source
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.replace(/\/\/.*$/, ""))
    .join("\n")
    .replace(/\/\*[\s\S]*?\*\//g, "");
  assert.equal(/api-session\//.test(code), false, "不得再依赖客户端转发面的事件名");
});

// ---- 注入上下文与通知（issue #307）----

/** 真机形状的消息：`source.kind` 是唯一判据（本地 user 与远端 user-rpc 都是 `user`）。 */
const sourcedMessage = (content, source) => ({ id: "m", role: "user", content, source });
const eventOf = (data) => ({ type: "user/message", seq: 1, time: 1, data });

test("上下文注入不当机主提问：snapshot/catalog/instructions 一律不上屏", () => {
  const injects = [
    [{ kind: "runtime-context", form: "snapshot", sections: [] }, "Current runtime context. This snapshot supersedes earlier runtime-context snapshots."],
    [{ kind: "skill-catalog", form: "catalog", entries: [] }, "<system-reminder>\nA skill is a reusable set of task-specific instructions…"],
    [{ kind: "agent-instructions", form: "instructions", changes: [] }, "<system-reminder>\nThe following workspace instructions may be relevant…"],
    [{ kind: "skill-invocation", name: "tdd", form: "instructions" }, '<skill_content name="tdd">…</skill_content>'],
    [{ kind: "user-approval" }, 'The approval policy changed from "never" to "ask" (changed by the user).'],
  ];
  for (const [source, text] of injects) {
    assert.deepEqual(hookBodiesFor("session/event", fakeSession(), eventOf(sourcedMessage([textBlock(text)], source))), []);
  }
});

test("注入上下文的历史回放同样不上屏（skill-catalog 超长截断也不例外）", () => {
  const bodies = hookBodiesFor(
    "session/created",
    fakeSession({
      messages: [
        sourcedMessage([textBlock("<system-reminder>\n技能清单…")], { kind: "skill-catalog", form: "catalog" }),
        sourcedMessage([textBlock("Current runtime context. …")], { kind: "runtime-context", form: "snapshot" }),
        sourcedMessage([textBlock("帮我把背屏文字靠左对齐")], { kind: "user", rpcId: "r1" }),
        assistantMessage([textBlock("已经改好了")]),
      ],
    }),
  );
  assert.deepEqual(bodies, [
    { event: "session-added", sessionId: "s-1", workspace: "C:\\work" },
    { event: "user-message", sessionId: "s-1", userText: "帮我把背屏文字靠左对齐" },
    { event: "assistant-message", sessionId: "s-1", assistantText: "已经改好了" },
  ]);
});

test("通知类注入 → session-notice 一行提示＋原文（agent-message 取冒号后首行）", () => {
  const bodies = hookBodiesFor(
    "session/event",
    fakeSession(),
    eventOf(sourcedMessage(
      [textBlock("Agent 4a58458a-06da-4086-a060-932b97e557aa sent a message: 盘点做完了\n\n## 明细\n……")],
      { kind: "agent-message", form: "relay", senderSessionId: "4a58458a" },
    )),
  );
  assert.equal(bodies.length, 1);
  assert.equal(bodies[0].event, "session-notice");
  assert.equal(bodies[0].sessionId, "s-1");
  assert.equal(bodies[0].noticeText, "收到任务消息 · 盘点做完了");
  assert.match(bodies[0].noticeDetail, /^Agent 4a58458a/);
});

test("通知类注入：subagent-settled 取结束语、tool-jobs 取来源摘要；超长摘要截断", () => {
  const settled = hookBodiesFor("session/event", fakeSession(), eventOf(sourcedMessage(
    [textBlock("Background subagent 23399ece finished and will do no further work unless you send it more.Its closing message:盘点完成，完整结果已发给父代理。要点：\n\n1. …")],
    { kind: "subagent-settled", form: "notice", summary: "Background subagent 23399ece finished…" },
  )))[0];
  assert.equal(settled.noticeText, "子任务状态更新 · 盘点完成，完整结果已发给父代理。要点：");

  const job = hookBodiesFor("session/event", fakeSession(), eventOf(sourcedMessage(
    [textBlock("background job pwsh-5 (pwsh: .\\gradlew test) finished [status: completed, exit code: 1]. Read its output with job_output.")],
    { kind: "tool-jobs", form: "notice", summary: "pwsh .\\gradlew test --console=plain -q 2>&1 | Select-Object -Last 30 [status: completed, exit code: 1]" },
  )))[0];
  assert.ok(job.noticeText.startsWith("后台任务状态更新 · pwsh .\\gradlew test"), job.noticeText);
  assert.ok(job.noticeText.endsWith("…"), `超长摘要截到一行：${job.noticeText}`);
  assert.equal(job.noticeDetail, "background job pwsh-5 (pwsh: .\\gradlew test) finished [status: completed, exit code: 1]. Read its output with job_output.");
});

test("通知：来源没给摘要时退回正文首行；无正文则只剩标签", () => {
  const onlyLabel = hookBodiesFor("session/event", fakeSession(), eventOf(
    sourcedMessage([textBlock("model switched to x")], { kind: "model-selection", form: "notice" }),
  ))[0];
  assert.equal(onlyLabel.noticeText, "模型已切换 · model switched to x");

  const empty = hookBodiesFor("session/event", fakeSession(), eventOf(
    sourcedMessage([{ type: "image" }], { kind: "tool-jobs", form: "notice", summary: "pwsh-1 [status: completed]" }),
  ))[0];
  assert.equal(empty.noticeText, "后台任务状态更新 · pwsh-1 [status: completed]");
  assert.equal(empty.noticeDetail, undefined);
});

test("机主答复（user-question-reply）仍按提问上屏", () => {
  assert.deepEqual(
    hookBodiesFor("session/event", fakeSession(), eventOf(
      sourcedMessage([textBlock("甲案")], { kind: "user-question-reply", callId: "c1", outcome: "answered" }),
    )),
    [{ event: "user-message", sessionId: "s-1", userText: "甲案" }],
  );
});

test("source 整块缺失（老引擎形状）按真实提问保留，但仍过票 #248 的文本判据", () => {
  assert.deepEqual(
    hookBodiesFor("session/event", fakeSession(), eventOf({ id: "m", role: "user", content: [textBlock("老形状的真提问")] })),
    [{ event: "user-message", sessionId: "s-1", userText: "老形状的真提问" }],
  );
  assert.deepEqual(
    hookBodiesFor("session/event", fakeSession(), eventOf(
      { id: "m", role: "user", content: [textBlock("<system-reminder>\nhidden reminder\n</system-reminder>")] },
    )),
    [],
  );
});

test("mapDshHookToPatch：session-notice 只带通知字段，不动状态（不给 status）", () => {
  const patch = mapDshHookToPatch({
    event: "session-notice",
    sessionId: "s-1",
    noticeText: "子任务状态更新 · 盘点完成",
    noticeDetail: "Background subagent …",
  });
  assert.deepEqual(patch, {
    sessionId: "s-1",
    source: "dsh",
    noticeText: "子任务状态更新 · 盘点完成",
    noticeDetail: "Background subagent …",
  });
  assert.equal("status" in patch, false, "通知不是机主提问、也不代表在干活：由桥回填该会话最新状态");
  assert.equal(mapDshHookToPatch({ event: "session-notice", sessionId: "s-1" }), null);
});


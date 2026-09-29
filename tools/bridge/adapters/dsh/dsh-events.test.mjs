// DSH 纯映射判例（票 #171）：node --test tools/bridge/adapters/dsh/dsh-events.test.mjs
// fixture 全测两段映射：官方事件 → 钩子体（插件侧）→ 统一会话事件补丁（桥侧）。
// 本机无可连 DSH（ADR 0010：真机联调归 #178），所以这里用官方事件形状做 fixture 锁语义。
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  dshEventToHookBody,
  dshRemovalFromHook,
  mapDshHookToPatch,
  normalizeDshStatus,
  SUBSCRIBED_EVENTS,
  HOOK_EVENTS,
} from "./dsh-events.mjs";

// ---- 官方事件 → 钩子体（插件侧） ----

test("api-session/added → session-added（session 对象与平铺两形态都认）", () => {
  assert.deepEqual(
    dshEventToHookBody("api-session/added", { session: { id: "s-1", cwd: "C:/work/repo" } }),
    { event: "session-added", sessionId: "s-1", workspace: "C:/work/repo" },
  );
  assert.deepEqual(
    dshEventToHookBody("api-session/added", { sessionId: "s-2", workspace: "D:/w" }),
    { event: "session-added", sessionId: "s-2", workspace: "D:/w" },
  );
  assert.equal(dshEventToHookBody("api-session/added", {}), null, "缺 sessionId 不猜");
});

test("api-session/status → session-status 原词搬运；缺 status 不发", () => {
  assert.deepEqual(
    dshEventToHookBody("api-session/status", { sessionId: "s-1", status: "running" }),
    { event: "session-status", sessionId: "s-1", status: "running" },
  );
  assert.equal(dshEventToHookBody("api-session/status", { sessionId: "s-1" }), null);
});

test("approval/request 与 user-questions/request → 等待钩子体（summary 留位）", () => {
  assert.deepEqual(
    dshEventToHookBody("approval/request", { sessionId: "s-1", title: "想修改 xx 文件" }),
    { event: "approval-request", sessionId: "s-1", summary: "想修改 xx 文件" },
  );
  assert.deepEqual(
    dshEventToHookBody("user-questions/request", { session_id: "s-2", summary: "选哪个方案" }),
    { event: "question-request", sessionId: "s-2", summary: "选哪个方案" },
  );
  assert.equal(dshEventToHookBody("approval/request", { title: "没有会话" }), null);
});

test("等待摘要退化（票 #172）：提问/批准正文顶上当一句话摘要", () => {
  // 提问正文（p.question）→ summary；{text}/{content} 包装也认。
  assert.deepEqual(
    dshEventToHookBody("user-questions/request", { session_id: "s-2", question: "选哪个方案" }),
    { event: "question-request", sessionId: "s-2", summary: "选哪个方案" },
  );
  assert.deepEqual(
    dshEventToHookBody("user-questions/request", { sessionId: "s-3", question: { text: "要不要重建索引" } }),
    { event: "question-request", sessionId: "s-3", summary: "要不要重建索引" },
  );
  // 显式 summary 优先于正文；批准缺省退化用请求正文（要干什么）。
  assert.equal(
    dshEventToHookBody("user-questions/request", { sessionId: "s-4", question: "正文", summary: "显式摘要" }).summary,
    "显式摘要",
  );
  assert.deepEqual(
    dshEventToHookBody("approval/request", { sessionId: "s-5", action: "要执行 bash rm -rf build" }),
    { event: "approval-request", sessionId: "s-5", summary: "要执行 bash rm -rf build" },
  );
  // 长文截 200（防通知行长文）；什么正文都没有则不造空键。
  const long = dshEventToHookBody("user-questions/request", { sessionId: "s-6", question: "x".repeat(300) });
  assert.equal(long.summary.length, 200);
  assert.equal("summary" in dshEventToHookBody("user-questions/request", { sessionId: "s-7" }), false);
});

test("会话流：user/message、assistant/message（整段与 delta 两态）、assistant/attempt", () => {
  assert.deepEqual(
    dshEventToHookBody("user/message", { sessionId: "s-1", text: "帮我跑测试" }),
    { event: "user-message", sessionId: "s-1", userText: "帮我跑测试" },
  );
  assert.deepEqual(
    dshEventToHookBody("assistant/message", { sessionId: "s-1", text: "跑完了" }),
    { event: "assistant-message", sessionId: "s-1", assistantText: "跑完了" },
  );
  assert.deepEqual(
    dshEventToHookBody("assistant/message", { sessionId: "s-1", delta: "流式中间批" }),
    { event: "assistant-delta", sessionId: "s-1", assistantDelta: "流式中间批" },
  );
  assert.deepEqual(
    dshEventToHookBody("assistant/attempt", { sessionId: "s-1", delta: "第一段" }),
    { event: "assistant-delta", sessionId: "s-1", assistantDelta: "第一段" },
  );
  assert.equal(dshEventToHookBody("user/message", { sessionId: "s-1" }), null, "空正文不发");
});

test("turn/start → working；tool/result → session-activity 带动作摘要", () => {
  assert.deepEqual(
    dshEventToHookBody("turn/start", { sessionId: "s-1" }),
    { event: "session-status", sessionId: "s-1", status: "working" },
  );
  const activity = dshEventToHookBody("tool/result", {
    sessionId: "s-1",
    toolName: "edit",
    input: "src/App.kt",
    cwd: "C:/work/repo",
  });
  assert.equal(activity.event, "session-activity");
  assert.equal(activity.currentAction, "edit src/App.kt");
  assert.equal(activity.workspace, "C:/work/repo");
});

test("api-session/error → session-error 透传；未知事件名 → null", () => {
  assert.deepEqual(
    dshEventToHookBody("api-session/error", { sessionId: "s-1", summary: "工具崩了" }),
    { event: "session-error", sessionId: "s-1", summary: "工具崩了" },
  );
  assert.equal(dshEventToHookBody("goal/activation-changed", { sessionId: "s-1" }), null);
  assert.equal(dshEventToHookBody("api-session/added", null), null, "坏载荷不抛");
});

// ---- 钩子体 → 统一会话事件补丁（桥侧） ----

test("mapDshHookToPatch：注册/状态归一/未知状态词跳过", () => {
  assert.deepEqual(mapDshHookToPatch({ event: "session-added", sessionId: "s-1", workspace: "C:/w" }), {
    sessionId: "s-1",
    source: "dsh",
    workspace: "C:/w",
    status: "idle",
  });
  assert.equal(mapDshHookToPatch({ event: "session-status", sessionId: "s-1", status: "nonsense" }), null);
  assert.equal(mapDshHookToPatch({ event: "session-status", status: "working" }), null, "缺 sessionId 不猜");
  assert.equal(mapDshHookToPatch(null), null);
});

test("normalizeDshStatus：三档词表归一，未知词 null", () => {
  for (const w of ["working", "running", "busy", "active", "in_progress"]) {
    assert.equal(normalizeDshStatus(w), "working", w);
  }
  for (const w of ["waiting", "attention", "approval", "needs_input", "blocked"]) {
    assert.equal(normalizeDshStatus(w), "waiting", w);
  }
  for (const w of ["idle", "done", "completed", "finished", "stopped"]) {
    assert.equal(normalizeDshStatus(w), "idle", w);
  }
  assert.equal(normalizeDshStatus("dancing"), null);
  assert.equal(normalizeDshStatus(""), null);
  assert.equal(normalizeDshStatus(undefined), null);
});

test("mapDshHookToPatch：问答流补丁（提问/整段回答/增量）", () => {
  assert.deepEqual(mapDshHookToPatch({ event: "user-message", sessionId: "s-1", userText: "继续" }), {
    sessionId: "s-1",
    source: "dsh",
    status: "working",
    userText: "继续",
  });
  assert.deepEqual(mapDshHookToPatch({ event: "assistant-message", sessionId: "s-1", assistantText: "完成" }), {
    sessionId: "s-1",
    source: "dsh",
    status: "working",
    assistantText: "完成",
  });
  assert.deepEqual(mapDshHookToPatch({ event: "assistant-delta", sessionId: "s-1", assistantDelta: "批" }), {
    sessionId: "s-1",
    source: "dsh",
    status: "working",
    assistantDelta: "批",
  });
  assert.equal(mapDshHookToPatch({ event: "assistant-delta", sessionId: "s-1" }), null, "空增量不发");
});

test("mapDshHookToPatch：等待两态归一 waiting；removed/error/未知返回 null", () => {
  for (const event of ["approval-request", "question-request"]) {
    assert.deepEqual(mapDshHookToPatch({ event, sessionId: "s-1", summary: "等你拍板" }), {
      sessionId: "s-1",
      source: "dsh",
      status: "waiting",
      summary: "等你拍板",
    });
  }
  assert.equal(mapDshHookToPatch({ event: "session-removed", sessionId: "s-1" }), null);
  assert.equal(mapDshHookToPatch({ event: "session-error", sessionId: "s-1" }), null);
  assert.equal(mapDshHookToPatch({ event: "who-knows", sessionId: "s-1" }), null);
});

test("摘要字段留位：带则进补丁，缺则无该键（缺省退化不破坏兼容）", () => {
  const withSummary = mapDshHookToPatch({ event: "session-status", sessionId: "s-1", status: "idle", summary: "改完了" });
  assert.equal(withSummary.summary, "改完了");
  const without = mapDshHookToPatch({ event: "session-status", sessionId: "s-1", status: "idle" });
  assert.equal("summary" in without, false, "缺省不造空键");
});

test("dshRemovalFromHook：仅认 session-removed", () => {
  assert.deepEqual(dshRemovalFromHook({ event: "session-removed", sessionId: "s-1" }), { sessionId: "s-1" });
  assert.equal(dshRemovalFromHook({ event: "session-added", sessionId: "s-1" }), null);
  assert.equal(dshRemovalFromHook({ event: "session-removed" }), null);
  assert.equal(dshRemovalFromHook(null), null);
});

test("全链 fixture：官方事件 → 钩子体 → 统一补丁", () => {
  const chain = (name, payload) => mapDshHookToPatch(dshEventToHookBody(name, payload));
  assert.deepEqual(chain("api-session/status", { sessionId: "s-9", state: "running" }), {
    sessionId: "s-9",
    source: "dsh",
    status: "working",
  });
  assert.deepEqual(chain("approval/request", { sessionId: "s-9", title: "要执行 npm install" }), {
    sessionId: "s-9",
    source: "dsh",
    status: "waiting",
    summary: "要执行 npm install",
  });
  assert.equal(chain("api-session/status", { sessionId: "s-9", status: "dancing" }), null, "未知状态词整条跳过");
});

test("订阅面与钩子词表是有限封闭集（防漂移：增删要改判例）", () => {
  assert.ok(SUBSCRIBED_EVENTS.includes("approval/request"));
  assert.ok(SUBSCRIBED_EVENTS.includes("user-questions/request"));
  assert.equal(new Set(SUBSCRIBED_EVENTS).size, SUBSCRIBED_EVENTS.length, "订阅名不重复");
  assert.equal(new Set(HOOK_EVENTS).size, HOOK_EVENTS.length, "钩子词表不重复");
});

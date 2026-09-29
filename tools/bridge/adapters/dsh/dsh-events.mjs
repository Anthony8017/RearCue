/**
 * DSH 事件 → 桥钩子体 → 统一会话事件 的纯映射（ADR 0010 / spec 0018-1，票 #171）。
 *
 * 两段各一份、都可独立判例：
 * - [dshEventToHookBody]：DSH 官方事件（api-session/*、approval/request、
 *   user-questions/request、会话流 user/message 等）→ POST 到桥 `/hooks/dsh` 的钩子体。
 *   由只读插件（dsh-bridge-plugin.mjs）调用——**只订阅、绝不调用任何写方法**。
 * - [mapDshHookToPatch]：钩子体 → 统一会话事件的部分补丁（bridge.mjs 的 mapHookToPatch
 *   把 source=dsh 委托到这里），问答流增量交给桥的会话窗口攒。
 *
 * 本票边界（#171）：working / idle + 问答流；waiting 只归一状态（插队语义归 #172）；
 * 「摘要」字段在钩子体与补丁里**留位**（#173/#174 用），缺省即退化、不破坏兼容。
 * 出错事件（api-session/error）只透传到钩子体，桥侧本票忽略（202）——出错提醒归 #173。
 *
 * 会话退出（api-session/removed）不是状态补丁：[dshRemovalFromHook] 单独判定，
 * 桥侧把会话摘出在册快照（手机重连对账据此清锁——「电脑端消失自动清锁」的桥侧前提）。
 *
 * 容错契约与 codex/claude 同族：坏载荷 / 缺 sessionId / 未知状态词 → 返回 null（调用方
 * 跳过该条），不抛、不猜。状态词表按 DSH 运行状态的常见词形归一到 working|waiting|idle。
 */

/** 插件订阅的 DSH 官方事件名（ADR 0010 调研 + session-controller README 的 SessionEvent 流）。 */
export const SUBSCRIBED_EVENTS = [
  "api-session/added",
  "api-session/removed",
  "api-session/status",
  "api-session/activity",
  "api-session/error",
  "approval/request",
  "user-questions/request",
  "turn/start",
  "user/message",
  "assistant/message",
  "assistant/attempt",
  "tool/result",
];

/** 钩子体事件词表（插件 → 桥的私有契约；桥侧 [mapDshHookToPatch] 只认这些）。 */
export const HOOK_EVENTS = [
  "session-added",
  "session-removed",
  "session-status",
  "session-activity",
  "session-error",
  "user-message",
  "assistant-message",
  "assistant-delta",
  "approval-request",
  "question-request",
];

const WORKING_WORDS = new Set(["working", "running", "busy", "active", "in_progress", "in-progress"]);
const WAITING_WORDS = new Set(["waiting", "attention", "approval", "question", "blocked", "needs_input", "needs-input"]);
const IDLE_WORDS = new Set(["idle", "done", "completed", "complete", "finished", "stopped", "inactive"]);

/** DSH 运行状态词 → 桥统一词表（working|waiting|idle）；未知词返回 null（该条跳过）。 */
export function normalizeDshStatus(raw) {
  const word = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  if (!word) return null;
  if (WORKING_WORDS.has(word)) return "working";
  if (WAITING_WORDS.has(word)) return "waiting";
  if (IDLE_WORDS.has(word)) return "idle";
  return null;
}

/** 取第一个非空白字符串；都不合格返回 null。 */
function firstString(...candidates) {
  for (const c of candidates) {
    if (typeof c === "string" && c.trim()) return c.trim();
  }
  return null;
}

/** 正文取值：字符串直取；{text}/{content} 形态的包一层也认（官方载荷两种都出现过）。 */
function textOf(...candidates) {
  for (const c of candidates) {
    if (typeof c === "string" && c.trim()) return c.trim();
    if (c && typeof c === "object") {
      const nested = firstString(c.text, c.content, c.value);
      if (nested) return nested;
    }
  }
  return null;
}

/** 会话 id 的可能藏身处（平铺或 session 对象里；字段名跨版本漂移，逐个认）。 */
function sessionIdOf(payload) {
  const p = payload && typeof payload === "object" ? payload : {};
  const s = p.session && typeof p.session === "object" ? p.session : p;
  return firstString(s.sessionId, s.session_id, s.id, p.sessionId, p.session_id, p.id);
}

function workspaceOf(payload) {
  const p = payload && typeof payload === "object" ? payload : {};
  const s = p.session && typeof p.session === "object" ? p.session : p;
  return firstString(s.workspace, s.cwd, s.workingDirectory, p.workspace, p.cwd);
}

/**
 * DSH 官方事件 → 钩子体（POST `/hooks/dsh` 的载荷）。无法识别返回 null（不转发）。
 * 只做**形状搬运**：状态词归一留给桥侧（[mapDshHookToPatch]）一处收口。
 */
export function dshEventToHookBody(name, payload) {
  const p = payload && typeof payload === "object" ? payload : {};
  const sessionId = sessionIdOf(payload);
  const workspace = workspaceOf(payload);
  const summary = firstString(p.summary, p.title, p.description);

  switch (name) {
    case "api-session/added": {
      if (!sessionId) return null;
      const body = { event: "session-added", sessionId };
      if (workspace) body.workspace = workspace;
      if (summary) body.summary = summary;
      return body;
    }
    case "api-session/removed": {
      return sessionId ? { event: "session-removed", sessionId } : null;
    }
    case "api-session/status": {
      const status = firstString(p.status, p.state);
      if (!sessionId || !status) return null;
      const body = { event: "session-status", sessionId, status };
      if (workspace) body.workspace = workspace;
      if (summary) body.summary = summary;
      return body;
    }
    case "api-session/activity": {
      if (!sessionId) return null;
      const body = { event: "session-activity", sessionId };
      const action = firstString(p.currentAction, p.action, p.activity);
      if (action) body.currentAction = action.slice(0, 200);
      if (workspace) body.workspace = workspace;
      if (summary) body.summary = summary;
      return body;
    }
    case "api-session/error": {
      // 出错只透传到钩子体（#173 的提醒输入）；桥侧本票忽略。
      if (!sessionId) return null;
      const body = { event: "session-error", sessionId };
      if (summary) body.summary = summary;
      return body;
    }
    case "approval/request": {
      if (!sessionId) return null;
      const body = { event: "approval-request", sessionId };
      if (summary) body.summary = summary;
      return body;
    }
    case "user-questions/request": {
      if (!sessionId) return null;
      const body = { event: "question-request", sessionId };
      if (summary) body.summary = summary;
      return body;
    }
    case "turn/start": {
      return sessionId ? { event: "session-status", sessionId, status: "working" } : null;
    }
    case "user/message": {
      const text = textOf(p.text, p.message, p.content, p.userText);
      return sessionId && text ? { event: "user-message", sessionId, userText: text } : null;
    }
    case "assistant/message": {
      if (!sessionId) return null;
      // 带 delta 的是流式中间批（当增量），整段 text 是完整助手输出。
      const delta = firstString(p.delta);
      if (delta) return { event: "assistant-delta", sessionId, assistantDelta: delta };
      const text = textOf(p.text, p.message, p.content, p.assistantText);
      return text ? { event: "assistant-message", sessionId, assistantText: text } : null;
    }
    case "assistant/attempt": {
      const delta = firstString(p.delta, p.text);
      return sessionId && delta ? { event: "assistant-delta", sessionId, assistantDelta: delta } : null;
    }
    case "tool/result": {
      if (!sessionId) return null;
      const tool = firstString(p.toolName, p.name, p.tool);
      const input = firstString(p.input, p.args, p.summary);
      const action = `${tool || "tool"}${input ? ` ${input}` : ""}`.trim().slice(0, 200);
      const body = { event: "session-activity", sessionId, currentAction: action };
      if (workspace) body.workspace = workspace;
      return body;
    }
    default:
      return null;
  }
}

/** 会话退出判定：`session-removed` → {sessionId}；其余 null。桥侧据此把会话摘出在册快照。 */
export function dshRemovalFromHook(body) {
  if (!body || typeof body !== "object") return null;
  if (body.event !== "session-removed") return null;
  const sessionId = firstString(body.sessionId, body.session_id);
  return sessionId ? { sessionId } : null;
}

/**
 * 钩子体 → 统一会话事件的部分补丁（缺字段由 bridge.mjs 的 appendEvent 按会话最新态回填）。
 * 会话退出走 [dshRemovalFromHook]，这里返回 null。无法识别返回 null（调用方 202 空操作）。
 */
export function mapDshHookToPatch(body) {
  if (!body || typeof body !== "object") return null;
  const sessionId = firstString(body.sessionId, body.session_id);
  if (!sessionId) return null;
  const workspace = firstString(body.workspace, body.cwd);
  const summary = firstString(body.summary);
  const patch = { sessionId, source: "dsh" };
  if (workspace) patch.workspace = workspace;
  if (summary) patch.summary = summary;

  switch (body.event) {
    case "session-added":
      // 注册在册：还没跑起来就按 idle 先进会话列表（有在册会话即显示）。
      patch.status = "idle";
      return patch;
    case "session-status": {
      const status = normalizeDshStatus(body.status);
      if (!status) return null; // 未知状态词跳过（容错契约）
      patch.status = status;
      return patch;
    }
    case "session-activity":
      patch.status = "working";
      {
        const action = firstString(body.currentAction, body.action);
        if (action) patch.currentAction = action;
      }
      return patch;
    case "user-message": {
      const text = firstString(body.userText, body.text);
      if (!text) return null;
      patch.status = "working";
      patch.userText = text;
      return patch;
    }
    case "assistant-message": {
      const text = firstString(body.assistantText, body.text);
      if (!text) return null;
      patch.status = "working";
      patch.assistantText = text;
      return patch;
    }
    case "assistant-delta": {
      const delta = typeof body.assistantDelta === "string" && body.assistantDelta ? body.assistantDelta : null;
      if (!delta) return null;
      patch.status = "working";
      patch.assistantDelta = delta;
      return patch;
    }
    case "approval-request":
    case "question-request":
      // #171 只归一状态；插队/脉冲/光带语义沿既有 waiting 仲裁（#172 专做 DSH 细化）。
      patch.status = "waiting";
      return patch;
    default:
      return null; // 含 session-removed / session-error / 未知事件
  }
}

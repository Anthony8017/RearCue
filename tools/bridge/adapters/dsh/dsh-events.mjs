import { membershipFromDshHook } from "../source-membership.mjs";
/**
 * DSH **宿主侧**事件 → 桥钩子体 的纯映射（ADR 0010 / spec 0018-1 / 票 #181）。
 *
 * 真机联调（2026-09-30）改写了本文件的订阅面：ADR 0010 原假设的客户端转发面
 * （`ctx.remote.$on`）只转发 `api-session/*` 等**会话级**事件、且参数形状是
 * `(sessionId, running:boolean)` 这类位置参数；**对话正文根本不在转发集里**。
 * 因此订阅面整体搬到宿主侧（`ctx.on`），并按 app.asar 0.2.0-rc.2 的真机形状对齐：
 *
 * | 宿主事件 | 参数 | 本文件映射 |
 * | --- | --- | --- |
 * | `session/created` | `(session)` | `session-added` ＋ `session.deriveMessages()` 的历史回放 |
 * | `session/disposed` | `(session)` | `session-removed` |
 * | `agent/created` | `({agent})` | `session-added`（可用性露面） |
 * | `agent/status` | `({agent, status})` | `session-status`（`running`→working，`idle`→idle） |
 * | `agent/error` | `({agent, error})` | `session-error`（错误链文本截 200） |
 * | `session/event` | `(session, event)` | `user/message`→user-message；`assistant/message`→assistant-message；
 *   `tool/call`→session-activity；`turn/start`→session-status(working)；`session/title`→session-summary |
 * | `agent/assistant-stream` | `({agent, frame})` | `text-delta` 帧 → assistant-delta（流式） |
 * | `approval/request` | `(req, next)` | `approval-request`（摘要＝reason/工具名） |
 * | `user-questions/request` | `(req, next)` | `question-request`（摘要＝问题，选项＝label） |
 *
 * 形状要点（证据见票 #181 调研）：消息正文在 `data.message.content` 的文本块
 * （`block.type === "text"` → `block.text`）；`user/message` 的 `data` 就是 UserMessage
 * 本身（没有 turn/step）；选项对象没有 id 字段，只有 `label`，而应答里的 `selected`
 * 填的就是 **label**；批准 waterfall 的规范应答值是 `'allowed-once' | 'rejected' |
 * 'cancelled' | 'unavailable'`，不是 `{decision}` 对象。
 *
 * 容错契约与 codex/claude 同族：坏载荷 / 缺 sessionId / 未知类型 → 空数组（调用方跳过），
 * 不抛、不猜。
 */

/** 钩子体事件词表（插件 → 桥的私有契约；桥侧 [mapDshHookToPatch] 只认这些）。 */
export const HOOK_EVENTS = [
  "session-added",
  "session-removed",
  "session-status",
  "session-activity",
  "session-error",
  "session-summary",
  "user-message",
  "assistant-message",
  "assistant-delta",
  "approval-request",
  "question-request",
];

/** 插件订阅的宿主事件名（真机核对 2026-09-30：全部存在于 0.2.0-rc.2 的宿主分发）。 */
export const SUBSCRIBED_EVENTS = [
  "session/created",
  "session/disposed",
  "agent/created",
  "agent/status",
  "agent/error",
  "session/event",
  "agent/assistant-stream",
  "approval/request",
  "user-questions/request",
];

/** 单条正文最长（防超长粘贴把钩子体撑爆；桥侧仍按轮次窗口二次裁剪）。 */
const MAX_TEXT = 8000;
/** 历史回放条数上限（session/created 时补历史；桥侧窗口会再裁）。 */
const MAX_HISTORY_MESSAGES = 40;

const WORKING_WORDS = new Set(["working", "running", "busy", "active", "in_progress", "in-progress"]);
const WAITING_WORDS = new Set(["waiting", "attention", "approval", "question", "blocked", "needs_input", "needs-input"]);
const IDLE_WORDS = new Set(["idle", "done", "completed", "complete", "finished", "stopped", "inactive"]);
const ERROR_WORDS = new Set(["error", "failed", "crashed"]);

/** DSH 运行状态词 → 桥统一词表（working|waiting|idle|error）；未知词返回 null（该条跳过）。 */
export function normalizeDshStatus(raw) {
  const word = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  if (!word) return null;
  if (WORKING_WORDS.has(word)) return "working";
  if (WAITING_WORDS.has(word)) return "waiting";
  if (IDLE_WORDS.has(word)) return "idle";
  if (ERROR_WORDS.has(word)) return "error";
  return null;
}

/** 取第一个非空白字符串；都不合格返回 null。 */
function firstString(...candidates) {
  for (const c of candidates) {
    if (typeof c === "string" && c.trim()) return c.trim();
  }
  return null;
}

/**
 * 文本块拼接（真机形状）：`content: readonly ContentBlock[]`，文本块判别字段 `type`、
 * 文本字段 `text`；推理/工具块不上屏（spec 0017 口径：工具结果行与思考不入镜像正文）。
 */
export function textOfContent(content) {
  if (!Array.isArray(content)) return null;
  const parts = [];
  for (const block of content) {
    if (block && typeof block === "object" && block.type === "text" && typeof block.text === "string" && block.text) {
      parts.push(block.text);
    }
  }
  const joined = parts.join("").trim();
  return joined ? joined.slice(0, MAX_TEXT) : null;
}

/** 消息 → 正文（消息对象形状：`{ role, content, source, id }`）。 */
function messageText(message) {
  if (!message || typeof message !== "object") return null;
  return textOfContent(message.content);
}

/**
 * 错误链文本（宿主 `errorChain` 的极小复刻：message → cause 链；够提醒摘要用）。
 * 只做诊断呈现，不解析结果（与宿主同一条口径）。
 */
export function errorText(error) {
  const seen = new Set();
  const walk = (value) => {
    if (value && typeof value === "object" && seen.has(value)) return "<circular cause>";
    if (value instanceof Error) {
      seen.add(value);
      const head = value.message || value.name;
      const cause = value.cause === undefined || value.cause === null ? "" : walk(value.cause);
      return cause && cause !== head ? `${head}: ${cause}` : head;
    }
    if (value && typeof value === "object" && typeof value.message === "string") return value.message;
    return typeof value === "string" ? value : String(value ?? "");
  };
  try {
    const text = walk(error).trim();
    return text ? text.slice(0, 200) : null;
  } catch {
    return null;
  }
}

/** 会话 id：session 对象 / agent 对象 / 裸 id 三种形态都认。 */
function sessionIdOfSession(session) {
  return firstString(session?.id, session?.sessionId, session?.header?.id);
}

function workspaceOfSession(session) {
  return firstString(session?.header?.cwd, session?.cwd, session?.workspace);
}

/** 批准摘要：reason/displayReason 优先，退化为工具名（真机 req 不带工具参数）。 */
function approvalSummary(req) {
  return firstString(
    req?.reason,
    req?.displayReason?.zh,
    req?.displayReason?.en,
    req?.toolName ? `工具 ${req.toolName}` : null,
  );
}

/** 提问正文与选项（选项只有 label；桥侧 pendingOptions 用 label 当 id）。 */
export function questionAskOf(req) {
  const items = Array.isArray(req?.questions) ? req.questions : [];
  const first = items.find((q) => q && typeof q === "object") ?? null;
  if (!first) return null;
  const question = firstString(first.question, first.header);
  if (!question) return null;
  const options = (Array.isArray(first.options) ? first.options : [])
    .map((o) => {
      const label = firstString(typeof o === "string" ? o : o?.label);
      if (!label) return null;
      const description = typeof o === "object" ? firstString(o?.description) : null;
      return description ? { id: label, label, description: description.slice(0, 120) } : { id: label, label };
    })
    .filter(Boolean);
  return {
    question: question.slice(0, 200),
    questionId: firstString(first.id) ?? "q1",
    options,
    multiSelect: first.multiSelect === true,
    planApproveLabel: firstString(first.intent?.approve),
  };
}

/**
 * 宿主事件 → 钩子体数组（0..n 条；session/created 会带历史回放）。
 * 参数按宿主签名原样收（`(...args)`），逐事件解构——形状差异只在本文件收口。
 */
export function hookBodiesFor(name, ...args) {
  switch (name) {
    case "session/created": {
      const session = args[0];
      const sessionId = sessionIdOfSession(session);
      if (!sessionId) return [];
      const bodies = [{ event: "session-added", sessionId }];
      const workspace = workspaceOfSession(session);
      if (workspace) bodies[0].workspace = workspace;
      // 历史回放（spec 0018-4「完整历史」的 DSH 侧）：只读 deriveMessages，条数与单条长度双封顶。
      let messages = [];
      try {
        messages = typeof session?.deriveMessages === "function" ? session.deriveMessages() : [];
      } catch {
        messages = [];
      }
      if (Array.isArray(messages) && messages.length > 0) {
        for (const message of messages.slice(-MAX_HISTORY_MESSAGES)) {
          const text = messageText(message);
          if (!text) continue;
          if (message.role === "user") bodies.push({ event: "user-message", sessionId, userText: text });
          else if (message.role === "assistant") bodies.push({ event: "assistant-message", sessionId, assistantText: text });
        }
      }
      return bodies;
    }
    case "session/disposed": {
      const sessionId = sessionIdOfSession(args[0]);
      return sessionId ? [{ event: "session-removed", sessionId }] : [];
    }
    case "agent/created": {
      const sessionId = firstString(args[0]?.agent?.id, args[0]?.id);
      return sessionId ? [{ event: "session-added", sessionId }] : [];
    }
    case "agent/status": {
      const payload = args[0] ?? {};
      const sessionId = firstString(payload.agent?.id, payload.id);
      // 真机两值：running | idle（等待态由 approval/question waterfall 表达）。
      const status = payload.status === "running" ? "working" : payload.status === "idle" ? "idle" : null;
      return sessionId && status ? [{ event: "session-status", sessionId, status }] : [];
    }
    case "agent/error": {
      const payload = args[0] ?? {};
      const sessionId = firstString(payload.agent?.id, payload.id);
      if (!sessionId) return [];
      const body = { event: "session-error", sessionId };
      const summary = errorText(payload.error);
      if (summary) body.summary = summary;
      return [body];
    }
    case "session/event": {
      const session = args[0];
      const event = args[1];
      const sessionId = sessionIdOfSession(session);
      if (!sessionId || !event || typeof event !== "object") return [];
      const data = event.data ?? {};
      switch (event.type) {
        case "user/message": {
          const text = messageText(data);
          return text ? [{ event: "user-message", sessionId, userText: text }] : [];
        }
        case "assistant/message": {
          const text = messageText(data.message);
          return text ? [{ event: "assistant-message", sessionId, assistantText: text }] : [];
        }
        case "tool/call": {
          const tool = firstString(data.name);
          if (!tool) return [];
          return [{
            event: "session-activity",
            sessionId,
            currentAction: `工具 ${tool}`,
            toolName: tool,
            toolSummary: `工具 ${tool}`,
            toolDetail: JSON.stringify(data.input ?? data.arguments ?? data),
          }];
        }
        case "tool/result":
        case "tool/completed": {
          const tool = firstString(data.name, data.toolName);
          const detail = typeof data.output === "string"
            ? data.output
            : typeof data.result === "string"
              ? data.result
              : JSON.stringify(data.output ?? data.result ?? data);
          return [{
            event: "tool-result",
            sessionId,
            toolName: tool || undefined,
            toolResultSummary: tool ? `${tool} 完成` : "工具完成",
            toolResultDetail: detail,
          }];
        }
        case "turn/start": {
          return [{ event: "session-status", sessionId, status: "working" }];
        }
        case "session/title": {
          const title = firstString(data.title);
          return title ? [{ event: "session-summary", sessionId, summary: title.slice(0, 120) }] : [];
        }
        default:
          return [];
      }
    }
    case "agent/assistant-stream": {
      const payload = args[0] ?? {};
      const sessionId = firstString(payload.agent?.id, payload.id);
      const frame = payload.frame;
      if (!sessionId || !frame || frame.type !== "chunk") return [];
      const chunk = frame.chunk;
      if (!chunk || chunk.type !== "text-delta" || typeof chunk.text !== "string" || !chunk.text) return [];
      return [{ event: "assistant-delta", sessionId, assistantDelta: chunk.text.slice(0, MAX_TEXT) }];
    }
    case "approval/request": {
      const req = args[0] ?? {};
      const sessionId = firstString(req.agent?.id, req.id);
      if (!sessionId) return [];
      const body = { event: "approval-request", sessionId };
      const summary = approvalSummary(req);
      if (summary) body.summary = summary.slice(0, 200);
      return [body];
    }
    case "user-questions/request": {
      const req = args[0] ?? {};
      const sessionId = firstString(req.agent?.id, req.id);
      const ask = questionAskOf(req);
      if (!sessionId || !ask) return [];
      const body = { event: "question-request", sessionId, summary: ask.question };
      if (ask.options.length > 0) body.options = ask.options;
      return [body];
    }
    default:
      return [];
  }
}

/** 会话退出判定：`session-removed` → 携带来源在册墓碑；其余 null。桥侧据此实时广播出册。 */
export function dshRemovalFromHook(body) {
  const fact = membershipFromDshHook(body);
  return fact && fact.membership === "ABSENT" ? { sessionId: fact.sourceSessionId, fact } : null;
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
    case "session-added": {
      // 注册在册：还没跑起来就按 idle 先进会话列表；同时发来源在册正事实。
      patch.status = "idle";
      const fact = membershipFromDshHook({ ...body, event: "session-added" });
      return fact ? { ...patch, ...fact } : patch;
    }
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
      for (const key of ["toolName", "toolSummary", "toolDetail"]) {
        if (body[key] !== undefined) patch[key] = body[key];
      }
      return patch;
    case "session-summary":
      // 会话标题（票 #181）：只更新摘要，不动状态（标题事件可能在 idle 时到）。
      return summary ? patch : null;
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
    case "tool-result": {
      patch.status = "working";
      for (const key of ["toolName", "toolResultSummary", "toolResultDetail"]) {
        if (body[key] !== undefined) patch[key] = body[key];
      }
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
      // 摘要退化（票 #172）：钩子体没带 summary 时用提问/请求正文顶上（与插件侧同一条链）。
      if (!("summary" in patch)) {
        const fallback = textOfContent(body.content) ?? firstString(body.question, body.text, body.message);
        if (fallback) patch.summary = fallback.slice(0, 200);
      }
      // 选择题选项（票 #176）：清洗后进统一事件 pendingOptions（坏条目跳过）。
      {
        const opts = sanitizeOptions(body.options);
        if (opts.length > 0) patch.pendingOptions = opts;
      }
      return patch;
    case "session-error":
      // 出错词（spec 0018-4 票 #174 顺手项）：api-session/error 是明确信号，归一为
      // error 状态（出错提醒 #173 的来源语义）；摘要照常带上。
      patch.status = "error";
      return patch;
    default:
      return null; // 含 session-removed / 未知事件
  }
}

/** 选择题选项清洗：只收 {id,label}（label 缺失用 id 顶，两者皆无跳过）；上限 8 条。 */
export function sanitizeOptions(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const item of raw) {
    if (out.length >= 8) break;
    if (typeof item === "string" && item.trim()) {
      out.push({ id: item.trim(), label: item.trim() });
      continue;
    }
    if (!item || typeof item !== "object") continue;
    const id = firstString(item.id, item.label);
    const label = firstString(item.label, item.id);
    if (!id || !label) continue;
    const option = { id, label };
    const description = firstString(item.description);
    if (description) option.description = description.slice(0, 120);
    out.push(option);
  }
  return out;
}

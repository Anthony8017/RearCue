/**
 * ZCode 桥适配器（票 #240）：把 ZCode 会话映射进桥的统一会话事件。
 *
 * 实测边界（2026-10-01）：
 * - `node zcode.cjs app-server` 的 `session/list` 可列持久化会话（sessionId/title/
 *   titleSource/status/workspace/updatedAt/archivedAt），但实测它会返回任务列表已归档的
 *   历史且 `archivedAt` 可整体缺省；归档真值改读 `tasks-index.sqlite`。`session/subscribe` 对列出的
 *   idle 会话回 `-32004 Session is not active`，且桌面端活动 app-server 是私有 stdio
 *   子进程、外部不可接管。因此订阅只是机会性增强，不能当实时主链路，更不能为了看历史
 *   去 `session/resume` 把用户会话逐个激活。
 * - 本机只读补充链路：`~/.zcode/cli/rollout/model-io-<sessionId>.jsonl` 按完成模型调用
 *   追加一行 JSON（request.messages + response.text/toolCalls），尾部增量解析即可得到
 *   提问、回复原文和当前动作。这里只读本地文件，不碰 remote relay。
 *
 * 映射目标仍是桥唯一契约：
 *   { sessionId, source:"zcode", title?, workspace?, status, userText? |
 *     assistantText? | assistantDelta?, currentAction?, summary?, pendingOptions? }
 * 等待确认优先级最高：有未决 interaction 时 error/working/idle 不得盖过 waiting。
 */
import { spawn } from "node:child_process";
import {
  closeSync,
  existsSync,
  openSync,
  readSync,
  readdirSync,
  statSync,
} from "node:fs";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import { readZCodeHistory } from "./zcode-history.mjs";
import { DEFAULT_ZCODE_TASK_INDEX_DB, readZCodeTaskIndex } from "./zcode-task-index.mjs";

const LOCAL_APP_DATA = process.env.LOCALAPPDATA || join(homedir(), "AppData", "Local");
export const DEFAULT_ZCODE_APP_SERVER = join(
  LOCAL_APP_DATA,
  "Programs",
  "ZCode",
  "resources",
  "glm",
  "zcode.cjs",
);
export const DEFAULT_ZCODE_BUILTIN_PROVIDER_CONFIG = join(
  LOCAL_APP_DATA,
  "Programs",
  "ZCode",
  "resources",
  "config",
  "provider",
  "zcode-builtin.json",
);
export const DEFAULT_ZCODE_PERSONAL_PROVIDER_CONFIG = join(homedir(), ".zcode", "v2", "provider_config.json");
export const DEFAULT_ZCODE_MODEL_IO_DIR = join(homedir(), ".zcode", "cli", "rollout");

const ACTION_MAX = 120;
const DEFAULT_POLL_MS = 1500;
const DEFAULT_REQUEST_TIMEOUT_MS = 10_000;
const DEFAULT_RESTART_MS = 30_000;
const INITIAL_TAIL_BYTES = 2 * 1024 * 1024;
const MAX_TAIL_BYTES = 16 * 1024 * 1024;
const MAX_INCREMENT_BYTES = 4 * 1024 * 1024;

function nonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function finiteTime(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}

function textFromContent(content) {
  if (typeof content === "string") return content.trim();
  if (Array.isArray(content)) {
    return content
      .map((part) => {
        if (typeof part === "string") return part;
        if (typeof part?.text === "string") return part.text;
        if (typeof part?.content === "string") return part.content;
        return "";
      })
      .join("")
      .trim();
  }
  return "";
}

function isDisplayableUserText(text) {
  const value = nonEmptyString(text);
  if (!value) return false;
  return !(
    /^<system-reminder>[\s\S]*<\/system-reminder>$/i.test(value) ||
    /^<environment_details>/i.test(value) ||
    /^Caveat:/i.test(value)
  );
}

function summaryText(value, max = 200) {
  return nonEmptyString(value)?.replace(/\s+/g, " ").slice(0, max) || null;
}

function toolAction(toolName, input) {
  let body = "";
  if (typeof input === "string") body = input;
  else if (input && typeof input === "object") {
    body = [input.command, input.description, input.path, input.file_path]
      .map((value) => (typeof value === "string" ? value : ""))
      .filter(Boolean)
      .join(" ");
    if (!body) {
      try {
        body = JSON.stringify(input);
      } catch {
        body = "";
      }
    }
  }
  return `${nonEmptyString(toolName) || "tool"} ${body}`.replace(/\s+/g, " ").trim().slice(0, ACTION_MAX);
}

/** ZCode 状态词 → 桥四词表；paused/completed 收口为 idle，坏值不造状态。 */
export function mapZCodeStatus(status) {
  switch (status) {
    case "running":
      return "working";
    case "waiting":
      return "waiting";
    case "error":
      return "error";
    case "idle":
    case "paused":
    case "completed":
      return "idle";
    default:
      return null;
  }
}

function applySessionMetadata(patch, { title, workspace, status, updatedAt } = {}) {
  const realTitle = nonEmptyString(title);
  if (realTitle) patch.title = realTitle;
  const realWorkspace = nonEmptyString(workspace);
  if (realWorkspace) patch.workspace = realWorkspace;
  const mappedStatus = mapZCodeStatus(status);
  if (mappedStatus) patch.status = mappedStatus;
  const time = finiteTime(updatedAt);
  if (time !== null) patch.updatedAt = time;
  return patch;
}

/** session/list 单条 → roster 补丁。标题是新的可选契约字段，原样保留。 */
export function mapZCodeSessionToPatch(session) {
  if (!session || typeof session !== "object") return null;
  const sessionId = nonEmptyString(session.sessionId);
  const status = mapZCodeStatus(session.status);
  if (!sessionId || !status) return null;
  const patch = { sessionId, source: "zcode", status };
  const title = nonEmptyString(session.title);
  if (title) patch.title = title;
  const workspace = nonEmptyString(session.workspace?.workspacePath || session.workspacePath);
  if (workspace) patch.workspace = workspace;
  const updatedAt = finiteTime(session.updatedAt);
  if (updatedAt !== null) patch.updatedAt = updatedAt;
  return patch;
}

function baseEventPatch(event, context = {}) {
  const sessionId = nonEmptyString(event?.sessionId || context.sessionId);
  if (!sessionId) return null;
  const patch = { sessionId, source: "zcode" };
  const title = nonEmptyString(context.title);
  if (title) patch.title = title;
  const workspace = nonEmptyString(context.workspace);
  if (workspace) patch.workspace = workspace;
  if (mapZCodeStatus(context.status)) patch.status = mapZCodeStatus(context.status);
  const updatedAt = finiteTime(event?.timestamp);
  if (updatedAt !== null) patch.updatedAt = updatedAt;
  return patch;
}

function patchFromPart(part, patch) {
  if (!part || typeof part !== "object") return;
  if (part.type === "text") {
    const text = nonEmptyString(part.text);
    if (text) {
      patch.assistantText = text;
      patch.status = "working";
    }
    return;
  }
  if (part.type === "tool") {
    const action = toolAction(part.tool, part.state?.input || part.metadata?.input);
    if (action) patch.currentAction = action;
    if (part.state?.status === "pendingApproval") {
      patch.status = "waiting";
    } else if (part.state?.status === "error") {
      patch.status = "error";
    } else {
      patch.status = "working";
    }
    return;
  }
  if (part.type === "reasoning") {
    patch.status = "working";
  }
}

function pendingOptionsFromPermission(payload) {
  const options = Array.isArray(payload?.options) ? payload.options : [];
  const normalized = options
    .map((option, index) => ({
      id: nonEmptyString(option?.optionId) || `option-${index}`,
      label: nonEmptyString(option?.name || option?.label || option?.optionId),
    }))
    .filter((option) => option.label);
  const specialOnly = normalized.length > 0 && normalized.every((option) =>
    ["allowOnce", "allowAlways", "deny"].includes(option.id),
  );
  return specialOnly ? undefined : normalized;
}

/** Compound question option identity stays on the wire as questionIndex:value. */
function encodeQuestionOptionId(questionIndex, value, questionCount) {
  return questionCount > 1 ? `${questionIndex}:${value}` : value;
}

function decodeQuestionOptionId(optionId, questionCount) {
  const raw = nonEmptyString(optionId);
  if (!raw) return null;
  if (questionCount <= 1) return { questionIndex: 0, value: raw };
  const separator = raw.indexOf(":");
  if (separator <= 0) return null;
  const questionIndex = Number(raw.slice(0, separator));
  const value = raw.slice(separator + 1);
  return Number.isInteger(questionIndex) && value ? { questionIndex, value } : null;
}

function pendingOptionsFromUserInput(payload) {
  const out = [];
  const questions = Array.isArray(payload?.questions) ? payload.questions : [];
  questions.forEach((question, questionIndex) => {
    const options = Array.isArray(question?.options) ? question.options : [];
    options.forEach((option, optionIndex) => {
      const label = nonEmptyString(option?.label || option?.value);
      const value = nonEmptyString(option?.value || option?.label);
      if (!label || !value) return;
      out.push({
        id: encodeQuestionOptionId(questionIndex, value, questions.length),
        label,
      });
    });
    if (options.length === 0 && nonEmptyString(question?.question)) {
      out.push({ id: `reject:${questionIndex}`, label: "拒绝" });
    }
  });
  if (out.length) return out;
  const choices = Array.isArray(payload?.choices) ? payload.choices : [];
  for (const choice of choices) {
    const value = nonEmptyString(choice);
    if (value) out.push({ id: value, label: value });
  }
  return out.length ? out : undefined;
}

/**
 * 单条 session/event → 部分补丁。返回 null 表示该事件不进入桥契约。
 * 订阅不可用时该映射仍供协议 fixture / 将来可接管的 app-server 使用。
 */
export function mapZCodeEventToPatch(event, context = {}) {
  if (!event || typeof event !== "object") return null;
  const patch = baseEventPatch(event, context);
  if (!patch) return null;
  const type = nonEmptyString(event.type);
  const payload = event.payload && typeof event.payload === "object" ? event.payload : {};

  switch (type) {
    case "session.titleUpdated": {
      const title = nonEmptyString(payload.title);
      if (title) patch.title = title;
      break;
    }
    case "session.updated": {
      const title = nonEmptyString(payload.title);
      const workspace = nonEmptyString(payload.workspace?.workspacePath || payload.workspacePath);
      const status = mapZCodeStatus(payload.status);
      if (title) patch.title = title;
      if (workspace) patch.workspace = workspace;
      if (status) patch.status = status;
      break;
    }
    case "turn.started": {
      const userText = textFromContent(payload.input);
      if (isDisplayableUserText(userText)) patch.userText = userText;
      patch.status = "working";
      break;
    }
    case "turn.steerQueued":
      return null;
    case "turn.completed": {
      const assistantText = nonEmptyString(payload.response);
      if (assistantText) patch.assistantText = assistantText;
      patch.currentAction = null;
      patch.status = String(payload.resultType || "").startsWith("error") ? "error" : "idle";
      if (patch.status === "error") patch.summary = summaryText(payload.resultType);
      break;
    }
    case "turn.failed": {
      patch.status = "error";
      patch.currentAction = null;
      patch.summary = summaryText(payload.error?.message || payload.error?.type || payload.turnPhase);
      break;
    }
    case "part.started":
    case "part.upserted": {
      patchFromPart(payload.part, patch);
      break;
    }
    case "part.delta": {
      if (payload.field === "text" && typeof payload.delta === "string" && payload.delta) {
        patch.assistantDelta = payload.delta;
        patch.status = "working";
      }
      break;
    }
    case "model.streaming": {
      if (payload.kind === "text_delta" && typeof payload.delta === "string" && payload.delta) {
        patch.assistantDelta = payload.delta;
        patch.status = "working";
      } else if (payload.kind === "tool_call" || payload.kind === "tool_input_end") {
        const action = toolAction(payload.toolName, payload.input);
        if (action) patch.currentAction = action;
        patch.status = "working";
      }
      break;
    }
    case "tool.updated": {
      const action = toolAction(payload.toolName, payload.input);
      if (action) patch.currentAction = action;
      if (payload.kind === "error") {
        patch.status = "error";
        patch.summary = summaryText(payload.error?.message || payload.error?.type);
      } else if (payload.kind === "result") {
        patch.status = "working";
      } else {
        patch.status = "working";
      }
      break;
    }
    case "permission.requested":
    case "userInput.requested": {
      patch.status = "waiting";
      patch.summary = summaryText(payload.reason || payload.prompt);
      patch.pendingOptions =
        type === "permission.requested"
          ? pendingOptionsFromPermission(payload)
          : pendingOptionsFromUserInput(payload);
      break;
    }
    case "permission.resolved":
    case "userInput.resolved": {
      patch.status = "working";
      patch.pendingOptions = [];
      break;
    }
    default:
      return Object.keys(patch).length > 2 ? patch : null;
  }
  return patch;
}

/** model_io 完成记录 → 桥补丁（只读尾部链路的主映射）。 */
export function mapZCodeModelIoRecordToPatch(record) {
  if (!record || typeof record !== "object") return null;
  const sessionId = nonEmptyString(record.sessionId);
  if (!sessionId) return null;
  const patch = { sessionId, source: "zcode" };
  const updatedAt = finiteTime(record.completedAt || record.startedAt);
  if (updatedAt !== null) patch.updatedAt = updatedAt;

  const messages = Array.isArray(record.request?.messages) ? record.request.messages : [];
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user") {
      const userText = textFromContent(messages[i].content);
      if (isDisplayableUserText(userText)) {
        patch.userText = userText;
        break;
      }
    }
  }

  const response = record.response && typeof record.response === "object" ? record.response : {};
  const assistantText = nonEmptyString(response.text);
  const toolCalls = Array.isArray(response.toolCalls) ? response.toolCalls : [];
  if (assistantText) patch.assistantText = assistantText;
  if (toolCalls.length) {
    patch.currentAction = toolCalls
      .map((call) => toolAction(call?.name || call?.toolName, call?.input))
      .filter(Boolean)
      .slice(0, 3)
      .join(" | ")
      .slice(0, ACTION_MAX * 2);
    patch.status = "working";
  } else {
    patch.status = assistantText ? "idle" : mapZCodeStatus(record.status) || "idle";
  }
  if (response.error || record.error) {
    patch.status = "error";
    patch.summary = summaryText(response.error?.message || record.error?.message || record.error);
  }
  return Object.keys(patch).length > 2 ? patch : null;
}
/** interaction server request → adapter 本地待决项；只保留 approve/reject/select 语义。 */
export function zcodeInteractionFromServerRequest(message) {
  if (!message || typeof message !== "object") return null;
  const method = nonEmptyString(message.method);
  if (method !== "interaction/requestPermission" && method !== "interaction/requestUserInput") return null;
  const params = message.params && typeof message.params === "object" ? message.params : {};
  const sessionId = nonEmptyString(params.sessionId);
  const protocolRequestId = message.id;
  const requestId = nonEmptyString(params.requestId) || (protocolRequestId != null ? String(protocolRequestId) : null);
  if (!sessionId || !requestId) return null;
  const kind = method.endsWith("requestPermission") ? "permission" : "userInput";
  const pending = {
    kind,
    protocolRequestId,
    requestId,
    sessionId,
    summary: summaryText(params.reason || params.prompt),
    payload: params,
    pendingOptions:
      kind === "permission"
        ? pendingOptionsFromPermission(params)
        : pendingOptionsFromUserInput(params),
    createdAt: Date.now(),
  };
  return pending;
}

/** 事件里的 permission/userInput.requested → 同一待决队列（协议 request 尚未到时的兼容面）。 */
export function zcodeInteractionFromEvent(event) {
  const type = nonEmptyString(event?.type);
  if (type !== "permission.requested" && type !== "userInput.requested") return null;
  const payload = event.payload && typeof event.payload === "object" ? event.payload : {};
  const sessionId = nonEmptyString(event.sessionId || payload.sessionId);
  const requestId = nonEmptyString(payload.requestId);
  if (!sessionId || !requestId) return null;
  const kind = type.startsWith("permission") ? "permission" : "userInput";
  return {
    kind,
    protocolRequestId: null,
    requestId,
    sessionId,
    summary: summaryText(payload.reason || payload.prompt),
    payload,
    pendingOptions:
      kind === "permission"
        ? pendingOptionsFromPermission(payload)
        : pendingOptionsFromUserInput(payload),
    createdAt: Date.now(),
  };
}

function responseForPermission(payload, action, optionId) {
  const options = Array.isArray(payload?.options) ? payload.options : [];
  if (action === "select") {
    const selected = options.find((option) => option?.optionId === optionId);
    if (selected?.response && typeof selected.response === "object") return selected.response;
    return null;
  }
  if (action === "approve") {
    const allow = options.find((option) => {
      const id = String(option?.optionId || "").toLowerCase();
      const kind = String(option?.kind || "").toLowerCase();
      return id.includes("allow") || kind.includes("allow");
    });
    return allow?.response || { decision: "allow" };
  }
  const deny = options.find((option) => {
    const id = String(option?.optionId || "").toLowerCase();
    const kind = String(option?.kind || "").toLowerCase();
    return id.includes("deny") || kind.includes("deny");
  });
  return deny?.response || { decision: "deny", reason: "Rejected from Agent Mirror" };
}

function responseForUserInput(payload, action, optionId) {
  const questions = Array.isArray(payload?.questions) ? payload.questions : [];
  if (action === "reject") return { action: "decline" };
  if (action === "approve") {
    const first = questions[0]?.options?.[0];
    const answer = nonEmptyString(first?.value || first?.label);
    return answer ? { action: "accept", content: { answer } } : { action: "accept", content: {} };
  }
  if (action === "select") {
    if (questions.length > 1 && optionId?.includes(":")) {
      const [indexText, ...rest] = optionId.split(":");
      const index = Number(indexText);
      const value = rest.join(":");
      const question = questions[index];
      if (!question || !Number.isInteger(index) || !value) return null;
      return {
        action: "accept",
        content: { answers: { [question.question || `question-${index}`]: value } },
      };
    }
    const value = nonEmptyString(optionId);
    if (!value) return null;
    if (questions.length === 1) {
      const question = questions[0];
      return {
        action: "accept",
        content: { answers: { [question?.question || "question"]: value } },
      };
    }
    return { action: "accept", content: { answer: value } };
  }
  return null;
}

/**
 * 手机动作 → app-server server request 的 result。
 * 只接受 approve|reject|select；自由文字、prompt、按键在这里没有形状。
 */
export function zcodeActionResponseFor(interaction, action, optionId = null) {
  if (!interaction || typeof interaction !== "object") return null;
  if (action !== "approve" && action !== "reject" && action !== "select") return null;
  if (action === "select" && !nonEmptyString(optionId)) return null;
  return interaction.kind === "permission"
    ? responseForPermission(interaction.payload, action, optionId)
    : responseForUserInput(interaction.payload, action, optionId);
}

/** 取文件尾部的完整 JSONL 行；行很大时逐级扩大窗口，永不把半行当事件。 */
function readTailLines(file, { minLines = 1, initialBytes = INITIAL_TAIL_BYTES, maxBytes = MAX_TAIL_BYTES } = {}) {
  let size;
  try {
    size = statSync(file).size;
  } catch {
    return { lines: [], nextOffset: 0 };
  }
  let windowBytes = Math.min(size, initialBytes);
  while (true) {
    const start = Math.max(0, size - windowBytes);
    const length = size - start;
    const buffer = Buffer.alloc(length);
    let fd;
    try {
      fd = openSync(file, "r");
      readSync(fd, buffer, 0, length, start);
    } catch {
      return { lines: [], nextOffset: size };
    } finally {
      if (fd !== undefined) closeSync(fd);
    }
    let text = buffer.toString("utf8");
    const firstNewline = text.indexOf("\n");
    if (start > 0 && firstNewline >= 0) text = text.slice(firstNewline + 1);
    const lines = text.split("\n");
    const lastPartial = text.endsWith("\n") ? "" : lines.pop();
    const complete = lines.filter((line) => line.trim());
    if (complete.length >= minLines || windowBytes >= Math.min(size, maxBytes) || windowBytes >= size) {
      // nextOffset 按原始 buffer 的最后一个换行算：窗口起点落在半行中间也不会错位。
      const lastNewline = buffer.lastIndexOf(0x0a);
      return { lines: complete, nextOffset: lastNewline >= 0 ? start + lastNewline + 1 : start };
    }
    windowBytes = Math.min(size, Math.max(windowBytes * 2, initialBytes));
  }
}

function readLinesFrom(file, offset, maxBytes = MAX_INCREMENT_BYTES) {
  let size;
  try {
    size = statSync(file).size;
  } catch {
    return { lines: [], nextOffset: offset };
  }
  if (size < offset) return readTailLines(file, { minLines: 1 });
  if (size === offset) return { lines: [], nextOffset: offset };
  const length = Math.min(size - offset, maxBytes);
  const buffer = Buffer.alloc(length);
  let fd;
  try {
    fd = openSync(file, "r");
    readSync(fd, buffer, 0, length, offset);
  } catch {
    return { lines: [], nextOffset: offset };
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
  const text = buffer.toString("utf8");
  const lines = text.split("\n");
  const lastPartial = text.endsWith("\n") ? "" : lines.pop();
  const lastNewline = buffer.lastIndexOf(0x0a);
  return {
    lines: lines.filter((line) => line.trim()),
    nextOffset: lastNewline >= 0 ? offset + lastNewline + 1 : offset,
  };
}

function modelIoPathFor(root, sessionId) {
  return join(root, `model-io-${sessionId}.jsonl`);
}

function discoverModelIoSessionIds(root) {
  let names = [];
  try {
    names = readdirSync(root);
  } catch {
    return [];
  }
  return names
    .filter((name) => name.startsWith("model-io-sess_") && name.endsWith(".jsonl"))
    .map((name) => basename(name).slice("model-io-".length, -".jsonl".length))
    .filter(Boolean);
}
/** app-server stdio 传输：换行分隔 JSON，无 jsonrpc 字段。导出供协议 fixture 测试。 */
export function createZCodeProtocolClient(options = {}) {
  const command = options.command || process.execPath;
  const args = options.args || [options.appServerPath || DEFAULT_ZCODE_APP_SERVER, "app-server"];
  const env = options.env || zcodeAppServerEnv(process.env);
  const log = options.log || (() => {});
  const requestTimeoutMs = options.requestTimeoutMs || DEFAULT_REQUEST_TIMEOUT_MS;
  const spawnImpl = options.spawnImpl || spawn;
  const messageHandlers = new Set();
  const exitHandlers = new Set();
  const pending = new Map();
  let nextId = 1;
  let buffer = "";
  let stopped = false;
  let child;

  const rejectAll = (reason) => {
    for (const [, entry] of pending) {
      clearTimeout(entry.timer);
      entry.reject(reason);
    }
    pending.clear();
  };

  try {
    child = spawnImpl(command, args, {
      stdio: ["pipe", "pipe", "pipe"],
      env,
      windowsHide: true,
    });
  } catch (error) {
    return {
      request: () => Promise.reject(error),
      respond: () => false,
      onMessage: () => () => {},
      onExit: () => () => {},
      stop: () => {},
    };
  }

  child.stdout?.setEncoding?.("utf8");
  child.stderr?.setEncoding?.("utf8");
  child.stdout?.on("data", (chunk) => {
    buffer += String(chunk);
    let newline;
    while ((newline = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        log(`zcode app-server 非 JSON 输出已跳过：${line.slice(0, 160)}`);
        continue;
      }
      if (message && message.id != null && !message.method && pending.has(message.id)) {
        const entry = pending.get(message.id);
        pending.delete(message.id);
        clearTimeout(entry.timer);
        if (message.error) entry.reject(Object.assign(new Error(message.error.message || "zcode app-server error"), { code: message.error.code, data: message.error }));
        else entry.resolve(message.result);
        continue;
      }
      for (const handler of [...messageHandlers]) {
        try {
          handler(message);
        } catch (error) {
          log(`zcode app-server 消息处理异常：${error?.message || error}`);
        }
      }
    }
  });
  child.stderr?.on("data", (chunk) => {
    const text = String(chunk).trim();
    if (text) log(`zcode app-server stderr：${text.slice(0, 300)}`);
  });
  child.on("error", (error) => {
    log(`zcode app-server 启动失败：${error?.message || error}`);
    rejectAll(error);
  });
  child.on("exit", (code, signal) => {
    stopped = true;
    rejectAll(new Error(`zcode app-server exited code=${code ?? "null"} signal=${signal ?? "null"}`));
    for (const handler of [...exitHandlers]) {
      try {
        handler({ code, signal });
      } catch {
        /* 退出回调不反向打崩桥 */
      }
    }
  });

  return {
    request(method, params = {}) {
      if (stopped) return Promise.reject(new Error("zcode app-server stopped"));
      const id = nextId++;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`zcode app-server request timeout: ${method}`));
        }, requestTimeoutMs);
        pending.set(id, { resolve, reject, timer });
        try {
          child.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
        } catch (error) {
          pending.delete(id);
          clearTimeout(timer);
          reject(error);
        }
      });
    },
    respond(id, result) {
      if (stopped || id == null) return false;
      try {
        child.stdin.write(`${JSON.stringify({ id, result })}\n`);
        return true;
      } catch {
        return false;
      }
    },
    onMessage(handler) {
      messageHandlers.add(handler);
      return () => messageHandlers.delete(handler);
    },
    onExit(handler) {
      exitHandlers.add(handler);
      return () => exitHandlers.delete(handler);
    },
    stop() {
      if (stopped) return;
      stopped = true;
      rejectAll(new Error("zcode app-server stopped"));
      try {
        child.kill();
      } catch {
        /* 已退出 */
      }
    },
  };
}

/** provider 配置只在调用方没显式覆盖时补默认值；坏/缺配置不阻塞桥启动。 */
export function zcodeAppServerEnv(baseEnv = process.env, options = {}) {
  const env = { ...baseEnv };
  const builtin = options.builtinConfigPath || DEFAULT_ZCODE_BUILTIN_PROVIDER_CONFIG;
  const personal = options.personalConfigPath || DEFAULT_ZCODE_PERSONAL_PROVIDER_CONFIG;
  if (!env.ZCODE_BUILTIN_PROVIDER_CONFIG_FILE && existsSync(builtin)) {
    env.ZCODE_BUILTIN_PROVIDER_CONFIG_FILE = builtin;
  }
  if (!env.ZCODE_PERSONAL_PROVIDER_CONFIG_FILE && existsSync(personal)) {
    env.ZCODE_PERSONAL_PROVIDER_CONFIG_FILE = personal;
  }
  return env;
}

export function createZCodeAdapter(emit, client, options = {}) {
  const log = options.log || (() => {});
  const now = options.now || (() => Date.now());
  const modelIoRoot = options.modelIoRoot || process.env.ZCODE_MODEL_IO_DIR || DEFAULT_ZCODE_MODEL_IO_DIR;
  const onSessionRemoved = options.onSessionRemoved || (() => {});
  const knownSessions = new Set();
  const tombstoneReasonBySession = new Map();
  const pollMs = Number.isFinite(options.pollMs) ? options.pollMs : DEFAULT_POLL_MS;
  const roster = new Map();
  const fingerprints = new Map();
  const subscriptions = new Set();
  const interactionsBySession = new Map();
  const modelState = new Map();
  const eventSeqBySession = new Map();
  let stopped = false;
  let timer = null;
  let unsubMessage = null;
  let unsubExit = null;
  let lastListErrorLogAt = 0;
  let membershipRevision = 0;

  const emitPatch = (partial) => {
    if (!partial || typeof partial !== "object" || stopped) return null;
    const patch = { ...partial, source: "zcode" };
    try {
      return emit(patch);
    } catch (error) {
      log(`zcode 事件灌桥失败：${error?.message || error}`);
      return null;
    }
  };

  const pendingFor = (sessionId) => interactionsBySession.get(sessionId) || [];
  const hasPending = (sessionId) => pendingFor(sessionId).length > 0;

  const withPrecedence = (patch) => {
    if (!patch) return patch;
    const rosterStatus = roster.get(patch.sessionId)?.status;
    if ((hasPending(patch.sessionId) || rosterStatus === "waiting") && patch.status && patch.status !== "waiting") {
      return { ...patch, status: "waiting" };
    }
    return patch;
  };

  const nextMembershipRevision = () => {
    membershipRevision = Math.max(membershipRevision + 1, Number(now()) || 0);
    return membershipRevision;
  };

  const emitMembership = (sessionId, membership, reason, remembered) => {
    const revision = nextMembershipRevision();
    const base = remembered || roster.get(sessionId) || {};
    emitPatch({
      kind: "membership",
      sourceSessionId: sessionId,
      membership,
      archiveState: membership === "ACTIVE" ? "ACTIVE" : membership === "ARCHIVED" ? "ARCHIVED" : "UNKNOWN",
      reason,
      generation: revision,
      revision,
      status: base.status || "idle",
      workspace: base.workspace,
      title: base.title,
      currentAction: base.currentAction,
      latestReply: base.latestReply,
      summary: base.summary,
      updatedAt: base.updatedAt ?? now(),
    });
  };

  const rememberRoster = (session) => {
    const patch = mapZCodeSessionToPatch(session);
    if (!patch) return;
    const tombstoneReason = tombstoneReasonBySession.get(patch.sessionId);
    roster.set(patch.sessionId, patch);
    knownSessions.add(patch.sessionId);
    if (tombstoneReason !== undefined) {
      tombstoneReasonBySession.delete(patch.sessionId);
      emitMembership(patch.sessionId, "ACTIVE", tombstoneReason === "archive" ? "unarchive" : "source-created", patch);
    }
    const fingerprint = JSON.stringify([
      patch.title ?? null,
      patch.workspace ?? null,
      patch.status,
      patch.updatedAt ?? null,
    ]);
    if (fingerprints.get(patch.sessionId) !== fingerprint) {
      fingerprints.set(patch.sessionId, fingerprint);
      emitPatch(withPrecedence(patch));
    }
  };

  const removeSession = (sessionId, reason = "archive") => {
    if (tombstoneReasonBySession.has(sessionId)) return;
    const remembered = roster.get(sessionId);
    if (!remembered && !modelState.has(sessionId) && !knownSessions.has(sessionId)) return;
    roster.delete(sessionId);
    fingerprints.delete(sessionId);
    subscriptions.delete(sessionId);
    interactionsBySession.delete(sessionId);
    modelState.delete(sessionId);
    tombstoneReasonBySession.set(sessionId, reason);
    emitMembership(sessionId, reason === "archive" ? "ARCHIVED" : "ABSENT", reason, remembered);
    onSessionRemoved(sessionId, reason);
  };

  const addInteraction = (interaction) => {
    if (!interaction) return;
    const list = pendingFor(interaction.sessionId).filter((item) => item.requestId !== interaction.requestId);
    list.push(interaction);
    interactionsBySession.set(interaction.sessionId, list);
    const patch = {
      sessionId: interaction.sessionId,
      source: "zcode",
      status: "waiting",
      updatedAt: now(),
    };
    if (interaction.summary) patch.summary = interaction.summary;
    if (interaction.pendingOptions?.length) patch.pendingOptions = interaction.pendingOptions;
    emitPatch(patch);
  };

  const resolveInteraction = (interaction, action, optionId) => {
    const response = zcodeActionResponseFor(interaction, action, optionId);
    if (!response) return false;
    if (interaction.protocolRequestId != null && client?.respond) {
      if (!client.respond(interaction.protocolRequestId, response)) return false;
    } else if (interaction.protocolRequestId == null) {
      return false;
    }
    const list = pendingFor(interaction.sessionId).filter((item) => item.requestId !== interaction.requestId);
    if (list.length) interactionsBySession.set(interaction.sessionId, list);
    else interactionsBySession.delete(interaction.sessionId);
    emitPatch({
      sessionId: interaction.sessionId,
      source: "zcode",
      status: list.length ? "waiting" : "working",
      pendingOptions: list.at(-1)?.pendingOptions || [],
      updatedAt: now(),
    });
    return true;
  };

  const resolveAction = (body) => {
    const sessionId = nonEmptyString(body?.sessionId);
    const action = body?.action;
    const optionId = nonEmptyString(body?.optionId);
    if (!sessionId || !["approve", "reject", "select"].includes(action)) return false;
    if (action === "select" && !optionId) return false;
    const list = pendingFor(sessionId);
    if (!list.length) return false;
    const requestId = nonEmptyString(body?.requestId);
    const interaction =
      (requestId && list.find((item) => item.requestId === requestId || String(item.protocolRequestId) === requestId)) ||
      list.at(-1);
    return resolveInteraction(interaction, action, optionId);
  };

  const applyEventBatch = (params) => {
    const sessionId = nonEmptyString(params?.sessionId);
    if (!sessionId) return;
    const eventSeq = Number.isFinite(params?.eventSeq) ? params.eventSeq : null;
    if (eventSeq !== null) {
      const previous = eventSeqBySession.get(sessionId);
      if (previous !== undefined && eventSeq <= previous) return;
      eventSeqBySession.set(sessionId, eventSeq);
    }
    const context = roster.get(sessionId) || {};
    const snapshotPatch = params.snapshot?.session
      ? mapZCodeSessionToPatch({ ...params.snapshot.session, sessionId })
      : null;
    if (snapshotPatch) {
      rememberRoster({ ...params.snapshot.session, sessionId });
      emitPatch({ ...withPrecedence(snapshotPatch), resetTurns: true });
    }
    const events = Array.isArray(params?.events) ? params.events : [];
    for (const event of events) {
      const interaction = zcodeInteractionFromEvent(event);
      if (interaction) addInteraction(interaction);
      if (event?.type === "permission.resolved" || event?.type === "userInput.resolved") {
        const requestId = nonEmptyString(event.payload?.requestId);
        const list = pendingFor(sessionId).filter((item) => item.requestId !== requestId);
        if (list.length) interactionsBySession.set(sessionId, list);
        else interactionsBySession.delete(sessionId);
      }
      const patch = mapZCodeEventToPatch(event, context);
      if (patch) emitPatch(withPrecedence(patch));
    }
  };

  const handleMessage = (message) => {
    if (!message || typeof message !== "object") return;
    if (message.method === "session/event") {
      applyEventBatch(message.params || {});
      return;
    }
    const interaction = zcodeInteractionFromServerRequest(message);
    if (interaction) addInteraction(interaction);
  };

  const modelPatchForRecord = (sessionId, record, state) => {
    const recordId = nonEmptyString(record.requestId) || `${record.turnId || ""}:${record.startedAt || ""}`;
    if (recordId && state.seen.has(recordId)) return null;
    if (recordId) {
      state.seen.add(recordId);
      if (state.seen.size > 500) state.seen.delete(state.seen.values().next().value);
    }
    const patch = mapZCodeModelIoRecordToPatch(record);
    if (!patch) return null;
    const turnId = nonEmptyString(record.turnId) || recordId || "default";
    const userText = patch.userText;
    if (userText) {
      if (state.userByTurn.get(turnId) === userText) delete patch.userText;
      else state.userByTurn.set(turnId, userText);
      if (state.userByTurn.size > 200) state.userByTurn.delete(state.userByTurn.keys().next().value);
    }
    return Object.keys(patch).length > 2 ? patch : null;
  };

  const scanModelIoFor = (sessionId) => {
    const file = modelIoPathFor(modelIoRoot, sessionId);
    if (!existsSync(file)) return;
    let state = modelState.get(sessionId);
    if (!state) {
      state = { nextOffset: 0, initialized: false, seen: new Set(), userByTurn: new Map() };
      modelState.set(sessionId, state);
    }
    let read;
    if (!state.initialized) {
      read = readTailLines(file, { minLines: 1 });
      state.initialized = true;
    } else {
      read = readLinesFrom(file, state.nextOffset);
    }
    state.nextOffset = read.nextOffset;
    for (const line of read.lines) {
      let record;
      try {
        record = JSON.parse(line);
      } catch {
        continue;
      }
      const patch = modelPatchForRecord(sessionId, record, state);
      if (patch) emitPatch(withPrecedence(patch));
    }
  };

  const scan = async () => {
    if (stopped) return;
    let sessions = [];
    const listedIds = new Set();
    const activeIds = new Set();
    let listOk = false;
    // taskIndexPath 显式给出才读任务索引；默认入口由 startZCodeAdapter 补生产路径，
    // 单元测试仍可只测协议映射，不误读本机真实数据库。
    const taskIndex = options.taskIndexPath ? readZCodeTaskIndex(options.taskIndexPath) : { ok: false, states: new Map() };
    if (client?.request) {
      try {
        const result = await client.request("session/list", {});
        sessions = Array.isArray(result?.sessions) ? result.sessions : [];
        listOk = true;
        for (const session of sessions) {
          const sessionId = nonEmptyString(session?.sessionId);
          if (!sessionId) continue;
          listedIds.add(sessionId);
          const taskState = taskIndex.ok ? taskIndex.states.get(sessionId) : null;
          const archivedByIndex = taskIndex.ok && !taskState?.active;
          const removalReason = taskIndex.ok ? (!taskState || taskState.present === false ? "source-removed" : "archive") : "archive";
          if (archivedByIndex || (!taskIndex.ok && session.archivedAt)) {
            removeSession(sessionId, removalReason);
            continue;
          }
          activeIds.add(sessionId);
          rememberRoster(session);
        }
      } catch (error) {
        const message = error?.message || String(error);
        if (now() - lastListErrorLogAt > 30_000) {
          log(`zcode session/list 不可用，转 model-io 尾部降级：${message}`);
          lastListErrorLogAt = now();
        }
      }
    }
    if (listOk) {
      for (const sessionId of [...roster.keys()]) {
        if (taskIndex.ok ? !activeIds.has(sessionId) : !listedIds.has(sessionId)) {
          removeSession(sessionId, (() => {
            if (!taskIndex.ok) return "source-removed";
            const state = taskIndex.states.get(sessionId);
            return !state || state.present === false ? "source-removed" : "archive";
          })());
        }
      }
    }

    const ids = new Set(
      listOk || options.modelIoDiscovery === false
        ? [...roster.keys()]
        : [...roster.keys(), ...discoverModelIoSessionIds(modelIoRoot)],
    );
    for (const sessionId of ids) scanModelIoFor(sessionId);
    // 订阅是机会性增强：fresh app-server 对桌面持久会话常回 -32004，失败只记一次，不重试风暴。
    for (const session of roster.values()) {
      if (!["working", "waiting"].includes(session.status) || subscriptions.has(session.sessionId) || !client?.request) continue;
      subscriptions.add(session.sessionId);
      try {
        await client.request("session/subscribe", {
          sessionId: session.sessionId,
          deliveryKind: "desktop-continuous",
          includeSnapshot: true,
        });
      } catch (error) {
        log(`zcode session/subscribe 不可用（model-io 降级继续）：${error?.message || error}`);
      }
    }
  };
  if (client?.onMessage) unsubMessage = client.onMessage(handleMessage);
  if (client?.onExit) {
    unsubExit = client.onExit(({ code, signal }) => {
      log(`zcode app-server 退出 code=${code ?? "null"} signal=${signal ?? "null"}；桥继续靠 model-io 尾部降级`);
    });
  }
  if (pollMs > 0) {
    timer = setInterval(() => {
      scan().catch((error) => log(`zcode scan 异常：${error?.message || error}`));
    }, pollMs);
    timer.unref?.();
  }
  scan().catch((error) => log(`zcode 首扫异常：${error?.message || error}`));

  return {
    resolveAction,
    scanNow: scan,
    historyFor(sessionId) { return readZCodeHistory(modelIoRoot, sessionId); },
    stop() {
      stopped = true;
      if (timer) clearInterval(timer);
      unsubMessage?.();
      unsubExit?.();
    },
  };
}

export function startZCodeAdapter(emit, options = {}) {
  const log = options.log || (() => {});
  let client = options.client || null;
  let adapter = null;
  let restartTimer = null;
  let stopped = false;

  const start = () => {
    if (stopped) return;
    if (!client) {
      const appServerPath = options.appServerPath || DEFAULT_ZCODE_APP_SERVER;
      if (!existsSync(appServerPath)) {
        log(`zcode app-server 不存在，桥继续 model-io 尾部降级：${appServerPath}`);
      } else {
        const baseEnv = options.env || process.env;
        if (!baseEnv.ZCODE_BUILTIN_PROVIDER_CONFIG_FILE && !existsSync(DEFAULT_ZCODE_BUILTIN_PROVIDER_CONFIG)) {
          log(`zcode builtin provider config 缺失，使用 app-server 默认/环境配置：${DEFAULT_ZCODE_BUILTIN_PROVIDER_CONFIG}`);
        }
        if (!baseEnv.ZCODE_PERSONAL_PROVIDER_CONFIG_FILE && !existsSync(DEFAULT_ZCODE_PERSONAL_PROVIDER_CONFIG)) {
          log(`zcode personal provider config 缺失，使用 app-server 默认/环境配置：${DEFAULT_ZCODE_PERSONAL_PROVIDER_CONFIG}`);
        }
        client = createZCodeProtocolClient({
          appServerPath,
          env: zcodeAppServerEnv(options.env || process.env, options),
          log,
          requestTimeoutMs: options.requestTimeoutMs,
          spawnImpl: options.spawnImpl,
        });
      }
    }
    adapter = createZCodeAdapter(emit, client, {
      ...options,
      log,
      taskIndexPath: options.taskIndexPath || DEFAULT_ZCODE_TASK_INDEX_DB,
    });
    if (client?.onExit && options.restart !== false) {
      client.onExit(() => {
        if (stopped || restartTimer) return;
        restartTimer = setTimeout(() => {
          restartTimer = null;
          client = null;
          adapter?.stop();
          start();
        }, options.restartMs || DEFAULT_RESTART_MS);
        restartTimer.unref?.();
      });
    }
  };

  start();
  return {
    resolveAction: (body) => adapter?.resolveAction(body) || false,
    scanNow: () => adapter?.scanNow() || Promise.resolve(),
    historyFor: (sessionId) => adapter?.historyFor(sessionId) || [],
    stop() {
      stopped = true;
      if (restartTimer) clearTimeout(restartTimer);
      adapter?.stop();
      client?.stop?.();
    },
  };
}

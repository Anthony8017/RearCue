/**
 * Codex 主动对话控制面（spec 0024 / ADR 0017）：桥内起一个 `codex app-server --stdio`
 * 子进程，经官方 JSON 协议创建/恢复线程、发起回合、中断与删除失败空会话。
 *
 * 与只读 rollout 适配器分工：本模块只管请求面；正文与会话列表仍由 adapters/codex.mjs
 * 从 rollout 归一。所有文件/命令批准由 app-server 发 server-request，本模块原样挂起，
 * 等手机 Remote Approval 决定后回写；绝不使用 never / bypass。
 */
import { spawn, spawnSync } from "node:child_process";
import { existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import { questionRequests } from "./speech-facts.mjs";
import { codexAssistantKind, codexMessageText } from "./codex-message.mjs";

/** Scheduled-task environments do not inherit the interactive PATH; resolve Codex explicitly. */
export function resolveCodexCommand(env = process.env, findInPath = spawnSync) {
  if (env.CODEX_BIN?.trim()) return env.CODEX_BIN.trim();
  try {
    const found = findInPath("where.exe", ["codex"], { encoding: "utf8", windowsHide: true });
    const first = String(found.stdout || "").split(/\r?\n/).map((line) => line.trim()).find(Boolean);
    if (first) return first;
  } catch {
    /* fall through to known install roots */
  }
  if (process.platform === "win32" && env.LOCALAPPDATA) {
    const binRoot = join(env.LOCALAPPDATA, "OpenAI", "Codex", "bin");
    if (existsSync(binRoot)) {
      const candidates = readdirSync(binRoot, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => join(binRoot, entry.name, "codex.exe"))
        .filter(existsSync)
        .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs);
      if (candidates[0]) return candidates[0];
    }
  }
  return "codex";
}

export const CODEX_APPROVAL_METHODS = new Set([
  "applyPatchApproval",
  "execCommandApproval",
  "item/commandExecution/requestApproval",
  "item/fileChange/requestApproval",
  "item/permissions/requestApproval",
]);

/** 官方 server-request → 手机可见的一句摘要（正文仍由 rollout 适配器负责）。 */
export function approvalSummary(method, params = {}) {
  if (method === "item/fileChange/requestApproval") return "想修改文件";
  if (method === "item/permissions/requestApproval") return "想申请额外权限";
  const command = Array.isArray(params.command) ? params.command.join(" ") : "";
  return command ? `想执行命令：${command}`.slice(0, 160) : "想执行命令";
}

/** 手机 approve/reject → app-server 回执。只允许一次性决定，不做 always/session 级放行。 */
export function approvalResponse(method, action, params = {}) {
  const approve = action === "approve";
  if (method === "item/fileChange/requestApproval") {
    return { decision: approve ? "accept" : "decline" };
  }
  if (method === "item/permissions/requestApproval") {
    return approve
      ? { permissions: params.permissions || {}, scope: "turn" }
      : { permissions: {}, scope: "turn" };
  }
  return approve
    ? { decision: "approved" }
    : { decision: { denied: { rejection: "RearCue 手机端拒绝" } } };
}

/** app-server 通知 → 桥统一事件的部分补丁；不认识的事件返回 null。 */
export function codexEventPatch(message, messagePhases = new Map()) {
  const params = message?.params || {};
  if (params.thread?.source?.subagent || params.thread?.source?.subAgent) return null;
  const threadId = params.threadId || params.thread?.id || params.conversationId;
  if (!threadId) return null;
  const turnId = params.turnId || params.turn?.id || null;
  const base = { sessionId: threadId, source: "codex", ...(turnId ? { turnId } : {}) };
  const method = String(message.method || "");
  const entryId = firstString(params.itemId, params.item?.id, params.partId) || undefined;
  const phaseKey = entryId ? `${threadId}:${entryId}` : null;
  const phase = firstString(params.item?.phase, params.phase, params.item?.channel);
  if (phaseKey && phase) messagePhases.set(phaseKey, phase);
  const assistantKind = codexAssistantKind(phase || messagePhases.get(phaseKey));
  const delta = firstString(params.delta, params.textDelta, params.part?.textDelta, params.text);
  if (method === "thread/started") {
    return { ...base, workspace: params.thread?.cwd || null, status: "idle" };
  }
  if (method === "turn/started") {
    return { ...base, status: "working", currentAction: null, taskStarted: true };
  }
  if (method === "turn/completed") {
    for (const key of messagePhases.keys()) if (key.startsWith(`${threadId}:`)) messagePhases.delete(key);
    const turnError = params.turn?.error?.message || params.error?.message;
    const failed = !!turnError || params.turn?.status === "failed";
    return {
      ...base,
      status: failed ? "error" : "idle",
      currentAction: null,
      summary: turnError || undefined,
      completeStream: true,
      errorText: turnError || (failed ? "Codex 回合失败" : undefined),
      completion: params.turn?.status === "interrupted" ? "cancelled" : failed ? "error" : "done",
    };
  }

  // 官方 delta 通知按稳定 item id 追加；不同 Codex 版本的方法名有漂移，按语义认，
  // 不要求恰好一个字符串字面量。
  const lowerMethod = method.toLowerCase();
  if (lowerMethod.includes("delta")) {
    if (!delta) return null;
    if (/agentmessage|assistant/i.test(lowerMethod) || /agentMessage|assistant/i.test(String(params.item?.type || ""))) {
      return { ...base, status: "working", assistantDelta: delta, assistantKind, entryId };
    }
    if (/reasoning|thinking/i.test(lowerMethod) || /reasoning|thinking/i.test(String(params.item?.type || ""))) {
      return { ...base, status: "working", thinkingDelta: delta, entryId };
    }
    return null;
  }

  if (method === "item/completed" || method === "item/updated" || method === "item/created") {
    const item = params.item || {};
    const type = String(item.type || params.itemType || "");
    const text = itemText(item);
    const detail = itemDetail(item);
    if (/userMessage/i.test(type)) {
      return method === "item/completed" ? { ...base, userText: text || undefined, entryId, status: "working" } : null;
    }
    if (/agentMessage|assistant/i.test(type)) {
      if (method !== "item/completed" && delta) {
        return { ...base, status: "working", assistantDelta: delta, assistantKind, entryId };
      }
      const body = codexMessageText(text);
      return { ...base, assistantText: body || undefined, assistantKind, entryId,
        ...(body !== text && text ? { assistantDetail: text } : {}),
        status: "working", completeStream: method === "item/completed" };
    }
    if (/reasoning|thinking/i.test(type)) {
      return {
        ...base,
        status: "working",
        thinkingText: text || undefined,
        thinkingDelta: method !== "item/completed" ? delta : undefined,
        entryId,
      };
    }
    if (/toolResult|commandExecution|fileChange|toolCall|mcpToolCall/i.test(type)) {
      const isResult = /Result|completed/i.test(type) || item.output !== undefined || item.result !== undefined;
      const summary = itemSummary(item, !isResult);
      return {
        ...base,
        status: "working",
        entryId,
        toolName: firstString(item.name, item.toolName) || undefined,
        command: firstString(item.command?.join?.(" "), item.command) || undefined,
        ...(firstString(item.path, item.filePath) ? { path: firstString(item.path, item.filePath) } : {}),
        ...(isResult
          ? { toolResultSummary: summary, toolResultDetail: detail }
          : { toolSummary: summary, toolDetail: detail }),
      };
    }
    if (/error/i.test(type)) {
      return { ...base, status: "working", errorText: text || "Codex 工具失败", errorDetail: detail, recoverableError: true };
    }
    return null;
  }
  return null;
}

function firstString(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return null;
}

function itemSummary(item, includeText = true) {
  const name = firstString(item.name, item.toolName, item.type);
  const command = firstString(item.command?.join?.(" "), item.command);
  const path = firstString(item.path, item.filePath);
  const text = includeText ? itemText(item) : null;
  return [name, command || path, text].filter(Boolean).join(" ").trim().slice(0, 240)
    || "Codex 工具调用";
}

function itemDetail(item) {
  const candidates = [item.output, item.result, item.diff, item.content, item.input, item.error];
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate.trim()) return candidate;
  }
  try {
    const raw = JSON.stringify(item);
    return raw && raw !== "{}" ? raw : undefined;
  } catch {
    return undefined;
  }
}

function itemText(item) {
  if (typeof item.text === "string") return item.text;
  if (typeof item.output === "string") return item.output;
  if (Array.isArray(item.content)) {
    return item.content
      .map((part) => (typeof part?.text === "string" ? part.text : ""))
      .join("")
      .trim();
  }
  return "";
}

/**
 * 一个常驻 app-server 进程。请求/应答是 JSONL；server-request 若属批准类则挂起，
 * 由 [resolveApproval] 回写。进程退出会拒绝全部在途请求，桥侧回显式失败/未知态。
 */
export class CodexAppServerControl extends EventEmitter {
  constructor({
    command = null,
    args = ["app-server", "--stdio"],
    spawnProcess = spawn,
    log = () => {},
    requestTimeoutMs = 30_000,
    queueDeliveryTimeoutMs = 20_000,
    queuePollMs = 250,
    threadBusy = () => false,
    env = process.env,
  } = {}) {
    super();
    this.messagePhases = new Map();
    this.command = command || resolveCodexCommand(env);
    this.args = args;
    this.spawnProcess = spawnProcess;
    this.log = log;
    this.requestTimeoutMs = requestTimeoutMs;
    this.queueDeliveryTimeoutMs = queueDeliveryTimeoutMs;
    this.queuePollMs = queuePollMs;
    this.threadBusy = threadBusy;
    this.queuedTurns = new Map();
    this.queueCleanupPromise = null;
    this.env = env;
    this.child = null;
    this.buffer = "";
    this.nextId = 1;
    this.pending = new Map();
    this.approvals = new Map();
    this.internalThreads = new Map();
    this.activeTurns = new Map();
    this.readyPromise = null;
    this.closingPromise = null;
    this.stopped = false;
  }

  async start() {
    if (this.readyPromise) return this.readyPromise;
    if (this.closingPromise) await this.closingPromise;
    if (this.readyPromise) return this.readyPromise;
    this.readyPromise = this.#start();
    try {
      await this.readyPromise;
    } catch (error) {
      this.readyPromise = null;
      throw error;
    }
    return this.readyPromise;
  }

  async #start() {
    this.stopped = false;
    const child = this.spawnProcess(this.command, this.args, {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
      env: this.env,
    });
    this.child = child;
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => this.#onData(chunk));
    child.stderr.on("data", (chunk) => {
      const text = String(chunk).trim();
      if (text) this.log(`codex app-server stderr: ${text.slice(0, 500)}`);
    });
    child.on("error", (error) => {
      this.log(`codex app-server spawn failed: ${error.message}`);
      const failure = new Error(`codex app-server spawn failed: ${error.message}`);
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(failure);
      }
      this.pending.clear();
      this.approvals.clear();
      this.activeTurns.clear();
      this.readyPromise = null;
      this.stopped = true;
      if (this.child === child) this.child = null;
      this.emit("exit", { code: null, signal: null, error });
    });
    child.on("exit", (code, signal) => {
      this.log(`codex app-server exit code=${code ?? "null"} signal=${signal ?? "null"}`);
      const error = new Error(`codex app-server exited code=${code ?? "null"}`);
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(error);
      }
      this.pending.clear();
      this.approvals.clear();
      this.activeTurns.clear();
      this.readyPromise = null;
      this.stopped = true;
      if (this.child === child) this.child = null;
      this.emit("exit", { code, signal });
    });
    await this.#sendRequest("initialize", {
      clientInfo: { name: "RearCueBridge", title: "RearCue PC Bridge", version: "1.0.0" },
      capabilities: { experimentalApi: true },
    });
    this.log("codex app-server ready");
  }

  async request(method, params = {}, timeoutMs = this.requestTimeoutMs) {
    await this.start();
    return this.#sendRequest(method, params, timeoutMs);
  }

  async #sendRequest(method, params = {}, timeoutMs = this.requestTimeoutMs) {
    const id = this.nextId++;
    const payload = JSON.stringify({ id, method, params }) + "\n";
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`codex app-server timeout: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer, method });
      this.child.stdin.write(payload, (error) => {
        if (!error) return;
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      });
    });
  }

  resolveApproval(serverRequestId, action) {
    this.log(`codex approval resolve id=${serverRequestId} action=${action} present=${this.approvals.has(serverRequestId)}`);
    const approval = this.approvals.get(serverRequestId);
    if (!approval) return false;
    this.approvals.delete(serverRequestId);
    const result = approvalResponse(approval.method, action, approval.params);
    this.child?.stdin.write(JSON.stringify({ id: serverRequestId, result }) + "\n");
    this.emit("approvalResolved", { threadId: approval.threadId, action, requestId: approval.requestId });
    queueMicrotask(() => this.#releaseIfIdle());
    return true;
  }

  #releaseIfIdle() {
    if (this.activeTurns.size || this.approvals.size || this.pending.size || this.queuedTurns.size) return;
    void this.stop();
  }

  async listProjects() {
    const result = await this.request("project/list", {
      sortKey: "recencyAt",
      sortDirection: "desc",
      limit: 50,
    });
    return result.data || [];
  }

  async listModels() {
    const result = await this.request("model/list", { includeHidden: false, limit: 100 });
    return result.data || [];
  }

  async startConversation({ workspace, projectId = null, model = null, prompt }) {
    const thread = await this.request("thread/start", {
      cwd: workspace,
      projectId,
      model,
      allowProviderModelFallback: false,
      approvalPolicy: "untrusted",
      approvalsReviewer: "user",
      sandbox: "workspace-write",
    });
    const threadId = thread.thread?.id || thread.threadId;
    if (!threadId) throw new Error("codex thread/start returned no thread id");
    try {
      const turn = await this.startTurn({ threadId, model, prompt });
      return { threadId, turnId: turn.turnId || turn.turn?.id || this.activeTurns.get(threadId) || null };
    } catch (error) {
      error.threadId = threadId;
      error.phase = "turn";
      this.#releaseIfIdle();
      throw error;
    }
  }

  async sendTurn({ threadId, model = null, prompt, requestId = randomUUID() }) {
    try {
      if (this.threadBusy(threadId)) throw new Error("电脑正在回答，请结束后再发送；草稿已保留");
      try {
        await this.request("thread/resume", { threadId, excludeTurns: true });
      } catch (error) {
        if (!/active writer/i.test(error.message)) throw error;
        if (model) throw new Error("这个会话由电脑端管理，请选择“自动选择可用模型”后继续追问");
        return await this.#sendToDesktop({ threadId, prompt, requestId });
      }
      return await this.startTurn({ threadId, model, prompt });
    } catch (error) {
      error.threadId = threadId;
      error.phase = "turn";
      this.#releaseIfIdle();
      throw error;
    }
  }

  async #queuedDelivery(entry) {
    const read = await this.request("thread/read", { threadId: entry.threadId, includeTurns: true }, 3000);
    for (const turn of read.thread?.turns || []) {
      if ((turn.items || []).some(item => item.type === "userMessage" && item.clientId === entry.requestId)) {
        entry.delivered = true;
        return { turnId: turn.id, managedBy: "desktop" };
      }
    }
    return null;
  }

  async #cancelQueued(entry) {
    if (entry.delivered) return false;
    if (!entry.id) {
      const listed = await this.request("thread/queue/list", { threadId: entry.threadId }, 3000);
      entry.id = (listed.data || []).find(item => item.clientUserMessageId === entry.requestId)?.id;
    }
    if (!entry.id) return false;
    const result = await this.request("thread/queue/delete", { threadId: entry.threadId, queuedSubmissionId: entry.id }, 3000);
    return result.deleted === true;
  }

  async #sendToDesktop({ threadId, prompt, requestId }) {
    if (this.threadBusy(threadId)) throw new Error("电脑正在回答，请结束后再发送；草稿已保留");
    const entry = { threadId, requestId, id: null, delivered: false, cancelled: false };
    this.queuedTurns.set(requestId, entry);
    try {
      const added = await this.request("thread/queue/add", {
        threadId, clientUserMessageId: requestId, input: [{ type: "text", text: prompt }],
      }, 5000);
      entry.id = added.queuedSubmission?.id;
      if (!entry.id) throw new Error("Codex 未返回消息投递凭据");
      const deadline = Date.now() + this.queueDeliveryTimeoutMs;
      while (!entry.cancelled && Date.now() < deadline) {
        const delivered = await this.#queuedDelivery(entry);
        if (delivered) return delivered;
        if (this.threadBusy(threadId)) break;
        await new Promise(resolve => setTimeout(resolve, this.queuePollMs));
      }
      throw new Error("Codex 未及时接收消息");
    } catch (error) {
      if (entry.cancelled) {
        if (this.queueCleanupPromise) await this.queueCleanupPromise;
        if (entry.cancelledConfirmed) throw new Error("电脑尚未接收追问，消息已撤回；草稿已保留");
        throw new Error("codex app-server timeout: desktop message delivery status unknown");
      }
      try {
        const delivered = await this.#queuedDelivery(entry);
        if (delivered) return delivered;
      } catch (readError) {
        this.log(`codex queue receipt check failed thread=${threadId}: ${readError.message}`);
      }
      let cancelled = false;
      try { cancelled = await this.#cancelQueued(entry); }
      catch (cleanupError) { this.log(`codex queue cleanup failed thread=${threadId}: ${cleanupError.message}`); }
      if (cancelled) throw new Error("电脑尚未接收追问，消息已撤回；草稿已保留，请稍后手动重试");
      try {
        const afterCancel = await this.#queuedDelivery(entry);
        if (afterCancel) return afterCancel;
      } catch (readError) {
        this.log(`codex queue final receipt check failed thread=${threadId}: ${readError.message}`);
      }
      // 没确认消费也没确认撤销，必须保留未知态，不能自动重新提交。
      throw new Error("codex app-server timeout: desktop message delivery status unknown");
    } finally {
      this.queuedTurns.delete(requestId);
      this.#releaseIfIdle();
    }
  }

  async startTurn({ threadId, model = null, prompt }) {
    const result = await this.request("turn/start", {
      threadId,
      input: [{ type: "text", text: prompt }],
      model,
      approvalPolicy: "untrusted",
      approvalsReviewer: "user",
      sandboxPolicy: { type: "workspaceWrite" },
    });
    const turnId = result.turnId || result.turn?.id || null;
    if (turnId) this.activeTurns.set(threadId, turnId);
    return { ...result, turnId };
  }

  async interrupt(threadId) {
    const turnId = this.activeTurns.get(threadId);
    if (!turnId) return false;
    await this.request("turn/interrupt", { threadId, turnId });
    this.activeTurns.delete(threadId);
    this.#releaseIfIdle();
    return true;
  }

  async deleteThread(threadId) {
    await this.request("thread/delete", { threadId });
    this.activeTurns.delete(threadId);
    this.#releaseIfIdle();
    return true;
  }

  stop() {
    if (this.queueCleanupPromise) return this.queueCleanupPromise;
    if (this.queuedTurns.size) {
      const entries = [...this.queuedTurns.values()];
      entries.forEach(entry => { entry.cancelled = true; });
      this.queueCleanupPromise = Promise.all(entries.map(async entry => {
        try { entry.cancelledConfirmed = await this.#cancelQueued(entry); }
        catch (error) { this.log(`codex queue shutdown cleanup failed: ${error.message}`); }
      })).then(() => this.#stopChild()).finally(() => { this.queueCleanupPromise = null; });
      return this.queueCleanupPromise;
    }
    return this.#stopChild();
  }

  #stopChild() {
    this.stopped = true;
    this.readyPromise = null;
    const child = this.child;
    if (!child) return Promise.resolve();
    if (this.closingPromise) return this.closingPromise;
    this.closingPromise = new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        if (this.child === child) this.child = null;
        this.closingPromise = null;
        resolve();
      };
      child.once("exit", finish);
      child.once("close", finish);
      child.kill();
    });
    return this.closingPromise;
  }

  #onData(chunk) {
    this.buffer += chunk;
    let index;
    while ((index = this.buffer.indexOf("\n")) >= 0) {
      const line = this.buffer.slice(0, index).trim();
      this.buffer = this.buffer.slice(index + 1);
      if (!line) continue;
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        this.log(`codex app-server bad json: ${line.slice(0, 200)}`);
        continue;
      }
      this.#onMessage(message);
    }
  }

  #onMessage(message) {
    if (message.id != null && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id);
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) {
        const error = new Error(message.error.message || `codex ${pending.method} failed`);
        error.code = message.error.code;
        pending.reject(error);
      } else {
        pending.resolve(message.result ?? {});
      }
      return;
    }
    const thread = message.params?.thread;
    const childSource = thread?.source?.subagent || thread?.source?.subAgent;
    const eventThreadId = thread?.id || message.params?.threadId || message.params?.conversationId;
    if (childSource && eventThreadId) {
      const parentId = childSource.thread_spawn?.parent_thread_id || childSource.threadSpawn?.parentThreadId;
      this.internalThreads.set(eventThreadId, parentId || null);
    }
    // Child output/termination stays internal. Human permission requests are transferred only with a known parent.
    if (this.internalThreads.has(eventThreadId) && !CODEX_APPROVAL_METHODS.has(message.method)) return;
    if (message.id != null && /requestUserInput$/.test(message.method || "")) {
      const params = message.params || {};
      this.emit("event", { sessionId: params.threadId || params.conversationId, source: "codex", status: "waiting",
        inputRequests: questionRequests(params.itemId || String(message.id), params.questions) });
      return;
    }
    if (message.id != null && CODEX_APPROVAL_METHODS.has(message.method)) {
      const params = message.params || {};
      const nativeThreadId = params.threadId || params.conversationId;
      const threadId = this.internalThreads.has(nativeThreadId) ? this.internalThreads.get(nativeThreadId) : nativeThreadId;
      const requestId = `${params.turnId || params.itemId || "approval"}:${message.id}`;
      const entry = { method: message.method, params, threadId, requestId };
      this.approvals.set(message.id, entry);
      if (!threadId) return; // no arbitrary parent assignment; native request remains pending
      this.log(`codex approval pending id=${message.id} thread=${threadId} method=${message.method}`);
      this.emit("approval", {
        serverRequestId: message.id,
        threadId,
        method: message.method,
        summary: approvalSummary(message.method, params),
        requestId,
      });
      return;
    }
    const patch = codexEventPatch(message, this.messagePhases);
    if (patch) {
      const turnId = message.params?.turnId || message.params?.turn?.id;
      if (turnId && message.method === "turn/started") this.activeTurns.set(patch.sessionId, turnId);
      if (message.method === "turn/completed") this.activeTurns.delete(patch.sessionId);
      this.emit("event", patch);
      if (message.method === "turn/completed") this.#releaseIfIdle();
    }
  }
}


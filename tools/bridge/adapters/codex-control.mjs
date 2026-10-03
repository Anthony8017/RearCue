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
export function codexEventPatch(message) {
  const params = message?.params || {};
  const threadId = params.threadId || params.thread?.id;
  if (!threadId) return null;
  const turnId = params.turnId || params.turn?.id || null;
  const base = { sessionId: threadId, source: "codex" };
  if (message.method === "thread/started") {
    return { ...base, workspace: params.thread?.cwd || null, status: "idle" };
  }
  if (message.method === "turn/started") {
    return { ...base, status: "working", currentAction: null };
  }
  if (message.method === "turn/completed") {
    const turnError = params.turn?.error?.message || params.error?.message;
    return {
      ...base,
      status: turnError ? "error" : "idle",
      currentAction: null,
      summary: turnError || undefined,
    };
  }
  if (message.method === "item/completed") {
    const item = params.item || {};
    const text = itemText(item);
    if (item.type === "userMessage") return { ...base, userText: text || undefined, status: "working" };
    if (item.type === "agentMessage") return { ...base, assistantText: text || undefined, status: "working" };
    return null;
  }
  return null;
}

function itemText(item) {
  if (typeof item.text === "string") return item.text;
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
    env = process.env,
  } = {}) {
    super();
    this.command = command || resolveCodexCommand(env);
    this.args = args;
    this.spawnProcess = spawnProcess;
    this.log = log;
    this.requestTimeoutMs = requestTimeoutMs;
    this.env = env;
    this.child = null;
    this.buffer = "";
    this.nextId = 1;
    this.pending = new Map();
    this.approvals = new Map();
    this.activeTurns = new Map();
    this.readyPromise = null;
    this.stopped = false;
  }

  async start() {
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
    this.child = this.spawnProcess(this.command, this.args, {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
      env: this.env,
    });
    this.child.stdout.setEncoding("utf8");
    this.child.stderr.setEncoding("utf8");
    this.child.stdout.on("data", (chunk) => this.#onData(chunk));
    this.child.stderr.on("data", (chunk) => {
      const text = String(chunk).trim();
      if (text) this.log(`codex app-server stderr: ${text.slice(0, 500)}`);
    });
    this.child.on("error", (error) => {
      this.log(`codex app-server spawn failed: ${error.message}`);
      const failure = new Error(`codex app-server spawn failed: ${error.message}`);
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(failure);
      }
      this.pending.clear();
      this.approvals.clear();
      this.readyPromise = null;
      this.emit("exit", { code: null, signal: null, error });
    });
    this.child.on("exit", (code, signal) => {
      this.log(`codex app-server exit code=${code ?? "null"} signal=${signal ?? "null"}`);
      const error = new Error(`codex app-server exited code=${code ?? "null"}`);
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(error);
      }
      this.pending.clear();
      this.approvals.clear();
      this.readyPromise = null;
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
    this.emit("approvalResolved", { threadId: approval.threadId, action });
    return true;
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
      throw error;
    }
  }

  async sendTurn({ threadId, model = null, prompt }) {
    await this.request("thread/resume", { threadId, excludeTurns: true });
    return this.startTurn({ threadId, model, prompt });
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
    let turnId = this.activeTurns.get(threadId);
    if (!turnId) {
      const read = await this.request("thread/read", { threadId, includeTurns: true });
      turnId = (read.thread?.turns || []).filter((turn) => turn.status === "inProgress").at(-1)?.id || null;
    }
    if (!turnId) return false;
    await this.request("turn/interrupt", { threadId, turnId });
    this.activeTurns.delete(threadId);
    return true;
  }

  async deleteThread(threadId) {
    await this.request("thread/delete", { threadId });
    this.activeTurns.delete(threadId);
    return true;
  }

  stop() {
    this.stopped = true;
    this.child?.kill();
    this.child = null;
    this.readyPromise = null;
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
    if (message.id != null && CODEX_APPROVAL_METHODS.has(message.method)) {
      const params = message.params || {};
      const threadId = params.threadId || params.conversationId;
      const entry = { method: message.method, params, threadId };
      this.approvals.set(message.id, entry);
      this.log(`codex approval pending id=${message.id} thread=${threadId} method=${message.method}`);
      this.emit("approval", {
        serverRequestId: message.id,
        threadId,
        method: message.method,
        summary: approvalSummary(message.method, params),
      });
      return;
    }
    const patch = codexEventPatch(message);
    if (patch) {
      const turnId = message.params?.turnId || message.params?.turn?.id;
      if (turnId && message.method === "turn/started") this.activeTurns.set(patch.sessionId, turnId);
      if (message.method === "turn/completed") this.activeTurns.delete(patch.sessionId);
      this.emit("event", patch);
    }
  }
}


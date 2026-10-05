/**
 * Codex 适配器（ADR 0006 / 票 #118 / spec 0023 票 #238）：tail 活跃
 * `~/.codex/sessions` 深层的 `rollout-*.jsonl` 内容；
 * 注册名单与归档状态以桌面端 state_5.sqlite 为准。
 *
 * 活动映射（rollout 实测类型）：session_meta、assistant/user message、
 * custom_tool_call、task_started / task_complete / turn_aborted；回合完成或终止只表示 idle，
 * **不是归档**。索引中的可见性消失即出册，恢复可见性即回册；不因测试文件、
 * 空记录或迟到活动扩大名单。隔离文件解析测试可显式关闭索引验证。
 *
 * 会话名不从 rollout 猜：Codex 的自动命名/人工改名落在 `~/.codex/session_index.jsonl`
 * 的追加记录（issue #249 实测）。适配器每次 scan 对账索引，改名即发 title 补丁；
 * rollout 只负责身份、workspace 与问答活动。
 *
 * 生命周期在每次 scan 先对账、活动增量随后读：索引归档的会话先出
 * ARCHIVED 墓碑，尾随去抖里的迟到活动也会被桥的 membership ledger 拦下。
 * 默认 800ms 轮询，保持 spec 0023 的 2s 同步预算；测试可注入 pollMs/debounceMs。
 */
import { homedir } from "node:os";
import { join, basename, dirname } from "node:path";
import {
  readdirSync,
  statSync,
  existsSync,
  openSync,
  readSync,
  closeSync,
  readFileSync,
} from "node:fs";
import { readFileFrom, createDebouncedEmitter } from "./tail-util.mjs";
import { isInjectedUserText } from "./turn-log.mjs";
import { membershipFact, membershipFromExplicitHook } from "./source-membership.mjs";
import { codexSessionReadState, loadCodexReadState } from "./codex-read-state.mjs";
import { CodexQuestionTracker, codexQuestionReplies } from "./codex-questions.mjs";
import { questionRequests } from "./speech-facts.mjs";
import { readCodexThreadIndex } from "./codex-thread-index.mjs";

const RECENT_MS = 30 * 60 * 1000; // 近 30 分钟被改写 → 冷启动从头补读
const ACTION_MAX = 80;
export const CODEX_POLL_MS = 800;

/** 单行 → 部分状态补丁（无法识别返回 null）。导出供测试。 */
export function parseCodexLine(line) {
  const patch = parseCodexActivity(line);
  if (!patch) return null;
  const record = JSON.parse(line);
  const at = Date.parse(record.timestamp);
  if (Number.isFinite(at)) patch.sourceAt = at;
  if (record.payload?.turn_id) patch.turnId = record.payload.turn_id;
  return patch;
}

export function isCodexSubagentMeta(record) {
  return record?.type === "session_meta" && !!record.payload?.source?.subagent;
}

function parseCodexActivity(line) {
  let o;
  try {
    o = JSON.parse(line);
  } catch {
    return null;
  }
  // 显式来源在册契约（spec 0023 / 票 #236）：只有 membership 生命周期行能出册/回册。
  const membership = membershipFromExplicitHook("codex", o);
  if (membership) return membership;
  const p = o.payload;
  if (!p) return null;
  if (o.type === "session_meta") {
    if (isCodexSubagentMeta(o)) return null;
    return {
      sessionId: p.id || p.session_id || null,
      workspace: p.cwd || null,
      status: null,
      currentAction: null,
      latestReply: null,
    };
  }
  if (o.type === "response_item" && p.type === "message" && p.role === "assistant") {
    const text = (p.content || [])
      .map((c) => (typeof c?.text === "string" ? c.text : ""))
      .join("")
      .trim();
    return text ? { assistantText: text, status: "working", completeStream: true } : null;
  }
  if (o.type === "response_item" && p.type === "message" && p.role === "user") {
    const text = (p.content || [])
      .map((c) => (typeof c?.text === "string" ? c.text : ""))
      .join("")
      .trim();
    const replies = codexQuestionReplies(text);
    if (replies.length) return { userText: replies.map((r) => `${r.question || "回答"}\n${r.answer}`).join("\n\n"), status: "working" };
    // 电脑端不可见的注入上下文在 rollout 里也落成 role=user；别让它冒充机主提问或推进工作态。
    return text && !isInjectedUserText(text) ? { userText: text, status: "working" } : null;
  }
  if (o.type === "response_item" && p.type === "reasoning") {
    const text = reasoningText(p);
    return text ? { thinkingText: text, status: "working" } : null;
  }
  if (
    o.type === "response_item" &&
    (p.type === "custom_tool_call" || p.type === "function_call" || p.type === "tool_call")
  ) {
    const input = typeof p.input === "string" ? p.input : typeof p.arguments === "string" ? p.arguments : "";
    const compact = input.replace(/\s+/g, " ").trim();
    const action = `${p.name || "tool"} ${compact}`.trim().slice(0, ACTION_MAX);
    let inputRequests;
    if (/(?:^|\.)(request_user_input|request_user_input_async)$/.test(p.name || "")) {
      try { inputRequests = questionRequests(p.call_id || p.id, JSON.parse(input).questions, Date.parse(o.timestamp)); } catch { /* malformed tool input */ }
    }
    return {
      currentAction: action,
      status: "working",
      toolName: p.name || undefined,
      toolSummary: action || "Codex 工具调用",
      toolDetail: input || undefined,
      ...(inputRequests?.length ? { inputRequests } : {}),
    };
  }
  if (
    o.type === "response_item" &&
    (p.type === "custom_tool_call_output" || p.type === "function_call_output" || p.type === "tool_call_output")
  ) {
    const output = typeof p.output === "string" ? p.output : JSON.stringify(p.output ?? "");
    let resolvedRequestPrefix = p.call_id;
    try {
      const result = JSON.parse(output);
      if (result?.accepted === true || result?.pending === true) resolvedRequestPrefix = undefined;
    } catch { /* ordinary tool output */ }
    return {
      status: "working",
      toolResultSummary: "工具完成",
      toolResultDetail: output || undefined,
      ...(resolvedRequestPrefix ? { resolvedRequestPrefix } : {}),
    };
  }
  if (o.type === "event_msg" && p.type === "task_started") return { status: "working", taskStarted: true };
  if (o.type === "event_msg" && p.type === "turn_aborted") {
    // 桌面端手动停止只写 turn_aborted，不会补 task_complete；会话仍在册。
    return { status: "idle", currentAction: null, completion: "cancelled", clearInputRequests: true };
  }
  if (o.type === "event_msg" && p.type === "task_complete") {
    return {
      status: "idle",
      currentAction: null,
      completion: "done",
      assistantText: typeof p.last_agent_message === "string" && p.last_agent_message.trim()
        ? p.last_agent_message
        : undefined,
    };
  }
  return null;
}

function reasoningText(payload) {
  const values = [];
  if (typeof payload.text === "string") values.push(payload.text);
  if (typeof payload.summary_text === "string") values.push(payload.summary_text);
  for (const item of Array.isArray(payload.summary) ? payload.summary : []) {
    const text = typeof item === "string" ? item : item?.text;
    if (typeof text === "string") values.push(text);
  }
  for (const item of Array.isArray(payload.content) ? payload.content : []) {
    const text = typeof item === "string" ? item : item?.text;
    if (typeof text === "string") values.push(text);
  }
  return values.join("\n").trim();
}

/** Codex title-index 单行：同一 id 的追加记录按 updated_at 新者胜，平手取更晚一行。 */
export function parseCodexTitleRecord(line) {
  let o;
  try {
    o = JSON.parse(line);
  } catch {
    return null;
  }
  const sessionId = [o.id, o.session_id, o.thread_id].find(
    (value) => typeof value === "string" && value.trim(),
  );
  if (!sessionId) return null;
  const rawTitle = o.thread_name ?? o.name ?? o.display_title ?? o.title;
  const title = typeof rawTitle === "string" ? rawTitle.trim() || null : null;
  const rawUpdatedAt = o.updated_at ?? o.updatedAt ?? o.source_updated_at;
  const updatedAt = typeof rawUpdatedAt === "number"
    ? rawUpdatedAt
    : Date.parse(typeof rawUpdatedAt === "string" ? rawUpdatedAt : "");
  return { sessionId: sessionId.trim(), title, updatedAt: Number.isFinite(updatedAt) ? updatedAt : null };
}

/** 全量读 title index；坏行跳过，同 id 后写的新标题覆盖旧标题。 */
export function loadCodexTitleIndex(file) {
  const out = new Map();
  if (!file || !existsSync(file)) return out;
  let text = "";
  try {
    text = readFileSync(file, "utf8");
  } catch {
    return out;
  }
  let ordinal = 0;
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    const record = parseCodexTitleRecord(line);
    ordinal += 1;
    if (!record) continue;
    const prior = out.get(record.sessionId);
    const priorAt = prior?.updatedAt ?? Number.NEGATIVE_INFINITY;
    const nextAt = record.updatedAt ?? Number.NEGATIVE_INFINITY;
    if (!prior || nextAt >= priorAt) {
      out.set(record.sessionId, { ...record, ordinal });
    }
  }
  return out;
}

/** 文件名兜底会话 id：`rollout-<ts>-<uuid>.jsonl` 取最后一个 UUID 形段（meta 未读到时用）。 */
export function sessionIdFromFilename(file) {
  const name = basename(file);
  const uuids = name.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi);
  return uuids && uuids.length ? uuids[uuids.length - 1] : name.replace(/\.jsonl$/, "");
}

/** 递归收集 rollout 文件；membership 对账要看全部活跃/归档文件，不只近两天。
 * 全量递归覆盖各日目录，避免 UTC/本机日期分桶错指昨天。
 */
export function discoverRolloutFiles(root) {
  const out = [];
  if (!root || !existsSync(root)) return out;
  const now = Date.now();
  const walk = (dir) => {
    let entries = [];
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const file = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(file);
        continue;
      }
      if (!entry.isFile() || !entry.name.startsWith("rollout-") || !entry.name.endsWith(".jsonl")) continue;
      try {
        out.push({ file, recent: now - statSync(file).mtimeMs < RECENT_MS });
      } catch {
        /* raced */
      }
    }
  };
  walk(root);
  return out;
}

/** 读文件头找 session_meta；找不到退回文件名 UUID，保证移动前后 identity 稳定。 */
function fileIdentity(file) {
  let fallback = sessionIdFromFilename(file);
  let workspace = null;
  let internal = null;
  let fd;
  try {
    fd = openSync(file, "r");
    const buf = Buffer.allocUnsafe(64 * 1024);
    const n = readSync(fd, buf, 0, buf.length, 0);
    const firstLine = buf.toString("utf8", 0, n).split("\n", 1)[0];
    const parsed = JSON.parse(firstLine);
    internal = isCodexSubagentMeta(parsed);
    const id = parsed?.payload?.id || parsed?.payload?.session_id;
    if (typeof id === "string" && id.trim()) fallback = id.trim();
    const cwd = parsed?.payload?.cwd;
    if (typeof cwd === "string" && cwd.trim()) workspace = cwd.trim();
  } catch {
    /* 文件头坏/正在写：用稳定文件名兜底 */
  } finally {
    if (fd !== undefined) {
      try {
        closeSync(fd);
      } catch {
        /* ignore */
      }
    }
  }
  return { sessionId: fallback, workspace, internal };
}

function sessionIdForFile(file, fileMeta) {
  return fileMeta.get(file)?.sessionId || fileIdentity(file).sessionId;
}

function statePatchForLine(patch, meta) {
  return {
    source: "codex",
    workspace: meta.workspace,
    status: patch.status,
    currentAction: patch.currentAction,
    latestReply: patch.latestReply,
    title: meta.title ?? null,
  };
}

export function startCodexAdapter(emit, options = {}) {
  const root = options.root || join(homedir(), ".codex", "sessions");
  const archivedRoot = options.archivedRoot || join(homedir(), ".codex", "archived_sessions");
  const titleIndexFile = options.titleIndexFile || join(homedir(), ".codex", "session_index.jsonl");
  const globalStateFile = options.globalStateFile || join(homedir(), ".codex", ".codex-global-state.json");
  // null is only for isolated legacy file-parser fixtures; production always uses the desktop index.
  const threadIndexFile = options.threadIndexFile === null ? null :
    options.threadIndexFile || join(dirname(root), "state_5.sqlite");
  const pollMs = Number.isFinite(options.pollMs) ? Math.max(10, options.pollMs) : CODEX_POLL_MS;
  const debounceMs = Number.isFinite(options.debounceMs) ? Math.max(0, options.debounceMs) : 400;
  if (threadIndexFile === null && !existsSync(root) && !existsSync(archivedRoot)) {
    options.log?.("codex 适配器：无会话目录，跳过");
    return { stop() {} };
  }
  const offsets = new Map(); // file -> 下一读取字节偏移
  const fileMeta = new Map(); // file -> { sessionId, workspace }（跨 scan 记忆）
  const sessionState = new Map(); // sourceSessionId -> 最近状态补丁（unarchive 恢复）
  const questionTrackers = new Map(); // file -> 当前回合的待答题；任务状态独立保留
  const questionHistory = new Map(); // 去抖期间保留每个新题，不被后续工具补丁覆盖
  const locationBySession = new Map(); // sourceSessionId -> active|archived
  const titleBySession = new Map(); // sourceSessionId -> Codex 当前显示名（null=显式清空）
  let titleIndexStamp = null;
  let globalStateStamp = null;
  let sourceUnreadIds = new Set();
  const sourceReadStateBySession = new Map();
  const pendingReadSync = new Set(); // 完成时坏读：下一份可信状态补齐同态回执。
  const adapterStartedAt = Date.now();
  let threadIndex = null;
  let indexUnavailable = false;
  const internalFiles = new Map();
  const visibleFiles = (files) => files.filter(({ file }) => {
    if (!internalFiles.has(file)) {
      const internal = fileIdentity(file).internal;
      if (internal === null) return false;
      internalFiles.set(file, internal);
      if (internal) options.onInternalSession?.(fileIdentity(file).sessionId);
    }
    return !internalFiles.get(file);
  });
  let lifecycleRevision = Date.now();

  /** title index 只在文件变化时全量重读；返回本次标题发生变化的 id 集。 */
  const refreshTitleIndex = () => {
    let stamp = null;
    try {
      const st = statSync(titleIndexFile);
      stamp = `${st.size}:${st.mtimeMs}`;
    } catch {
      stamp = "missing";
    }
    if (stamp === titleIndexStamp) return new Set();
    titleIndexStamp = stamp;
    const next = loadCodexTitleIndex(titleIndexFile);
    const changed = new Set();
    for (const id of new Set([...titleBySession.keys(), ...next.keys()])) {
      const title = next.get(id)?.title ?? null;
      if ((titleBySession.get(id) ?? null) !== title) {
        changed.add(id);
        titleBySession.set(id, title);
      }
    }
    // rollout 的 fileMeta 跨 scan 记住 title；只更新 titleBySession 会让后续活动拿旧值，
    // 把刚同步的改名再次冲掉（issue #249 回归判例）。
    for (const meta of fileMeta.values()) {
      if (meta.sessionId && changed.has(meta.sessionId)) {
        meta.title = titleBySession.get(meta.sessionId) ?? null;
      }
    }
    return changed;
  };
  const nextLifecycleRevision = () => {
    lifecycleRevision = Math.max(lifecycleRevision + 1, Date.now());
    return lifecycleRevision;
  };
  const debounced = createDebouncedEmitter((event) => {
    if (threadIndexFile !== null && !threadIndex?.threads.get(event.sessionId)?.visible) return;
    const entries = questionHistory.get(event.sessionId);
    if (entries?.length) event.contentEntries = entries;
    questionHistory.delete(event.sessionId);
    if (event?.sessionId) {
      const prior = sessionState.get(event.sessionId) || {};
      sessionState.set(event.sessionId, {
        ...prior,
        ...Object.fromEntries(Object.entries(event).filter(([, v]) => v !== undefined)),
      });
    }
    // 正文一直打开时，官方集合会一直是已阅，没有「未读→已读」边沿可等。
    // 完成时重读来源事实，把回执放在同一事件里，避免被完成补丁覆盖。
    // 不缓存到活动补丁，防止后续改名/恢复会话拿旧回执覆盖新的未阅。
    // 坏读不作已阅事实，仍由桥按完整新回答推导没阅。
    if (event.completion === "done") {
      const unread = loadCodexReadState(globalStateFile, null);
      if (unread !== null) {
        event.readState = codexSessionReadState(unread, event.sessionId);
        pendingReadSync.delete(event.sessionId);
      } else {
        pendingReadSync.add(event.sessionId);
      }
    }
    emit(event);
  }, debounceMs);

  const emitMembership = (id, membership, reason) => {
    const revision = nextLifecycleRevision();
    const fact = membershipFact({
      source: "codex",
      sourceSessionId: id,
      membership,
      generation: revision,
      revision,
      reason,
    });
    if (!fact) return;
    const remembered = sessionState.get(id) || {};
    emit({
      ...remembered,
      ...fact,
      sessionId: id,
      source: "codex",
      status: remembered.status || "idle",
      updatedAt: Date.now(),
    });
  };

  /** 生命周期先于增量：active/archived 目录移动是唯一归档/取消归档事实。 */
  const reconcileMembership = (activeFiles, archivedFiles) => {
    const activeBySession = new Map();
    const archivedBySession = new Map();
    for (const item of activeFiles) {
      const id = sessionIdForFile(item.file, fileMeta);
      if (!activeBySession.has(id)) activeBySession.set(id, item);
    }
    for (const item of archivedFiles) {
      const id = sessionIdForFile(item.file, fileMeta);
      if (!archivedBySession.has(id)) archivedBySession.set(id, item);
    }
    const nextLocations = new Map();
    for (const id of activeBySession.keys()) nextLocations.set(id, "active");
    for (const id of archivedBySession.keys()) {
      // copy/move 的短暂双目录竞态：活跃目录优先，下一拍只剩 archived 再出墓碑。
      if (!nextLocations.has(id)) nextLocations.set(id, "archived");
    }
    for (const [id, location] of nextLocations) {
      const previous = locationBySession.get(id);
      if (previous === location) continue;
      if (location === "active") {
        // 首次发现活跃文件只登记位置，不伪造 ACTIVE 事实：活动事件本身负责露面；
        // 只有 archived -> active 的真实移动才是 unarchive 正事实。
        if (previous === "archived") emitMembership(id, "ACTIVE", "unarchive");
      } else {
        emitMembership(id, "ARCHIVED", "archive");
      }
      locationBySession.set(id, location);
    }
    // 同时从两个目录消失不是归档（研究口径：文件缺失/超时都不是生命周期事实）。
    for (const id of [...locationBySession.keys()]) {
      if (!nextLocations.has(id)) locationBySession.delete(id);
    }
  };

  const refreshThreadIndex = () => {
    const next = readCodexThreadIndex(threadIndexFile);
    if (!next.ok) {
      if (!indexUnavailable) options.log?.("codex 可见会话索引暂不可读：保持最后可信名单，不扩大文件收录范围");
      indexUnavailable = true;
      return threadIndex !== null;
    }
    indexUnavailable = false;
    const previous = threadIndex;
    threadIndex = next;
    options.onVisibleSessions?.(new Set([...next.threads].filter(([, row]) => row.visible).map(([id]) => id)));
    for (const [id, row] of previous?.threads || []) {
      if (!row.visible || next.threads.get(id)?.visible) continue;
      const archived = next.threads.get(id)?.archived === true;
      emitMembership(id, archived ? "ARCHIVED" : "ABSENT", archived ? "archive" : "source-hidden");
    }
    for (const [id, row] of next.threads) {
      if (!row.visible || previous?.threads.get(id)?.visible) continue;
      const remembered = sessionState.get(id) || {};
      sessionState.set(id, { ...remembered, workspace: row.workspace, title: titleBySession.get(id) ?? null });
      emitMembership(id, "ACTIVE", previous?.threads.get(id)?.archived ? "unarchive" : "source-visible");
    }
    return true;
  };

  const indexedFiles = () => {
    const out = [];
    for (const row of threadIndex.threads.values()) {
      if (!row.visible || !row.rolloutPath) continue;
      try {
        out.push({ file: row.rolloutPath, recent: Date.now() - statSync(row.rolloutPath).mtimeMs < RECENT_MS });
      } catch { /* index membership remains valid even if the content file is temporarily unavailable */ }
    }
    return visibleFiles(out);
  };

  const scan = () => {
    const changedTitleIds = refreshTitleIndex();
    if (threadIndexFile !== null && !refreshThreadIndex()) return;
    const activeFiles = threadIndexFile !== null ? indexedFiles() : visibleFiles(discoverRolloutFiles(root));
    if (threadIndexFile === null) reconcileMembership(activeFiles, visibleFiles(discoverRolloutFiles(archivedRoot)));
    for (const { file, recent } of activeFiles) {
      if (!offsets.has(file)) {
        // 冷启动：近活跃文件从头补读（恢复当前态）；较旧文件只补当前 idle，不重放历史正文。
        const start = recent ? 0 : statSync(file).size;
        offsets.set(file, start);
        if (!recent) {
          const identity = fileIdentity(file);
          const meta = fileMeta.get(file) || {
            sessionId: identity.sessionId,
            workspace: identity.workspace,
            title: titleBySession.get(identity.sessionId) ?? null,
          };
          fileMeta.set(file, meta);
          const tracker = new CodexQuestionTracker();
          // 旧文件只重建题目生命周期，不重放历史正文或完成提醒。
          const replay = readFileFrom(file, 0);
          for (const line of (replay?.text || "").split("\n")) {
            try { tracker.apply(JSON.parse(line)); } catch { /* partial/bad line */ }
          }
          questionTrackers.set(file, tracker);
          if (!sessionState.has(meta.sessionId)) {
            const baseline = {
              source: "codex",
              workspace: meta.workspace ?? null,
              status: tracker.pendingQuestions.length ? tracker.status : "idle",
              pendingQuestions: tracker.pendingQuestions,
              title: meta.title ?? null,
            };
            sessionState.set(meta.sessionId, baseline);
            debounced.schedule(meta.sessionId, baseline);
          }
          continue;
        }
      }
      const from = offsets.get(file);
      const read = readFileFrom(file, from);
      if (!read || !read.text) {
        if (read) offsets.set(file, read.next);
        continue;
      }
      const lines = read.text.split("\n");
      const lastPartial = read.text.endsWith("\n") ? "" : lines.pop();
      offsets.set(
        file,
        from + Buffer.byteLength(read.text, "utf8") - Buffer.byteLength(lastPartial || "", "utf8"),
      );
      const meta = fileMeta.get(file) || {
        sessionId: sessionIdFromFilename(file),
        workspace: null,
        title: titleBySession.get(sessionIdFromFilename(file)) ?? null,
      };
      const tracker = questionTrackers.get(file) || new CodexQuestionTracker();
      questionTrackers.set(file, tracker);
      for (const line of lines) {
        if (!line.trim()) continue;
        let changedQuestions = false;
        const previousQuestionIds = new Set(tracker.pendingQuestions.map((q) => q.id));
        let record;
        try { record = JSON.parse(line); changedQuestions = tracker.apply(record); } catch { /* bad line */ }
        if (record?.type === "event_msg" && ["task_complete", "turn_aborted"].includes(record.payload?.type) &&
          typeof record.payload.turn_id === "string" && tracker.currentTurnId &&
          record.payload.turn_id !== tracker.currentTurnId) continue;
        const parsed = parseCodexLine(line);
        if (!parsed && !changedQuestions) continue;
        const patch = parsed || { status: tracker.status };
        if (patch.kind === "membership") {
          if (threadIndexFile !== null) continue; // desktop index is the registration authority
          // rollout 内显式 membership 行仍走桥 ledger；它不是文件移动生命周期。
          emit({ ...patch, source: "codex", sessionId: patch.sourceSessionId });
          continue;
        }
        if (patch.sessionId) {
          meta.sessionId = patch.sessionId;
          meta.title = titleBySession.get(meta.sessionId) ?? null;
        }
        if (patch.workspace) meta.workspace = patch.workspace;
        const newQuestions = tracker.pendingQuestions.filter((q) => !previousQuestionIds.has(q.id));
        if (newQuestions.length) {
          const entries = questionHistory.get(meta.sessionId) || [];
          entries.push(...newQuestions.map((q) => ({
            kind: "question", entryId: q.id,
            text: [q.title, ...q.options.map((option) => `- ${option}`)].join("\n"),
          })));
          questionHistory.set(meta.sessionId, entries);
        }
        if (meta.sessionId) {
          const prior = sessionState.get(meta.sessionId) || {};
          sessionState.set(meta.sessionId, { ...prior, ...statePatchForLine(patch, meta), pendingQuestions: tracker.pendingQuestions });
        }
        debounced.schedule(meta.sessionId, {
          ...patch,
          source: "codex",
          replay: Number.isFinite(patch.sourceAt) && patch.sourceAt < adapterStartedAt,
          workspace: meta.workspace,
          status: patch.status,
          currentAction: patch.currentAction,
          userText: patch.userText,
          assistantText: patch.assistantText,
          title: meta.title,
          pendingQuestions: tracker.pendingQuestions,
        });
      }
      fileMeta.set(file, meta);
    }

    // Codex 电脑端已阅事实（ADR 0018）：只读官方 Electron 状态；从 unreadByIdentity
    // 消失＝来源端实际打开过正文。普通扫描只在成员变化时出 read-state；
    // 完成时的同态已阅由上面的完成事件补齐，无关写入不清蓝点。
    let readStamp = null;
    try {
      const st = statSync(globalStateFile);
      readStamp = `${st.size}:${st.mtimeMs}`;
    } catch {
      readStamp = "missing";
    }
    if (readStamp !== globalStateStamp || pendingReadSync.size) {
      const nextUnread = loadCodexReadState(globalStateFile, null);
      if (nextUnread !== null) {
        const initialReadState = globalStateStamp === null;
        globalStateStamp = readStamp;
        const changed = new Set();
        for (const id of new Set([...sessionState.keys(), ...sourceUnreadIds, ...nextUnread])) {
          const known = sessionState.has(id);
          const previous = sourceReadStateBySession.get(id);
          const next = codexSessionReadState(nextUnread, id);
          const pendingRead = pendingReadSync.has(id);
          if (previous === next && !pendingRead) continue;
          sourceReadStateBySession.set(id, next);
          // 启动时只补「没阅」；缺省会话按 ADR 0018 的旧会话初始已阅处理。
          if (known && (!initialReadState || next === "unread" || pendingRead)) changed.add(id);
        }
        sourceUnreadIds = nextUnread;
        pendingReadSync.clear();
        for (const id of changed) {
          emit({
            kind: "read-state",
            source: "codex",
            sessionId: id,
            readState: sourceReadStateBySession.get(id),
            updatedAt: Date.now(),
          });
        }
      }
    }
    // 纯改名也必须实时出事件；只对已由 rollout 露面的会话发，title index 不复活归档/历史会话。
    for (const id of changedTitleIds) {
      if (!sessionState.has(id)) continue;
      if (threadIndexFile !== null && !threadIndex?.threads.get(id)?.visible) continue;
      const remembered = sessionState.get(id) || {};
      debounced.schedule(id, {
        source: "codex",
        workspace: remembered.workspace ?? null,
        status: remembered.status || "idle",
        title: titleBySession.get(id) ?? null,
      });
    }
  };

  const timer = setInterval(scan, pollMs);
  scan();
  options.log?.(
    `codex 适配器已开 root=${root} archived=${archivedRoot} titles=${titleIndexFile} poll=${pollMs}ms`,
  );
  return {
    stop() {
      clearInterval(timer);
      debounced.dispose();
    },
  };
}

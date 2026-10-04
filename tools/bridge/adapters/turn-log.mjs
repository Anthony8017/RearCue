/**
 * 问答流构造（spec 0017 / 票 #169）：把「机主提问」与「agent 输出」按时间顺序攒成一份 turns，
 * 并按尾部窗口（默认最近 20 条 / 16000 字）裁剪——桥每次把**整个 turns 数组**发给手机，
 * 手机端重渲染整段（自愈：漏一帧也能靠下一帧追上）。
 *
 * 票 #177（完整历史）：存量**不再裁剪**——内部持有全量历史（受 [maxHistory] 内存护栏），
 * `list()` 仍只给尾部窗口（实时推流口径不变），`all()` 给全量（`GET /history` 消费，
 * 手机回看当前会话从头到尾）。窗口自此是**视图**，不是删存量的剪刀。
 *
 * 为什么窗口在桥侧：电脑端的原始记录是无限长的，手机只需要「最近这一段」；
 * 窗口值与 spec 0010 时代的 `tail-util.createTailHistory` 保持一致（20 条 / 16000 字），
 * 换来的差别是**提问与回答共用同一个窗口**，不再是「只有助手文本被留、提问被丢」。
 *
 * 一条 turn 的形状（与手机端 `AgentTurn` 对齐）：
 *   { role: "user"|"assistant", text: string, ts: number, open?: boolean }
 * `open: true` 表示这一条**仍在增长**（Claude 的 MessageDisplay 中间批、ZCode 的流式增量）。
 */
/**
 * 电脑端不显示、但来源日志会伪装成 `role=user` 的注入上下文。
 *
 * 判据只认**结构明确**的封装；无法可靠判断的一律保留为真实提问，宁可多显示一条，
 * 也不静默吞掉机主输入。旧历史由 visibleTurns() 在出口统一过滤。
 */
export function isInjectedUserText(text) {
  const value = typeof text === "string" ? text.trim() : "";
  if (!value) return false;
  if (
    (value.startsWith("# AGENTS.md instructions for ") ||
      value.startsWith("# CLAUDE.md instructions for ")) &&
    value.includes("\n<INSTRUCTIONS>")
  ) {
    return true;
  }
  if (
    value.startsWith("<external_codex_apps_open_page>") &&
    /^<external_codex_apps_open_page>\s*\{[\s\S]*"page_id"[\s\S]*\}<\/external_codex_apps_open_page>$/.test(value)
  ) {
    return true;
  }
  if (
    value.startsWith("<subagent_notification>") &&
    /^<subagent_notification>\s*\{[\s\S]*"agent_path"[\s\S]*"status"[\s\S]*\}<\/subagent_notification>$/.test(value)
  ) {
    return true;
  }
  for (const tag of ["system-reminder", "environment_context", "app_context", "permissions instructions", "skills_instructions"]) {
    if (value.startsWith(`<${tag}>`) && value.endsWith(`</${tag}>`)) return true;
  }
  return false;
}

export function createTurnLog({ maxEntries = 20, maxChars = 16000, maxHistory = 5000, separator = "\n\n────────\n\n" } = {}) {
  /** @type {Array<Record<string, any>>} */
  let turns = [];
  let nextEntryId = 1;

  const totalChars = (list) => {
    if (list.length === 0) return 0;
    const body = list.reduce((sum, t) => sum + (typeof t.text === "string" ? t.text.length : 0), 0);
    return body + separator.length * (list.length - 1);
  };

  function trimHistory() {
    while (turns.length > maxHistory) turns.shift();
  }

  function visibleTurns() {
    return turns.filter((turn) => !(turn.role === "user" && isInjectedUserText(turn.text)));
  }

  function windowView() {
    const visible = visibleTurns();
    let start = 0;
    while (start < visible.length - 1) {
      const tail = visible.slice(start);
      if (tail.length <= maxEntries && totalChars(tail) <= maxChars) break;
      start++;
    }
    return visible.slice(start);
  }

  function isDuplicateOfLast(role, kind, text) {
    const last = turns[turns.length - 1];
    return !!last && last.role === role && last.kind === kind && last.text === text;
  }

  function normalizeKind(role, kind) {
    const normalized = String(kind || "").trim().toLowerCase().replaceAll("-", "_");
    const allowed = new Set([
      "prompt", "answer", "thinking", "tool", "tool_result", "error", "approval", "usage", "notice", "question",
    ]);
    if (allowed.has(normalized)) return normalized;
    return role === "user" ? "prompt" : "answer";
  }

  function pushEntry(input, ts = Date.now()) {
    const role = input.role === "user" ? "user" : "assistant";
    const kind = normalizeKind(role, input.kind);
    const text = typeof input.text === "string" ? input.text.trim() : "";
    const detail = typeof input.detail === "string" && input.detail ? input.detail : undefined;
    if (!text && !detail) return this.list();
    if (isDuplicateOfLast(role, kind, text)) return this.list();
    const entry = {
      entryId: input.entryId || `turn-${nextEntryId++}`,
      role,
      kind,
      text,
      ts,
    };
    if (detail !== undefined) entry.detail = detail;
    for (const key of ["toolName", "command", "path"]) {
      if (typeof input[key] === "string" && input[key]) entry[key] = input[key];
    }
    if (input.open === true) entry.open = true;
    turns.push(entry);
    trimHistory();
    return this.list();
  }

  function appendDelta(kind, text, ts = Date.now(), entryId) {
    if (typeof text !== "string" || !text) return this.list();
    const last = turns[turns.length - 1];
    if (last && last.role === "assistant" && last.kind === kind && last.open) {
      last.text += text;
    } else {
      turns.push({
        entryId: entryId || `turn-${nextEntryId++}`,
        role: "assistant",
        kind,
        text,
        ts,
        open: true,
      });
    }
    trimHistory();
    return this.list();
  }

  return {
    user(text, ts = Date.now()) {
      return pushEntry.call(this, { role: "user", kind: "prompt", text }, ts);
    },

    agent(text, ts = Date.now()) {
      const t = (text || "").trim();
      if (!t) return this.list();
      const last = turns[turns.length - 1];
      if (last && last.role === "assistant" && last.open && last.kind === "answer") {
        last.text = t;
        delete last.open;
        return this.list();
      }
      return pushEntry.call(this, { role: "assistant", kind: "answer", text: t }, ts);
    },

    delta(text, ts = Date.now(), entryId) {
      return appendDelta.call(this, "answer", text, ts, entryId);
    },

    thinking(text, ts = Date.now()) {
      return pushEntry.call(this, { role: "assistant", kind: "thinking", text }, ts);
    },

    thinkingDelta(text, ts = Date.now(), entryId) {
      return appendDelta.call(this, "thinking", text, ts, entryId);
    },

    tool(summary, detail, metadata = {}, ts = Date.now()) {
      return pushEntry.call(this, {
        role: "assistant",
        kind: "tool",
        text: summary,
        detail,
        ...metadata,
      }, ts);
    },

    toolResult(summary, detail, metadata = {}, ts = Date.now()) {
      return pushEntry.call(this, {
        role: "assistant",
        kind: "tool_result",
        text: summary,
        detail,
        ...metadata,
      }, ts);
    },

    error(summary, detail, ts = Date.now()) {
      return pushEntry.call(this, { role: "assistant", kind: "error", text: summary, detail }, ts);
    },

    approval(summary, detail, ts = Date.now()) {
      return pushEntry.call(this, { role: "assistant", kind: "approval", text: summary, detail }, ts);
    },

    usage(summary, detail, ts = Date.now()) {
      return pushEntry.call(this, { role: "assistant", kind: "usage", text: summary, detail }, ts);
    },

    /**
     * 通知行（issue #307）：**不是机主提问、也不是回答**，而是来源自己发的通知
     * （DSH 的「收到任务消息 / 子任务状态更新 / 后台任务状态更新」）。背屏按低强调一行显示，
     * 原文进 detail；不进 latestReply、不推进「空闲·没阅」（两者只看 kind=answer）。
     */
    notice(summary, detail, ts = Date.now()) {
      return pushEntry.call(this, { role: "assistant", kind: "notice", text: summary, detail }, ts);
    },

    entry(input, ts = Date.now()) {
      return pushEntry.call(this, input, ts);
    },

    complete(ts = Date.now()) {
      const last = turns[turns.length - 1];
      if (last?.open) {
        delete last.open;
        if (Number.isFinite(ts)) last.ts = ts;
      }
      return this.list();
    },

    reset() {
      turns = [];
      return this.list();
    },

    list() {
      return windowView().map((t) => ({ ...t }));
    },

    all() {
      return visibleTurns().map((t) => ({ ...t }));
    },

    latestReply() {
      for (let i = turns.length - 1; i >= 0; i--) {
        if (turns[i].role === "assistant" && turns[i].kind === "answer") return turns[i].text;
      }
      return null;
    },

    get size() {
      return windowView().length;
    },
  };
}

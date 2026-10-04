/**
 * Claude Desktop / Claude Code 适配器（ADR 0006 / 票 #119；spec 0017 / 票 #169 改问答流口径）：
 * tail `~/.claude/projects/<slug>/<sessionId>.jsonl` 的增量行，映射为统一会话事件。
 *
 * 映射（transcript 实测类型，2026-09-28 本机样本）：
 * - 每行 cwd / 文件名    → workspace（跨 scan 记忆）/ sessionId（文件名即会话 id）
 * - type=assistant + text → **一条完整助手输出**（`assistantText`）交给桥的问答流窗口；
 *   tool_use → currentAction（工具名+摘要）
 * - type=user 纯文本     → **机主提问**（`userText`，spec 0017 起采集；此前这类行因
 *   `content` 不是数组而被整体丢弃，背屏永远看不到「我问了什么」）
 * - 增量行 / tool_result  → status=working（会话静默保持原状，idle/waiting 由 hooks
 *                          经桥 /hooks/claude 注入——见 adapters/claude-hook.mjs）
 *
 * 流式：**真正的逐字中间态来自 `MessageDisplay` 钩子**（claude-hook.mjs 转发），本适配器
 * 只能给到「整块落盘」的粒度——本机实测 assistant 行虽在流式途中就开始写（多行消息的
 * `usage.output_tokens` 递增），但 `text` 块本身只写一次、不增量。
 *
 * Chat 标签（claude.ai 聊天）不在此列（无落盘 jsonl，ADR 0006 明确放弃）。
 * 只跟踪「最近 30 分钟内活跃」的 transcript，冷启动从头补读恢复当前态；
 * 坏行跳过；每会话尾随去抖（[tail-util]）。
 */
import { homedir } from "node:os";
import { join } from "node:path";
import { readdirSync, statSync, existsSync } from "node:fs";
import { readFileFrom, createDebouncedEmitter } from "./tail-util.mjs";
import { membershipFromExplicitHook } from "./source-membership.mjs";
import { questionRequests } from "./speech-facts.mjs";
import {
  CLAUDE_MEMBERSHIP_POLL_MS,
  defaultClaudeMembershipRoot,
  startClaudeMembershipScanner,
} from "./claude-membership.mjs";

const RECENT_MS = 30 * 60 * 1000;
const ACTION_MAX = 80;
const POLL_MS = CLAUDE_MEMBERSHIP_POLL_MS;

/** 单行 → 部分状态补丁（无法识别返回 null）。导出供测试。 */
export function parseClaudeLine(line) {
  let o;
  try {
    o = JSON.parse(line);
  } catch {
    return null;
  }
  if (o.isSidechain === true || (typeof o.parent_tool_use_id === "string" && o.parent_tool_use_id)) return null;
  // 显式来源在册契约（spec 0023 / 票 #236）：Stop/活动/文件生命周期都不是归档事实。
  const membership = membershipFromExplicitHook("claude", o);
  if (membership) return membership;
  const patch = {};
  if (typeof o.cwd === "string" && o.cwd) patch.workspace = o.cwd;
  const content = o.message?.content;
  if (o.type === "assistant" && Array.isArray(content)) {
    const texts = content.filter((c) => c?.type === "text" && typeof c.text === "string").map((c) => c.text);
    const tools = content.filter((c) => c?.type === "tool_use");
    const requests = tools.filter((tool) => tool.name === "AskUserQuestion").flatMap((tool) => questionRequests(tool.id, tool.input?.questions));
    if (requests.length) patch.inputRequests = requests;
    if (texts.length) {
      const text = texts.join("").trim();
      if (text) {
        patch.assistantText = text;
        patch.status = "working";
      }
    }
    if (tools.length) {
      const t = tools[tools.length - 1];
      const rawInput = t.input ? JSON.stringify(t.input) : "";
      const input = rawInput.replace(/\s+/g, " ");
      patch.currentAction = `${t.name || "tool"} ${input}`.slice(0, ACTION_MAX);
      patch.toolName = t.name || undefined;
      patch.toolSummary = patch.currentAction;
      patch.toolDetail = rawInput || undefined;
      patch.status = "working";
    }
  }
  // 机主提问（spec 0017 / 票 #169）：纯文本 user 行——此前因 `content` 不是数组被整体丢弃。
  if (o.type === "user") {
    if (typeof content === "string" && content.trim()) {
      patch.userText = content.trim();
    } else if (Array.isArray(content)) {
      const results = content.filter((c) => c?.type === "tool_result");
      if (results.length) {
        patch.resolvedRequestPrefixes = results.map((result) => result.tool_use_id).filter(Boolean);
        const result = results[results.length - 1];
        const detail = typeof result.content === "string"
          ? result.content
          : JSON.stringify(result.content ?? "");
        patch.status = "working";
        patch.toolResultSummary = "工具完成";
        patch.toolResultDetail = detail || undefined;
      } else {
        const texts = content.filter((c) => c?.type === "text" && typeof c.text === "string").map((c) => c.text);
        const text = texts.join("").trim();
        if (text) patch.userText = text;
      }
    }
  }
  return Object.keys(patch).length ? patch : null;
}

/** 近 30 分钟内活跃的 transcript（全部项目目录；不排序，活跃性由 mtime 窗口判定）。 */
function discoverTranscripts(root) {
  const out = [];
  const now = Date.now();
  let slugs = [];
  try {
    slugs = readdirSync(root);
  } catch {
    return out;
  }
  for (const slug of slugs) {
    const dir = join(root, slug);
    let names = [];
    try {
      names = readdirSync(dir);
    } catch {
      continue;
    }
    for (const n of names) {
      if (!n.endsWith(".jsonl")) continue;
      const file = join(dir, n);
      try {
        const st = statSync(file);
        if (now - st.mtimeMs < RECENT_MS) out.push({ file, sessionId: n.replace(/\.jsonl$/, "") });
      } catch {
        /* raced */
      }
    }
  }
  return out;
}

export function startClaudeAdapter(emit, options = {}) {
  const root = options.root || join(homedir(), ".claude", "projects");
  const membershipRoot = options.membershipRoot || defaultClaudeMembershipRoot();
  const pollMs = Number.isFinite(options.pollMs) ? Math.max(10, options.pollMs) : POLL_MS;
  const debounceMs = Number.isFinite(options.debounceMs) ? Math.max(0, options.debounceMs) : 400;
  if (!existsSync(root) && !existsSync(membershipRoot)) {
    options.log?.("claude 适配器：无 projects/local-agent-mode-sessions 目录，跳过");
    return { stop() {} };
  }
  const offsets = new Map(); // file -> 下一读取字节偏移
  const workspaces = new Map(); // sessionId -> workspace（跨 scan 记忆）
  const sessionState = new Map(); // sessionId -> 最近活动状态（unarchive 恢复）
  const emitActivity = (event) => {
    if (event?.sessionId) {
      const prior = sessionState.get(event.sessionId) || {};
      sessionState.set(event.sessionId, {
        ...prior,
        ...Object.fromEntries(Object.entries(event).filter(([, value]) => value !== undefined)),
      });
    }
    emit(event);
  };
  const debounced = createDebouncedEmitter(emitActivity, debounceMs);
  const membershipScanner = startClaudeMembershipScanner(
    (fact) => {
      const remembered = sessionState.get(fact.sourceSessionId) || {};
      emit({
        ...remembered,
        ...fact,
        sessionId: fact.sourceSessionId,
        source: "claude",
        workspace: remembered.workspace || workspaces.get(fact.sourceSessionId) || null,
        status: remembered.status || "idle",
        updatedAt: Date.now(),
      });
    },
    {
      root: membershipRoot,
      pollMs,
      autoStart: false,
      scanImmediately: false,
      log: options.log,
    },
  );

  const scanTranscripts = () => {
    if (!existsSync(root)) return;
    for (const { file, sessionId } of discoverTranscripts(root)) {
      if (!offsets.has(file)) {
        // 近活跃文件从头补读（恢复当前态——包括滚动尾巴，首事件即带历史正文）。
        offsets.set(file, 0);
      }
      const from = offsets.get(file);
      const read = readFileFrom(file, from);
      if (!read || !read.text) {
        if (read) offsets.set(file, read.next);
        continue;
      }
      const lines = read.text.split("\n");
      const lastPartial = read.text.endsWith("\n") ? "" : lines.pop();
      offsets.set(file, from + Buffer.byteLength(read.text, "utf8") - Buffer.byteLength(lastPartial || "", "utf8"));
      for (const line of lines) {
        if (!line.trim()) continue;
        const patch = parseClaudeLine(line);
        if (!patch) continue;
        if (patch.workspace) workspaces.set(sessionId, patch.workspace);
        // spec 0017 / 票 #169：提问与回答都按「一条」交给桥的问答流窗口（窗口只有一份）。
        debounced.schedule(sessionId, {
          ...patch,
          source: "claude",
          workspace: workspaces.get(sessionId) || null,
          status: patch.status,
          currentAction: patch.currentAction,
          userText: patch.userText,
          assistantText: patch.assistantText,
          toolName: patch.toolName,
          toolSummary: patch.toolSummary,
          toolDetail: patch.toolDetail,
          toolResultSummary: patch.toolResultSummary,
          toolResultDetail: patch.toolResultDetail,
        });
      }
    }
  };

  const scan = () => {
    scanTranscripts();
    membershipScanner.scan();
  };
  const timer = setInterval(scan, pollMs);
  scan();
  options.log?.(`claude 适配器已开 root=${root} membership=${membershipRoot} poll=${pollMs}ms`);
  return {
    stop() {
      clearInterval(timer);
      membershipScanner.stop();
      debounced.dispose();
    },
  };
}

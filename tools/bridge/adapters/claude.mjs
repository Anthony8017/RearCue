/**
 * Claude Desktop / Claude Code 适配器（ADR 0006 / 票 #119）：tail
 * `~/.claude/projects/<slug>/<sessionId>.jsonl` 的增量行，映射为统一会话事件。
 *
 * 映射（transcript 实测类型，2026-09-28 本机样本）：
 * - 每行 cwd / 文件名    → workspace / sessionId（文件名即会话 id）
 * - type=assistant + message.content text → latestReply（取最新一条助手文本）
 * - type=assistant + message.content tool_use → currentAction（工具名+摘要）
 * - 有增量行            → status=working（会话静默时保持原状，idle 由 Stop hook 经
 *                          桥 /hooks/claude 注入——见 adapters/claude-hook.mjs）
 *
 * Chat 标签（claude.ai 聊天）不在此列（无落盘 jsonl，ADR 0006 明确放弃）。
 * 只跟踪「最近 30 分钟内活跃」的 transcript，冷启动从头补读恢复当前态；
 * 坏行跳过；每会话尾随 400ms 去抖。
 */
import { homedir } from "node:os";
import { join } from "node:path";
import { readdirSync, statSync, openSync, readSync, closeSync, existsSync } from "node:fs";

const RECENT_MS = 30 * 60 * 1000;
const ACTION_MAX = 80;
const POLL_MS = 1000;
const DEBOUNCE_MS = 400;

/** 单行 → 部分状态补丁（无法识别返回 null）。导出供测试。 */
export function parseClaudeLine(line) {
  let o;
  try {
    o = JSON.parse(line);
  } catch {
    return null;
  }
  const patch = {};
  if (typeof o.cwd === "string" && o.cwd) patch.workspace = o.cwd;
  const content = o.message?.content;
  if (o.type === "assistant" && Array.isArray(content)) {
    const texts = content.filter((c) => c?.type === "text" && typeof c.text === "string").map((c) => c.text);
    const tools = content.filter((c) => c?.type === "tool_use");
    if (texts.length) {
      const text = texts.join("").trim();
      if (text) {
        patch.latestReply = text;
        patch.status = "working";
      }
    }
    if (tools.length) {
      const t = tools[tools.length - 1];
      const input = t.input ? JSON.stringify(t.input).replace(/\s+/g, " ") : "";
      patch.currentAction = `${t.name || "tool"} ${input}`.slice(0, ACTION_MAX);
      patch.status = "working";
    }
  }
  if (o.type === "user" && Array.isArray(content) && content.some((c) => c?.type === "tool_result")) {
    patch.status = "working";
  }
  return Object.keys(patch).length ? patch : null;
}

/** 近期活跃的 transcript 文件（全部项目目录里 mtime 最新者优先）。 */
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

function readFileFrom(file, offset) {
  let fd;
  try {
    fd = openSync(file, "r");
    const size = statSync(file).size;
    if (size <= offset) return { text: "", next: size };
    const len = size - offset;
    const buf = Buffer.allocUnsafe(len);
    readSync(fd, buf, 0, len, offset);
    return { text: buf.toString("utf8"), next: size };
  } catch {
    return null;
  } finally {
    if (fd !== undefined) {
      try {
        closeSync(fd);
      } catch {
        /* ignore */
      }
    }
  }
}

export function startClaudeAdapter(emit, options = {}) {
  const root = options.root || join(homedir(), ".claude", "projects");
  if (!existsSync(root)) {
    options.log?.("claude 适配器：无 projects 目录，跳过");
    return { stop() {} };
  }
  const offsets = new Map();
  const pending = new Map();
  const timers = new Map();

  const flush = (sessionId) => {
    timers.delete(sessionId);
    const st = pending.get(sessionId);
    pending.delete(sessionId);
    if (st) emit({ ...st, sessionId, updatedAt: Date.now() });
  };
  const schedule = (sessionId, patch) => {
    const cur = pending.get(sessionId) || {};
    pending.set(sessionId, {
      ...cur,
      ...Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined)),
    });
    if (!timers.has(sessionId)) {
      timers.set(sessionId, setTimeout(() => flush(sessionId), DEBOUNCE_MS));
    }
  };

  const scan = () => {
    for (const { file, sessionId } of discoverTranscripts(root)) {
      if (!offsets.has(file)) {
        // 近活跃文件从头补读（恢复当前态——包括最新 assistant 文本，首事件即有正文）。
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
      let workspace = null;
      for (const line of lines) {
        if (!line.trim()) continue;
        const patch = parseClaudeLine(line);
        if (!patch) continue;
        if (patch.workspace) workspace = patch.workspace;
        schedule(sessionId, { workspace, status: patch.status, currentAction: patch.currentAction, latestReply: patch.latestReply });
      }
    }
  };

  const timer = setInterval(scan, POLL_MS);
  scan();
  options.log?.(`claude 适配器已开 root=${root}`);
  return {
    stop() {
      clearInterval(timer);
      for (const t of timers.values()) clearTimeout(t);
      timers.clear();
    },
  };
}

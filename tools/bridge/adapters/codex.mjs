/**
 * Codex 适配器（ADR 0006 / 票 #118）：tail `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`
 * 的增量行，映射为统一会话事件灌进桥（[emit] 即 bridge.mjs 的 appendEvent）。
 *
 * 映射（rollout 实测类型，2026-09-28 样本；spec 0017 / 票 #169 改问答流口径）：
 * - session_meta            → sessionId / workspace=cwd（**跨 scan 记忆**：meta 只在文件
 *   首行，每次 scan 重建会导致后续批次落到文件路径键——评审修复）
 * - response_item/message(assistant) → **一条完整助手输出**（`assistantText`）交给桥的问答流
 *   窗口攒与裁剪（窗口只有一份，不在适配器里再拼一遍整段尾巴）
 * - response_item/message(user)      → **机主提问**（`userText`，spec 0017 起采集；
 *   spec 0010 时代这行被整行丢弃，背屏只看到无头无尾的回答）
 * - response_item/custom_tool_call → currentAction（name + input 摘要，单行截断）
 * - event_msg/task_started  → status=working
 * - event_msg/task_complete → status=idle + last_agent_message 当一条助手输出
 *
 * 流式能力（2026-09-29 实测）：rollout 在回合进行中持续追加（工具调用、思考摘要、token 计数），
 * 但**助手正文只有整条落盘**——`item/agentMessage/delta` 只存在于 app-server 协议，Windows 上
 * 那个进程是桌面程序的 stdio 子进程，外部观察者不可达。故 Codex 接受**消息级**到达。
 *
 * 容错（ADR 0006：会话文件是非稳定接口）：坏行跳过不抛；文件冷启动只跟增量
 * （30 分钟内被改写的文件从头补读，恢复当前态）；每会话尾随去抖（[tail-util]）。
 * 等待批准信号不来自 rollout——经桥 /hooks/codex 的 notify 事件注入。
 */
import { homedir } from "node:os";
import { join, basename } from "node:path";
import { readdirSync, statSync, existsSync } from "node:fs";
import { readFileFrom, createDebouncedEmitter } from "./tail-util.mjs";

const RECENT_MS = 30 * 60 * 1000; // 近 30 分钟被改写 → 冷启动从头补读
const ACTION_MAX = 80;
const POLL_MS = 800;

/** 单行 → 部分状态补丁（无法识别返回 null）。导出供测试。 */
export function parseCodexLine(line) {
  let o;
  try {
    o = JSON.parse(line);
  } catch {
    return null;
  }
  const p = o.payload;
  if (!p) return null;
  if (o.type === "session_meta") {
    return {
      sessionId: p.session_id || p.id || null,
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
    // spec 0017：助手正文按**一条完整消息**交出去（桥的问答流窗口负责攒与裁剪），
    // 不再在适配器里拼整段尾巴。
    return text ? { assistantText: text, status: "working" } : null;
  }
  if (o.type === "response_item" && p.type === "message" && p.role === "user") {
    // 机主提问（spec 0017 / 票 #169）：spec 0010 时代这里被整行丢弃，背屏只看到无头无尾的回答。
    const text = (p.content || [])
      .map((c) => (typeof c?.text === "string" ? c.text : ""))
      .join("")
      .trim();
    return text ? { userText: text, status: "working" } : null;
  }
  if (o.type === "response_item" && p.type === "custom_tool_call") {
    const input = typeof p.input === "string" ? p.input.replace(/\s+/g, " ").trim() : "";
    const action = `${p.name || "tool"} ${input}`.trim().slice(0, ACTION_MAX);
    return { currentAction: action, status: "working" };
  }
  if (o.type === "event_msg" && p.type === "task_started") return { status: "working" };
  if (o.type === "event_msg" && p.type === "task_complete") {
    return {
      status: "idle",
      currentAction: null,
      assistantText: typeof p.last_agent_message === "string" && p.last_agent_message.trim()
        ? p.last_agent_message
        : undefined,
    };
  }
  return null;
}

/** 文件名兜底会话 id：`rollout-<ts>-<uuid>.jsonl` 取最后一个 UUID 形段（meta 未读到时用）。 */
export function sessionIdFromFilename(file) {
  const name = basename(file);
  const uuids = name.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi);
  return uuids && uuids.length ? uuids[uuids.length - 1] : name.replace(/\.jsonl$/, "");
}

/** 近 2 天的 rollout 文件（按日目录），返回 [{file, recent}]。
 * 会话目录使用本机日期分桶；UTC 日期会在北京时间午夜后错指昨天。
 */
function discoverRolloutFiles(root) {
  const out = [];
  const now = Date.now();
  for (const dayOffset of [0, 1]) {
    const d = new Date(now - dayOffset * 86400_000);
    const dir = join(
      root,
      String(d.getFullYear()),
      String(d.getMonth() + 1).padStart(2, "0"),
      String(d.getDate()).padStart(2, "0"),
    );
    if (!existsSync(dir)) continue;
    let names = [];
    try {
      names = readdirSync(dir);
    } catch {
      continue;
    }
    for (const n of names) {
      if (!n.startsWith("rollout-") || !n.endsWith(".jsonl")) continue;
      const file = join(dir, n);
      try {
        out.push({ file, recent: now - statSync(file).mtimeMs < RECENT_MS });
      } catch {
        /* raced */
      }
    }
  }
  return out;
}

export function startCodexAdapter(emit, options = {}) {
  const root = options.root || join(homedir(), ".codex", "sessions");
  if (!existsSync(root)) {
    options.log?.("codex 适配器：无会话目录，跳过");
    return { stop() {} };
  }
  const offsets = new Map(); // file -> 下一读取字节偏移
  const fileMeta = new Map(); // file -> { sessionId, workspace }（跨 scan 记忆）
  const debounced = createDebouncedEmitter(emit);

  const scan = () => {
    for (const { file, recent } of discoverRolloutFiles(root)) {
      if (!offsets.has(file)) {
        // 冷启动：近活跃文件从头补读（恢复当前态），其余只跟新增量。
        const start = recent ? 0 : statSync(file).size;
        offsets.set(file, start);
        if (!recent) continue;
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
      const meta = fileMeta.get(file) || { sessionId: sessionIdFromFilename(file), workspace: null };
      for (const line of lines) {
        if (!line.trim()) continue;
        const patch = parseCodexLine(line);
        if (!patch) continue; // 坏行/无关类型：跳过（容错契约）
        if (patch.sessionId) meta.sessionId = patch.sessionId;
        if (patch.workspace) meta.workspace = patch.workspace;
        // spec 0017 / 票 #169：正文按**一条**交给桥的问答流窗口（同文复读由窗口去重），
        // 适配器不再自己维护整段尾巴——窗口与裁剪只有一份，免得两处各写一套。
        debounced.schedule(meta.sessionId, {
          source: "codex",
          workspace: meta.workspace,
          status: patch.status,
          currentAction: patch.currentAction,
          userText: patch.userText,
          assistantText: patch.assistantText,
        });
      }
      fileMeta.set(file, meta);
    }
  };

  const timer = setInterval(scan, POLL_MS);
  scan();
  options.log?.(`codex 适配器已开 root=${root}`);
  return {
    stop() {
      clearInterval(timer);
      debounced.dispose();
    },
  };
}

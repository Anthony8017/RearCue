/**
 * tail 读取与尾随去抖的共享工具（票 #118/#119 评审：codex/claude 两适配器同形逻辑
 * 抽取——readFileFrom 字节增量读、schedule/flush 的尾随合并）。
 */
import { openSync, readSync, statSync, closeSync } from "node:fs";

/** 从字节偏移读到文件尾；打不开/竞争返回 null。 */
export function readFileFrom(file, offset) {
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

/** 每会话尾随合并：patch 落 pending，去抖窗内后续 patch 合并，到点一次 flush 到 emit。 */
export function createDebouncedEmitter(emit, debounceMs = 400) {
  const pending = new Map();
  const timers = new Map();

  const flush = (sessionId) => {
    timers.delete(sessionId);
    const st = pending.get(sessionId);
    pending.delete(sessionId);
    if (st) emit({ ...st, sessionId, updatedAt: Date.now() });
  };

  return {
    schedule(sessionId, patch) {
      const cur = pending.get(sessionId) || {};
      pending.set(sessionId, {
        ...cur,
        ...Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined)),
      });
      if (!timers.has(sessionId)) {
        timers.set(sessionId, setTimeout(() => flush(sessionId), debounceMs));
      }
    },
    dispose() {
      for (const t of timers.values()) clearTimeout(t);
      timers.clear();
      pending.clear();
    },
  };
}

/**
 * 滚动回看尾巴（#115 评审：回看要能翻「历史」而非单条最新回复）——
 * 每会话保留最近 N 条助手输出（条数与总字符双上限，超限丢最旧），
 * push 后返回拼接正文（适配器以其作为 latestReply 事件值）。
 */
export function createTailHistory({ maxEntries = 20, maxChars = 16000, separator = "\n\n────────\n\n" } = {}) {
  let entries = [];
  return {
    push(text) {
      const t = (text || "").trim();
      if (!t) return this.text();
      if (entries[entries.length - 1] === t) return this.text(); // 同文去重（task_complete 复读）
      entries.push(t);
      while (entries.length > maxEntries) entries.shift();
      while (entries.length > 1 && entries.join(separator).length > maxChars) entries.shift();
      return this.text();
    },
    text() {
      return entries.join(separator);
    },
    get size() {
      return entries.length;
    },
  };
}

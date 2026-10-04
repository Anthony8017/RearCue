/**
 * tail 读取与尾随去抖的共享工具（票 #118/#119 评审：codex/claude 两适配器同形逻辑
 * 抽取——readFileFrom 字节增量读、schedule/flush 的尾随合并）。
 *
 * 注：原 `createTailHistory`（滚动回看尾巴）已随 spec 0017 / 票 #169 删除——问答流的窗口
 * 收口在 `turn-log.mjs` 一处，两个适配器不再各留一份「最近 N 条」的拼接逻辑。
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
      // Lifecycle/request boundaries cannot be folded into a later turn's status.
      if (patch.taskStarted || patch.userText || patch.completion || patch.inputRequests ||
          patch.resolvedRequestIds || patch.resolvedRequestPrefix || patch.resolvedRequestPrefixes || patch.clearInputRequests) {
        if (pending.has(sessionId)) {
          clearTimeout(timers.get(sessionId));
          flush(sessionId);
        }
        emit({ ...patch, sessionId, updatedAt: Date.now() });
        return;
      }
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

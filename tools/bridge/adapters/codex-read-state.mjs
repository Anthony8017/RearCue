import { readFileSync } from "node:fs";

/**
 * Codex 电脑端已阅事实（ADR 0018 / issue #296）：
 * Electron 官方把「尚未看过」的 thread id 放在
 * `.codex-global-state.json` 的 `electron-thread-read-state-v1.unreadByIdentity`
 * 下；从集合里消失就是来源端实际打开过正文。
 *
 * 这里只读官方状态，不反向改 Codex 自己的文件。
 */
export function parseCodexReadState(raw) {
  let value = raw;
  if (typeof value === "string") {
    try {
      value = JSON.parse(value);
    } catch {
      return new Set();
    }
  }
  const root = value?.["electron-thread-read-state-v1"];
  const byIdentity = root?.unreadByIdentity;
  const unread = new Set();
  if (byIdentity && typeof byIdentity === "object") {
    for (const hosts of Object.values(byIdentity)) {
      if (!hosts || typeof hosts !== "object") continue;
      for (const ids of Object.values(hosts)) {
        if (!Array.isArray(ids)) continue;
        for (const id of ids) {
          if (typeof id === "string" && id) unread.add(id);
        }
      }
    }
  }
  const legacy = root?.legacyMigration?.unreadThreadIdsByHostId;
  if (legacy && typeof legacy === "object") {
    for (const ids of Object.values(legacy)) {
      if (!Array.isArray(ids)) continue;
      for (const id of ids) {
        if (typeof id === "string" && id) unread.add(id);
      }
    }
  }
  return unread;
}

/** 当前会话是否在官方电脑端未读集合；不在集合＝来源端已阅。 */
export function codexSessionReadState(unreadIds, sessionId) {
  return unreadIds.has(sessionId) ? "unread" : "read";
}

/** 读取文件内容；原子替换/暂时不可读时保留上一份状态，不让坏写入误判已阅。 */
export function loadCodexReadState(file, previous = new Set()) {
  try {
    return parseCodexReadState(readFileSync(file, "utf8"));
  } catch {
    return new Set(previous);
  }
}

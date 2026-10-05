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

function validReadState(value) {
  const object = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
  const idsByHost = (v) => object(v) && Object.values(v).every(
    (ids) => Array.isArray(ids) && ids.every((id) => typeof id === "string"),
  );
  const root = value?.["electron-thread-read-state-v1"];
  if (!object(root) || (root.version !== undefined && root.version !== 1)) return false;
  const byIdentity = root.unreadByIdentity;
  const legacy = root.legacyMigration?.unreadThreadIdsByHostId;
  if (byIdentity === undefined && legacy === undefined) return false;
  return (byIdentity === undefined || (object(byIdentity) && Object.values(byIdentity).every(idsByHost))) &&
    (legacy === undefined || idsByHost(legacy));
}

/** 只接受完整状态；previous=null 可要求一次可信的新读取，失败时返回 null。 */
export function loadCodexReadState(file, previous = new Set()) {
  try {
    const value = JSON.parse(readFileSync(file, "utf8"));
    if (!validReadState(value)) throw new Error("invalid Codex read-state");
    return parseCodexReadState(value);
  } catch {
    return previous === null ? null : new Set(previous);
  }
}

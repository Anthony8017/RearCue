/**
 * DSH 的工作区登记与会话投影是本机只读事实：工作区决定背屏名册，
 * turnBoundary 决定回合是否仍在跑。事件插件继续负责正文和等待确认。
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";

const DEFAULT_DSH_HOME = join(homedir(), ".dsh");
const SESSION_ID = /^[a-zA-Z0-9_-]+$/;

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

const PROJECTION_DOMAINS = ["session_projcache_archive_manager_v2", "session_projcache_archive_manager", "session_projcache"];
const PROJECTION_ROWS = ["sessionListMetadata", "sessionStats", "title", "turnBoundary"];

function readProjection(storage, sessionId, memo) {
  const key = `session_${createHash("sha256").update(sessionId, "utf8").digest("base64url")}`;
  const records = [];
  for (const domain of PROJECTION_DOMAINS) {
    for (const physicalKey of [key, sessionId]) {
      try {
        const record = readJson(join(storage, domain, "sessions", `${physicalKey}.json`))?.record;
        if (!record?.rows || (record.sessionId !== undefined && record.sessionId !== sessionId) ||
            (physicalKey === key && record.sessionId !== sessionId)) continue;
        records.push(record);
      } catch { /* missing/torn caches are retried next poll */ }
    }
  }
  if (records.length === 0) return memo.get(sessionId) || null;
  const format = Math.max(...records.map((r) => r.identity?.formatVersion || 0));
  const rows = {};
  for (const record of records) {
    if ((record.identity?.formatVersion || 0) !== format) continue;
    for (const name of PROJECTION_ROWS) {
      const row = record.rows[name];
      if (row && (!rows[name] || (row.seq ?? -1) > (rows[name].seq ?? -1))) rows[name] = row;
    }
  }
  const blank = rows.sessionListMetadata?.val?.blank;
  const hasConversation = typeof blank === "boolean" ? !blank :
    rows.sessionStats?.val?.turns > 0 || rows.sessionListMetadata?.val?.lastPromptAt > 0;
  if (typeof blank === "boolean" || hasConversation) {
    const projection = { rows, hasConversation };
    memo.set(sessionId, projection);
    return projection;
  }
  return memo.get(sessionId) || null;
}

export function readDshRoster(dshHome = process.env.BRIDGE_DSH_HOME || DEFAULT_DSH_HOME, memo = new Map()) {
  const storage = join(dshHome, "storages");
  const workspace = readJson(join(storage, "workspace.json"));
  const workspaces = workspace?.tables?.workspaces;
  const orderedIds = workspace?.global?.workspaceIds;
  const archivedIds = workspace?.global?.archivedSessionIds;
  if (!workspaces || !Array.isArray(orderedIds) || !Array.isArray(archivedIds)) {
    throw new Error("DSH 工作区名册格式不完整");
  }
  const archived = new Set(archivedIds.filter((id) => typeof id === "string"));
  const seen = new Set();
  const sessions = [];
  for (const workspaceId of orderedIds) {
    const entry = workspaces[workspaceId];
    if (!entry || !Array.isArray(entry.sessionIds)) continue;
    for (const sessionId of entry.sessionIds) {
      if (typeof sessionId !== "string" || !SESSION_ID.test(sessionId) ||
        archived.has(sessionId) || seen.has(sessionId)) continue;
      seen.add(sessionId);
      const projection = readProjection(storage, sessionId, memo);
      if (!projection?.hasConversation) continue; // blank New Session placeholders are not conversations
      const row = { sessionId, workspace: typeof entry.path === "string" ? entry.path : null };
      {
        const rows = projection.rows;
        const title = rows?.title?.val;
        if (typeof title === "string" && title.trim()) row.title = title.trim().slice(0, 120);
        const boundary = rows?.turnBoundary?.val;
        if (boundary && Object.hasOwn(boundary, "openTurnStartSeq")) {
          row.status = boundary.openTurnStartSeq == null ? "idle" : "working";
        }
      }
      sessions.push(row);
    }
  }
  return { sessions, archived };
}

export function startDshRosterAdapter(onRoster, { dshHome, log = () => {}, intervalMs = 1000 } = {}) {
  let failed = false;
  const projectionMemo = new Map();
  const poll = () => {
    try {
      onRoster(readDshRoster(dshHome, projectionMemo));
      if (failed) log("DSH 工作区名册已恢复读取");
      failed = false;
    } catch (error) {
      if (!failed) log(`DSH 工作区名册暂不可读：${error?.message || error}`);
      failed = true;
    }
  };
  poll();
  const timer = setInterval(poll, intervalMs);
  timer.unref?.();
  return () => clearInterval(timer);
}

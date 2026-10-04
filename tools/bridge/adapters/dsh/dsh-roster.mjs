/**
 * DSH 的工作区登记与会话投影是本机只读事实：工作区决定背屏名册，
 * turnBoundary 决定回合是否仍在跑。事件插件继续负责正文和等待确认。
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const DEFAULT_DSH_HOME = join(homedir(), ".dsh");
const SESSION_ID = /^[a-zA-Z0-9_-]+$/;

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

export function readDshRoster(dshHome = process.env.BRIDGE_DSH_HOME || DEFAULT_DSH_HOME) {
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
      const row = { sessionId, workspace: typeof entry.path === "string" ? entry.path : null };
      try {
        const projection = readJson(join(storage, "session_projcache", "sessions", `${sessionId}.json`));
        const rows = projection?.record?.rows;
        const title = rows?.title?.val;
        if (typeof title === "string" && title.trim()) row.title = title.trim().slice(0, 120);
        const boundary = rows?.turnBoundary?.val;
        if (boundary && Object.hasOwn(boundary, "openTurnStartSeq")) {
          row.status = boundary.openTurnStartSeq == null ? "idle" : "working";
        }
      } catch {
        // 新会话的投影可能稍后落盘；先登记，下一轮补标题与状态。
      }
      sessions.push(row);
    }
  }
  return { sessions, archived };
}

export function startDshRosterAdapter(onRoster, { dshHome, log = () => {}, intervalMs = 1000 } = {}) {
  let failed = false;
  const poll = () => {
    try {
      onRoster(readDshRoster(dshHome));
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

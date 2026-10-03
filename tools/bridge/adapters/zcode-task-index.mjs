/**
 * ZCode 任务索引的归档真值（spec 0023）。
 *
 * `session/list` 是持久化会话历史，不保证携带 archivedAt；电脑端任务列表实际读取
 * `~/.zcode/v2/tasks-index.sqlite` 的 tasks 表。这里只读该 SQLite，按 task_id 聚合：
 * 任一 `archived=0 AND deleted=0` 行即当前在册；只有归档/删除行则不在册。
 */
import { DatabaseSync } from "node:sqlite";
import { homedir } from "node:os";
import { join } from "node:path";

export const DEFAULT_ZCODE_TASK_INDEX_DB = join(homedir(), ".zcode", "v2", "tasks-index.sqlite");

/**
 * @returns {{ok:boolean, states:Map<string,{active:boolean,present:boolean,status?:string|null}>}}
 * `ok=false` 表示索引不可读，调用方必须保持容错，不得把未知当归档。
 * `status` 是在册行里 `updated_at` 最新那条的 `task_status`（桌面任务列表的运行态真值）。
 */
export function readZCodeTaskIndex(path = DEFAULT_ZCODE_TASK_INDEX_DB) {
  try {
    const db = new DatabaseSync(path, { readOnly: true, timeout: 50 });
    try {
      const rows = db.prepare(`
        select task_id, archived, deleted, updated_at, task_status
        from tasks
      `).all();
      const states = new Map();
      for (const row of rows) {
        const sessionId = String(row.task_id || "");
        if (!sessionId) continue;
        const deleted = Number(row.deleted) === 1;
        const archived = Number(row.archived) === 1;
        const state = states.get(sessionId) || { active: false, present: false, status: null, updatedAt: -1 };
        if (!deleted) state.present = true;
        if (!deleted && !archived) {
          state.active = true;
          const updatedAt = Number(row.updated_at);
          if (Number.isFinite(updatedAt) && updatedAt >= state.updatedAt) {
            state.updatedAt = updatedAt;
            state.status = typeof row.task_status === "string" && row.task_status ? row.task_status : null;
          }
        }
        states.set(sessionId, state);
      }
      for (const state of states.values()) delete state.updatedAt;
      return { ok: true, states };
    } finally {
      db.close();
    }
  } catch {
    return { ok: false, states: new Map() };
  }
}

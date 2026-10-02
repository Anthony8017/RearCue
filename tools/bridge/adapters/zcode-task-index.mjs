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
 * @returns {{ok:boolean, states:Map<string,{active:boolean,present:boolean}>}}
 * `ok=false` 表示索引不可读，调用方必须保持容错，不得把未知当归档。
 */
export function readZCodeTaskIndex(path = DEFAULT_ZCODE_TASK_INDEX_DB) {
  try {
    const db = new DatabaseSync(path, { readOnly: true, timeout: 50 });
    try {
      const rows = db.prepare(`
        select task_id,
               max(case when archived = 0 and deleted = 0 then 1 else 0 end) as active,
               max(case when deleted = 0 then 1 else 0 end) as present
        from tasks
        group by task_id
      `).all();
      const states = new Map();
      for (const row of rows) {
        const sessionId = String(row.task_id || "");
        if (!sessionId) continue;
        states.set(sessionId, {
          active: Number(row.active) === 1,
          present: Number(row.present) === 1,
        });
      }
      return { ok: true, states };
    } finally {
      db.close();
    }
  } catch {
    return { ok: false, states: new Map() };
  }
}

import { DatabaseSync } from "node:sqlite";

/** Desktop thread/list uses interactive sources and excludes records without a preview.
 * Read the same local index; rollout files alone are never registration authority.
 * A failed read is unknown, so callers retain the last trusted snapshot.
 */
export function readCodexThreadIndex(path) {
  let db;
  try {
    db = new DatabaseSync(path, { readOnly: true, timeout: 50 });
    const rows = db.prepare("SELECT id, source, archived, preview, cwd, rollout_path FROM threads").all();
    const threads = new Map();
    for (const row of rows) {
      if (typeof row.id !== "string" || !row.id) continue;
      const archived = Number(row.archived) === 1;
      threads.set(row.id, {
        visible: !archived && ["cli", "vscode"].includes(row.source) &&
          typeof row.preview === "string" && row.preview.length > 0,
        archived,
        workspace: typeof row.cwd === "string" ? row.cwd : null,
        rolloutPath: typeof row.rollout_path === "string" ? row.rollout_path : null,
      });
    }
    return { ok: true, threads };
  } catch {
    return { ok: false, threads: new Map() };
  } finally {
    db?.close();
  }
}

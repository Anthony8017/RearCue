import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";

function activeProvider(configFile) {
  let raw;
  try { raw = readFileSync(configFile, "utf8"); }
  catch (error) { if (error.code === "ENOENT") return "openai"; throw error; }
  for (const line of raw.split(/\r?\n/)) {
    if (/^\s*\[/.test(line)) break; // root settings only; provider tables are separate
    if (!/^\s*model_provider\s*=/.test(line)) continue;
    const match = line.match(/^\s*model_provider\s*=\s*("(?:[^"\\]|\\.)*"|'[^']*')\s*(?:#.*)?$/);
    if (!match) throw new Error("Unreadable Codex model_provider setting");
    const provider = match[1].startsWith('"') ? JSON.parse(match[1]) : match[1].slice(1,-1);
    if (!provider) throw new Error("Empty Codex model_provider setting");
    return provider;
  }
  return "openai";
}

/** Desktop thread/list uses interactive sources and excludes records without a preview.
 * Read the same local index; rollout files alone are never registration authority.
 * A failed read is unknown, so callers retain the last trusted snapshot.
 */
export function readCodexThreadIndex(path, configFile = join(dirname(path), "config.toml")) {
  let db;
  try {
    db = new DatabaseSync(path, { readOnly: true, timeout: 50 });
    const provider = activeProvider(configFile);
    const rows = db.prepare("SELECT id, source, archived, preview, cwd, rollout_path, model_provider, is_pinned FROM threads").all();
    const threads = new Map();
    for (const row of rows) {
      if (typeof row.id !== "string" || !row.id) continue;
      const archived = Number(row.archived) === 1;
      threads.set(row.id, {
        visible: !archived && ["cli", "vscode"].includes(row.source) &&
          typeof row.preview === "string" && row.preview.length > 0 &&
          (Number(row.is_pinned) === 1 || row.model_provider === provider),
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

import { createReadStream } from "node:fs";
import { readdir, readFile, open } from "node:fs/promises";
import { createInterface } from "node:readline";
import { join, basename } from "node:path";
import { homedir } from "node:os";
import { DatabaseSync } from "node:sqlite";
import { zstdDecompressSync } from "node:zlib";
import { parseCodexLine, isCodexSubagentMeta } from "./adapters/codex.mjs";
import { parseClaudeLine } from "./adapters/claude.mjs";
import { defaultClaudeUserDataRoot, defaultClaudeMembershipRoots, discoverClaudeMembershipRecords, parseClaudeMembershipRecord } from "./adapters/claude-membership.mjs";
import { readCodexThreadIndex } from "./adapters/codex-thread-index.mjs";
import { createClaudeDesktopProfileReader, claudeDesktopProfileMatches, claudeDesktopRecordVisible } from "./adapters/claude-desktop-profile.mjs";
import { readDshRoster } from "./adapters/dsh/dsh-roster.mjs";
import { reconstructZCodeHistory } from "./adapters/zcode-history.mjs";
import { isInjectedUserText } from "./adapters/turn-log.mjs";
import { mirrorKey } from "./mirror-roster.mjs";

const time = (value) => typeof value === "number" ? value : (Date.parse(value) || 0);
const text = (content) => typeof content === "string" ? content : Array.isArray(content)
  ? content.filter((c) => c?.type === "text" || c?.type === "input_text" || c?.type === "output_text").map((c) => c.text || "").join("") : "";

async function files(root, suffix) {
  const result = [];
  async function visit(path) {
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const file = join(path, entry.name);
      if (entry.isDirectory()) await visit(file);
      else if (entry.isFile() && entry.name.endsWith(suffix)) result.push(file);
    }
  }
  await visit(root);
  return result;
}
async function head(file) {
  const fd = await open(file, "r");
  try {
    const buffer = Buffer.alloc(65536);
    const { bytesRead } = await fd.read(buffer, 0, buffer.length, 0);
    return JSON.parse(buffer.subarray(0, bytesRead).toString("utf8").split(/\r?\n/)[0]);
  } finally { await fd.close(); }
}
async function records(file) {
  const result = [];
  const input = createReadStream(file);
  const lines = createInterface({ input, crlfDelay: Infinity });
  try {
    for await (const line of lines) {
      if (!line.trim()) continue;
      try { result.push(JSON.parse(line)); } catch { throw new Error("历史文件含损坏或未完成记录，请稍后重试"); }
    }
  } finally { lines.close(); input.destroy(); }
  return result;
}

/** Metadata catalogue; discovery never registers a conversation or calls a model. */
export class SessionLibrary {
  constructor({ home = homedir(), enabled = ["codex", "claude", "zcode", "dsh"] } = {}) {
    this.home = home;
    this.enabled = new Set(enabled);
    this.rows = new Map();
    this.failures = {};
    this.refreshing = null;
    this.claudeProfileRoot = null;
    this.claudeProfileReader = null;
  }
  get(source, id) { return this.rows.get(mirrorKey(source, id)); }
  observe(row) {
    if (!row?.sessionId) return;
    const key = mirrorKey(row.source, row.sessionId);
    this.rows.set(key, { ...this.rows.get(key), available: true, ...row });
  }
  refresh() {
    if (this.refreshing) return this.refreshing;
    this.refreshing = this.scan().finally(() => { this.refreshing = null; });
    return this.refreshing;
  }
  async scan() {
    for (const source of this.enabled) {
      try {
        const rows = await this[source]();
        const present = new Set(rows.map((row) => mirrorKey(source, row.sessionId)));
        for (const [key, old] of this.rows) if (old.source === source && old.file && !present.has(key)) {
          this.rows.set(key, { ...old, available: false });
        }
        for (const row of rows) this.observe({ available: true, source, ...row });
        delete this.failures[source];
      } catch {
        this.failures[source] = "来源的本地会话记录暂不可读";
        for (const [key, old] of this.rows) if (old.source === source) this.rows.set(key, { ...old, available: false });
      }
    }
  }
  async codex() {
    const root = process.env.BRIDGE_CODEX_HOME || join(this.home, ".codex");
    const index = readCodexThreadIndex(join(root, "state_5.sqlite"));
    if (!index.ok) throw new Error("Codex 当前可见会话索引暂不可读");
    const names = new Map();
    try { for (const row of await records(join(root, "session_index.jsonl"))) names.set(row.id, row.thread_name || row.title); } catch { /* optional titles */ }
    const result = [];
    for (const file of await files(join(root, "sessions"), ".jsonl")) {
      try {
        const record = await head(file);
        if (record.type !== "session_meta") continue;
        const meta = record.payload;
        if (!meta?.id) continue;
        if (isCodexSubagentMeta(record)) { this.observe({ source: "codex", sessionId: meta.id, internal: true, file }); continue; }
        if (!index.threads.get(meta.id)?.visible) continue;
        result.push({ sessionId: meta.id, workspace: meta.cwd, title: names.get(meta.id), createdAt: time(meta.timestamp || record.timestamp), file });
      } catch { /* a partial new header will be found on the next refresh */ }
    }
    return result;
  }
  async claude() {
    const userDataRoot = defaultClaudeUserDataRoot();
    if (this.claudeProfileRoot !== userDataRoot) {
      this.claudeProfileRoot = userDataRoot;
      this.claudeProfileReader = createClaudeDesktopProfileReader(userDataRoot);
    }
    const selection = this.claudeProfileReader();
    if (!selection.ok) throw new Error("Claude 当前账号资料暂不可读");
    if (!selection.profile) return [];
    const transcripts = new Map();
    const internal = new Set();
    const root = process.env.BRIDGE_CLAUDE_HOME || join(this.home, ".claude");
    for (const file of await files(join(root, "projects"), ".jsonl")) {
      try {
        const first = await head(file);
        if (first.isSidechain || first.parent_tool_use_id) {
          const sessionId = first.sessionId || basename(file, ".jsonl");
          internal.add(sessionId); this.observe({ source: "claude", sessionId, internal: true, file }); continue;
        }
        transcripts.set(first.sessionId || basename(file, ".jsonl"), { file, workspace: first.cwd, createdAt: time(first.timestamp) });
      } catch { /* partial transcript */ }
    }
    const result = [];
    const roots = process.env.BRIDGE_CLAUDE_MEMBERSHIP_ROOTS?.split(";") || defaultClaudeMembershipRoots();
    for (const directory of roots) for (const item of discoverClaudeMembershipRecords(directory)) {
      if (!claudeDesktopProfileMatches(item, selection.profile)) continue;
      const raw = await readFile(item.path || item.file, "utf8");
      if (!claudeDesktopRecordVisible(JSON.parse(raw), item.store, selection.profile)) continue;
      const row = parseClaudeMembershipRecord(raw, item.sessionIdHint);
      if (!row.valid) continue;
      if (internal.has(row.sessionId)) continue;
      const record = JSON.parse(raw);
      result.push({ ...transcripts.get(row.sessionId), ...row, archived: row.isArchived,
        createdAt: time(record.createdAt || record.created_at || transcripts.get(row.sessionId)?.createdAt), available: true });
      transcripts.delete(row.sessionId);
    }
    // A transcript alone cannot expand Desktop's current account/org admission.
    return result;
  }
  async zcode() {
    const root = process.env.BRIDGE_ZCODE_HOME || join(this.home, ".zcode", "v2");
    const db = new DatabaseSync(join(root, "tasks-index.sqlite"), { readOnly: true, timeout: 50 });
    try {
      const result = new Map();
      for (const row of db.prepare("SELECT task_id, title, workspace_path, created_at, archived, deleted, meta_json FROM tasks").all()) {
        if (Number(row.deleted)) continue;
        let meta = {}; try { meta = JSON.parse(row.meta_json || "{}"); } catch { /* optional */ }
        if (meta.sessionKind === "subagent_child" || meta.session_kind === "subagent_child") {
          this.observe({ source: "zcode", sessionId: row.task_id, internal: true }); continue;
        }
        const candidate = { sessionId: row.task_id, title: row.title, workspace: row.workspace_path,
          createdAt: time(row.created_at), archived: !!row.archived,
          file: join(process.env.ZCODE_MODEL_IO_DIR || join(this.home, ".zcode", "cli", "rollout"), `model-io-${row.task_id}.jsonl`) };
        if (!result.has(row.task_id) || !candidate.archived) result.set(row.task_id, candidate);
      }
      return [...result.values()];
    } finally { db.close(); }
  }
  async dsh() {
    const root = process.env.BRIDGE_DSH_HOME || join(this.home, ".dsh");
    const { sessions } = readDshRoster(root);
    const wanted = new Map(sessions.map((row) => [row.sessionId, row]));
    const result = [];
    const all = [...await files(join(root, "sessions"), ".jsonl.zstd"), ...await files(join(root, "sessions"), ".jsonl")];
    const best = new Map();
    for (const file of all) {
      // Read only the header frame: catalogue scans do not replay conversation text.
      const header = await dshHeader(file);
      if (header.origin === "subagent" || header.sessionKind === "subagent_child") {
        this.observe({ source: "dsh", sessionId: header.id, internal: true, file }); continue;
      }
      if (!wanted.has(header.id)) continue;
      if (!best.has(header.id) || Number(header.version) > Number(best.get(header.id).header.version)) best.set(header.id, { header, file });
    }
    for (const [id, { header, file }] of best) result.push({ ...wanted.get(id), file, createdAt: time(header.createdAt), workspace: header.cwd || wanted.get(id).workspace });
    return result;
  }
  async history(row) {
    if (!row?.file) throw new Error("本机未保存此对话的历史问答");
    const list = row.source === "dsh" ? await dshRecords(row.file) : await records(row.file);
    if (row.source === "dsh" && (list[0]?.type !== "session" || list[0]?.version !== 4)) throw new Error("此历史格式暂不支持，请保留原文件");
    if (row.source === "zcode") return reconstructZCodeHistory(list);
    const turns = [];
    for (const record of list) {
      let user, assistant;
      if (row.source === "codex") {
        const patch = parseCodexLine(JSON.stringify(record));
        user = patch?.userText; assistant = patch?.assistantText;
      } else if (row.source === "claude") {
        const patch = parseClaudeLine(JSON.stringify(record));
        user = patch?.userText; assistant = patch?.assistantText;
      } else if (row.source === "dsh") {
        const kind = record.data?.source?.kind;
        if (record.type === "user/message" && (!kind || kind === "user" || kind === "user-question-reply")) user = text(record.data?.content);
        if (record.type === "assistant/message") assistant = text(record.data?.message?.content);
      }
      const ts = time(record.timestamp || record.time);
      if (user && !isInjectedUserText(user)) turns.push({ role: "user", text: user, ts });
      if (assistant && !(row.source === "codex" && record.type === "event_msg" &&
          turns.at(-1)?.role === "assistant" && turns.at(-1)?.text === assistant)) turns.push({ role: "assistant", text: assistant, ts });
    }
    return turns;
  }
}

async function dshHeader(file) {
  if (!file.endsWith(".zstd")) return head(file);
  const fd = await open(file, "r");
  try {
    const buffer = Buffer.alloc(65536);
    const { bytesRead } = await fd.read(buffer, 0, buffer.length, 0);
    const size = zstdFrameSize(buffer.subarray(0, bytesRead));
    return JSON.parse(zstdDecompressSync(buffer.subarray(0, size)).toString("utf8").trim());
  } finally { await fd.close(); }
}

/** Standard Zstd frame boundary; decode each concatenated frame independently. */
export function zstdFrameSize(buffer, start = 0) {
  let offset = start;
  if (buffer.readUInt32LE(offset) !== 0xfd2fb528) throw new Error("不支持的历史压缩格式");
  offset += 4;
  const descriptor = buffer[offset++];
  const single = !!(descriptor & 32);
  if (!single) offset++;
  offset += [0, 1, 2, 4][descriptor & 3];
  offset += [single ? 1 : 0, 2, 4, 8][descriptor >> 6];
  let last = false;
  while (!last) {
    if (offset + 3 > buffer.length) throw new Error("历史压缩帧不完整");
    const header = buffer.readUIntLE(offset, 3); offset += 3;
    last = !!(header & 1);
    const type = (header >> 1) & 3;
    if (type === 3) throw new Error("历史压缩块无效");
    offset += type === 1 ? 1 : header >> 3;
    if (offset > buffer.length) throw new Error("历史压缩帧不完整");
  }
  if (descriptor & 4) offset += 4;
  if (offset > buffer.length) throw new Error("历史压缩校验不完整");
  return offset - start;
}
async function dshRecords(file) {
  if (!file.endsWith(".zstd")) return records(file);
  const buffer = await readFile(file);
  let offset = 0;
  const result = [];
  while (offset < buffer.length) {
    const size = zstdFrameSize(buffer, offset);
    const decoded = zstdDecompressSync(buffer.subarray(offset, offset + size)).toString("utf8");
    for (const line of decoded.split(/\r?\n/)) if (line.trim()) result.push(JSON.parse(line));
    offset += size;
    await new Promise((resolve) => setImmediate(resolve));
  }
  return result;
}

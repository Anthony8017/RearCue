/**
 * Claude Desktop authoritative local membership scanner (spec 0023 / ticket #238).
 *
 * Claude Desktop persists Code and Cowork records under `claude-code-sessions`
 * and `local-agent-mode-sessions`, both with `<accountId>/<orgId>/<sessionId>.json`.
 * `cliSessionId` links the Desktop record to transcript/hooks; `sessionId` is the
 * fallback for older records. A valid record has `isArchived: boolean`; archive/unarchive writes that
 * boolean. The scanner turns those authoritative records into the shared membership
 * contract while remaining tolerant:
 * - valid `isArchived:false` -> ACTIVE; false -> true -> ARCHIVED; true -> false -> UNARCHIVE;
 * - invalid JSON/shape -> UNKNOWN;
 * - a previously seen file disappearing -> UNKNOWN, never archive/removal;
 * - UNKNOWN is diagnostic/non-authoritative and cannot clear an existing tombstone.
 *
 * Facts are generation protected through SourceMembershipLedger. The default poll is
 * 1s, within Archive Synchrony's 2s budget. Explicit Claude membership hooks remain
 * supported independently in claude.mjs/source-membership.mjs.
 */
import { homedir } from "node:os";
import { join, basename } from "node:path";
import {
  existsSync,
  readdirSync,
  readFileSync,
  statSync,
} from "node:fs";
import { membershipFact, SourceMembershipLedger } from "./source-membership.mjs";

export const CLAUDE_MEMBERSHIP_POLL_MS = 1000;

function firstString(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return null;
}

/** Electron user-data directory for Claude Desktop on the current platform. */
export function defaultClaudeUserDataRoot({ platform = process.platform, env = process.env, home = homedir() } = {}) {
  const explicit = firstString(env.CLAUDE_DESKTOP_USER_DATA);
  if (explicit) return explicit;
  let candidates;
  if (platform === "win32") {
    const roaming = env.APPDATA || join(home, "AppData", "Roaming");
    const local = env.LOCALAPPDATA || join(home, "AppData", "Local");
    candidates = [
      join(roaming, "Claude"),
      join(local, "Claude-3p"),
      join(local, "Claude"),
      join(local, "Packages", "Claude_pzs8sxrjxfjjc", "LocalCache", "Roaming", "Claude"),
    ];
  } else {
    const parent = platform === "darwin"
      ? join(home, "Library", "Application Support")
      : env.XDG_CONFIG_HOME || join(home, ".config");
    candidates = [join(parent, "Claude"), join(parent, "Claude-3p")];
  }
  // Empty/stale Electron folders without session stores are not authoritative.
  return candidates.find((root) => defaultClaudeMembershipRoots(root).some(existsSync)) || candidates[0];
}

export function defaultClaudeMembershipRoots(userDataRoot = defaultClaudeUserDataRoot()) {
  return ["claude-code-sessions", "local-agent-mode-sessions"].map((name) => join(userDataRoot, name));
}

/** Tolerant record parser: invalid shape is UNKNOWN, never a guessed archive bit. */
export function parseClaudeMembershipRecord(text, fallbackSessionId) {
  const fallback = firstString(fallbackSessionId);
  let record;
  try {
    record = JSON.parse(String(text ?? ""));
  } catch {
    return { valid: false, sessionId: fallback, reason: "unknown" };
  }
  if (!record || typeof record !== "object" || Array.isArray(record)) {
    return { valid: false, sessionId: fallback, reason: "unknown" };
  }
  const sessionId = firstString(record.cliSessionId, record.cli_session_id, record.sessionId, record.session_id, record.id);
  if (!sessionId || typeof record.isArchived !== "boolean") {
    return { valid: false, sessionId: fallback || sessionId, reason: "unknown" };
  }
  const identity = {};
  const title = firstString(record.title);
  const workspace = firstString(record.originCwd, record.cwd);
  if (title) identity.title = title;
  if (workspace) identity.workspace = workspace;
  return { valid: true, sessionId, isArchived: record.isArchived, ...identity };
}

/** Build one shared-contract fact from a valid/invalid record observation. */
export function claudeMembershipFact(input) {
  const {
    record,
    previousArchived = null,
    generation,
    revision,
  } = input || {};
  if (!record || !Number.isSafeInteger(generation) || generation < 0 ||
      !Number.isSafeInteger(revision) || revision < 0) {
    return null;
  }
  if (!record.valid) {
    return membershipFact({
      source: "claude",
      sourceSessionId: record.sessionId,
      membership: "UNKNOWN",
      generation,
      revision,
      reason: "unknown",
    });
  }
  if (record.isArchived) {
    return membershipFact({
      source: "claude",
      sourceSessionId: record.sessionId,
      membership: "ARCHIVED",
      generation,
      revision,
      reason: "archive",
      title: record.title,
      workspace: record.workspace,
    });
  }
  return membershipFact({
    source: "claude",
    sourceSessionId: record.sessionId,
    membership: "ACTIVE",
    generation,
    revision,
    reason: previousArchived === true ? "unarchive" : "authoritative-snapshot",
    title: record.title,
    workspace: record.workspace,
  });
}

/** Exact persisted shape: `<root>/<accountId>/<orgId>/<sessionId>.json`. */
export function discoverClaudeMembershipRecords(root) {
  const out = [];
  if (!root || !existsSync(root)) return out;
  let accounts = [];
  try {
    accounts = readdirSync(root, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const account of accounts) {
    if (!account.isDirectory()) continue;
    const accountDir = join(root, account.name);
    let orgs = [];
    try {
      orgs = readdirSync(accountDir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const org of orgs) {
      if (!org.isDirectory()) continue;
      const orgDir = join(accountDir, org.name);
      let files = [];
      try {
        files = readdirSync(orgDir, { withFileTypes: true });
      } catch {
        continue;
      }
      for (const file of files) {
        if (!file.isFile() || !file.name.endsWith(".json")) continue;
        out.push({
          file: join(orgDir, file.name),
          sessionIdHint: basename(file.name, ".json"),
          accountId: account.name,
          orgId: org.name,
        });
      }
    }
  }
  return out;
}

/**
 * Start the tolerant scanner. `emit` receives normalized membership events with
 * `sourceSessionId`; callers may decorate them with cached status/workspace.
 */
export function startClaudeMembershipScanner(emit, options = {}) {
  const roots = options.roots || (options.root ? [options.root] : defaultClaudeMembershipRoots(options.userDataRoot));
  const pollMs = Number.isFinite(options.pollMs)
    ? Math.max(10, options.pollMs)
    : CLAUDE_MEMBERSHIP_POLL_MS;
  const ledger = options.ledger || new SourceMembershipLedger();
  const signatures = new Map(); // file -> observed stat/parse signature
  const knownPaths = new Map(); // file -> sourceSessionId
  const missingPaths = new Set();
  const authoritativeArchived = new Map(); // sourceSessionId -> last valid isArchived
  const versions = new Map(); // sourceSessionId -> {generation, revision}
  let revisionCounter = 0;

  const nextVersion = (sessionId, generationHint) => {
    const previous = versions.get(sessionId);
    const generation = Number.isSafeInteger(generationHint) && generationHint >= 0
      ? generationHint
      : previous?.generation ?? Date.now();
    revisionCounter = Math.max(revisionCounter + 1, generation, previous?.revision ?? 0);
    const version = { generation, revision: revisionCounter };
    versions.set(sessionId, version);
    return version;
  };

  const deliver = (record, generationHint) => {
    const version = nextVersion(record.sessionId, generationHint);
    const fact = claudeMembershipFact({
      record,
      previousArchived: authoritativeArchived.has(record.sessionId)
        ? authoritativeArchived.get(record.sessionId)
        : null,
      generation: version.generation,
      revision: version.revision,
    });
    if (!fact) return;
    const applied = ledger.apply(fact);
    if (!applied?.accepted) return;
    emit({ ...fact, updatedAt: Date.now() });
    if (record.valid) authoritativeArchived.set(record.sessionId, record.isArchived);
  };

  const scan = () => {
    const seen = new Set();
    for (const item of roots.flatMap(discoverClaudeMembershipRecords)) {
      seen.add(item.file);
      missingPaths.delete(item.file);
      let stat;
      let text = "";
      try {
        stat = statSync(item.file);
        text = readFileSync(item.file, "utf8");
      } catch {
        continue; // raced read; the next poll retries
      }
      const record = parseClaudeMembershipRecord(text, knownPaths.get(item.file) || item.sessionIdHint);
      const signature = [
        stat.mtimeMs,
        stat.size,
        record.valid,
        record.sessionId,
        record.valid ? record.isArchived : "invalid",
      ].join("\u0000");
      if (signatures.get(item.file) === signature) continue;
      signatures.set(item.file, signature);
      knownPaths.set(item.file, record.sessionId || item.sessionIdHint);
      deliver(record, Math.floor(stat.mtimeMs));
    }

    // A previously observed path disappearing is UNKNOWN, not source-removed/archive.
    for (const [file, sessionId] of knownPaths) {
      if (seen.has(file) || missingPaths.has(file)) continue;
      missingPaths.add(file);
      signatures.delete(file);
      deliver({ valid: false, sessionId, reason: "unknown" }, versions.get(sessionId)?.generation);
    }
  };

  let timer = null;
  if (options.autoStart !== false) timer = setInterval(scan, pollMs);
  if (options.autoStart !== false || options.scanImmediately !== false) scan();
  options.log?.(`claude membership scanner roots=${roots.join(";")} poll=${pollMs}ms`);

  return {
    scan,
    ledger,
    stop() {
      if (timer) clearInterval(timer);
      timer = null;
    },
  };
}

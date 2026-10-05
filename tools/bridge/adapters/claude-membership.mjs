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
 * - current Desktop profile and Code/Cowork visible record types define admission;
 * - a record leaving a successfully enumerated visible roster -> ABSENT/source-hidden, not archive;
 * - selector/record read failures retain only previously confirmed identities;
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
import { createClaudeDesktopProfileReader, claudeDesktopProfileMatches, claudeDesktopRecordVisible } from "./claude-desktop-profile.mjs";

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
  const available = candidates.filter((root) => defaultClaudeMembershipRoots(root).some(existsSync));
  const lastUsed = (root) => {
    try { return statSync(join(root, "config.json")).mtimeMs; } catch { return 0; }
  };
  return available.sort((a, b) => lastUsed(b) - lastUsed(a))[0] || candidates[0];
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
  const legacyArchive = record.isArchived === undefined && typeof record.cwd === "string" &&
    Number.isFinite(record.createdAt) && Number.isFinite(record.lastActivityAt);
  if (!sessionId || (typeof record.isArchived !== "boolean" && !legacyArchive)) {
    return { valid: false, sessionId: fallback || sessionId, reason: "unknown" };
  }
  const identity = {};
  const title = firstString(record.title);
  const workspace = firstString(record.originCwd, record.cwd);
  if (title) identity.title = title;
  if (workspace) identity.workspace = workspace;
  return { valid: true, sessionId, isArchived: record.isArchived === true, ...identity };
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
          orgDir,
          store: basename(root),
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
  // Explicit single-root fixtures retain legacy parser semantics. Production uses scoped Desktop stores.
  const strict = options.strictDesktopRoster ?? !options.root;
  const resolveRoot = options.userDataRootResolver || defaultClaudeUserDataRoot;
  let userDataRoot = options.userDataRoot || resolveRoot();
  let roots = options.roots || (options.root ? [options.root] : defaultClaudeMembershipRoots(userDataRoot));
  let readProfile = options.profileReader || createClaudeDesktopProfileReader(userDataRoot);
  const dynamicRoot = strict && !options.userDataRoot && !options.root && !options.roots;
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
  let visibleIds = new Set();
  let profileKey = null;
  let profileGeneration = 0;
  const records = new Map(); // file -> last valid Desktop record within the selected profile

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

  const deliver = (record, generationHint, hidden = false) => {
    const version = nextVersion(record.sessionId, generationHint);
    const fact = hidden ? membershipFact({
      source: "claude", sourceSessionId: record.sessionId, membership: "ABSENT",
      generation: version.generation, revision: version.revision, reason: "source-hidden",
    }) : claudeMembershipFact({
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
    if (dynamicRoot) {
      const selectedRoot = resolveRoot();
      if (selectedRoot !== userDataRoot) {
        const oldIds = [...visibleIds];
        visibleIds = new Set();
        options.onVisibleSessions?.(new Set());
        for (const id of oldIds) deliver({ valid: true, sessionId: id, isArchived: false }, Date.now(), true);
        userDataRoot = selectedRoot;
        roots = defaultClaudeMembershipRoots(selectedRoot);
        readProfile = options.profileReader || createClaudeDesktopProfileReader(selectedRoot);
      }
    }
    const selection = strict ? readProfile() : { ok: true, profile: null };
    if (!selection.ok) return; // a torn selector cannot admit new CLI activity
    const nextProfileKey = strict ? JSON.stringify([userDataRoot, selection.profile]) : "fixture";
    if (nextProfileKey !== profileKey) {
      records.clear();
      signatures.clear();
      profileKey = nextProfileKey;
      profileGeneration = Math.max(profileGeneration + 1, Date.now());
    }
    const seen = new Set();
    const deliveries = [];
    for (const item of roots.flatMap(discoverClaudeMembershipRecords)) {
      if (strict && (!item.sessionIdHint.startsWith("local_") || !claudeDesktopProfileMatches(item, selection.profile))) continue;
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
      let hidden = false;
      if (record.valid) {
        const raw = JSON.parse(text);
        hidden = strict && !claudeDesktopRecordVisible(raw, item.store, selection.profile);
        records.set(item.file, { record, hidden });
      }
      const signature = [
        stat.mtimeMs,
        stat.size,
        record.valid,
        record.sessionId,
        record.valid ? record.isArchived : "invalid",
        hidden,
      ].join("\u0000");
      if (signatures.get(item.file) === signature) continue;
      signatures.set(item.file, signature);
      knownPaths.set(item.file, record.sessionId || item.sessionIdHint);
      deliveries.push({ record, generation: strict ? Math.max(profileGeneration, Math.floor(stat.mtimeMs)) : Math.floor(stat.mtimeMs), hidden });
    }

    for (const file of records.keys()) if (!seen.has(file)) records.delete(file);
    const nextVisibleIds = new Set([...records.values()]
      .filter(({ record, hidden }) => !hidden && !record.isArchived).map(({ record }) => record.sessionId));
    const removed = [...visibleIds].filter((id) => !nextVisibleIds.has(id));
    visibleIds = nextVisibleIds;
    options.onVisibleSessions?.(new Set(visibleIds)); // publish authority before emitting membership/activity
    if (strict) for (const id of removed) {
      if (!deliveries.some(({ record }) => record.sessionId === id && record.isArchived)) {
        deliver({ valid: true, sessionId: id, isArchived: false }, Date.now(), true);
      }
    }
    for (const { record, generation, hidden } of deliveries) deliver(record, generation, hidden && !record.isArchived);

    // Missing records never invent an archive bit. Strict visibility removals were emitted above;
    // UNKNOWN observations retain the previous authority (legacy fixtures keep the old behaviour).
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
    visibleSessionIds: () => new Set(visibleIds),
    stop() {
      if (timer) clearInterval(timer);
      timer = null;
    },
  };
}

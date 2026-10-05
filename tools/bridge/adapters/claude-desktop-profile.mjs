/** Claude Desktop's current account/org, not every historical profile on disk. */
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HIDDEN_COWORK_TYPES = new Set(["agent", "radar", "chat"]);

export function createClaudeDesktopProfileReader(userDataRoot) {
  let logSignature = null;
  let contexts = new Map();
  let previous = null;
  return () => {
    let config;
    try { config = JSON.parse(readFileSync(join(userDataRoot, "config.json"), "utf8")); }
    catch { return { ok: false, profile: previous }; }
    if (!config || typeof config !== "object" || Array.isArray(config)) return { ok: false, profile: previous };
    if (config.windowSizeWasSignedIn === false) {
      previous = null;
      return { ok: true, profile: null };
    }
    const accountId = typeof config.lastKnownAccountUuid === "string" ? config.lastKnownAccountUuid.trim().toLowerCase() : null;
    if (!accountId || !UUID.test(accountId)) {
      previous = null;
      return { ok: true, profile: null };
    }
    try {
      const file = join(userDataRoot, "logs", "main.log");
      const stat = statSync(file);
      const signature = `${stat.mtimeMs}:${stat.size}`;
      if (signature !== logSignature) {
        const text = readFileSync(file, "utf8");
        const next = new Map();
        // These are the actual Desktop managers' completed account/org selections.
        for (const match of text.matchAll(/\[(?:LocalSessionManager|LocalAgentModeSessionManager)\] Initialization succeeded[^\r\n]*?accountId=([0-9a-f-]+), orgId=([0-9a-f-]+)/gi)) {
          const account = match[1].toLowerCase(), org = match[2].toLowerCase();
          if (UUID.test(account) && UUID.test(org)) next.set(account, org);
        }
        if (next.size) contexts = next;
        logSignature = signature;
      }
    } catch { /* retain a selection only for the same account, never expand to sibling profiles */ }
    const orgId = contexts.get(accountId) || (previous?.accountId === accountId ? previous.orgId : null);
    if (!orgId) {
      previous = null;
      return { ok: true, profile: null };
    }
    previous = { accountId, orgId };
    return { ok: true, profile: previous };
  };
}

export function claudeDesktopProfileMatches(item, profile) {
  if (!profile) return false;
  const account = item.accountId.toLowerCase(), org = item.orgId.toLowerCase();
  if (account === profile.accountId && org === profile.orgId) return true;
  if (account !== profile.accountId.slice(0, 8) || org !== profile.orgId.slice(0, 8)) return false;
  // Cowork uses shortened directory names; its marker binds them to the full org UUID.
  try {
    const marker = JSON.parse(readFileSync(`${item.orgDir}.profile-origin.json`, "utf8"));
    return ["local", "hybrid"].includes(marker.mode) && marker.org?.toLowerCase() === profile.orgId;
  } catch { return false; }
}

export function claudeDesktopRecordVisible(raw, store, profile) {
  if (typeof raw.sessionId !== "string" || !raw.sessionId.startsWith("local_") ||
      typeof raw.cwd !== "string" || !Number.isFinite(raw.createdAt) || !Number.isFinite(raw.lastActivityAt)) return false;
  if (store === "local-agent-mode-sessions" && typeof raw.processName !== "string") return false;
  if (raw.sessionType !== undefined && typeof raw.sessionType !== "string") return false;
  if (raw.isArchived === true || raw.prewarmHidden) return false;
  if (store === "local-agent-mode-sessions" && HIDDEN_COWORK_TYPES.has(raw.sessionType)) return false;
  if (raw.isAgentOwned === true && raw.startedByAccountId?.toLowerCase?.() !== profile?.accountId) return false;
  return true;
}

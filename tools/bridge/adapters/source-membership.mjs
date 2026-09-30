/**
 * Codex / Claude / DSH 的显式来源在册契约（spec 0023 / 票 #236）。
 *
 * Codex rollout 与 Claude transcript/hooks 没有原生归档/取消归档事件；Codex 由
 * `codex.mjs` 的目录移动、Claude 由 `claude-membership.mjs` 的权威 `isArchived` JSON
 * 记录产生同一事实面。桥仍接受刻意很小的显式契约：`kind|type|event|hook_event_name === "membership"`，
 * `membership: ACTIVE|ARCHIVED|ABSENT`，并带 `generation`（同代可选 `revision`）。
 * `task_complete`、`Stop`、文件缺失、超时、无活动都**不是**归档；它们不会调用这里。
 *
 * DSH 的官方生命周期是例外：`session/created`/`agent/created` → ACTIVE，
 * `session/disposed` → ABSENT/source-removed。它不是 ARCHIVED（ADR 0010 只证明移除）。
 */
export const MEMBERSHIP_EVENT = "membership";
export const MEMBERSHIP_STATES = new Set(["ACTIVE", "ARCHIVED", "ABSENT", "PRESENT", "UNKNOWN"]);
const SOURCES = new Set(["codex", "claude", "dsh"]);

function firstString(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return null;
}

function nonNegativeInteger(value) {
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 ? n : null;
}

let fallbackRevision = 0;

function nextFallbackRevision() {
  fallbackRevision = Math.max(fallbackRevision + 1, Date.now());
  return fallbackRevision;
}

function sourceKey(source, sourceSessionId) {
  return `${source}\u0000${sourceSessionId}`;
}

function wireReason(reason) {
  return String(reason || "").trim().toLowerCase().replace(/_/g, "-");
}

/** 统一事实形状：桥事件与 hook 输入共用。 */
export function membershipFact(input) {
  const {
    source,
    sourceSessionId,
    membership,
    generation,
    revision,
    reason,
  } = input || {};
  const normalizedSource = firstString(source)?.toLowerCase();
  const id = firstString(sourceSessionId);
  const rawState = firstString(membership)?.toUpperCase();
  const requestedArchiveState = firstString(input?.archiveState)?.toUpperCase();
  const state = rawState === "PRESENT"
    ? (requestedArchiveState === "ARCHIVED" ? "ARCHIVED" : requestedArchiveState === "UNKNOWN" ? "UNKNOWN" : "ACTIVE")
    : rawState === "UNKNOWN" ? "UNKNOWN" : rawState;
  const gen = nonNegativeInteger(generation);
  const rev = nonNegativeInteger(revision ?? generation);
  if (!SOURCES.has(normalizedSource) || !id || !MEMBERSHIP_STATES.has(state) || gen === null || rev === null) {
    return null;
  }
  const archiveState = state === "ABSENT" ? "UNKNOWN" : state;
  const member = state === "ABSENT" || state === "ARCHIVED" ? "ABSENT" : "PRESENT";
  const normalizedReason = wireReason(reason) || (
    state === "ARCHIVED" ? "archive" : state === "ABSENT" ? "source-removed" :
      state === "UNKNOWN" ? "unknown" : "membership-contract"
  );
  return {
    kind: MEMBERSHIP_EVENT,
    source: normalizedSource,
    sourceSessionId: id,
    membership: member,
    archiveState,
    reason: normalizedReason,
    generation: gen,
    revision: rev,
  };
}

/**
 * Codex / Claude 的显式 membership hook → 事实。
 * 只认 `membership` 生命周期事件；其余活动/停止/文件面恒 null。
 */
export function membershipFromExplicitHook(source, body) {
  if (!body || typeof body !== "object") return null;
  const event = firstString(body.kind, body.type, body.event, body.hook_event_name)?.toLowerCase();
  if (event !== MEMBERSHIP_EVENT) return null;
  return membershipFact({
    source,
    sourceSessionId: firstString(body.sourceSessionId, body.session_id, body.sessionId),
    membership: firstString(body.membership, body.archiveState),
    generation: body.generation,
    revision: body.revision,
    reason: body.reason,
  });
}

/** DSH 的官方创建/移除生命周期 → 同一事实契约。 */
export function membershipFromDshHook(body) {
  if (!body || typeof body !== "object") return null;
  const event = firstString(body.event, body.type, body.hook_event_name)?.toLowerCase();
  const id = firstString(body.sourceSessionId, body.sessionId, body.session_id);
  if (!id) return null;
  if (event === "session-added") {
    return membershipFact({
      source: "dsh",
      sourceSessionId: id,
      membership: "ACTIVE",
      generation: body.generation ?? 0,
      revision: body.revision ?? body.generation ?? nextFallbackRevision(),
      reason: "source-created",
    });
  }
  if (event === "session-removed") {
    return membershipFact({
      source: "dsh",
      sourceSessionId: id,
      membership: "ABSENT",
      generation: body.generation ?? 0,
      revision: body.revision ?? body.generation ?? nextFallbackRevision(),
      reason: "source-removed",
    });
  }
  return null;
}

/**
 * 来源在册账本：按 `(source, sourceSessionId)` 保存最后事实，拒绝旧代/同代旧序号。
 * 墓碑会拦截后续活动，直到一个更新的 ACTIVE 正事实到达。
 */
export class SourceMembershipLedger {
  constructor() {
    this.facts = new Map();
  }

  apply(input) {
    const fact = input?.kind === MEMBERSHIP_EVENT ? input : membershipFact(input || {});
    if (!fact) return null;
    const key = sourceKey(fact.source, fact.sourceSessionId);
    const old = this.facts.get(key);
    if (old && (fact.generation < old.generation ||
      (fact.generation === old.generation && fact.revision <= old.revision))) {
      return { accepted: false, fact, previous: old };
    }
    // UNKNOWN 是容错观测，不是生命周期：有权威旧事实时不覆盖它，避免坏文件/缺席
    // 把 ARCHIVED 墓碑降级成可活动，或把 ACTIVE 写成假归档。事件仍广播供诊断。
    if (fact.membership === "PRESENT" && fact.archiveState === "UNKNOWN" && old) {
      return { accepted: true, fact, previous: old, authoritative: old };
    }
    this.facts.set(key, fact);
    return { accepted: true, fact, previous: old || null, authoritative: fact };
  }

  /** 活动补丁能否进入：墓碑后的活动/迟到消息一律 false。 */
  acceptsActivity(source, sourceSessionId) {
    const normalizedSource = firstString(source)?.toLowerCase();
    const id = firstString(sourceSessionId);
    if (!normalizedSource || !id) return true;
    const fact = this.facts.get(sourceKey(normalizedSource, id));
    return !fact || (fact.membership === "PRESENT" && fact.archiveState !== "ARCHIVED");
  }

  tombstone(source, sourceSessionId) {
    const fact = this.facts.get(sourceKey(firstString(source)?.toLowerCase(), firstString(sourceSessionId)));
    return Boolean(fact && (fact.membership === "ABSENT" || fact.archiveState === "ARCHIVED"));
  }

  snapshot() {
    return [...this.facts.values()].map((fact) => ({ ...fact }));
  }
}

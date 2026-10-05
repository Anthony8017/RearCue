// 来源在册契约判例（spec 0023 / 票 #236）：node --test tools/bridge/adapters/source-membership.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  membershipFact,
  membershipFromDshHook,
  membershipFromExplicitHook,
  SourceMembershipLedger,
} from "./source-membership.mjs";

function applyAll(ledger, facts) {
  for (const fact of facts) assert.equal(ledger.apply(fact).accepted, true);
}

test("桥二次归一化保留 ARCHIVED，而来源移除仍为 UNKNOWN", () => {
  for (const membership of ["ACTIVE", "ARCHIVED", "ABSENT", "UNKNOWN"]) {
    const fact = membershipFact({ source: "claude", sourceSessionId: "cli-1", membership, generation: 1 });
    assert.deepEqual(membershipFact(fact), fact, `${membership} 的 wire 事实不能被改写`);
  }
});

test("codex：显式 membership 支持 ACTIVE → ARCHIVED → ACTIVE 与 ACTIVE → ABSENT", () => {
  const active = membershipFromExplicitHook("codex", {
    type: "membership",
    sessionId: "c-1",
    membership: "ACTIVE",
    generation: 1,
    revision: 1,
  });
  const archived = membershipFromExplicitHook("codex", {
    hook_event_name: "membership",
    session_id: "c-1",
    membership: "ARCHIVED",
    generation: 2,
  });
  const unarchived = membershipFromExplicitHook("codex", {
    event: "membership",
    sessionId: "c-1",
    membership: "ACTIVE",
    generation: 3,
    reason: "unarchive",
  });
  const archiveLedger = new SourceMembershipLedger();
  applyAll(archiveLedger, [active, archived]);
  assert.equal(archiveLedger.tombstone("codex", "c-1"), true);
  assert.equal(archiveLedger.acceptsActivity("codex", "c-1"), false);
  applyAll(archiveLedger, [unarchived]);
  assert.equal(archiveLedger.tombstone("codex", "c-1"), false);
  assert.equal(archiveLedger.acceptsActivity("codex", "c-1"), true);

  const absentLedger = new SourceMembershipLedger();
  const absent = membershipFromExplicitHook("codex", {
    type: "membership",
    sessionId: "c-1",
    membership: "ABSENT",
    generation: 2,
  });
  applyAll(absentLedger, [active, absent]);
  assert.equal(absentLedger.tombstone("codex", "c-1"), true);
  assert.equal(absentLedger.snapshot()[0].archiveState, "UNKNOWN");
});

test("claude：Stop 不是归档；只有显式 membership 能出册/回册", () => {
  assert.equal(membershipFromExplicitHook("claude", { hook_event_name: "Stop", session_id: "s-1" }), null);
  const ledger = new SourceMembershipLedger();
  applyAll(ledger, [
    membershipFromExplicitHook("claude", { type: "membership", sessionId: "s-1", membership: "ACTIVE", generation: 1 }),
    membershipFromExplicitHook("claude", { type: "membership", sessionId: "s-1", membership: "ARCHIVED", generation: 2 }),
    membershipFromExplicitHook("claude", { type: "membership", sessionId: "s-1", membership: "ACTIVE", generation: 3 }),
    membershipFromExplicitHook("claude", { type: "membership", sessionId: "s-1", membership: "ABSENT", generation: 4 }),
  ]);
  assert.equal(ledger.tombstone("claude", "s-1"), true);
  assert.equal(ledger.acceptsActivity("claude", "s-1"), false);
});

test("dsh：session/disposed 是 LIVE source-removed；session/created 可正事实回册", () => {
  const ledger = new SourceMembershipLedger();
  const active = membershipFromDshHook({ event: "session-added", sessionId: "d-1", generation: 1, revision: 1 });
  const removed = membershipFromDshHook({ event: "session-removed", sessionId: "d-1", generation: 1, revision: 2 });
  applyAll(ledger, [active, removed]);
  assert.equal(ledger.tombstone("dsh", "d-1"), true);
  assert.equal(ledger.snapshot()[0].reason, "source-removed");
  assert.equal(ledger.snapshot()[0].archiveState, "UNKNOWN", "DSH disposed 只证明移除，不冒充 archive");

  const recreated = membershipFromDshHook({ event: "session-added", sessionId: "d-1", generation: 1, revision: 3 });
  applyAll(ledger, [recreated]);
  assert.equal(ledger.acceptsActivity("dsh", "d-1"), true);
});

test("生成代保护：墓碑后的旧 ACTIVE/旧活动不能复活，文件缺失也不造归档事实", () => {
  const ledger = new SourceMembershipLedger();
  applyAll(ledger, [
    membershipFromExplicitHook("codex", { type: "membership", sessionId: "x", membership: "ACTIVE", generation: 2, revision: 2 }),
    membershipFromExplicitHook("codex", { type: "membership", sessionId: "x", membership: "ARCHIVED", generation: 2, revision: 3 }),
  ]);
  const stale = ledger.apply(
    membershipFromExplicitHook("codex", { type: "membership", sessionId: "x", membership: "ACTIVE", generation: 1, revision: 99 }),
  );
  assert.equal(stale.accepted, false);
  assert.equal(ledger.acceptsActivity("codex", "x"), false);
  assert.equal(membershipFromExplicitHook("codex", { type: "task_complete", sessionId: "x" }), null);
  assert.equal(membershipFromExplicitHook("claude", { type: "Stop", sessionId: "x" }), null);
  assert.equal(ledger.tombstone("codex", "x"), true);
});

test("显式 hook 严格只收 ACTIVE|ARCHIVED|ABSENT；UNKNOWN 只留在内部容错扫描", () => {
  assert.equal(membershipFromExplicitHook("codex", {
    type: "membership",
    sessionId: "strict",
    membership: "PRESENT",
    generation: 1,
  }), null);
  assert.equal(membershipFromExplicitHook("codex", {
    type: "membership",
    sessionId: "strict",
    membership: "UNKNOWN",
    generation: 2,
  }), null);
  assert.equal(membershipFromExplicitHook("codex", {
    type: "membership",
    sessionId: "strict",
    archiveState: "ARCHIVED",
    generation: 3,
  }), null);
  assert.equal(membershipFromExplicitHook("codex", {
    type: "membership",
    sessionId: "strict",
    membership: "ARCHIVED",
    generation: 4,
  })?.archiveState, "ARCHIVED");
});

test("同原始 id 分来源记账：Codex 墓碑不拦 Claude 活动", () => {
  const ledger = new SourceMembershipLedger();
  applyAll(ledger, [
    membershipFromExplicitHook("codex", { type: "membership", sessionId: "same", membership: "ACTIVE", generation: 1 }),
    membershipFromExplicitHook("claude", { type: "membership", sessionId: "same", membership: "ACTIVE", generation: 1 }),
    membershipFromExplicitHook("codex", { type: "membership", sessionId: "same", membership: "ARCHIVED", generation: 2 }),
  ]);
  assert.equal(ledger.tombstone("codex", "same"), true);
  assert.equal(ledger.tombstone("claude", "same"), false);
  assert.equal(ledger.acceptsActivity("codex", "same"), false);
  assert.equal(ledger.acceptsActivity("claude", "same"), true);
});

// Claude authoritative local-record membership scanner tests (spec 0023 / ticket #238).
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  CLAUDE_MEMBERSHIP_POLL_MS,
  claudeMembershipFact,
  parseClaudeMembershipRecord,
  startClaudeMembershipScanner,
} from "./claude-membership.mjs";
import { membershipFact } from "./source-membership.mjs";

async function waitForEvent(events, predicate, timeoutMs = 2500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = events.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("claude membership event timeout");
}

function fixture() {
  const temp = mkdtempSync(join(tmpdir(), "rearcue-claude-membership-"));
  const root = join(temp, "local-agent-mode-sessions");
  const dir = join(root, "account-1", "org-1");
  mkdirSync(dir, { recursive: true });
  return { temp, root, file: join(dir, "session-1.json") };
}

test("Claude JSON records：ACTIVE -> ARCHIVED -> UNKNOWN -> UNARCHIVE，缺失文件只给 UNKNOWN", async () => {
  const { temp, root, file } = fixture();
  writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false, title: "first" }), "utf8");
  const events = [];
  const scanner = startClaudeMembershipScanner((event) => events.push(event), { root, pollMs: 20 });
  try {
    const active = await waitForEvent(events, (e) => e.sourceSessionId === "session-1" && e.archiveState === "ACTIVE");
    assert.equal(active.membership, "PRESENT");
    assert.equal(active.reason, "authoritative-snapshot");

    writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: true }), "utf8");
    const archived = await waitForEvent(events, (e) => e.sourceSessionId === "session-1" && e.archiveState === "ARCHIVED");
    assert.equal(archived.membership, "ABSENT");
    assert.equal(archived.reason, "archive");

    writeFileSync(file, "{not-json", "utf8");
    const unknown = await waitForEvent(events, (e) => e.sourceSessionId === "session-1" && e.archiveState === "UNKNOWN");
    assert.equal(unknown.membership, "PRESENT");
    assert.equal(unknown.reason, "unknown");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), true, "UNKNOWN 不得清除归档墓碑");
    assert.equal(scanner.ledger.acceptsActivity("claude", "session-1"), false);

    writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false }), "utf8");
    const restored = await waitForEvent(
      events,
      (e) => e.sourceSessionId === "session-1" && e.archiveState === "ACTIVE" && e.reason === "unarchive",
    );
    assert.equal(restored.membership, "PRESENT");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), false);

    rmSync(file, { force: true });
    const absentUnknown = await waitForEvent(
      events,
      (e) => e.sourceSessionId === "session-1" && e.archiveState === "UNKNOWN" && e !== unknown,
    );
    assert.equal(absentUnknown.reason, "unknown");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), false);
    assert.equal(scanner.ledger.acceptsActivity("claude", "session-1"), true, "缺失文件不是归档");
  } finally {
    scanner.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

test("Claude JSON records：坏形状回 UNKNOWN；扫描代数保护拒绝旧记录覆盖新墓碑", async () => {
  assert.ok(CLAUDE_MEMBERSHIP_POLL_MS <= 2000, "Claude membership 必须留在 2s 同步预算内");
  assert.deepEqual(parseClaudeMembershipRecord("{broken", "fallback"), {
    valid: false,
    sessionId: "fallback",
    reason: "unknown",
  });
  assert.equal(parseClaudeMembershipRecord(JSON.stringify({ sessionId: "x" }), "fallback").valid, false);
  assert.equal(
    claudeMembershipFact({
      record: { valid: false, sessionId: "fallback" },
      generation: 1,
      revision: 1,
    }).archiveState,
    "UNKNOWN",
  );

  const { temp, root, file } = fixture();
  writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false }), "utf8");
  const events = [];
  const scanner = startClaudeMembershipScanner((event) => events.push(event), { root, pollMs: 20 });
  try {
    const first = await waitForEvent(events, (e) => e.archiveState === "ACTIVE");
    const newerTombstone = membershipFact({
      source: "claude",
      sourceSessionId: "session-1",
      membership: "ARCHIVED",
      generation: first.generation + 1_000_000,
      revision: first.revision + 1_000_000,
      reason: "archive",
    });
    assert.equal(scanner.ledger.apply(newerTombstone).accepted, true);

    const before = events.length;
    writeFileSync(file, JSON.stringify({ sessionId: "session-1", isArchived: false, changed: true }), "utf8");
    scanner.scan();
    assert.equal(events.length, before, "旧 generation 的记录不得覆盖更新墓碑");
    assert.equal(scanner.ledger.tombstone("claude", "session-1"), true);
    assert.equal(scanner.ledger.acceptsActivity("claude", "session-1"), false);
  } finally {
    scanner.stop();
    rmSync(temp, { recursive: true, force: true });
  }
});

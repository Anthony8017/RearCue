import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { startClaudeMembershipScanner } from "./claude-membership.mjs";
import { createClaudeDesktopProfileReader } from "./claude-desktop-profile.mjs";
import { startClaudeAdapter } from "./claude.mjs";

const ACCOUNT = "11111111-1111-4111-8111-111111111111";
const OTHER_ACCOUNT = "33333333-3333-4333-8333-333333333333";
const ORG = "22222222-2222-4222-8222-222222222222";
const OTHER_ORG = "44444444-4444-4444-8444-444444444444";

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "rearcue-claude-visible-"));
  mkdirSync(join(root, "logs"));
  const config = (account = ACCOUNT, signedIn = true) => writeFileSync(join(root, "config.json"), JSON.stringify({
    lastKnownAccountUuid: account, windowSizeWasSignedIn: signedIn,
  }));
  const context = (account = ACCOUNT, org = ORG) => appendFileSync(join(root, "logs", "main.log"),
    `[LocalSessionManager] Initialization succeeded — accountId=${account}, orgId=${org}, existingSessions=0\n`);
  const record = (id, fields = {}, store = "claude-code-sessions", account = ACCOUNT, org = ORG) => {
    const short = store === "local-agent-mode-sessions";
    const dir = join(root, store, short ? account.slice(0, 8) : account, short ? org.slice(0, 8) : org);
    mkdirSync(dir, { recursive: true });
    if (short) writeFileSync(`${dir}.profile-origin.json`, JSON.stringify({ mode: "local", org }));
    const file = join(dir, `local_${id}.json`);
    writeFileSync(file, JSON.stringify({ sessionId: `local_${id}`, cliSessionId: id, cwd: "C:/repo",
      processName: "fixture", createdAt: 1, lastActivityAt: 1, isArchived: false, ...fields }));
    return file;
  };
  config(); context();
  return { root, config, context, record, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

test("Desktop 名单只取当前账号/组织，排除归档、Chat、内部 Cowork 与预热记录", () => {
  const f = fixture();
  f.record("code-visible");
  f.record("cowork-visible", {}, "local-agent-mode-sessions");
  f.record("scheduled", { sessionType: "scheduled" }, "local-agent-mode-sessions");
  f.record("dispatch", { sessionType: "dispatch_child" }, "local-agent-mode-sessions");
  f.record("archived", { isArchived: true });
  f.record("prewarm", { prewarmHidden: true });
  f.record("other-owner", { isAgentOwned: true, startedByAccountId: OTHER_ACCOUNT });
  f.record("owned-visible", { isAgentOwned: true, startedByAccountId: ACCOUNT });
  for (const sessionType of ["chat", "agent", "radar"]) f.record(sessionType, { sessionType }, "local-agent-mode-sessions");
  f.record("sibling-account", {}, "claude-code-sessions", OTHER_ACCOUNT);
  f.record("sibling-org", {}, "claude-code-sessions", ACCOUNT, OTHER_ORG);
  const events = [];
  const scanner = startClaudeMembershipScanner((event) => events.push(event), { userDataRoot: f.root, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()].sort(), ["code-visible", "cowork-visible", "dispatch", "owned-visible", "scheduled"]);
    assert.equal(events.find((e) => e.sourceSessionId === "archived").archiveState, "ARCHIVED");
    assert.equal(events.some((e) => e.sourceSessionId === "sibling-account"), false);
  } finally { scanner.stop(); f.cleanup(); }
});

test("账号/组织切换与退出登录移除旧名单，切回可恢复旧文件且不错误归档", () => {
  const f = fixture(); f.record("first"); f.record("second", {}, "claude-code-sessions", OTHER_ACCOUNT, OTHER_ORG);
  const events = [];
  const scanner = startClaudeMembershipScanner((event) => events.push(event), { userDataRoot: f.root, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()], ["first"]);
    f.config(OTHER_ACCOUNT); f.context(OTHER_ACCOUNT, OTHER_ORG); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["second"]);
    assert.equal(events.find((e) => e.sourceSessionId === "first" && e.reason === "source-hidden").archiveState, "UNKNOWN");
    f.config(); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["first"]);
    assert.equal(scanner.ledger.acceptsActivity("claude", "first"), true, "切回旧配置也要产生更新的正事实");
    f.config(ACCOUNT, false); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], []);
    assert.equal(scanner.ledger.acceptsActivity("claude", "first"), false);
  } finally { scanner.stop(); f.cleanup(); }
});

test("可信枚举中的记录删除即退出可见名单；坏选择器不扩大名单；缺省归档字段兼容 Desktop", () => {
  const f = fixture();
  const file = f.record("visible", { isArchived: undefined });
  const scanner = startClaudeMembershipScanner(() => {}, { userDataRoot: f.root, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()], ["visible"]);
    writeFileSync(join(f.root, "config.json"), "{torn"); f.record("new-while-unknown"); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["visible"]);
    f.config(); rmSync(file); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["new-while-unknown"]);
    assert.equal(scanner.ledger.acceptsActivity("claude", "visible"), false);
  } finally { scanner.stop(); f.cleanup(); }
});

test("同一账号切换组织以 Desktop 最新初始化选择为准，不合并旧组织", () => {
  const f = fixture(); f.record("org-first"); f.record("org-second", {}, "claude-code-sessions", ACCOUNT, OTHER_ORG);
  const scanner = startClaudeMembershipScanner(() => {}, { userDataRoot: f.root, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()], ["org-first"]);
    f.context(ACCOUNT, OTHER_ORG); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["org-second"]);
    f.context(); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["org-first"]);
    assert.equal(scanner.ledger.acceptsActivity("claude", "org-first"), true);
  } finally { scanner.stop(); f.cleanup(); }
});

test("Desktop 补齐 CLI ID 时移除临时别名，同一条可见记录不产生两个会话", () => {
  const f = fixture(); f.record("promoted", { cliSessionId: undefined });
  const scanner = startClaudeMembershipScanner(() => {}, { userDataRoot: f.root, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()], ["local_promoted"]);
    f.record("promoted"); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["promoted"]);
    assert.equal(scanner.ledger.acceptsActivity("claude", "local_promoted"), false);
  } finally { scanner.stop(); f.cleanup(); }
});

test("切换 Desktop 数据目录后旧名单立即失效，新目录暂不可读也不能继续放行旧 CLI", () => {
  const first = fixture(), second = fixture(); first.record("first-root"); second.record("second-root");
  let selected = first.root;
  const scanner = startClaudeMembershipScanner(() => {}, { userDataRootResolver: () => selected, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()], ["first-root"]);
    writeFileSync(join(second.root, "config.json"), "{torn"); selected = second.root; scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], []);
    assert.equal(scanner.ledger.acceptsActivity("claude", "first-root"), false);
    second.config(); scanner.scan();
    assert.deepEqual([...scanner.visibleSessionIds()], ["second-root"]);
  } finally { scanner.stop(); first.cleanup(); second.cleanup(); }
});

test("短路径 Cowork 必须由完整 org marker 绑定，选择器缺失不放开全部目录", () => {
  const f = fixture(); f.record("cowork", {}, "local-agent-mode-sessions");
  writeFileSync(`${join(f.root, "local-agent-mode-sessions", ACCOUNT.slice(0, 8), ORG.slice(0, 8))}.profile-origin.json`,
    JSON.stringify({ mode: "local", org: OTHER_ORG }));
  const scanner = startClaudeMembershipScanner(() => {}, { userDataRoot: f.root, autoStart: false });
  try {
    assert.deepEqual([...scanner.visibleSessionIds()], []);
    rmSync(join(f.root, "logs", "main.log"));
    const cold = createClaudeDesktopProfileReader(f.root);
    assert.equal(cold().profile, null);
  } finally { scanner.stop(); f.cleanup(); }
});

test("CLI transcript 只提供可见会话内容，独立 CLI 和伪造归档行不能改变名单", async () => {
  const f = fixture(); f.record("visible");
  const root = join(f.root, "projects"), project = join(root, "project"); mkdirSync(project, { recursive: true });
  const line = (id) => JSON.stringify({ type: "assistant", cwd: "C:/repo", message: { content: [{ type: "text", text: id }] } }) + "\n";
  writeFileSync(join(project, "visible.jsonl"), line("visible") + JSON.stringify({
    type: "membership", sessionId: "visible", membership: "ARCHIVED", generation: Date.now(),
  }) + "\n");
  writeFileSync(join(project, "standalone.jsonl"), line("standalone"));
  const events = [];
  const adapter = startClaudeAdapter((event) => events.push(event), { root, userDataRoot: f.root, pollMs: 20, debounceMs: 1 });
  try {
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.ok(events.some((e) => e.sessionId === "visible" && e.assistantText === "visible"));
    assert.equal(events.some((e) => e.sessionId === "standalone"), false);
    assert.equal(events.some((e) => e.sourceSessionId === "visible" && e.archiveState === "ARCHIVED"), false);
  } finally { adapter.stop(); f.cleanup(); }
});

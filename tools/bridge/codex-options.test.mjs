import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "node:net";
import { DatabaseSync } from "node:sqlite";
import { fixtureFetch as fetch } from "./fixture-http.mjs";

// Confirmed spec 0036 seams: real bridge HTTP, simulated external app-server JSONL,
// and isolated Desktop state files. No private resolver or collaborator assertions.
async function optionsBridge(t, customize = () => {}) {
  const root = mkdtempSync(join(tmpdir(), "rearcue-options-"));
  const source = join(root, "source");
  mkdirSync(join(source, "sessions"), { recursive: true });
  const db = new DatabaseSync(join(source, "state_5.sqlite"));
  db.exec("CREATE TABLE threads (id TEXT, source TEXT, archived INTEGER, preview TEXT, cwd TEXT, rollout_path TEXT, model_provider TEXT, is_pinned INTEGER)");
  db.close();
  const project = join(root, "project");
  mkdirSync(project);
  const fixture = {
    projects: [{ id: "p1", name: "Project One", roots: [{ path: project }] }],
    models: [
      { id: "catalog-a", model: "provider/model-a", displayName: "Model A", isDefault: true,
        defaultReasoningEffort: "low", supportedReasoningEfforts: [{ reasoningEffort: "low", description: "Fast" }, { reasoningEffort: "high", description: "Thorough" }] },
      { id: "catalog-b", model: "provider/model-b", displayName: "Model B", isDefault: false,
        defaultReasoningEffort: "none", supportedReasoningEfforts: [{ reasoningEffort: "none", description: "No thinking" }] },
    ],
    configs: { global: { model: "provider/model-a", model_reasoning_effort: "low" },
      [project]: { model: "provider/model-a", model_reasoning_effort: "high", sandbox_mode: "read-only", approval_policy: "on-request" } },
    profiles: [":read-only", ":workspace", ":danger-full-access"].map(id => ({ id, description: null, allowed: true })),
    requirements: null,
  };
  const atoms = { "agent-mode-by-host-id": { local: "full-access", remote: "read-only" },
    "permission-selection-by-host-id:local": { kind: "agent-mode", agentMode: "full-access" } };
  customize(fixture, atoms, project);
  const fixturePath = join(root, "fixture.json");
  const desktopPath = join(source, ".codex-global-state.json");
  writeFileSync(fixturePath, JSON.stringify(fixture));
  writeFileSync(desktopPath, JSON.stringify({ "electron-persisted-atom-state": atoms }));
  const socket = createServer();
  await new Promise(r => socket.listen(0, "127.0.0.1", r));
  const port = socket.address().port;
  await new Promise(r => socket.close(r));
  const child = spawn(process.execPath, ["--import", new URL("fixtures/codex-options-loader.mjs", import.meta.url).href,
    fileURLToPath(new URL("bridge.mjs", import.meta.url)), "--no-tunnel", "--no-claude", "--no-dsh", "--no-zcode"], {
    windowsHide: true, stdio: "ignore", env: { ...process.env, BRIDGE_PORT: String(port), BRIDGE_ACCESS_TOKEN: "options-token",
      CODEX_BIN: "codex-options-fixture", CODEX_HOME: source, BRIDGE_CODEX_HOME: source,
      RCU_OPTIONS_FIXTURE: fixturePath, RCU_OPTIONS_REQUESTS: join(root, "requests.jsonl"), BRIDGE_LOG: join(root, "bridge.log"), BRIDGE_SEQ_FILE: join(root, "seq"),
      BRIDGE_IDENTITY_FILE: join(root, "identity.json"), RCU_TRAY_STATE: join(root, "tray.json"), BRIDGE_URL_FILE: join(root, "bridge.url") },
  });
  t.after(async () => {
    if (child.exitCode === null) { const exited = once(child, "exit"); child.kill(); await exited; }
    assert.ok(resolve(root).startsWith(join(tmpdir(), "rearcue-options-")));
    rmSync(root, { recursive: true });
  });
  const base = `http://127.0.0.1:${port}`;
  let healthy = false;
  for (let n = 0; n < 100; n++) {
    try { if ((await fetch(base + "/health")).ok) { healthy = true; break; } } catch { /* starting */ }
    await new Promise(r => setTimeout(r, 50));
  }
  assert.ok(healthy, "isolated bridge failed to start");
  return { fixture, project, desktopPath,
    requests: () => readFileSync(join(root, "requests.jsonl"), "utf8").trim().split("\n").filter(Boolean).map(JSON.parse),
    post: async body => {
      const response = await fetch(base + "/codex/conversations", { method: "POST",
        headers: { Authorization: "Bearer options-token", "Content-Type": "application/json" }, body: JSON.stringify(body) });
      return { status: response.status, body: await response.json() };
    },
    save: () => writeFileSync(fixturePath, JSON.stringify(fixture)),
    get: async (query = "?projectId=p1") => {
      const response = await fetch(base + "/codex/options" + query, { headers: { Authorization: "Bearer options-token" } });
      return { status: response.status, body: await response.json() };
    } };
}

test("project defaults and local Desktop permission reach HTTP independently of catalog defaults", async t => {
  const bridge = await optionsBridge(t);
  const desktopBefore = readFileSync(bridge.desktopPath, "utf8");
  const { status, body } = await bridge.get();
  assert.equal(status, 200);
  assert.deepEqual(body.context, { projectId: "p1", workspace: bridge.project, model: "provider/model-a" });
  assert.deepEqual(body.defaults, { model: "provider/model-a", effort: "high", permission: "full-access" });
  assert.deepEqual(body.efforts, [{ id: "low", description: "Fast" }, { id: "high", description: "Thorough" }]);
  assert.deepEqual(body.permissions.find(p => p.id === "full-access")?.settings,
    { permissions: ":danger-full-access", approvalPolicy: "never", approvalsReviewer: "user" });
  assert.deepEqual(body.errors, []);
  assert.equal(readFileSync(bridge.desktopPath, "utf8"), desktopBefore);
});

test("managed permission limits remove forbidden local preferences and full access", async t => {
  const bridge = await optionsBridge(t, fixture => {
    fixture.requirements = { allowedPermissionProfiles: { ":workspace": true, ":read-only": false },
      allowedSandboxModes: ["workspace-write"], allowedApprovalPolicies: ["on-request"], allowedApprovalsReviewers: ["user"],
      defaultPermissions: ":workspace" };
  });
  const { body } = await bridge.get();
  assert.deepEqual(body.permissions.map(p => p.id), ["auto"]);
  assert.equal(body.defaults.permission, "auto");
});

test("failed default read preserves valid manual model, effort and permission choices", async t => {
  const bridge = await optionsBridge(t, fixture => { fixture.fail = ["config/read"]; });
  const { status, body } = await bridge.get("?projectId=p1&model=provider%2Fmodel-b");
  assert.equal(status, 200);
  assert.deepEqual(body.defaults, { model: null, effort: null, permission: null });
  assert.deepEqual(body.context, { projectId: "p1", workspace: bridge.project, model: "provider/model-b" });
  assert.deepEqual(body.efforts, [{ id: "none", description: "No thinking" }]);
  assert.ok(body.permissions.some(p => p.id === "full-access"));
  assert.equal(body.models.length, 2);
  assert.deepEqual(body.errors, ["defaults.model", "defaults.effort", "defaults.permission"].map(field => ({ field, code: "read-failed" })));
  assert.equal(JSON.stringify(body).includes("sensitive fixture error"), false);
});

test("unreadable Desktop preferences leave only permission default unknown", async t => {
  const bridge = await optionsBridge(t);
  writeFileSync(bridge.desktopPath, "not valid JSON");
  const { status, body } = await bridge.get();
  assert.equal(status, 200);
  assert.deepEqual(body.defaults, { model: "provider/model-a", effort: "high", permission: null });
  assert.ok(body.permissions.some(p => p.id === "full-access"));
  assert.deepEqual(body.errors, [{ field: "defaults.permission", code: "read-failed" }]);
});

test("fresh Desktop without a preference file uses the project permission profile", async t => {
  const bridge = await optionsBridge(t, (fixture, atoms, project) => { fixture.configs[project].default_permissions = ":workspace"; });
  rmSync(bridge.desktopPath);
  const { body } = await bridge.get();
  assert.equal(body.defaults.permission, "auto");
  assert.deepEqual(body.errors, []);
});

test("unavailable permission authority never fabricates a selectable list", async t => {
  for (const method of ["permissionProfile/list", "configRequirements/read"]) await t.test(method, async t => {
    const bridge = await optionsBridge(t, fixture => { fixture.fail = [method]; });
    const { status, body } = await bridge.get("?projectId=p1&model=provider%2Fmodel-a");
    assert.equal(status, 200);
    assert.deepEqual(body.permissions, []);
    assert.equal(body.defaults.permission, null);
    assert.equal(body.efforts.length, 2);
    assert.ok(body.errors.some(e => e.field === "permissions" && e.code === "read-failed"));
    assert.ok(body.errors.some(e => e.field === "defaults.permission" && e.code === "read-failed"));
  });
});

test("invalid explicit context is rejected instead of reading a different project or model", async t => {
  const bridge = await optionsBridge(t);
  for (const [query, error] of [["?projectId=gone", "invalid-project"], ["?projectId=p1&model=gone", "invalid-model"]]) {
    const { status, body } = await bridge.get(query);
    assert.equal(status, 400);
    assert.deepEqual(body, { ok: false, receipt: "bad-request", error });
  }
  bridge.fixture.projects[0].roots = [];
  bridge.save();
  assert.equal((await bridge.get()).body.error, "invalid-project");
});

test("model catalog read failure preserves project and permission choices without invented efforts", async t => {
  const bridge = await optionsBridge(t, fixture => { fixture.fail = ["model/list"]; });
  const { status, body } = await bridge.get();
  assert.equal(status, 200);
  assert.deepEqual(body.models, []);
  assert.deepEqual(body.efforts, []);
  assert.deepEqual(body.defaults, { model: "provider/model-a", effort: null, permission: "full-access" });
  assert.ok(body.permissions.some(p => p.id === "full-access"));
  for (const field of ["models", "efforts", "defaults.effort"]) assert.ok(body.errors.some(e => e.field === field && e.code === "read-failed"));
});

test("unreadable project list cannot silently resolve an explicit project against global config", async t => {
  const bridge = await optionsBridge(t, fixture => { fixture.fail = ["project/list"]; });
  assert.deepEqual(await bridge.get(), { status: 503, body: { ok: false, receipt: "unavailable", error: "projects-unavailable" } });
  const { status, body } = await bridge.get("");
  assert.equal(status, 200);
  assert.deepEqual(body.context, { projectId: null, workspace: null, model: "provider/model-a" });
  assert.equal(body.defaults.effort, "low");
  assert.deepEqual(body.errors, [{ field: "projects", code: "read-failed" }]);
});

test("projects, models and allowed profiles after the first page remain selectable", async t => {
  const bridge = await optionsBridge(t, fixture => {
    fixture.pages = {
      "project/list": [{ cursor: null, data: [], nextCursor: "projects-2" }, { cursor: "projects-2", data: fixture.projects, nextCursor: null }],
      "model/list": [{ cursor: null, data: [fixture.models[0]], nextCursor: "models-2" }, { cursor: "models-2", data: [fixture.models[1]], nextCursor: null }],
      "permissionProfile/list": [{ cursor: null, data: fixture.profiles.slice(0, 2), nextCursor: "profiles-2" },
        { cursor: "profiles-2", data: fixture.profiles.slice(2), nextCursor: null }],
    };
  });
  const { status, body } = await bridge.get("?projectId=p1&model=provider%2Fmodel-b");
  assert.equal(status, 200);
  assert.deepEqual(body.defaults, { model: "provider/model-a", effort: "none", permission: "full-access" });
  assert.deepEqual(body.efforts, [{ id: "none", description: "No thinking" }]);
  assert.deepEqual(body.models.map(m => m.id), ["provider/model-a", "provider/model-b"]);
  assert.ok(body.permissions.some(p => p.id === "full-access"));
});

test("managed new-thread model and effort take precedence over project defaults", async t => {
  const bridge = await optionsBridge(t, fixture => {
    fixture.requirements = { models: { newThread: { model: "provider/model-b", modelReasoningEffort: "none" } } };
  });
  const { body } = await bridge.get();
  assert.deepEqual(body.context, { projectId: "p1", workspace: bridge.project, model: "provider/model-b" });
  assert.deepEqual(body.defaults, { model: "provider/model-b", effort: "none", permission: "full-access" });
  const selected = (await bridge.get("?projectId=p1&model=provider%2Fmodel-a")).body;
  assert.deepEqual(selected.defaults, { model: "provider/model-b", effort: "low", permission: "full-access" });
  assert.equal(selected.context.model, "provider/model-a");
});

test("allowed named profiles and Desktop profile preference remain explicit choices", async t => {
  const bridge = await optionsBridge(t, (fixture, atoms, project) => {
    fixture.profiles.push({ id: "team-review", description: "团队限定目录与确认规则", allowed: true }, { id: "blocked", description: "不可选", allowed: false });
    fixture.configs[project].default_permissions = ":workspace";
    fixture.requirements = { allowedPermissionProfiles: { ":workspace": true, "team-review": true }, defaultPermissions: "team-review",
      allowedApprovalsReviewers: ["user"] };
    atoms["permission-selection-by-host-id:local"] = { kind: "profile", profileId: "team-review" };
  });
  const { body } = await bridge.get();
  assert.equal(body.defaults.permission, "profile:team-review");
  assert.deepEqual(body.permissions.map(p => p.id), ["auto", "profile:team-review"]);
  assert.deepEqual(body.permissions.find(p => p.id === "profile:team-review"), {
    id: "profile:team-review", label: "team-review", description: "团队限定目录与确认规则", settings: { permissions: "team-review" },
  });
  writeFileSync(bridge.desktopPath, JSON.stringify({ "electron-persisted-atom-state": {} }));
  assert.equal((await bridge.get()).body.defaults.permission, "profile:team-review");
  bridge.fixture.requirements.allowedPermissionProfiles["team-review"] = false;
  bridge.fixture.requirements.defaultPermissions = ":workspace";
  bridge.save();
  const removed = (await bridge.get()).body;
  assert.deepEqual(removed.permissions.map(p => p.id), ["auto"]);
  assert.equal(removed.defaults.permission, "auto");
});

test("guardian permissions are advertised with real approval routing and managed model requirements", async t => {
  const bridge = await optionsBridge(t, fixture => { fixture.requirements = { allowedApprovalsReviewers: ["user", "auto_review"] }; });
  const { body } = await bridge.get();
  assert.deepEqual(body.permissions.find(p => p.id === "guardian-approvals")?.settings,
    { permissions: ":workspace", approvalPolicy: "on-request", approvalsReviewer: "guardian_subagent" });
  bridge.fixture.requirements.autoReview = { requiredOnModels: ["model-a"] };
  bridge.save();
  const required = (await bridge.get()).body;
  assert.deepEqual(required.permissions.map(p => p.id), ["guardian-approvals"]);
  assert.equal(required.defaults.permission, "guardian-approvals");
  const other = (await bridge.get("?projectId=p1&model=provider%2Fmodel-b")).body;
  assert.ok(other.permissions.some(p => p.id === "full-access"));
});

test("Desktop custom permission preserves the effective nonstandard workspace rules without exposing config", async t => {
  const bridge = await optionsBridge(t, (fixture, atoms, project) => {
    Object.assign(fixture.configs[project], { sandbox_mode: "workspace-write", approval_policy: "on-request", approvals_reviewer: "user",
      sandbox_workspace_write: { network_access: true, writable_roots: [project], exclude_slash_tmp: true },
      model_providers: { custom: { api_key: "never-return-this" } } });
    fixture.profiles.push({ id: "team-review", description: "Team rules", allowed: true });
    atoms["permission-selection-by-host-id:local"] = { kind: "custom" };
  });
  const { body } = await bridge.get();
  assert.equal(body.defaults.permission, "custom");
  assert.deepEqual(body.permissions.find(p => p.id === "custom")?.settings, {
    sandbox: "workspace-write", approvalPolicy: "on-request", approvalsReviewer: "user",
    config: { sandbox_workspace_write: { network_access: true, writable_roots: [bridge.project], exclude_slash_tmp: true } },
  });
  assert.equal(JSON.stringify(body).includes("never-return-this"), false);
  bridge.fixture.requirements = { allowedApprovalPolicies: ["never"] };
  bridge.save();
  assert.ok((await bridge.get()).body.permissions.every(p => p.id !== "custom"));
});

test("without a saved override project permission defaults refresh on each read", async t => {
  const bridge = await optionsBridge(t, (_fixture, atoms) => { for (const key of Object.keys(atoms)) delete atoms[key]; });
  assert.equal((await bridge.get()).body.defaults.permission, "read-only");
  Object.assign(bridge.fixture.configs[bridge.project], { sandbox_mode: "danger-full-access", approval_policy: "never" });
  bridge.save();
  assert.equal((await bridge.get()).body.defaults.permission, "full-access");
  bridge.fixture.configs[bridge.project].default_permissions = ":read-only";
  bridge.fixture.requirements = { defaultPermissions: ":workspace" };
  bridge.save();
  assert.equal((await bridge.get()).body.defaults.permission, "auto");
});

test("unknown defaults and inconsistent effort metadata stay unknown with manual choices intact", async t => {
  const bridge = await optionsBridge(t, (fixture, _atoms, project) => {
    Object.assign(fixture.configs[project], { model: null, model_reasoning_effort: null });
    for (const model of fixture.models) model.isDefault = false;
    fixture.models[0].defaultReasoningEffort = "not-in-supported-list";
  });
  const unselected = (await bridge.get()).body;
  assert.equal(unselected.defaults.model, null);
  assert.ok(unselected.errors.some(e => e.field === "defaults.model" && e.code === "unavailable"));
  const selected = (await bridge.get("?projectId=p1&model=provider%2Fmodel-a")).body;
  assert.equal(selected.defaults.effort, null);
  assert.deepEqual(selected.efforts, [{ id: "low", description: "Fast" }, { id: "high", description: "Thorough" }]);
  assert.ok(selected.errors.some(e => e.field === "defaults.effort" && e.code === "unavailable"));
});

test("legacy config-derived host state does not masquerade as an explicit Desktop override", async t => {
  const bridge = await optionsBridge(t, (_fixture, atoms) => {
    delete atoms["permission-selection-by-host-id:local"];
    atoms["config-derived-agent-mode-by-host-id"] = { local: "full-access" };
  });
  assert.equal((await bridge.get()).body.defaults.permission, "read-only");
  writeFileSync(bridge.desktopPath, JSON.stringify({ "electron-persisted-atom-state": {
    "agent-mode-by-host-id": { local: "full-access" }, "config-derived-agent-mode-by-host-id": { local: null },
  } }));
  assert.equal((await bridge.get()).body.defaults.permission, "full-access");
});


test("creation validates and applies the displayed snapshot after PC defaults change", async t => {
  const bridge = await optionsBridge(t);
  const shown = (await bridge.get()).body;
  assert.deepEqual(shown.defaults, { model: "provider/model-a", effort: "high", permission: "full-access" });
  bridge.fixture.configs[bridge.project].model_reasoning_effort = "low";
  bridge.save();
  const result = await bridge.post({ projectId: "p1", model: shown.context.model, prompt: "snapshot task",
    effort: shown.defaults.effort, permission: shown.defaults.permission, requestId: "snapshot-create" });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  assert.deepEqual({ model: result.body.model, effort: result.body.effort, permission: result.body.permission },
    { model: "provider/model-a", effort: "high", permission: "full-access" });
  const started = bridge.requests().find(request => request.method === "thread/start").params;
  assert.equal(started.permissions, ":danger-full-access");
  assert.equal(started.approvalPolicy, "never");
  assert.equal(started.config.model_reasoning_effort, "high");
  const turn = bridge.requests().find(request => request.method === "turn/start").params;
  for (const key of ["effort", "permissions", "approvalPolicy", "approvalsReviewer", "sandboxPolicy"]) assert.equal(Object.hasOwn(turn, key), false);
});

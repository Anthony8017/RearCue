// Spec 0036: read-only new-conversation options. No turn or configuration writes.
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import { isDeepStrictEqual } from "node:util";

const MODES = [
  { id: "read-only", label: "只读", description: "可读取文件；修改文件或执行超出只读范围的命令需确认。",
    settings: { permissions: ":read-only", approvalPolicy: "on-request", approvalsReviewer: "user" } },
  { id: "auto", label: "工作区访问", description: "可修改项目文件和执行工作区内命令；超出范围时需确认。",
    settings: { permissions: ":workspace", approvalPolicy: "on-request", approvalsReviewer: "user" } },
  { id: "guardian-approvals", label: "自动审查", description: "可修改项目文件；额外权限申请交由电脑的自动审查代理决定。",
    settings: { permissions: ":workspace", approvalPolicy: "on-request", approvalsReviewer: "guardian_subagent" } },
  { id: "full-access", label: "完全访问", description: "可修改电脑文件和执行命令，无需逐项确认。",
    settings: { permissions: ":danger-full-access", approvalPolicy: "never", approvalsReviewer: "user" } },
];

const SANDBOX_PROFILES = { "read-only": ":read-only", "workspace-write": ":workspace", "danger-full-access": ":danger-full-access" };
const WORKSPACE_KEYS = ["writable_roots", "network_access", "exclude_slash_tmp", "exclude_tmpdir_env_var"];

// Desktop's legacy custom mode resolves omitted approval/reviewer fields this way.
// Only permission fields cross the HTTP boundary, never the rest of config/read.
function legacySettings(config) {
  const sandbox = config.sandbox_mode ?? "read-only";
  if (!Object.hasOwn(SANDBOX_PROFILES, sandbox)) return null;
  const settings = { sandbox, approvalPolicy: config.approval_policy ?? (sandbox === "danger-full-access" ? "never" : "on-request"),
    approvalsReviewer: config.approvals_reviewer ?? "user" };
  if (sandbox === "workspace-write" && config.sandbox_workspace_write != null) {
    settings.config = { sandbox_workspace_write: Object.fromEntries(WORKSPACE_KEYS
      .filter(k => Object.hasOwn(config.sandbox_workspace_write, k)).map(k => [k, config.sandbox_workspace_write[k]])) };
  }
  return settings;
}

export async function readConversationOptions(control, { projectId = null, model = null } = {}) {
  let modelsFailed = false;
  let projectsFailed = false;
  const [projects, models] = await Promise.all([control.listProjects().catch(() => {
    projectsFailed = true;
    return [];
  }), control.listModels().catch(() => {
    modelsFailed = true;
    return [];
  })]);
  const project = projects.find(p => p.id === projectId);
  const workspace = project?.roots?.[0]?.path ?? null;
  if (projectId !== null && projectsFailed) {
    throw Object.assign(new Error("projects-unavailable"), { statusCode: 503, receipt: "unavailable" });
  }
  if (model !== null && modelsFailed) {
    throw Object.assign(new Error("models-unavailable"), { statusCode: 503, receipt: "unavailable" });
  }
  if (projectId !== null && (!project || !workspace || !isAbsolute(workspace))) {
    throw Object.assign(new Error("invalid-project"), { statusCode: 400, receipt: "bad-request" });
  }
  if (model !== null && !models.some(m => (m.model || m.id) === model)) {
    throw Object.assign(new Error("invalid-model"), { statusCode: 400, receipt: "bad-request" });
  }
  let configFailed = false;
  let desktopFailed = false;
  let profilesFailed = false;
  let requirementsFailed = false;
  const errors = [];
  if (projectsFailed) errors.push({ field: "projects", code: "read-failed" });
  if (modelsFailed) for (const field of ["defaults.effort", "models", "efforts"]) errors.push({ field, code: "read-failed" });
  const [{ config }, profiles, { requirements }, desktop] = await Promise.all([
    control.request("config/read", { cwd: workspace, includeLayers: false }).catch(() => {
      configFailed = true;
      for (const field of ["defaults.model", "defaults.effort", "defaults.permission"]) errors.push({ field, code: "read-failed" });
      return { config: {} };
    }),
    control.listPermissionProfiles(workspace).catch(() => {
      profilesFailed = true;
      return [];
    }),
    control.request("configRequirements/read").catch(() => {
      requirementsFailed = true;
      return { requirements: null };
    }),
    readFile(join(control.env.CODEX_HOME || join(homedir(), ".codex"), ".codex-global-state.json"), "utf8").then(JSON.parse).catch(error => {
      if (error.code === "ENOENT") return {};
      desktopFailed = true;
      return {};
    }),
  ]);
  const managed = requirements?.models?.newThread;
  const defaultModel = configFailed || requirementsFailed ? null : managed?.model ?? config.model ?? models.find(m => m.isDefault)?.model ?? null;
  const selectedModel = model ?? defaultModel;
  const metadata = models.find(m => (m.model || m.id) === selectedModel);
  const efforts = (metadata?.supportedReasoningEfforts || []).map(e => ({ id: e.reasoningEffort, description: e.description || "" }));
  const savedEffort = managed?.model != null ? managed.modelReasoningEffort : managed?.modelReasoningEffort ?? config.model_reasoning_effort;
  const effort = configFailed || requirementsFailed ? null : efforts.some(e => e.id === savedEffort) ? savedEffort
    : efforts.find(e => e.id === metadata?.defaultReasoningEffort)?.id ?? null;
  // Desktop managed-model matching strips one provider prefix, never arbitrary path segments.
  const policyModel = selectedModel?.replace(/^[A-Za-z0-9_-]+\/(?=[^/]+$)/, "");
  const guardianRequired = requirements?.autoReview?.requiredOnModels?.includes(policyModel) === true;
  const permissions = requirementsFailed ? [] : MODES.filter(p => {
    const profileId = p.settings.permissions;
    const sandbox = { ":read-only": "read-only", ":workspace": "workspace-write", ":danger-full-access": "danger-full-access" }[profileId];
    const reviewer = p.settings.approvalsReviewer;
    return (!guardianRequired || p.id === "guardian-approvals")
      && (p.id !== "guardian-approvals" || guardianRequired || (config["features.guardian_approval"] ?? config.features?.guardian_approval) !== false)
      && profiles.some(profile => profile.allowed && profile.id === profileId)
      && (requirements?.allowedPermissionProfiles == null || requirements.allowedPermissionProfiles[profileId] === true)
      && (requirements?.allowedSandboxModes == null || requirements.allowedSandboxModes.includes(sandbox))
      && (requirements?.allowedApprovalPolicies == null || requirements.allowedApprovalPolicies.some(a => isDeepStrictEqual(a, p.settings.approvalPolicy)))
      && (requirements?.allowedApprovalsReviewers == null || requirements.allowedApprovalsReviewers.includes(reviewer)
        || reviewer === "guardian_subagent" && requirements.allowedApprovalsReviewers.includes("auto_review"));
  });
  if (!requirementsFailed && !guardianRequired) for (const profile of profiles) {
    if (!profile.allowed || profile.id.startsWith(":")) continue;
    if (requirements?.allowedPermissionProfiles != null && requirements.allowedPermissionProfiles[profile.id] !== true) continue;
    permissions.push({ id: `profile:${profile.id}`, label: profile.id,
      description: profile.description || "采用电脑定义的权限与确认规则。", settings: { permissions: profile.id } });
  }
  const legacy = configFailed ? null : legacySettings(config);
  const workspaceRules = legacy?.config?.sandbox_workspace_write;
  const hasExtraRules = workspaceRules && ((workspaceRules.writable_roots?.length ?? 0) > 0 || workspaceRules.network_access === true
    || workspaceRules.exclude_slash_tmp === true || workspaceRules.exclude_tmpdir_env_var === true);
  const equivalent = legacy && !hasExtraRules ? MODES.find(p => p.settings.permissions === SANDBOX_PROFILES[legacy.sandbox]
    && isDeepStrictEqual(p.settings.approvalPolicy, legacy.approvalPolicy) && p.settings.approvalsReviewer === legacy.approvalsReviewer)?.id : null;
  if (legacy && !equivalent && !configFailed && !requirementsFailed && !guardianRequired
      && profiles.some(p => p.allowed && p.id === SANDBOX_PROFILES[legacy.sandbox])
      && (requirements?.allowedPermissionProfiles == null || requirements.allowedPermissionProfiles[SANDBOX_PROFILES[legacy.sandbox]] === true)
      && (requirements?.allowedSandboxModes == null || requirements.allowedSandboxModes.includes(legacy.sandbox))
      && (requirements?.allowedApprovalPolicies == null || requirements.allowedApprovalPolicies.some(p => isDeepStrictEqual(p, legacy.approvalPolicy)))
      && (requirements?.allowedApprovalsReviewers == null || requirements.allowedApprovalsReviewers.includes(legacy.approvalsReviewer)
        || legacy.approvalsReviewer === "guardian_subagent" && requirements.allowedApprovalsReviewers.includes("auto_review"))) {
    permissions.push({ id: "custom", label: "电脑自定义", description: "采用电脑配置的文件访问范围、网络权限与确认规则。", settings: legacy });
  }
  const atoms = desktop["electron-persisted-atom-state"] || {};
  const preference = atoms["permission-selection-by-host-id:local"];
  const legacyMode = atoms["agent-mode-by-host-id"]?.local;
  const explicitLegacyMode = legacyMode !== atoms["config-derived-agent-mode-by-host-id"]?.local ? legacyMode : null;
  const preferred = preference?.kind === "profile" ? `profile:${preference.profileId}` : preference?.kind === "custom" ? "custom"
    : preference?.agentMode ?? explicitLegacyMode;
  const configuredProfile = requirements?.defaultPermissions ?? config.default_permissions;
  const hasLegacyDefault = [config.sandbox_mode, config.approval_policy, config.approvals_reviewer].some(v => v != null);
  const configuredPermission = configuredProfile != null ? permissions.find(p => p.settings.permissions === configuredProfile)?.id ?? null
    : hasLegacyDefault ? equivalent ?? "custom" : null;
  const permission = configFailed || desktopFailed || profilesFailed || requirementsFailed ? null
    : guardianRequired ? permissions.find(p => p.id === "guardian-approvals")?.id ?? null
    : permissions.some(p => p.id === preferred) ? preferred : permissions.find(p => p.id === configuredPermission)?.id ?? null;
  if (desktopFailed && !configFailed) errors.push({ field: "defaults.permission", code: "read-failed" });
  if (profilesFailed || requirementsFailed) {
    for (const field of requirementsFailed ? ["defaults.model", "defaults.effort", "defaults.permission", "permissions"] : ["defaults.permission", "permissions"]) {
      if (!errors.some(e => e.field === field)) errors.push({ field, code: "read-failed" });
    }
  }
  for (const [field, value] of [["defaults.model", defaultModel], ["defaults.effort", effort], ["defaults.permission", permission]]) {
    if (value == null && !errors.some(e => e.field === field)) errors.push({ field,
      code: field === "defaults.permission" && (guardianRequired || configuredProfile != null) ? "not-allowed" : "unavailable" });
  }
  if (!efforts.length && !errors.some(e => e.field === "efforts")) errors.push({ field: "efforts", code: "unavailable" });
  if (!permissions.length && !errors.some(e => e.field === "permissions")) errors.push({ field: "permissions", code: "not-allowed" });
  return {
    ok: true,
    projects: projects.map(p => ({ id: p.id, name: p.name, roots: p.roots || [] })),
    models: models.map(m => ({ id: m.model || m.id, displayName: m.displayName || m.model || m.id, isDefault: Boolean(m.isDefault) })),
    context: { projectId, workspace, model: selectedModel },
    defaults: { model: defaultModel, effort, permission },
    efforts,
    permissions,
    errors: [...new Map(errors.map(e => [e.field, e])).values()],
  };
}

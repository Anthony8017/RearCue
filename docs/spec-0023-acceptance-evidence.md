# Spec 0023 acceptance evidence: Archive Synchrony

Date: 2026-10-01  
Scope: GitHub #238 (acceptance slice for #231), after #236 source boundaries and #237 mirror surfaces.

## Product rule verified

A source-side archive/removal fact is a tombstone in `AgentArchiveTruth`. It removes the
session from the unified roster and from waiting takeover, alerts, approval/question entry,
and Session Lock. Later activity cannot resurrect it. A newer positive `ACTIVE/PRESENT`
fact restores the session; restoration uses the source-supplied state or the last known
state and follows ordinary list ordering (waiting priority only when it is really waiting).

`AgentStateLogic.mirrorRoster` is the single main-screen/rear-screen roster seam. Both
`AppState.agentRoster` and the rear picker projection consume it before
`AgentStateLogic.projectRoster`; the acceptance test asserts identical projected IDs and
order.

## Source-by-source status

| Source | Positive / tombstone evidence | Automated evidence | Acceptance status |
|---|---|---|---|
| ZCode | Task table row `archived:false/omitted` → `ACTIVE`; `archived:true` → `ARCHIVED`; a missing row in a complete task-table snapshot → `ABSENT` (`source-removed`) | `TaskListParserMembershipTest`; `AgentArchiveAcceptanceTest` consumes `TaskListParser.parseMembership` through the unified seam and verifies `ACTIVE -> ARCHIVED -> ACTIVE` | **Automated pass**. Real desktop-operation-to-phone timing is not physically recorded in this environment. |
| Codex | Real filesystem lifecycle: active `~/.codex/sessions/**/rollout-*.jsonl` moved to `~/.codex/archived_sessions/rollout-*.jsonl` → `ARCHIVED`; reverse movement → `ACTIVE` with reason `unarchive` | `adapters.test.mjs` moves a rollout between both roots and asserts tombstone, restoration of cached `working` state/workspace, and no fact for mere file disappearance. `CODEX_POLL_MS=800`, within the 2s budget. | **Automated pass for the real filesystem lifecycle**. A physical phone screenshot/timestamp remains a manual acceptance item. |
| Claude | Claude app code has `isArchived`, `archiveSession`, `unarchiveSession`, and `/archive`/`/unarchive` APIs, but the bridge has no stable app-owned producer or hook that emits them | Explicit `membership` contract is consumed and generation-protected (`BridgeMembershipCodecTest`, `AgentArchiveAcceptanceTest`); `Stop` is not archive | **Blocking gap**. Do not claim real-source Claude acceptance until a producer sends authoritative membership facts (or a stable app-owned source is added). |
| DSH | Host `session/disposed` → `ABSENT` + `source-removed`; `session/created`/`agent/created` → `ACTIVE` | `dsh-events.test.mjs`, `bridge.test.mjs` assert the removal enters `/events` immediately and blocks late activity; `BridgeRelayClientSnapshotTest` proves phone-side ingress receives membership before the same-page late activity without reconnect; `AgentArchiveAcceptanceTest` drives the resulting `AgentSessionsRemoved` into `DashboardCore` and clears lock/state | **Protocol/phone-ingress pass**. The physical handset display check was not available here (no connected device); record that as the remaining manual visual check. |

## Cross-surface evidence

- `AgentArchiveSurfaceTest` (app) covers list rows, waiting takeover, alert bookkeeping, approval/question entry, and stale task/V4/bridge/waiting-exit activity.
- `AgentArchiveSurfaceTest` (core) covers authoritative removal, lock clearing to `Auto`, disconnect retention, and reconnect reconciliation.
- `AgentArchiveAcceptanceTest` parses real source-boundary wire shapes (ZCode task table, Codex/DSH/Claude bridge membership pages) and runs them through `AgentArchiveTruth`, `AgentStateLogic.mirrorRoster`, and `DashboardCore`.
- `BridgeRelayClientSnapshotTest` asserts `memberships` are consumed before snapshot `sessions`, and live event membership is delivered before late activity.

## Limitations that remain explicit

1. Claude real-source archive evidence is blocked by the missing bridge-accessible producer.
2. No physical phone was connected in this verification environment, so the visual 2s main/rear disappearance timestamps are protocol-level evidence rather than a captured handset run.
3. DSH `session/disposed` is proven as live removal, but the product wording remains `source-removed`, not `ARCHIVED`, because ADR 0010 does not prove that DSH archive maps to disposal.

## Recorded results

- Gradle full suite: `BUILD SUCCESSFUL`; 775 tests, 0 failures, 0 errors, 0 skipped.
- Node bridge suite: 101 tests, 0 failures, 0 errors, 0 skipped.
- `node tools/bridge/repo-check.mjs`: 9 scripts qualified (the missing UTF-8 BOM in
  `tools/bridge/dsh-plugin-toggle.ps1` was fixed during verification).

## Commands

```powershell
$env:JAVA_HOME='C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1'
$env:ANDROID_HOME='C:\Users\13691\AppData\Local\Temp\rearcue-android-sdk-20261001'
.\gradlew.bat :app:testDebugUnitTest :core:test :agent:test :notification:test :rear:testDebugUnitTest --console=plain
node --test tools/bridge/bridge.test.mjs tools/bridge/make-icons.test.mjs tools/bridge/tray.test.mjs tools/bridge/adapters/adapters.test.mjs tools/bridge/adapters/source-membership.test.mjs tools/bridge/adapters/dsh/*.test.mjs
node tools/bridge/repo-check.mjs
```

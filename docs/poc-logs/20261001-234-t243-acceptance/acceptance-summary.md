# #243 automated regression and acceptance evidence

Date: 2026-10-01 (Asia/Shanghai)
Branch: `codex/234-t243-acceptance`
Base inspected: `c607591`; merged PR implementation: `1d70fca`

## Automated results

| Check | Result | Evidence |
| --- | --- | --- |
| Full Gradle regression | **PASS**: `BUILD SUCCESSFUL`; 1011 tests, 0 failures, 0 errors, 0 skipped | `gradle-test.txt`, Gradle XML totals |
| Focused bridge regression | **PASS**: 64 tests, 0 failed | `bridge-focused-tests.txt` |
| ZCode title precedence | **PASS** | Existing `BridgeEventCodecTest` bridge event/snapshot title contract and `AgentStateLogicTest` title/source projection |
| Unified roster, Session Lock, disconnect/recovery | **PASS** | Existing `AgentStateLogicTest`, `BridgeRelayClientSnapshotTest`, and `BridgeZCodeSessionLockTest` |
| No direct ZCode fallback | **PASS** | Added `ZCodeBridgeOnlyBoundaryTest`: direct relay/link classes are absent from the app binary |
| Non-Agent Mirror regression | **PASS** | `diff-and-non-agent-regression.txt`, `non-agent-test-cases.txt`; full suite includes notification, rear/charging, posture, detail/manifest paths |

The only missing automated boundary was the no-fallback binary contract, so one focused test was added. Existing external-behavior tests already covered title precedence and unified lock/recovery behavior.

## Non-Agent Mirror diff audit

`notification/` and `rear/` have no changed paths. Shared app files changed only in Agent Mirror wiring: direct ZCode pairing/probe transport removal, bridge state/title propagation, and Agent settings wiring. Full regression passed all non-Agent Mirror tests.

## Safe device smoke

Device connected: `94250f9e`, model `25098PN5AC`; `com.rearcue.poc` version `0.1.0`.

Safe read/UI smoke only was performed. `MainActivity` started with `Status: ok`; `RearDashboardActivity` remained resumed on rear display 1. The `RearCueBridge` scheduled task was only read: state `Ready`. No stop, kill, taskkill, Stop-Process, Stop-ScheduledTask, or bridge restart command was used.

Evidence: `adb-devices.txt`, `device-package.txt`, `main-activity-start.txt`, `activity-smoke.txt`, `display0-main.png`, `display1-rear.png`, `main-ui.xml`, `bridge-task-read-only.txt`, `bridge-snapshot.json`.

## Real-device acceptance status

The connected PC bridge snapshot contained six Codex sessions and **zero ZCode sessions**. No live ZCode desktop session was available to drive acceptance.

| Real-device item | Status | Reason |
| --- | --- | --- |
| ZCode session appears in unified picker | **INCONCLUSIVE** | Bridge snapshot had no ZCode session |
| ZCode title matches desktop | **INCONCLUSIVE** | No live ZCode session/title |
| Reply follows live output | **INCONCLUSIVE** | No live ZCode turn |
| Waiting-for-Approval insertion | **INCONCLUSIVE** | No live ZCode approval request |
| Disconnect retains last content | **INCONCLUSIVE** | Bridge lifecycle must not be stopped; no live ZCode frame |
| Recovery continues live mirroring | **INCONCLUSIVE** | No controlled bridge outage was performed |
| Remote Approval approve/reject/select | **INCONCLUSIVE** | No live ZCode approval request |
| Detailed non-Agent Mirror device scenarios | **INCONCLUSIVE** | Safe smoke only; automated non-Agent regression passed |

No acceptance result was inferred from fixture-only tests.

## Review fixes rerun (2026-10-01)

| Check | Result | Evidence |
| --- | --- | --- |
| Focused bridge regression after review fixes | **PASS**: 68 tests, 0 failed | `node --test tools/bridge/adapters/zcode-history.test.mjs tools/bridge/adapters/zcode.test.mjs tools/bridge/adapters/adapters.test.mjs tools/bridge/adapters/turn-log.test.mjs tools/bridge/bridge.test.mjs` |
| Focused ZCode + bridge endpoint rerun after final refactor | **PASS**: 46 tests, 0 failed | Same bridge suite subset after helper extraction |
| New `AgentSessionDisplayTest` | **PASS** | Re-run with JDK 17 after fixing imports and narrowing non-ZCode title fallback |
| Final full Gradle regression | **PASS** | Re-run after review fixes with JDK 17 and Android SDK: all module tests successful |
| ZCode full model-io history reconstruction | **PASS** | New fixture exceeds the 2 MiB tail window and reconstructs earlier user/assistant turns through `GET /history` |
| `turn.steerQueued` non-waiting boundary | **PASS** | New test asserts no patch, waiting state, turn insertion, or alert |
| Bridge `title` transport | **PASS** | Existing `/events` and `/snapshot` title transport tests still pass |

All unavailable live ZCode cases in the table above remain **INCONCLUSIVE**. No live ZCode session was available, and the production `RearCueBridge` lifecycle was not stopped, killed, or restarted.

# Agent true-stream / full-information deployment

started  : 2026-10-04 11:40 Asia/Shanghai
commit   : 9be6af3
scope    : issue #301 / spec 0026 / PR #304

## Completed

- PR #304 (`feat(agent): stream full mirror information`) merged into `main`.
- Production checkout fast-forwarded to `9be6af3`; this also includes PR #305.
- `node --test tools/bridge/**/*.test.mjs`: 161 passed.
- `ANDROID_HOME=C:\Android\Sdk .\gradlew test :app:assembleDebug`: passed.
- Debug APK installed on `192.168.50.252:44947`; `versionName=0.1.0`, `lastUpdateTime=2026-10-04 11:38:26`.
- Notification listener re-authorized after reinstall; POST_NOTIFICATIONS granted.
- PC bridge running latest source: node PID 190056 started 11:33:19, after `bridge.mjs` update at 11:29:48.
- Local `/health`, public tunnel `/health`, and `/snapshot` succeeded; snapshot had 8 sessions and 93 memberships.
- Tray state reported `phone:true`; phone logcat received live bridge events after deployment.

## Lifecycle safety

No `Stop-ScheduledTask` or `disable-autostart.ps1` was used. The `RearCueBridge` scheduled task remained registered and the current production bridge was not hard-stopped by this deployment.

## Remaining boundaries

Recorded in spec 0026 and issue #301: external desktop-originated Codex sessions may remain message-level where no live channel is observable; pure incremental wire/resume, 30-day/500 MB phone retention, pause/resume UI, and per-session return anchors remain follow-up slices.

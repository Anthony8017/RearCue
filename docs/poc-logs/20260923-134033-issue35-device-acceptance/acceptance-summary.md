# Issue #35 real-device acceptance — canonical result

Run date: 2026-09-23 (Asia/Shanghai)
Device: Xiaomi 17 Pro, model `25098PN5AC`, Android 16, serial `94250f9e`
Code under test: committed fix `3082494`; no lasting source changes were made during acceptance.

## Automated checks

- `.\gradlew.bat test` — **BUILD SUCCESSFUL** (debug and release unit-test tasks).
- `.\gradlew.bat :app:assembleDebug` — **BUILD SUCCESSFUL**.
- A temporary callback-entry log was added only to the local debug build to locate the lock-screen delay. It was removed, the committed source restored, and a clean debug APK rebuilt and reinstalled before cleanup.

## Device acceptance

- Shizuku server was running, RearCue Shizuku permission was granted, and the notification listener remained enabled. The locked-screen task-move route returned `task-move word=OK ... displayId=1 onDisplay=true` during the acceptance runs.
- The app test notification was received with keyguard showing, and the Dashboard was placed on the rear display.
- With both `com.rearcue.poc` and the synthetic allowlist notification `com.android.shell` active, all 14 lock-transition samples reported `rearOwner=dashboard`; keyguard stayed locked. The main display reached `SCREEN_STATE_OFF` during the sample window.
- After cancelling only the app test notification, the shell-only Icon Set also remained `rearOwner=dashboard` in all 12 lock-transition samples, with keyguard locked and the main display off in the later samples.
- **New notifications while the main display is asleep remain delayed on this device.** With keyguard locked and `SCREEN_STATE_OFF`, a unique shell notification appeared in Android's notification service, but no `onNotificationPosted` entry arrived during the 20-second observation. System logs show `GreezeManager` froze RearCue UID `10339` at `14:03:21.198`; after the main display was woken while keyguard remained showing, the system thawed the UID at `14:03:43.601` and the callback entered RearCue at `14:03:43.607`. This places the delay at the OS freeze/thaw boundary, before RearCue's notification state machine.
- A control notification posted while keyguard was showing and the main display was on entered the listener and updated state immediately.

## Verdict and limits

**Partial acceptance.** The #35 rear-dashboard recovery passes for both combined and shell-only existing notification sets on the real device. The separate requirement that a newly arriving notification update RearCue while the main display is asleep does **not** pass on this HyperOS build: the app UID is frozen until screen wake. The test used synthetic `com.android.shell` notifications; no real Feishu notification was sent, so Feishu-specific delivery was not exercised.

## Device cleanup

- All seven synthetic `com.android.shell` test notifications were cancelled; the app test notification was cancelled.
- `POST_NOTIFICATIONS` was temporarily granted because it was denied at preflight, then restored to `granted=false`. Notification-listener access and Shizuku permission were left enabled/granted.
- The test Wake Keep-alive loop stopped cleanly (`ticks=50 failures=0`). The rear display was restored to `com.xiaomi.subscreencenter/.SubScreenLauncher` (native owner); keyguard remained showing and the main display was returned to `SCREEN_STATE_OFF`.
- No Feishu notifications were touched. No temporary debug instrumentation remains in source or in the installed clean debug APK.

## Evidence files

- `corrected-lock-samples.txt` — combined notification lock samples.
- `shell-only-lock-samples.txt` — shell-only lock samples.
- `shell-only-locked-incoming.txt` — notification-service record and callback timing.
- `instrumented-listener-screen-off.txt` — callback-entry probe plus Greeze freeze/thaw timeline.
- `instrumented-listener-lock-check.txt` — keyguard-on, main-display-on control.
- `final-device-state.txt` — cleanup verification.
- `erratum.md` — first-pass limitations and corrected interpretation.

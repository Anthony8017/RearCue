# Issue #35 lock-screen reproduction

Collected 2026-09-23 on the connected 25098PN5AC device (Android 16 / API 36, rear displayId 1).

## Build and setup

- Reporter confirmed the affected source is origin/spec-0004 at 1e3812667057f8ab00d40534326af16315abc707.
- Built app-debug.apk from that detached worktree; SHA-256: BAB817948DBBEDB15401082652D5E130E2CED004AFBDF23D09CCBABFFA5111AA.
- Installed with adb install -r; signing certificate matched the existing install, so app data was preserved.
- RearCue listener was connected for the two pre-lock baselines. The device had 10 active Feishu notification records, all from com.ss.android.lark; Dashboard's allowlist set contained only Feishu before adding the app's test notification.
- Shizuku server was running, but RearCue had no Shizuku grant/user service (granted=false, userService=false). MIUIOP 10008 was allow; MIUIOP 10020 was ignore.
- Granted POST_NOTIFICATIONS to RearCue to emit its local test notification. No Feishu message was sent and no Feishu notification was canceled.

## Results

1. **Feishu only:** owner was dashboard before lock. It was still dashboard at +439 ms, then native at +1207 ms and remained native through +12193 ms. App log: SUB_SCREEN_OFF -> LaunchDashboard; Dashboard detach; on screen wake, locked first-cast task-move fallback failed because Shizuku was unavailable.
2. **Feishu + RearCue test notification:** owner was dashboard before lock; the test notification produced one active app record. It was still dashboard at +429 ms, then native at +1137 ms and remained native through +12199 ms. App log again shows SUB_SCREEN_OFF -> LaunchDashboard followed by Dashboard detach; no recovery during the sample window.
3. **New notification while locked:** posted the app's own test notification as a proxy. Owner remained native throughout 10 seconds. At that point the notification listener was disconnected and RearCue was absent from enabled_notification_listeners, so no listener posted callback was recorded. This does not verify a fresh Feishu notification while locked.

## Cleanup and limits

- Canceled only the test notification created by this run; the 10 Feishu notifications were left intact.
- Re-enabled RearCue in enabled_notification_listeners; the service is currently bound. The app remains on the confirmed candidate build, and the test notification count is zero.
- The phone is still at secure lock screen; wm dismiss-keyguard did not dismiss it. Manual unlock is needed to complete post-lock listener recovery checks.
- Evidence is restricted to app log lines and owner samples; no notification bodies were collected.
Follow-up (2026-09-23): after the first restore, listener connect/disconnect cycles were logged at 12:09:43, 12:10:18/12:10:27, and 12:10:34/12:10:44, followed by a final disconnect at 12:13:34 while the phone remained at secure lock screen. At that check, enabled_notification_listeners no longer contained RearCue. The local POST_TEST notification count was zero. I re-applied allow_listener after this observation.
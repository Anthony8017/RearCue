# Issue #37 FGS freeze observation

## Scope

This is a read-only checkpoint taken after the valid issue #37 RED run. It did not
post, cancel, or modify any notification, and it did not change either display.

## Device facts

- Device serial: `94250f9e`
- Device clock: `2026-09-23 18:30:43.516`
- RearCue app UID: `10339`
- RearCue process cgroup freeze: `1`
- `dumpsys activity processes` reports the notification listener connection as
  `CR FGS !PRCP com.rearcue.poc/.notify.RearNotificationListener`.
- The same process record reports `mHasForegroundServices=true`.

## Interpretation

The existing `NotificationListenerService` binding carries Android's FGS
connection flag, and the process is still frozen by HyperOS Greeze. This rules
out treating that binding flag as an effective anti-freeze mechanism. It does
not test a separate, user-visible Android foreground service; issue #40 remains
blocked pending a controlled candidate experiment and the same issue #37
end-to-end verdict.

## Reproduction commands

```text
adb -s 94250f9e shell pidof com.rearcue.poc
adb -s 94250f9e shell cat /sys/fs/cgroup/apps/uid_10339/pid_<pid>/cgroup.freeze
adb -s 94250f9e shell dumpsys activity processes
```

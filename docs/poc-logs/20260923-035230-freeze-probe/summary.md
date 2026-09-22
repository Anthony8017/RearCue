# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue-wt\06\docs\poc-logs\20260923-035230-freeze-probe
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 25 |
| listener connected | False |
| posted / removed | 4 / 0 (allowlist hits: com.android.shell / ) |
| effects | LaunchDashboard |
| projection sent / confirmed | True / False |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals | android.intent.action.SCREEN_ON, android.intent.action.SCREEN_OFF |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### freeze-probe.txt

```
fz       : FZ-REPRODUCED (trigger=tobg: frozen 5s (cgroup wire) after the trigger; GreezeManager FZ uid=10336 reason=unknown; anchor: cgroup wire only (no FZ line captured))
events   : EVT-HELD-UNTIL-THAW (2 posts while frozen, 0 delivered live, all delivered after the freeze ended; delay 63.3s .. 74.8s = delivery - post on the device clock)
thaw     : THAW-NOT-BY-BROADCAST (still frozen 12s after the broadcast; the thaw only came with KEYCODE_WAKEUP at 09-23 03:54:12)
on-rear  : FZ-NO-REAR-BASELINE (keyguard up after the sleep path -- rear projection is denied while locked; the visible-skip fact stands on 20260923-030402 / -034557)
```

## scenario notes (GreezeManager freeze probe, ticket #29)

- **What is frozen**: the whole app uid (GreezeManager `FZ uid=`), measured at the
  `cgroup.freeze` files under `/sys/fs/cgroup/apps/uid_*/` (uid level + pid level).
- **Timeline basis (one clock)**: `pc-freeze-post-n*` markers go into logcat via `log`
  just BEFORE each `cmd notification post`; app `posted` lines are logcat lines on the
  same device clock. Delay = delivery stamp - marker stamp (upper bound by one adb trip).
- **No KEYCODE_POWER on purpose**: the tobg freeze reproduces with the screen on and the
  phone unlocked, and a locked phone cannot be unlocked over adb (secure keyguard) --
  locking here would leave the device locked for every parallel ticket. The screen-off
  freeze (`reason =screen off`) is documented from prior sessions, not re-measured.
- **The app process is NOT force-stopped**: the control leg doubles as the listener
  liveness check, and a force-stop would drag the MIUI rebind dance into a freezer probe.
- **Only `posted com.android.shell` lines pair with markers**: the automation posts from
  com.android.shell (an Allowlist App); `removed` lines and other packages are reported
  as unpaired on purpose (shell cannot cancel its own notifications -- `cmd notification`
  has no cancel subcommand on this build, see freeze-environment.txt).
- **Notification tags are unique per run on purpose**: a `cmd notification post` re-using
  an existing tag is a KEY UPDATE and fires no onNotificationPosted (ticket #3), and a
  post that lands while the listener is mid-rebind (9-13s) arrives via the initial
  snapshot with no event line either -- both traps are documented in the errata of
  20260923-030402 / -032045 / -033533 / -033746 and both are now designed around.
- **Deviation from the ticket wording (honest)**: the frozen-window posts cannot include
  a removal event (no shell-side cancel exists), so the "posted/removed burst on thaw"
  shape is corroborated by the ticket #5 raw (`docs/poc-logs/ticket5-e7-evidence.txt`),
  while THIS run measures the per-post hold delay with explicit stamps.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (207,389 bytes)
- dumpsys-display.txt (340,386 bytes)
- dumpsys-greezer.txt (828,963 bytes)
- dumpsys-window.txt (165,550 bytes)
- freeze-countermeasures.txt (720 bytes)
- freeze-environment.txt (1,270 bytes)
- freeze-post-outputs.txt (217 bytes)
- freeze-probe.txt (1,728 bytes)
- freeze-samples.txt (2,135 bytes)
- freeze-timeline.txt (3,366 bytes)
- logcat-greeze.txt (9,486 bytes)
- logcat-rearcue.txt (2,574 bytes)
- logcat-system-rear.txt (80,806 bytes)
- scenario-notes.md (2,136 bytes)
- screenshots\main.png (3,562 bytes)
- screenshots\notes.txt (190 bytes)
- session.md (311 bytes)
- state.txt (1,888 bytes)
- transcript.txt (25,396 bytes)

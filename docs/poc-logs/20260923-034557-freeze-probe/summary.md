# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue-wt\06\docs\poc-logs\20260923-034557-freeze-probe
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 4330 |
| listener connected | False |
| posted / removed | 2 / 3 (allowlist hits: com.android.shell / com.rearcue.poc,com.android.shell) |
| effects | UpdateIconSet, ExitDashboard, LaunchDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals | android.intent.action.SCREEN_ON, android.intent.action.MAIN, miui.intent.action.OP_AUTO_START, android.intent.action.VIEW |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### freeze-probe.txt

```
fz       : FZ-NOT-REPRODUCED (cgroup.freeze stayed 0 for 15s after HOME AND no GreezeManager FZ line for our uid)
events   : EVT-NOT-APPLICABLE (no freeze reproduced -- "held until thaw" is untestable this round)
thaw     : THAW-NOT-APPLICABLE (no freeze to thaw from)
on-rear  : FZ-SKIP-VISIBLE (cgroup.freeze stayed 0 for 30s after HOME with the Dashboard on the rear; no GreezeManager skip line captured in logcat -- check dumpsys-greezer.txt history)
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
- dumpsys-activities.txt (201,720 bytes)
- dumpsys-display.txt (340,897 bytes)
- dumpsys-greezer.txt (828,659 bytes)
- dumpsys-window.txt (158,096 bytes)
- freeze-countermeasures.txt (720 bytes)
- freeze-environment.txt (1,272 bytes)
- freeze-post-outputs.txt (217 bytes)
- freeze-probe.txt (1,424 bytes)
- freeze-samples.txt (801 bytes)
- freeze-timeline.txt (614,703 bytes)
- logcat-greeze.txt (4,217 bytes)
- logcat-rearcue.txt (606,775 bytes)
- logcat-system-rear.txt (108,648 bytes)
- scenario-notes.md (2,136 bytes)
- screenshots\main.png (29,620 bytes)
- screenshots\notes.txt (191 bytes)
- session.md (311 bytes)
- state.txt (2,406 bytes)
- transcript.txt (17,940 bytes)

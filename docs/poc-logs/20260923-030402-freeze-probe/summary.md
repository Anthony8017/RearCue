# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue-wt\06\docs\poc-logs\20260923-030402-freeze-probe
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 93 |
| listener connected | False |
| posted / removed | 1 / 2 (allowlist hits: com.android.shell / com.android.shell) |
| effects | ExitDashboard, LaunchDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 0 |
| exit requested / detached | True / True |
| takeover signals |  |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### freeze-probe.txt

```
fz       : FZ-NOT-REPRODUCED (cgroup.freeze stayed 0 for 15s after HOME)
events   : EVT-NOT-APPLICABLE (no freeze reproduced -- "held until thaw" is untestable this round)
thaw     : THAW-NOT-APPLICABLE (no freeze to thaw from)
on-rear  : FZ-SKIP-VISIBLE (Dashboard on the rear: cgroup.freeze stayed 0 for 30s after HOME; GreezeManager skip/subscreen lines 3)
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
- **Deviation from the ticket wording (honest)**: the frozen-window posts cannot include
  a removal event (no shell-side cancel exists), so the "posted/removed burst on thaw"
  shape is corroborated by the ticket #5 raw (`docs/poc-logs/ticket5-e7-evidence.txt`),
  while THIS run measures the per-post hold delay with explicit stamps.

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (202,724 bytes)
- dumpsys-display.txt (341,472 bytes)
- dumpsys-greezer.txt (821,807 bytes)
- dumpsys-window.txt (147,026 bytes)
- freeze-countermeasures.txt (720 bytes)
- freeze-environment.txt (1,265 bytes)
- freeze-probe.txt (1,621 bytes)
- freeze-samples.txt (716 bytes)
- logcat-greeze.txt (3,457 bytes)
- logcat-rearcue.txt (8,679 bytes)
- logcat-system-rear.txt (109,430 bytes)
- scenario-notes.md (1,706 bytes)
- screenshots\main.png (160,747 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (311 bytes)
- state.txt (2,087 bytes)
- transcript.txt (16,571 bytes)

# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue-wt\06\docs\poc-logs\20260923-033533-freeze-probe
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **dashboard**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 15 |
| listener connected | True |
| posted / removed | 0 / 0 (allowlist hits:  / ) |
| effects |  |
| projection sent / confirmed | False / False |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals |  |
| shizuku server / granted |  /  |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### freeze-probe.txt

```
fz       : FZ-NOT-REPRODUCED (run aborted before the freeze watch)
events   : EVT-NO-LISTENER (3 control probes, 0 `posted` deliveries)
thaw     : THAW-NOT-APPLICABLE (no freeze to thaw from)
on-rear  : FZ-NO-REAR-BASELINE (run aborted before the visible-skip leg)
```

## artifacts
- 02-authorize.txt (873 bytes)
- dumpsys-activities.txt (207,388 bytes)
- dumpsys-display.txt (340,996 bytes)
- dumpsys-greezer.txt (412,663 bytes)
- dumpsys-window.txt (165,551 bytes)
- freeze-environment.txt (1,275 bytes)
- freeze-post-outputs.txt (651 bytes)
- freeze-probe.txt (370 bytes)
- logcat-rearcue.txt (1,760 bytes)
- logcat-system-rear.txt (84,486 bytes)
- screenshots\main.png (3,562 bytes)
- screenshots\notes.txt (190 bytes)
- session.md (311 bytes)
- state.txt (1,443 bytes)
- transcript.txt (5,075 bytes)

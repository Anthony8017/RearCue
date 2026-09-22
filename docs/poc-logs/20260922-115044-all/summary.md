# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-115044-all
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 101 |
| listener connected | True |
| posted / removed | 60 / 2 (allowlist hits: com.rearcue.poc,com.android.shell / com.rearcue.poc,com.android.shell) |
| effects | LaunchDashboard, UpdateIconSet, ExitDashboard |
| projection sent / confirmed | True / True |
| icon set updates | 2 |
| exit requested / detached | True / True |
| takeover signals |  |
| shizuku server / granted | false / false |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### 03-shizuku.txt

```
  phone-unlocked         = True
  server-process-killed  = True
  server-process-back    = True
  app-alive              = True
  no-fatal               = True
  listener-kept-working  = True
  app-sees-server        = False
  app-saw-server-drop    = False
  fallback-usable        = False
crashes                = 0
shizuku_server after   : 16288
app pid after          : 18950
app-side binder view   : server=false granted=false
```

### e1-drive.txt

```
  projection-sent      = True
  icon-set-two         = True
  dashboard-on-rear    = True
  effect-launch        = True
  phone-unlocked       = True
  projection-attach    = True
```

### e7-drive.txt

```
  dashboard-detached   = True
  stayed-while-notify  = True
  exit-effect          = True
  native-restored      = True
  update-not-relaunch  = True
```

## artifacts
- 01-install.txt (338 bytes)
- 02-authorize.txt (873 bytes)
- 03-shizuku.txt (1,108 bytes)
- dumpsys-activities.txt (201,846 bytes)
- dumpsys-display.txt (338,949 bytes)
- e1-drive.txt (24,433 bytes)
- e7-drive.txt (25,159 bytes)
- logcat-rearcue.txt (11,984 bytes)
- logcat-system-rear.txt (114,232 bytes)
- photo-checkpoints.md (1,093 bytes)
- screenshots\main.png (260,629 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (296 bytes)
- state.txt (3,114 bytes)
- transcript.txt (7,298 bytes)

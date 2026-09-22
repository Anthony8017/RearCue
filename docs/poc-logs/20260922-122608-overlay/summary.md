# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-122608-overlay
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 51 |
| listener connected | True |
| posted / removed | 31 / 0 (allowlist hits:  / ) |
| effects |  |
| projection sent / confirmed | False / False |
| icon set updates | 0 |
| exit requested / detached | False / False |
| takeover signals |  |
| shizuku server / granted | true / true |
| crashes (FATAL/ANR) | 0 |

## step verdicts

### e9-overlay.txt

```
granted            : True
add-accepted(app)  : False
add-failed(app)    : True reason=BadTokenException: Unable to add window android.view.ViewRootImpl$W@4b8f9f -- permission denied for window type 2038
on-target-display  : False (probe window found=False displayId=)
system-window-log  : 1 hit(s), 0 denial(s)
removed            : False (still in dumpsys after remove: False)
verdict            : E9-SYSTEM-REJECTED (the add failed inside the app)
```

## artifacts
- 01-install.txt (525 bytes)
- dumpsys-activities.txt (201,948 bytes)
- dumpsys-display.txt (338,986 bytes)
- dumpsys-window.txt (159,502 bytes)
- dumpsys-window-after-remove.txt (159,502 bytes)
- dumpsys-window-before-remove.txt (159,502 bytes)
- e9-overlay.txt (25,918 bytes)
- logcat-overlay-system.txt (19,869 bytes)
- logcat-rearcue.txt (5,047 bytes)
- logcat-system-rear.txt (115,593 bytes)
- screenshots\main.png (288,885 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (300 bytes)
- state.txt (2,590 bytes)
- transcript.txt (4,377 bytes)
- usb-install-dialog.xml (8,291 bytes)

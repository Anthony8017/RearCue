# RearCue PC experiment summary

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-123134-overlay
device  : 25098PN5AC / BP2A.250605.031.A3

## rear display
- displayId 1, 904x572, density 450, state ON (committed ON)
- flags: FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP
- owner of display #1 now: **native**
- overlay probe window: not found in `dumpsys window windows`

## chain facts (parsed from logcat)

| fact | value |
|---|---|
| RearCue events | 53 |
| listener connected | True |
| posted / removed | 33 / 0 (allowlist hits:  / ) |
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
rear-policy-deny   : True (WindowManager "Not allow non-system app ... system_window on rear display")
on-target-display  : False (probe window found=False displayId=)
system-window-log  : 0 hit(s), 1 denial(s)
removed            : False (still in dumpsys after remove: False)
verdict            : E9-REAR-POLICY-BLOCKED (WindowManager denied a non-system app a system window on the rear display: "09-22 12:31:47.946  5157 10107 D WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display")
```

## artifacts
- 01-install.txt (568 bytes)
- dumpsys-activities.txt (208,801 bytes)
- dumpsys-display.txt (339,115 bytes)
- dumpsys-window.txt (160,748 bytes)
- dumpsys-window-after-remove.txt (160,748 bytes)
- dumpsys-window-before-remove.txt (160,748 bytes)
- e9-overlay.txt (24,579 bytes)
- logcat-overlay-system.txt (18,021 bytes)
- logcat-rearcue.txt (5,243 bytes)
- logcat-system-rear.txt (116,315 bytes)
- screenshots\main.png (262,449 bytes)
- screenshots\notes.txt (193 bytes)
- session.md (300 bytes)
- state.txt (2,796 bytes)
- transcript.txt (4,432 bytes)
- usb-install-dialog.xml (8,291 bytes)

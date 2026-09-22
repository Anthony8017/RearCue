# photo checkpoints (spec 0001: three manual shots)

The HyperOS build on this device cannot capture the rear screen (`screencap -d 1` ->
"Display Id '1' is not valid"), so visual proof is a human photo. Each checkpoint below
records the moment the script reached it, what the rear screen showed, and where the photo
belongs. Checkpoints not reached in this run stay listed with an empty "reached".

| # | checkpoint | target file | reached |
|---|---|---|---|
| 1 | Dashboard first on the rear screen | manual-photos/photo-01-first-launch.jpg | 2026-09-22 14:29:41 |
| 2 | Rear screen after locking the main screen | manual-photos/photo-02-locked-30s.jpg | - |
| 3 | The moment the native AOD takes the rear screen back | manual-photos/photo-03-aod-takeover.jpg | - |

## 1. Dashboard first on the rear screen
- reached : 2026-09-22 14:29:41
- shoot   : pure black + clock + the two app icons
- evidence: owner=dashboard state=ON/ON iconSet=com.android.shell,com.rearcue.poc
- save as : C:\Users\13691\Desktop\RearCue\docs\poc-logs\manual-photos\photo-01-first-launch.jpg


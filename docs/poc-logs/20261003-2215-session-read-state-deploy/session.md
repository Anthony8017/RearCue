# RearCue session-read-state deployment

started  : 2026-10-03 22:15 Asia/Shanghai
commit   : 0f6e6b0
scope    : issue #296 / ADR 0018

## Completed

- Merged `codex/voice-pitch-punctuation` into `main` and pushed the combined result.
- `node --test tools/bridge/bridge.test.mjs`: 39 passed.
- `.\gradlew test`: passed.
- `.\gradlew assembleDebug`: passed; APK ready at `app/build/outputs/apk/debug/app-debug.apk`.
- PC bridge redeployed through a stopflag-gated process restart and `Start-ScheduledTask RearCueBridge`.
- Bridge status: online on local port 18787 and reachable through its quick tunnel; 5 sessions in roster.

## Phone deployment gap

The phone was not reachable over ADB at deployment time. The previous wireless endpoint `192.168.50.252:34093` refused connection, and after restarting the ADB server no active mDNS ADB service was discovered. The APK was therefore built but not installed.

Resume when the phone is online:

1. `.\tools\ex\01-install.ps1 -Serial <adb-serial> -Build`
2. `.\tools\ex\02-authorize.ps1 -Serial <adb-serial>`

No `RearCueBridge` scheduled task was stopped or disabled during deployment.

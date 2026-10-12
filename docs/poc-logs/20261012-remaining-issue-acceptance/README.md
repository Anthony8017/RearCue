# 2026-10-12 remaining issue acceptance

## Automated device evidence

- `AgentPickerExitInteractionTest`: PASS. `RearDashboardActivity` starts through the shell path, the list shortcut transitions to Notification, and a second real Compose touch reaches the content-page callback without a new snapshot.
- `RemoteApprovalDeviceTest`: PASS. A real notification action is removed when approval is disabled; the saved `PendingIntent` is rejected while the debug session remains waiting. The test clears its debug fixture and restores settings.
- `VoiceBroadcastDeviceTest`: PASS (3 tests). Xiaomi speech completed real sentences; media pause/resume, next-item, discard, and `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` sentence-boundary pause/resume passed.

The voice tests prove the controller, MediaSession, audio-focus, and Xiaomi engine completion path. They do not prove Bluetooth earbud routing, subjective audibility, telephone/navigation audio, or overnight battery usage.

## Remaining manual acceptance

- Issue #170: real DSH approval trigger, physical rear-screen confirmation/cancel, 24-hour accidental-touch observation, two-night battery comparison.
- Issue #273: Bluetooth/telephone/navigation/silent-mode listening and music restoration.

During manual playback verification, the controller was found to request audio focus before recording the currently playing media session. The session was therefore paused by the platform before RearCue captured it. The fix moves media capture before the focus request; device re-verification is pending after ADB reconnects.
- Issue #308: physical rear-display touch and visual inspection; the automated test runs the real Activity on the test display.

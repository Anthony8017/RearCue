## scenario notes (process rebuild recovery, ticket #21)

  `am-kill` is the real-world death (backgrounded first, process dies WITHOUT the stopped
  state); `force-stop` is the harsher death whose stopped state blocks the listener rebind
  (measured REBUILD-NO-LISTENER 2026-09-23, session 20260923-005405).
- **Recovery trigger is the real-world one**: one more allowlist notification ~3s after
  the kill. NotificationManagerService must rebind the dead listener to deliver it; that
  rebind revives the process, IconSetFeed resyncs `getActiveNotifications` (= the current
  Icon Set), the core re-projects and WakeKeepAlive starts again.
- **Evidence split**: `rebuild-samples.txt` carries the pid per sample (the rebuild itself);
  `rebuild-recover.txt` splits the keep-alive markers into before/after the kill stamp, so
  the "loop came back" claim rests on markers from the NEW process only.
- **Deviation**: the main display state is not sampled (this question is about the
  rear chain only); the wire keeps the same slot with `main=no-display`.

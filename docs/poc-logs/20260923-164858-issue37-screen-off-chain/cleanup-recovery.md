# Issue #37 16:48 full-mode cleanup recovery

The integrated session remains `INVALID`; its firstcast leg's `RED-NO-CALLBACK`
is a leg observation, not a clean three-leg verdict. At 16:49:29 the session
ended with one synthetic shell key, `rc37-ccc9b9e63d-firstcast-off`.

Recovery was performed under `Global\RearCueDevice` with one WaitOne and one
ReleaseMutex. A read-only precheck found exactly that one `com.android.shell`
key and no other allowlist notifications. The main physical screen power was
OFF, rear was ON and Dashboard still owned it; the temporary
`POST_NOTIFICATIONS` permission had already been restored to false.

Only after checking the exact shell key, a targeted display-0 POWER key woke
the main screen to physical ON. One foreground `CANCEL_PACKAGE` debug broadcast
for `com.android.shell` then caused the receiver log at 16:51:22.391:
`debug cancel pkg=com.android.shell cancelled=1`. The shell key count became 0.
The main screen was returned to OFF. A final read-only check found allowlist
count 0, shell count 0, Main Display state and power OFF, keyguard locked,
rear ON with native owner, and `POST_NOTIFICATIONS=false`. No real notification
was cancelled. No new notification was posted during recovery.

Diagnosis: the integrated run's main screen really became ON at 16:49:15, but
four broadcast enqueue records through 16:49:23 had no RearCue receiver-side
`debug cancel` log. At 16:49:26 ActivityManager recorded a frozen process and
killed RearCue when permission was revoked; the listener reconnected in the
new process at 16:49:27. The recovered foreground broadcast executed in that
new process. This supports delayed/non-delivered broadcasts to the frozen
process, rather than a system key parse problem or a successful cancellation
hidden by the command reply. It does not prove which freezer/broadcast queue
policy suppressed delivery.

# Issue #37 OFF-only firstcast: post-window corroboration

The archived five-second leg ends before cleanup. Its marker was at 16:40:30.176,
the unique system key was first observed at 16:40:30.711, and there was no
`com.android.shell` callback through the 16:40:35.176 budget deadline while the
physical Main Display remained OFF. The last OFF sample at 16:40:36.252 still had
the key and native rear owner.

A later read-only filtered logcat inspection showed `PowerGroup` wake at
16:40:37.406, `GreezeManager` THAW of RearCue UID 10339 at 16:40:37.462, then
RearCue `posted com.android.shell` callback and Dashboard projection at
16:40:37.495-16:40:37.502. These are **after** the acceptance window and are not
counted as green. At 16:40:42.631 the debug receiver reported cancelling one
shell notification. The session's final device snapshot had zero allowlist
notifications, Main Display power OFF, keyguard true, and native rear owner;
rear was ON both initially and finally. The temporary notification permission
was revoked. This is a valid `RED-OFF` for the firstcast leg only; update and
locked-on control were skipped by `-OffOnly`.

## scenario notes (GreezeManager freeze probe, ticket #29)

- **What is frozen**: the whole app uid (GreezeManager `FZ uid=`), measured at the
  `cgroup.freeze` files under `/sys/fs/cgroup/apps/uid_*/` (uid level + pid level).
- **Timeline basis (one clock)**: `pc-freeze-post-n*` markers go into logcat via `log`
  just BEFORE each `cmd notification post`; app `posted` lines are logcat lines on the
  same device clock. Delay = delivery stamp - marker stamp (upper bound by one adb trip).
- **No KEYCODE_POWER on purpose**: the tobg freeze reproduces with the screen on and the
  phone unlocked, and a locked phone cannot be unlocked over adb (secure keyguard) --
  locking here would leave the device locked for every parallel ticket. The screen-off
  freeze (`reason =screen off`) is documented from prior sessions, not re-measured.
- **The app process is NOT force-stopped**: the control leg doubles as the listener
  liveness check, and a force-stop would drag the MIUI rebind dance into a freezer probe.
- **Only `posted com.android.shell` lines pair with markers**: the automation posts from
  com.android.shell (an Allowlist App); `removed` lines and other packages are reported
  as unpaired on purpose (shell cannot cancel its own notifications -- `cmd notification`
  has no cancel subcommand on this build, see freeze-environment.txt).
- **Notification tags are unique per run on purpose**: a `cmd notification post` re-using
  an existing tag is a KEY UPDATE and fires no onNotificationPosted (ticket #3), and a
  post that lands while the listener is mid-rebind (9-13s) arrives via the initial
  snapshot with no event line either -- both traps are documented in the errata of
  20260923-030402 / -032045 / -033533 / -033746 and both are now designed around.
- **Deviation from the ticket wording (honest)**: the frozen-window posts cannot include
  a removal event (no shell-side cancel exists), so the "posted/removed burst on thaw"
  shape is corroborated by the ticket #5 raw (`docs/poc-logs/ticket5-e7-evidence.txt`),
  while THIS run measures the per-post hold delay with explicit stamps.

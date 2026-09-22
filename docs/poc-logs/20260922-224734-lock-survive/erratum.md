# erratum -- collection defect of this round (ticket #19), NOT pollution

The round itself is valid (E13-PASS, no external interference: `pollution : none detected`) and
the full evidence lives in `e13-lock-survive.txt`. What went wrong is the `summary.md` chain
facts section captured after the run:

- `summary.md` says `RearCue events 1`, `posted / removed 0 / 0`, `projection sent / confirmed
  False / False` -- that is WRONG for this run. The real chain (all present in
  `e13-lock-survive.txt`'s app-log section): `project iconSet=[com.tencent.mm] -> ... displayId=1`
  at 22:47:47.404, `RearDashboardActivity onCreate display=1` at 22:47:47.428, the lock-window
  re-projection at 22:47:59.650. `logcat-rearcue.txt` is empty for the same reason.

Root cause (device fact): the run grew the main/system log buffers to 32M and shrank them back
BEFORE the 05-collect step ran, and `logcat -G` truncates the ring buffer on shrink -- the
collect captured only the single `debug state` line that landed after the shrink.

Fixed in the toolchain (commit for #19 review fixes): the shrink now happens in `ex.ps1` AFTER
the 05-collect step, and the scenario notes say so. Future rounds' `summary.md` chain facts read
the complete buffer. Read this round's facts from `e13-lock-survive.txt`, never from the chain
facts table of `summary.md`.

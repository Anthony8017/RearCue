## scenario notes (persist-reboot, spec 0007 / issue #50 review item 4)

- The leg the 20260926 acceptance round could not run (device physically dropped off USB
  at 22:47). Values chosen per the agreed re-verification protocol: privacy=false,
  auto-dismiss=7000ms (a value the pre-review tier ladder could NOT reach -- proves the
  range-complete numeric input actually persists), charging master switch=off.
- The drive functions are the 21/22 lineage with two hardenings learned in the first
  attempt (20260927-012838): (a) the value field is overwritten via KEYCODE_MOVE_END +
  8x KEYCODE_DEL + `input text` -- the focus select-all loses to the tap caret placement,
  so plain typing APPENDED (10 -> 107 -> 1077); (b) the charging toggle is hunted only
  after scrolling to the page bottom, with the failing dumps archived. Every change is
  judged from the app's own log lines + the STATE debug echo.
- First-read evidence comes in two layers: the boot-time snapshot (phase2-boot-logcat.txt,
  before any PC-side app start) and the controlled Start-ExApp first read
  (phase2-firstread-logcat.txt). The verdict uses the controlled start -- the same method
  the #55 persist-restart leg used -- with the boot snapshot as corroboration.
- Phase 3 restores charging=on / privacy=true / autoDismiss=10000ms so the follow-up
  21/22 re-runs start from documented defaults.

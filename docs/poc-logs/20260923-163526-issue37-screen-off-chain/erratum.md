# Issue #37 16:35 cleanup erratum

This run remains `INVALID`, not an app verdict. Its firstcast leg observed the unique
system key and no callback while main screen power was OFF, but the script's
`KEYCODE_WAKEUP` cleanup did not physically turn main ON; therefore the synthetic
`com.android.shell` key remained when the session ended. The temporary app
notification permission had been revoked.

Recovery was performed later under `Global\RearCueDevice`: the exact lone shell key
`rc37-34b36234dc-firstcast-off` was checked before any cancellation. The device's
`cmd notification help` had no cancel operation. An initial `CANCEL_PACKAGE` debug
broadcast and then an activity unfreeze plus foreground broadcast did not remove
the key. A single targeted main-display `KEYCODE_POWER` changed the physical main
screen power from OFF to ON; the existing debug receiver then cancelled the shell
notification. Main was returned to OFF and `cmd notification list` showed zero
shell keys. No real notification was cancelled. This recovery does not rehabilitate
the 16:35 verdict.

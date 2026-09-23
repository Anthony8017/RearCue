# Issue #37 run erratum

This `INVALID` run is harness evidence, not an app red/green verdict. At 16:03:07 the unique
shell notification key appeared in `cmd notification list`, although the harness rejected
the unrecognized `cmd notification post` reply. The verdict therefore discarded a real
system receipt. The later Main Display transition was sampled as `DOZE` / `DOZE_SUSPEND`;
the old harness required `DisplayInfo.state=OFF` immediately and failed during that
transition. The patched harness uses the display-0 power controller's `mScreenState` as
an additional physical-power check and waits for the transition to settle. It cannot
mark a leg green while the main panel is lit.

Synthetic shell and app notifications were removed and `POST_NOTIFICATIONS` returned to
`false`. Keyguard remained locked and the Main Display settled back to OFF. Native Rear
Screen owned the rear, but the rear display stayed ON instead of its initial OFF. With
the mutex held, targeted rear `KEYCODE_SLEEP` did not turn it OFF; a targeted power key
also left the rear ON and woke the Main Display, which was then returned to OFF.
This is a device-state restoration limitation. Future runs from a rear-OFF start now
stop before mutation; a rear-ON native start can be compared against a rear-ON finish.

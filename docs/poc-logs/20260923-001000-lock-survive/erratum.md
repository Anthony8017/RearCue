# erratum -- external pollution (ticket #19)

The run below was disturbed from outside the script (fingerprint wake / power button
pressed by hand / phone touched). Those samples are NOT lock-screen facts; raw output
is left untouched and this note marks it. findings must use a clean round.

- [fingerprint] 09-23 00:10:56.510  5157 10077 D DualScreenCoverManager: not show cover view on display 0 due to expected wake(details = android.policy:FINGERPRINT:finishCallBack:app anim), and Display 1 is on.
- [fingerprint] 09-23 00:10:56.510  5157 10077 I PowerGroup: Waking up power group from Dozing (groupId=0, uid=10224, reason=WAKE_REASON_UNKNOWN, details=android.policy:FINGERPRINT:finishCallBack:app anim)...
- [fingerprint] 09-23 00:10:56.510  5157 10077 I PowerManagerService: Waking up from Dozing (uid=10224, reason=WAKE_REASON_UNKNOWN, details=android.policy:FINGERPRINT:finishCallBack:app anim)...

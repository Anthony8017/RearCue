#!/system/bin/sh
# wake-keepalive.sh -- E12 wake keep-alive injection loop (ticket #16).
#
# Runs on the device as shell (uid 2000), the same identity a Shizuku UserService would have.
# The PC only starts and stops it; every injection happens here so the loop keeps running even
# when the PC is busy sampling:
#
#   start: adb shell "nohup sh /data/local/tmp/wake-keepalive.sh <display> <sleep_s> <tick_file> \
#                     >/dev/null 2>&1 &"
#   stop : adb shell "touch <stop_file>"   (default /data/local/tmp/wake-keepalive.stop)
#
# usage: sh wake-keepalive.sh <display_id> <sleep_seconds> <tick_file> [<stop_file>]
#
# Each iteration injects KEYCODE_WAKEUP (224) at the target display and appends one tick line
# carrying the `input` exit code. The tick file is the device-side proof that the loop really
# ran and that the injection commands were accepted -- never the PC's "the command did not
# error" claim. `input` stderr goes to <tick_file>.err next to it.
#
# ASCII-only source, like every file under tools/ex.

display_id=$1
sleep_s=$2
tick_file=$3
stop_file=$4
if [ -z "$display_id" ] || [ -z "$sleep_s" ] || [ -z "$tick_file" ]; then
  echo "usage: sh wake-keepalive.sh <display_id> <sleep_seconds> <tick_file> [<stop_file>]" 1>&2
  exit 2
fi
if [ -z "$stop_file" ]; then
  stop_file=/data/local/tmp/wake-keepalive.stop
fi
err_file=$tick_file.err

rm -f "$stop_file"
echo "loop start $(date '+%m-%d %H:%M:%S') pid=$$ display=$display_id sleep=$sleep_s" >> "$tick_file"
while [ ! -f "$stop_file" ]; do
  input -d "$display_id" keyevent KEYCODE_WAKEUP 2>>"$err_file"
  rc=$?
  echo "tick $(date '+%m-%d %H:%M:%S') rc=$rc" >> "$tick_file"
  sleep "$sleep_s"
done
echo "loop exit $(date '+%m-%d %H:%M:%S') pid=$$" >> "$tick_file"

#!/bin/bash
# 票 #63 Rear Tap 实验驱动：单场景 5 轮。
# 用法：bash drive-rear-tap.sh <scenario> "<input -d 1 之后的参数>"
#   tap-blank          "tap 350 460"
#   tap-icon           "tap 544 278"
#   longpress-icon     "swipe 544 278 544 278 800"
#   swipe-up           "swipe 550 560 550 200 200"
#   tap-icon-keepalive "tap 544 278"
# 每轮：仅在 Dashboard 不在屏时重投 → 清 logcat → 注入手势 → 4s → 全量 logcat 归档
#       + verdict（owner / 探针锚 / 原生手势行 / 保活循环 / 进程 / 输入投递）。
set -u
ADB="C:/Users/13691/AppData/Local/RearCue-tools/android-sdk/platform-tools/adb.exe"
SER=94250f9e
DIR="$(cd "$(dirname "$0")" && pwd)"
SC="$1"; GEST="$2"

for r in 1 2 3 4 5; do
  f="$DIR/${SC}-r${r}"
  owner=$("$ADB" -s $SER shell "dumpsys activity activities | grep -A3 'Display #1' | grep -c RearDashboardActivity" | tr -d '\r\n')
  repro="no"
  if [ "$owner" = "0" ]; then
    repro="yes"
    "$ADB" -s $SER shell "am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.PROJECT_REAR >/dev/null; sleep 2.5" >/dev/null
  fi
  "$ADB" -s $SER shell "logcat -c"
  "$ADB" -s $SER shell "input -d 1 $GEST" >/dev/null
  "$ADB" -s $SER shell "sleep 4" >/dev/null
  "$ADB" -s $SER shell "logcat -d -v time > /data/local/tmp/rear-tap-round.log"
  WINLOG=$(cygpath -w "$f.logcat")
  MSYS2_ARG_CONV_EXCL="*" "$ADB" -s $SER pull /data/local/tmp/rear-tap-round.log "$WINLOG" >/dev/null 2>&1
  {
    echo "scenario=$SC round=$r gesture=\"input -d 1 $GEST\" reprojected=$repro"
    echo "[owner-after]"
    "$ADB" -s $SER shell "dumpsys activity activities | grep -A4 'Display #1' | grep -E 'Task\{|topResumedActivity' | head -4" | tr -d '\r'
    echo "[probe-anchor] count=$(grep -c 'rear-tap received' "$f.logcat")"
    grep 'rear-tap received' "$f.logcat" | head -3
    echo "[native-gesture-lines] count=$(grep -icE 'gestureinput|startrecent' "$f.logcat")"
    grep -iE 'gestureinput|startrecent' "$f.logcat" | head -5
    echo "[subscreen-lines] count=$(grep -icE 'sub_screen|subscreencenter' "$f.logcat")"
    echo "[keepalive-loop]"
    "$ADB" -s $SER shell 'p=$(cat /data/local/tmp/rearcue-wake-loop.pid 2>/dev/null); if [ -n "$p" ] && [ -e /proc/$p ]; then echo "loop=alive pid=$p"; else echo "loop=dead"; fi' | tr -d '\r'
    echo "[wake-keep-alive-log-lines]"
    grep 'wake-keep-alive' "$f.logcat" | tail -2
    echo "[app-pid]"
    "$ADB" -s $SER shell "pidof com.rearcue.poc" | tr -d '\r'
    echo "[input-to-dashboard-window] lines=$(grep -c 'RearDashboardActivity' "$f.logcat")"
  } > "$f.verdict" 2>&1
  echo "== $SC r$r: probe=$(grep -c 'rear-tap received' "$f.logcat") gesture=$(grep -icE 'gestureinput|startrecent' "$f.logcat") owner=$(grep -c RearDashboardActivity "$f.verdict")"
done

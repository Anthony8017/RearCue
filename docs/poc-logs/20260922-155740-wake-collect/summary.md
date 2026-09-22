# RearCue PC experiment summary (back-filled at ticket #16 review close-out)

> 本 summary.md 是 #16 code review 收口时**补记**（本轮由 `-Task collect` 跑 fixture 采集，当时未生成 summary）；
> 归档原始产物一律未改动。约定依据：`tools/ex/README.md` 的 session 目录布局（session.md + summary.md）。

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-155740-wake-collect
device  : 25098PN5AC / BP2A.250605.031.A3 (94250f9e)
task    : E12 wake keep-alive fixture collection（票 #16）

## purpose

采集 `tools/ex/tests/` 里 E12 fixture 的真机原文：背屏/主屏采样行（基线段与保活段各一份）、
注入循环 tick 日志、PowerGroup logcat 行、`ps` 快照。fixture 与本目录产物逐字节一致
（`collect-samples-baseline.txt` → `e12-samples-baseline.txt`、`collect-samples-keepalive.txt` → `e12-samples-keepalive.txt`、
`collect-ticks.txt` → `e12-wake-ticks.txt`、`collect-logcat-power.txt` 过滤后 → `logcat-e12-power-group.txt`）。

## facts of this round

- 本轮含 KEYCODE_POWER 拨动复位段（息屏时按 POWER = 唤醒），保活段主屏曾被点亮（`MainFirstOnSec=2`）——
  该现象非干净观察，findings 以三个干净实验轮为准（见 `docs/poc-findings.md`「票 #16 验收」）。
- 本目录只作 fixture 来源，不作 E12 判定依据；E12 判定轮见 20260922-163828 / 164319 / 164628 / 165022 四个 session。

## artifacts

- collect-logcat-power.txt / collect-logcat-rearcue.txt
- collect-ps.txt / collect-state.txt / transcript.txt
- collect-samples-baseline.txt / collect-samples-keepalive.txt
- collect-ticks.txt / collect-ticks-err.txt
- session.md

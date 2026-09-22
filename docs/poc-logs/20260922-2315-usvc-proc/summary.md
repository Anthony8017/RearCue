# RearCue PC experiment summary

session : `docs/poc-logs/20260922-2315-usvc-proc/`
device  : 25098PN5AC / BP2A.250605.031.A3
ticket  : #17 收口补测（票 #12 重开条件② 再收窄）；纯只读采集，无注入、无锁屏操作

## purpose

票 #17 的遗留实验位是「真 Shizuku UserService 进程类能否 addView」。本补测不做 addView（那要
AIDL 加方法 + 重装 APK + `USER_SERVICE_VERSION`+1），只回答一个便宜且决定性的问题：真 UserService
进程是不是 ATMS 注册过的进程——注册墙（`calling from non-existing process`）的判定键正是这张表。

## device facts（verbatim，见原始采集文件）

- `ps-shizuku.txt`（`ps -A` 过滤行）：
  - `shell  9662  1  14271944 120720 do_epoll_wait  0 S com.rearcue.poc:shizuku`（真 UserService，uid shell）
  - `shell  28381  1  ...  shizuku_server`（Shizuku server，uid shell）
  - `u0_a430  20122  3139  ...  moe.shizuku.privileged.api`（Shizuku app）
  - `u0_a333  22419  3139  ...  com.rearcue.poc`（应用本体，uid u0_a333）
- `usvc-proc.txt`：`/proc/9662/cmdline` = `com.rearcue.poc:shizuku`；`/proc/9662/status` 头：`Name: main`、`State: S`、`Pid: 9662`、`PPid: 1`
- `atms-processes.txt`：`dumpsys activity processes` 里对该 pid **零匹配**；表里与 RearCue 相关的唯一
  ProcessRecord 是 `*APP* UID 10333 ProcessRecord{... 9824:com.rearcue.poc/u0a333}`（应用 uid，非 UserService）

## conclusion（结论不超证据）

- 设备事实：真 Shizuku UserService 进程（uid shell）不在 ATMS 进程表内。
- 机制推断（AOSP 语义，非设备事实）：`attachWindowContextToDisplayArea: calling from non-existing
  process` 这堵墙查的是 ATMS 进程表成员资格，与进程怎么 spawn 的无关——真 UserService 与裸
  `app_process` 在这堵墙前同类。墙对 display 0 和背屏一样挡（220336 轮对照臂同墙），所以这条路
  根本走不到背屏策略面前，`SH-UID-WINDOW-*` 仍无从归因。
- 判定词维持 **SH-UID-WINDOW-INCONCLUSIVE**（词表不改、不超证据）；票 #12 重开条件② 再收窄：
  除非构建放行未注册进程的 windowContext 注册（= 条件① 的口径），条件② 单独不构成翻案依据。

## deviations / 污染

无污染（只读采集）。偏差：未跑 addView 实验（成本 = AIDL + 重装 APK + UserService 重连，且最优结局
也只是拿到与 220336 轮相同的墙后无归因），如实记录、留给重开条件② 的执行者。

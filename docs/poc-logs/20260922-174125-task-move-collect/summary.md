# RearCue PC experiment summary (back-filled at ticket #18 review close-out)

> 本 summary.md 是 #18 收口时**补记**（fixture 采集轮当时以 `collection-notes.md` 记录，未生成 summary）；
> 归档原始产物一律未改动。约定依据：`tools/ex/README.md` 的 session 目录布局（session.md + summary.md）。

session : C:\Users\13691\Desktop\RearCue\docs\poc-logs\20260922-174125-task-move-collect
device  : 25098PN5AC / BP2A.250605.031.A3 (94250f9e)
task    : E14 task-move fixture collection + transaction-code scan（票 #18）

## purpose

解锁态手工跑任务搬运事务，采集解析器 fixture 的真机原文，并在一次性锁屏实验前**实测事务号**。
本目录不作 E14 判定依据（判定轮 = `20260922-181414-task-move/`）。

## facts of this round

- `service check activity_task` = found。
- MRSS/REAREye 记录的事务号 **50 在本 Android 16 构建是静默空操作**（回执仍像成功、落位不动，raw-11/raw-13）；
  `cmd activity display move-stack` 是真搬运的地面真值（raw-12）。
- 对一次性可弃任务（本应用 debug MainActivity 任务）扫码（raw-14）得 **N=51** 才是 moveRootTaskToDisplay，
  双向验证（raw-15）后用于真 Dashboard 任务（raw-20/21/22）。
- fixture 出处：`dumpsys-activities-task-moved.txt` ← raw-21；`service-call-task-move.txt` ← raw-21/raw-13 回执行
  （逐字提取，字符未改）；其余两份 fixture 来自早期归档切片（见 `collection-notes.md`）。

## capture flaws (stated, not edited)

- raw-21/raw-22 的注释行与回执被 PowerShell 逗号优先级坑压成一行（票 #16 同款）；回执子串完整，
  fixture 逐字提取，文件保持原样。
- 一次 `date '+%m-%d %H:%M:%S'` 被拆成两个 argv（引号被 PowerShell 吃掉），08/09 已按单字符串传递。

## artifacts

- raw-00-keyguard.txt / raw-00-service-check.txt / raw-01-dashboard-on-rear.txt
- raw-10-prep-dashboard-on-rear.txt / raw-11-after-move-to-0.txt / raw-11-service-call-move-to-0.txt
- raw-12-cmd-activity-help.txt / raw-13-reply-comparison.txt / raw-14-code-scan.txt / raw-15-code51-both-directions.txt
- raw-20-prep-dashboard-on-rear.txt / raw-21-after-move-to-0.txt / raw-22-after-move-to-rear.txt
- raw-21-service-call-move-to-0.txt / raw-22-service-call-move-to-rear.txt / raw-23-logcat-rearcue.txt
- collection-notes.md / session.md / transcript.txt

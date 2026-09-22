# PR body（Spec 0003：锁屏存活重攻 —— 唤醒保活 + Activity 通道，epic #15）

## Summary

```diff
 rear/
 ├── WakeKeepAlive.kt            # 新增：定向背屏唤醒键保活循环（跟随投送生命周期）
 ├── RearProjectionCommands.kt
+│     wakeKeyCommand            #   `input -d 1 keyevent KEYCODE_WAKEUP`（测试钉死定向）
+│     moveRootTaskCommand       #   `service call activity_task 51`（E14 事务号，测试钉死）
+│     startOnDefaultDisplayCommand  # 建任务只走默认屏（锁屏 `--display` 被拒，不走）
+├── RearTaskLocator.kt          #   从 dumpsys 找「带 Dashboard 的 root task」（只搬它）
 ├── RearDisplayBackend.kt       # HyperOsRearDisplayBackend 内部：
 │     project()
+│       keepAlive.start()            # 投送在途即起保活
 │       launchAndConfirm (in-app setLaunchDisplayId)
-│       projectViaShizuku (am start --display)   # 唯一兜底
+│       projectViaShellFallback      # 路由：锁屏 → 任务搬运；未锁屏 → am start --display
+│         projectViaTaskMove         #   E14 事务上屏；NO-TASK/REJECTED/TXN-BROKEN/NO-EFFECT
 │     exit()
-│       finishAll()                  # 交还原生
+│       finishAll() + moveRootTaskToDisplay(task, 0)   # 交还原生 + 归还搬走的任务
 tools/ex/
+├── 12-wake-cost.ps1            # 代价实测（单锁双腿：keep vs idle）
+├── 14-kill-recover.ps1         # 进程重建恢复（am-kill/force-stop 两档死亡）
+└── 15-lock-firstcast.ps1       # 锁屏首投一键场景（FIRSTCAST-* 判定词）
```

Wake Keep-alive 与任务搬运都走 Shizuku UserService（shell uid），DashboardCore / RearDisplayBackend 接口冻结未动。

## Evidence

- **Before**（无保活对照，票 #11 fixture `logcat-survive-cleared-*`）：锁屏后 Dashboard **1.3–1.4s** 被系统收走，背屏回原生。
  **After**（`20260923-001402-lock-survive`）：**E13-PASS** —— 60s 窗 26/26 采样 owner=dashboard、0 detach、主屏全程暗、`keep-alive-stopped: True`（exit 无残留）。
- **Before**（锁屏首投，E3/E14 反证）：锁屏稳态 `am start --display` 每次被 `rearDisplay check locked -> deny`，通知来了上不了屏。
  **After**（`20260923-011503-lock-firstcast`）：**FIRSTCAST-PASS** —— 一条白名单通知 **+4s** 上背屏（route = 任务搬运事务 taskId=13024），12/13 采样 owner=dashboard，清通知后 `native-returned True`。
- **代价**（`20260923-005525-wake-cost`，COST-ESTIMATED-DRAIN）：发热 keep−idle −0.2°C（噪声内）；耗电边际 ≈ **48mAh/h（0.8%/h）** @500ms；5000ms 档约 ÷10（E12 实测同样守得住）。
- 测试：`gradlew test` 全绿（WakeKeepAlive 7 条 + 命令形状钉死 + RearTaskLocator 真 fixture）；`ex.ps1 -Task selftest` **144/144**。

## Merge Danger

**Door:** two-way（回退 = 撤 `WakeKeepAlive.start()` 调用与锁屏路由一行；任务搬运事务有 `hand-back` 归还路径，不留跨界状态）。

**Blast Radius:** 耗电/注入面。保活 = 周期 shell 注入（500ms 默认、`WAKE_INTERVAL` 运行中可调），ADR 0003 已把「用轮询换可见」的取舍与失效条件（代价不可承受 / HyperOS 变脸 / 非轮询手段出现）写成重开条款；`DEFAULT_INTERVAL_MS=500` 是暂定值，定案材料在 `wake-cost.txt` 的 `cost-recommendation` 行。

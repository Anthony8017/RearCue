# POC Findings — RearCue

设备实验与机制验证记录。原始日志在 `docs/poc-logs/`。

## 设备基线（2026-09-21 采集）

| 项 | 值 |
|---|---|
| 机型 | 小米 17 Pro（25098PN5AC） |
| Android | 16（API 36） |
| HyperOS | OS3.0.319.0.WBLCNXM（> MRSS 报障的 3.0.304） |
| Shizuku | moe.shizuku.privileged.api 已安装，实验时经 ADB 拉起 |
| adb serial | 94250f9e |

## Rear Display DisplayInfo（dumpsys display，raw: poc-logs/dumpsys-display.txt）

- displayId=**1**，uniqueId `local:4630946949513469332`，physicalPort 148
- 904×572，density 450（400dpi 实际），120Hz LTPO（支持 24/30/60/90/120）
- cutout：左侧 296px（`@bind_left_cutout`）
- flags：`FLAG_PRESENTATION`、`FLAG_OWN_DISPLAY_GROUP`、`FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY`、`FLAG_OWN_CONTENT_ONLY`
- **自动识别依据**：非默认 + INTERNAL + `FLAG_PRESENTATION` + `FLAG_OWN_DISPLAY_GROUP` ⇒ 无需硬编码 displayId
- 当前 state OFF（原生背屏息屏由 subscreencenter 管）

## 机制结论（源码研究，_research/ 三仓库）

- 投送：`am start --display 1 -n <pkg>/<activity>`（Shizuku shell 已被 MRSS 在 17 Pro 验证）；兜底 `service call activity_task 50 i32 <taskId> i32 1`（= moveRootTaskToDisplay）。本机 `service check activity_task` = found。
- 准入：APK `<application>` 声明 `<meta-data android:name="miui.rear.policy" android:value="1"/>` + 背屏 Activity `miui` 值 ⇒ 进系统背屏白名单，免 hook。
- 抢回：`com.xiaomi.subscreencenter` 在 AOD/熄屏时以 reason="aod" 把 SubScreenLauncher 拉回 display 1（REAREye DexKit 锚点证实）。
- 保活候选（按优先级实验）：① Activity `showWhenLocked/turnScreenOn` + KEEP_SCREEN_ON ② SCREEN_BRIGHT WakeLock（不可单屏定向）③ 周期 `input -d 1 keyevent KEYCODE_WAKEUP`（MRSS 100ms 暴力法，仅兜底）。
- 已知风险：HyperOS 3.0.304+ `screencap -d 1` 抓不到背屏息屏画面 ⇒ 视觉验证靠拍照；MRSS 已停更（小米解锁收紧 + Shizuku 限制）。

## 实验矩阵（待跑）

| # | 实验 | 方法 | 状态 |
|---|---|---|---|
| E1 | `am start --display 1` 经 Shizuku 投 Dashboard | 03-drive.ps1 | 待做 |
| E2 | miui.rear.policy 准入是否必要/充分 | 对照安装（去 meta-data） | 待做 |
| E3 | 主屏锁屏后 Dashboard 存活（30s/5min） | dumpsys activity + 人眼 | 待做 |
| E4 | subscreencenter 抢回时机与恢复 | logcat SUB_SCREEN_ON/OFF | 待做 |
| E5 | 背屏自动息屏间隔（无保活） | dumpsys display state 轮询 | 待做 |
| E6 | 保活 ①/② 效果与功耗 | E5 + 保活对比 | 待做 |
| E7 | 通知移除 → Dashboard 退出 → 原生背屏恢复 | 03-drive.ps1 + 人眼 | 待做 |
| E8 | Shizuku 断开降级/恢复重挂 | 停 Shizuku 进程 | 待做 |

人工检查点：①Dashboard 首次上屏 ②锁屏后背屏 30s/5min ③AOD 抢回瞬间。

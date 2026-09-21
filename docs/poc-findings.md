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
| E1 | `am start --display 1` 经 Shizuku 投 Dashboard | 03-drive.ps1 | ✅ 见「票 #4」：投送路径可用（改为应用内 `setLaunchDisplayId` 主路径） |
| E2 | miui.rear.policy 准入是否必要/充分 | 对照安装（去 meta-data） | ✅ 见「票 #4」：必要且充分 |
| E3 | 主屏锁屏后 Dashboard 存活（30s/5min） | dumpsys activity + 人眼 | 待做（票 #6） |
| E4 | subscreencenter 抢回时机与恢复 | logcat SUB_SCREEN_ON/OFF | 待做（票 #6） |
| E5 | 背屏自动息屏间隔（无保活） | dumpsys display state 轮询 | 待做（票 #6） |
| E6 | 保活 ①/② 效果与功耗 | E5 + 保活对比 | 待做（票 #6） |
| E7 | 通知移除 → Dashboard 退出 → 原生背屏恢复 | 03-drive.ps1 + 人眼 | 待做（票 #5） |
| E8 | Shizuku 断开降级/恢复重挂 | 停 Shizuku 进程 | 待做（票 #6） |

人工检查点：①Dashboard 首次上屏 ②锁屏后背屏 30s/5min ③AOD 抢回瞬间。

## 票 #4 验收：Shizuku 投屏 Dashboard 上背屏（2026-09-22 实测）

链路：`AppContainer` → `RearDisplayBackend`（`:rear`，HyperOS 实现）→ `RearDashboardActivity`（纯黑 + 时间 + Icon Set）。原始证据 `poc-logs/ticket4-e1-e2-evidence.txt`。

**E1 投送（✅ 客观验证）**：识别与投送都按运行时事实判定，无硬编码 displayId。

| 项 | 证据 |
|---|---|
| 背屏按 flag 识别 | `screens=[0:16515, 1:16779] rear=1`（主屏/背屏掩码实测值；判定条件 = 非默认 + PRESENTATION + OWN_DISPLAY_GROUP） |
| 投送发出 | `project iconSet=[com.android.shell, com.tencent.mm] -> 应用内投送已发出 displayId=1` |
| 真的上屏 | `dumpsys activity activities` → `Display #1` 的 `topResumedActivity=com.rearcue.poc/.rear.RearDashboardActivity t12836`，`overrideConfig` 尺寸 904×572@450dpi（正是背屏） |
| 系统放行 | `ActivityStarterImpl: allow app = com.rearcue.poc show on rear display` / `Launching on SubScreen via options in SUB_BUILTIN_DISPLAY` |
| 退出 | `am force-stop com.rearcue.poc` 后 Display #1 回到 `com.xiaomi.subscreencenter/.SubScreenLauncher` |
| 重复投送 | 复用同一任务，不产生第二个任务栈 |

投送路径结论：**应用内 `ActivityOptions.setLaunchDisplayId(1)` 是主路径**（不需要 shell、单一 APK 权限模型），Shizuku（shell uid）保留为兜底通道（`am start --display <id>`）。两者都只负责把同一个 Activity 送上背屏，准入仍由 manifest 决定。

**E2 准入对照（✅ 必要且充分）**：同一份代码、同一个按钮，唯一差别是 `miui.rear.policy`。

| 条件 | 系统日志 | Display #1 | app 侧 |
|---|---|---|---|
| 无 meta-data | `should not allow app = com.rearcue.poc show on rear display` → `aborted activity = .../.rear.RearDashboardActivity` | 保持 SubScreenLauncher | `投送 displayId=1 未获确认（2500ms）` |
| 有 meta-data | `allow app = com.rearcue.poc show on rear display` | `topResumedActivity=.../RearDashboardActivity` | `投送确认：RearDashboardActivity 已创建 displayId=1` |

`miui.rear.policy=1` 声明在 `<application>` 下，既必要（缺了必被 aborted）也充分（加上即放行），无需 hook/root。

**背屏 Dashboard 观感**：`screencap -d 1` 在本机仍不可用（`Display Id '1' is not valid`）⇒ 视觉确认只能靠人眼/拍照。**待人工（拍照点①）**：人眼确认背屏正常显示纯黑 + 时间 + 图标（实验时手机平放、背屏朝上）。

本轮踩到的点（重要，已修）：

- **主线程自锁**：投送后若在主线程同步等待确认，界面 `onCreate` 也排不上队，必等到超时（实测 `onCreate` 恰好晚于超时 17ms 才执行）。改成「启动即返回 + 后台线程确认」，13ms 发出、19ms 确认。
- **启动不抛异常 ≠ 上屏**：被背屏白名单拒绝时 `startActivity` 静默成功、系统内部 aborted，只看返回值会把失败报成成功。现在用「界面生命周期回调 / 背屏任务栈」双信号确认，拿不到才判失败。
- **flag 位次**：`FLAG_PRESENTATION` 是 `1 shl 3`、`FLAG_OWN_DISPLAY_GROUP` 是 `1 shl 8`（本机掩码 16515/16779 锁定）。测试里若用 `1 shl n` 与被测代码共用同一错误常量，会一起错——测试改用真实数值断言。
- **AIDL/manifest 的中文注释会被工具链写坏**（`aidl.exe` 与 manifest merger 报 `directory ... not found`/XML 解析错误），这两个文件里的注释保持 ASCII。
- **PowerShell `Set-Content -Encoding UTF8` 会把已有中文写成乱码**：改文件一律走编辑器而不是 `Set-Content` 整文件回写。

**Shizuku 通道当前不可用（阻塞说明）**：本机 `shizuku_server` 是子 agent 用第三方 starter 以**裸 `shell` uid** 拉起的（`/data/local/tmp/shizuku_starter`），而它需要回调应用的 `ShizukuProvider`（受 `moe.shizuku.manager.permission.API_V23` 运行时权限保护）→ 稳定报 `Permission Denial: ... uid=2000 requires moe.shizuku.manager.permission.API_V23`，导致 `Shizuku.pingBinder()` 恒为 false。已按官方要求补上 provider 声明与 `ShizukuProvider` 依赖，**正常启动的 Shizuku（从 Shizuku app 内启动/无线调试授权）应可工作**，但需人工在手机上启动一次才能验证；在那之前投送走应用内主路径，Shizuku 兜底自动跳过（不崩、不阻塞）。

## 环境与自动化备注（#2 期间实测）

- 工具链统一在 `%LOCALAPPDATA%\RearCue-tools\android-sdk\`（adb 在其 `platform-tools\`；旧 `RearCue-tools\platform-tools\` 已不存在）；JDK `RearCue-tools\jdk-17.0.20.1+1`，Gradle 8.14.3。
- 锁屏态 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`：自动化安装前先 `adb shell wm dismiss-keyguard`（或保持手机解锁）。
- 设备 94250f9e 授权持续有效；`am start -W` 冷启 245ms 可作后续上屏判定基线。

## 票 #3 验收：通知监听 → Icon Set 主屏可视（2026-09-21 实测）

链路：`NotificationListenerService` → `NotificationRepository`（`:notification`，按 notification key 去重）→ `AppContainer` → `DashboardCore`（`:core`，算 Icon Set）→ 主屏调试页。观测面 `adb logcat -s RearCue`；原始日志归档在 `poc-logs/ticket3-logcat-acceptance.txt`。

环境准备（三条都必需）：

```powershell
adb shell cmd notification allow_listener com.rearcue.poc/com.rearcue.poc.notify.RearNotificationListener
adb shell pm grant com.rearcue.poc android.permission.POST_NOTIFICATIONS
adb shell settings get secure enabled_notification_listeners   # 复核：应含 com.rearcue.poc/...
```

| 验收标准 | 操作 | 结果 | 证据 |
|---|---|---|---|
| ① 发 Allowlist App 通知 → 出图标；清除 → 消失 | `adb shell cmd notification post -t "RearCue PC 测试" pctest "..."` | ✅ | `posted com.android.shell iconSet [com.tencent.mm] -> [com.tencent.mm, com.android.shell]`；另一轮 `removed com.rearcue.poc iconSet [...] -> [com.android.shell, com.tencent.mm] tracked=29` |
| ② 发测试通知按钮 → 出图标；清除 → 消失 | 主屏「发测试通知」/「清除测试通知」 | ✅ | `posted com.rearcue.poc iconSet [com.android.shell, com.tencent.mm] -> [..., com.rearcue.poc] tracked=30`；清除后 `removed com.rearcue.poc ... tracked=29` |
| ③ `cmd notification post`（com.android.shell）计入 | 同上 ① | ✅ | shell 通知 key `0\|com.android.shell\|2020\|pctest\|2000` 被跟踪并按包名并入 Icon Set |
| ④ 非 Allowlist App 通知不出现 | 监听连接时系统有 29~30 枚活跃通知（ChatGPT/misound/xiaomi.* 等） | ✅ | `snapshot 30 iconSet [com.android.shell, com.tencent.mm]`：30 枚里只有 2 枚 Allowlist 应用进 Icon Set |

截图：`poc-logs/ticket3-iconset-main-screen.png`（Icon Set 2：Shell + 微信，真实应用图标与应用名；QQ 当时无 Active Notification）。

本轮踩到的点（已修）：

- **同包多枚通知只出一枚图标**：快照里同包 3 枚 `com.miui.misound` → Icon Set 里 1 枚，符合「每个存在 Active Notification 的 Allowlist App 恰好一枚图标」。
- **应用图标解析需要 manifest 可见性**：Android 11+ 不声明可见性时 `getApplicationIcon(pkg)` 拿不到第三方图标（微信曾退化成首字母 `C`）。加 `<queries><intent MAIN/LAUNCHER>` 后正常拿到微信图标；没有用 `QUERY_ALL_PACKAGES`。
- **监听服务绑定时机**：`force-stop` 后系统不是立刻重绑，实测 9~13s 后才 `onListenerConnected`（此前 UI 合法地显示「监听服务未连接」）。改动监听服务代码后旧进程可能仍活着，需 `force-stop` 再启动才能确认新代码生效。
- **签名不一致**：旧安装包与当前 debug keystore 不匹配时 `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `adb uninstall`。
- **置顶窗口干扰自动化**：小米视频小窗浮在主屏上会抢 `uiautomator dump` 里的节点，自动化断言前先关掉。
- **重复 post 同 tag 不产生 Post 事件**：系统把 `cmd notification post` 当成既有通知更新（key 不变），仓库按 key 去重后不发事件——这是预期行为，验收 ① 的「出图标」证据以首枚为准。
- 通知移除的设备侧复现：`input tap` 打开通知内容（`autoCancel` 生效）或应用内「清除测试通知」；通知栏横滑在 HyperOS 上不生效。

review 后的收口（同票内完成）：

- Icon Set 决策收回 `DashboardCore`（新增 `iconSet` 只读视图），Android 层不再自己算 Icon Set；`activeCounts` 改 `LinkedHashMap`，图标顺序 = 首次出现顺序，不会因新通知重排。
- `getActiveNotifications()` 被拒时不再用空集冒充真相（原来会误清 Icon Set），改为保持跟踪集等下一次对账；`onListenerDisconnected` 追加 `requestRebind()`。
- 应用名改用 `PackageManager` 的真实 label（微信不再显示成 `mm`）；`isListenerEnabled` 改成组件名精确比较（原来 `contains(packageName)` 有子串误判）。


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


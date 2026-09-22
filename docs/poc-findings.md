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
| E3 | 主屏锁屏后 Dashboard 存活（30s/5min） | dumpsys activity + 人眼 | ✅ 见「票 #6」：锁屏前已上屏则可一直保住（5 分钟 62 点 + 人眼确认）；锁屏**稳态**下新投送被系统 deny |
| E4 | subscreencenter 抢回时机与恢复 | logcat SUB_SCREEN_ON/OFF | ✅ 见「票 #6」：广播收口为 Takeover 事件，锁屏窗口内重投被 allow、Dashboard 守住 |
| E5 | 背屏自动息屏间隔（无保活） | dumpsys display state 轮询 | ✅ 见「票 #6」：锁屏 <5s 内进 AOD（DOZE/DOZE_SUSPEND），没有「保持亮屏」的窗口（间隔 = 0s） |
| E6 | 保活 ①/② 效果与功耗 | E5 + 保活对比 | ✅ 见「票 #6」：窗口级保活（showWhenLocked/turnScreenOn/KEEP_SCREEN_ON，无轮询唤醒）实测唤醒并守住背屏 group；WakeLock/透明唤醒 Activity 未做（E5 表明没有可保的亮屏窗口） |
| E7 | 通知移除 → Dashboard 退出 → 原生背屏恢复 | 03-drive.ps1 + 人眼 | ✅ 见「票 #5」：自动上/下屏闭环打通，软退出后原生背屏恢复 |
| E8 | Shizuku 断开降级/恢复重挂 | 停 Shizuku 进程 | ⚠️ 见「票 #6」：不崩溃、监听照常 ✅；「恢复后自动重投」因 binder 握手权限未验证 |

人工检查点：①Dashboard 首次上屏 ②锁屏后背屏 30s/5min ③AOD 抢回瞬间。

## 票 #6 验收：韧性与降级（2026-09-22 实测）

链路：系统信号（SUB_SCREEN/SCREEN 广播 + DisplayListener + Shizuku binder）→ `DashboardCore` → `RearDisplayBackend`。
原始证据：`poc-logs/ticket6-e3-e6-lock-evidence-round3.txt`（正式链路，主）、`ticket6-e3-e6-lock-evidence.txt`（第一轮，反面样本）、
`ticket6-e5-baseline-evidence.txt`、`ticket6-miui-rear-policy-evidence.txt`、`ticket6-e8-shizuku-evidence.txt`。

**结论先说**：**「上屏后锁屏 → Dashboard 一直在背屏」成立**（5 分钟 62 个采样点 + 人眼确认）；
**「锁屏闲置中来了通知才上屏」仍走不通**——HyperOS 在 keyguard 锁定稳态下拒绝第三方应用新投送，且后台应用受 BAL 限制。

1. **锁屏稳态禁止第三方应用在背屏启动界面**（`ActivityStarterImpl.isAllowedToStartOnRearDisplay`，见证据 [A]/[B]/[C]）：
   `isShouldShowOnRearDisplay` 通过（`miui.rear.policy=1` 已放行）之后，代码再判 `WindowManagerService.isKeyguardLocked()`——
   未锁屏 ⇒ allow；锁屏 ⇒ 只有硬编码白名单 `SECONDARY_DISPLAY_WHITE_PACKAGE` 里的 8 个系统包
   （camera / gallery / mediaviewer / smarthome / provision / bluetooth / subscreencenter / mihomemanager）放行，第三方应用一律 `deny`。
   运行期日志：`ActivityStarterImpl: rearDisplay check locked: com.rearcue.poc -> deny` → `aborted activity = ... show on rear display`。
   Shizuku（shell uid）也绕不过：这条策略在 `ActivityStarter` 里，跟调用方 uid 无关（shell `am start --display 1` 同样被 aborted）。
   **关键细节**：判定的是「此刻 keyguard 是否已锁」。锁屏动作与 keyguard 真正上锁之间有一个短暂窗口——`SUB_SCREEN_OFF`/`SCREEN_OFF`
   正是在这个窗口里到达，所以**锁屏瞬间的重投被 allow**（第三轮实测，无 deny），Dashboard 因此能守住背屏。
2. **后台启动限制（BAL）拦掉应用内投送**：应用在后台（无可见 Activity）时 `startActivity` 被
   `Background activity launch blocked! ... resultIfPiCreatorAllowsBal: BAL_BLOCK` 拦下（`result code=102`）。
   票 #5 的「无手动干预自动上屏」成立条件是应用刚在前台（`BAL_ALLOW_GRACE_PERIOD` 宽限期）——真机闲置场景不成立。
3. **进程被杀后 MIUI 不放行监听重绑**（`AutoStartManagerService: MIUILOG- Reject service`）：需要用户在 MIUI 设置里
   给本应用开自启动，否则「应用被系统回收后靠通知唤醒」这条恢复路径也不成立。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 锁屏 30s/5min 后 Dashboard 仍在背屏 | 第三轮（正式链路，09:08:16 锁屏）：锁屏前 `Display #1 topResumedActivity=...RearDashboardActivity`（由 DashboardCore 依监听快照投送）；`KEYCODE_POWER` 后 +0s…+325s **62 个采样点全部仍是该界面**，观察结束后（>8 分钟）复查仍在屏；**人眼确认**背屏为纯黑 + 时间 + 一枚 Shell 图标（拍照点②） | ✅ **成立**（前提：Dashboard 在锁屏前已上屏） |
| ② AOD Takeover 后经 SUB_SCREEN 广播自动重投恢复 | 锁屏瞬间应用收到 `miui.intent.action.SUB_SCREEN_OFF` + `android.intent.action.SCREEN_OFF`（110ms 内合并）→ 产出 `LaunchDashboard(1)` → 系统 `ActivityStarterImpl: allow app = com.rearcue.poc show on rear display` → `投送确认：Dashboard 已在屏 displayId=1`；随后的 `SCREEN_ON` 信号同样触发一次重投（也被 allow）。第一轮的 `deny` 样本发生在 keyguard 已锁之后 | ✅ **成立**（锁屏窗口内重投被放行） |
| ③ findings 记录实测息屏间隔；保活生效、无周期唤醒轮询 | E5（无保活）：锁屏 <5s 内背屏进 AOD（`GreezeManager: onDisplayChanged displayId=1` + `Display{#1 state=DOZE→DOZE_SUSPEND}`），**没有**「亮屏保持 N 秒」的窗口 ⇒ 息屏间隔 = 0s（立即交棒）；E6 落地窗口级保活（`setShowWhenLocked` / `setTurnScreenOn` / `FLAG_KEEP_SCREEN_ON` + manifest `showWhenLocked`），实测把背屏 group 唤醒并守住（`WindowManager: Started waking up... (groupId=1 why=ON_BECAUSE_OF_APPLICATION)`，主屏 group 0 未被本应用唤醒），全程无任何周期性唤醒 | ✅（间隔已记录；保活生效且无轮询）。WakeLock / 透明唤醒 Activity **未实现**：E5 表明没有「亮屏窗口」可保，窗口级声明已足够 |
| ④ 杀 Shizuku：应用不崩溃、监听照常；Shizuku 恢复后自动重投 | `kill -9 shizuku_server` 后应用进程 pid 不变、无 FATAL/ANR，且继续处理通知事件（`posted/removed com.android.shell tracked=36→35`）；重启 server（官方 `start-shizuku.sh`）后本应用仍 `pingBinder=false`（见下） | ⚠️ **不崩溃 + 监听照常 ✅**；「恢复后自动重投」无法验证 |

**第一轮的 `⛔` 样本为什么不成立**：那轮 Dashboard 是 shell 手工投的、核心 iconSet 为空（信号到达时应用侧决策是「无需重投」），
锁屏后 5s 内就被 AOD 顶掉。第三轮补上了关键一环——**锁屏瞬间的重投**（信号驱动、应用内投送、被 allow），
这才是保住背屏的原因；两轮的差别不是「系统允许与否」，而是「锁屏窗口内有没有重投 + 界面有没有 keep-screen-on」。

**E5 实测细节**：背屏**没有**独立的息屏倒计时——主屏一锁，subscreencenter 立刻把背屏交给 AOD（`Sub_Screen_Aod` wakelock，DOZE_SUSPEND），
所以「按实测间隔决定唤醒频率」这个前提不存在；保活只能做成「界面在屏期间不让它被交出去」，即窗口级声明，不需要也不该做周期唤醒。

**E8 未验证部分的根因（票 #4 遗留）**：本机 `shizuku_server` 是官方 starter 以裸 shell uid 拉起的，回调本应用 `ShizukuProvider` 时报
`Permission Denial: ... uid=2000 requires moe.shizuku.manager.permission.API_V23`（该权限是 dangerous 级，shell 包未申请）⇒ `Shizuku.pingBinder()` 恒 false，
兜底通道用不上。要验证恢复重投，需人工在 Shizuku 应用内正常启动一次（无线调试 / 官方 ADB 流程）。

本轮实现要点：

- **背屏信号收口**：新增 `RearDisplaySignals`（注册 `miui.intent.action.SUB_SCREEN_ON/OFF` + 系统 `SCREEN_ON/OFF`，`RECEIVER_EXPORTED`），
  由 `HyperOsRearDisplayBackend` 转成 `DashboardEvent.TakeoverDetected`；1s 合并窗口避免锁屏/解锁成对广播连发 `startActivity`。
- **锁屏存活**：`RearDashboardActivity` 运行期 `setShowWhenLocked(true)` / `setTurnScreenOn(true)` + `FLAG_KEEP_SCREEN_ON`，
  manifest 同步声明 `showWhenLocked`（**上屏决策发生在 onCreate 之前，只看 manifest**）。
- **投送失败自动兜底**：应用内投送 2.5s 内确认不到界面（被 BAL 或背屏策略拦）→ 自动改走 Shizuku 兜底命令，
  兜底结果**回读任务栈**才算数（`am start` 成功 ≠ 上屏）；兜底要能跑起来，`RearDashboardActivity` 必须
  `exported=true` + `permission=android.permission.DUMP`（否则 shell `am start` 直接 `SecurityException: not exported from uid 10332`，
  票 #4 的兜底通道从未真正跑通过）。
- **Shizuku 生命周期**：`ShizukuShell` 注册 `addBinderReceivedListenerSticky` / `addBinderDeadListener`，
  掉线只清 UserService、**不**触发 Degrade（投送主路径是应用内启动），上线/授权成功时按当前 Icon Set 幂等重投。
- **PC 实验钩子**：`DebugCommandReceiver`（**只在 debug 构建注册**：`app/src/debug/`，`android.permission.DUMP` 保护，只有 `adb shell` 能触发）
  提供 `PROJECT_REAR` / `EXIT_REAR` / `STATE` 三个动作，用于「应用在后台」的实验，不必人点手机。

下一步（新票/ADR 待定，本票不实现）：

- **锁屏闲置时的首投**（唯一还没打通的真实场景）：`services.jar` 里有 `" add system_window on rear display` 这条日志，说明背屏还有一条 WindowManager
  侧的窗口通道（`TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW`），可能不受 `ActivityStarterImpl` 的锁屏策略限制——需实验验证。
- **MIUI 自启动**：进程被杀后系统重绑监听会被 `AutoStartManagerService: MIUILOG- Reject service` 拒绝（票 #5 已记录），
  本轮经 `cmd notification disallow_listener/allow_listener` 手动恢复绑定；日常使用需用户在 MIUI 设置里放行自启动。

## 票 #5 验收：通知驱动自动上/下屏（2026-09-22 实测）

链路：通知事件 → `NotificationRepository` → `DashboardCore`（事件→效果）→ `AppContainer.dispatch` → `RearDisplayBackend`。原始日志 `poc-logs/ticket5-e7-evidence.txt`。

| 验收标准 | 证据（`adb logcat -s RearCue`） | 结论 |
|---|---|---|
| ① 无手动干预，首条通知自动上屏 | `listener connected active=28` → `posted com.android.shell → LaunchDashboard(1)` → `project ... displayId=1` → `Dashboard attach 实例数=1`；dumpsys Display #1 `topResumedActivity=com.rearcue.poc/.rear.RearDashboardActivity`；**人眼**：纯黑底 + 时间 + 一枚图标（Shell） | ✅（未碰任何投送按钮） |
| ② 通知增删 → Icon Set 同步增删 | `posted com.rearcue.poc → UpdateIconSet(2)` → `update iconSet=[com.android.shell, com.rearcue.poc]`；清除后 `removed com.rearcue.poc → UpdateIconSet(1)`（**不重新投送**） | ✅ |
| ③ 末条通知消失 → 自动退出 + 原生背屏恢复 | `removed com.android.shell → ExitDashboard` → `exit 结束在屏 Dashboard=1，进程与通知监听继续` → `Dashboard detach 实例数=0`；dumpsys Display #1 回到 `com.xiaomi.subscreencenter/.SubScreenLauncher`，进程仍在；**人眼**：背屏变回原生样式 | ✅ dumpsys + 人眼双确认 |
| ④ findings 记录 E7 | 本节 + `poc-logs/ticket5-e7-evidence.txt`（含原始 logcat 与 dumpsys 片段） | ✅ |

**E7 现象**：投送/更新/退出都不需要点按钮；通知增删只走 `IconSetFeed` 广播（背屏界面自己重组），只有首投与「界面不在了」才真的 `am start`。

本轮实现要点：

- 效果→动作的搬运收在 `AppContainer.dispatch`（决策仍在 `DashboardCore`，效果短名 `DashboardEffect.label` 进日志），加上投送通道对齐（`DisplayListener` 感知背屏注册/注销）。
- **下屏不再 `am force-stop`**（票 #4 的旧实现）：监听服务与背屏 Dashboard 同进程，force-stop 会把监听一起杀掉，末条通知退出后就再没人能自动上屏。改为 `RearDashboardHost` 结束界面（进程内句柄，实例按 onCreate/onDestroy 登记，AOD 停屏后仍能被结束）。
- **通道判据改为「识别到背屏」**：应用内投送不需要 Shizuku（E1），所以 Shizuku 掉线不触发 Degrade；术语与 spec 0001 已同步（CONTEXT.md「投送通道」）。
- `update()` 自愈：核心以为在屏但界面其实没了（被系统结束）时按当前 Icon Set 重投，避免「通知变了但背屏不动」；上屏在途的 ~40ms 空档用 `launchPending` 排除（E7 实测该空档内会连发两枚通知）。
- 修掉票 #4 遗留：`RearProjectionCommands.dashboardComponent` 把组件名写成了 `.ui.RearDashboardActivity`（真实是 `.rear.`），Shizuku 兜底投送与任务栈校验都会失败。
- 顺带修掉票 #4 的确认竞态：`onActivityCreated` 监听改为**先挂再投送**（投送后 15ms 内即创建，晚挂会漏信号）。

本轮踩到的点（**真实使用路径的两个阻断条件，归票 #6**）：

- **后台被 HyperOS 冻结**：应用退到后台即被 `GreezeManager` 冻结（`cgroup.freeze=1`，日志 `FZ uid = 10332 reason =tobg`），冻结期间通知事件根本不到达（实测发的通知无任何 RearCue 日志），只有拿 Activity 把它顶到前台才 `THAW ... reason : Activity Start` 并一次性补投。⇒ 不保活的话，「手机闲置时来消息」这条主路径走不通。
- **进程被杀后系统拒绝重绑监听**：`AutoStartManagerService: MIUILOG- Reject service` + `NotificationListeners: AutStart Unable to bind notification listener service`——MIUI 自启动白名单没放行；`cmd appops set com.rearcue.poc AUTO_START allow` 在本机无效（`Unknown operation string`），需要用户在 MIUI 设置里开「自启动」。
- 验收 ① 的成立条件要写清楚：本轮的「无手动干预」是在**进程活着**（listener 已连接）时验证的；进程死亡后要等 MIUI 自启动放行才能重绑（上一条），后台被冻结时事件还会延迟到解冻——两者都归票 #6。
- 人工检查点①的拍照留档本轮未做（人眼已确认，照片缺）——`screencap -d 1` 在本机仍不可用，只能拍照。

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


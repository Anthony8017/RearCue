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
- 背屏断电与 MIUI 逐应用授权（票 #7 新增）：主屏休眠时系统会**连带关掉背屏 display group**
  （`PowerGroup: Powering off display group due to power_button (groupId= 1, ...)`），随后 `wm_finish_activity ... remove-task`
  收走第三方界面；同时 MIUI 用数值 appop 门控「锁屏显示」（`appops get` 显示为 `MIUIOP(10020)`，未授权时伴生
  `ActivityRecordImpl: MIUILOG- Show when locked PermissionDenied`）。授权方式：`adb shell appops set com.rearcue.poc 10020 allow`，
  **重装会重置**。
- 背屏原生手势会顶掉第三方界面（票 #7）：背屏上的触摸/上滑触发 `SubScreenCenter_GestureInputHelper ... startRecentAnimation`
  后原生 SubScreenLauncher 回到 display #1，且不产生任何本应用订阅的广播 ⇒ 应用侧只看到界面被结束。

## 实验矩阵（E1–E12 / E14 收口，票 #7 / 票 #10 / 票 #11 / 票 #16 / 票 #18；通道闸门决策见「票 #12」）

| # | 实验 | 方法 | 状态（含失败条件） |
|---|---|---|---|
| E1 | 把 Dashboard 投到背屏 | `tools/ex` 的 `04-drive.ps1`（`cmd notification post` + debug 广播，全自动） | ✅ 见「票 #4」「票 #7」：主路径是应用内 `ActivityOptions.setLaunchDisplayId`；票 #7 复现 6/6 绿。**失败条件**：主屏处于锁屏稳态（`ActivityStarterImpl: rearDisplay check locked -> deny`）或应用不可见（`Background activity launch blocked`，见 E3） |
| E2 | `miui.rear.policy` 准入是否必要/充分 | 对照安装（去 meta-data） | ✅ 见「票 #4」：必要且充分（缺了必被 aborted，加上即放行）；票 #7 的采集保留系统侧 `allow app = com.rearcue.poc show on rear display` 作正向对照。**失败条件**：缺 `miui.rear.policy` 的构建 100% 被 `aborted activity ... show on rear display`；有 meta-data 但主屏处于锁屏稳态时同样被 `rearDisplay check locked -> deny` 拦下 |
| E3 | 主屏锁屏后 Dashboard 存活（30s/5min） | `04-drive.ps1 -Scenario e3-lock`（KEYCODE_POWER + 逐点采样） | ⚠️ 见「票 #7」：**本轮三轮复跑都没复现票 #6 的 5 分钟存活**——锁屏后 1–5 秒内 Dashboard 被结束，直接原因两条：①锁屏瞬间的重投被 BAL 拦（`callingUidHasVisibleActivity: false`，应用侧表现为「投送 displayId=1 未获确认（2500ms）」）；②系统随主屏休眠关闭背屏 display group（`PowerGroup: Powering off display group due to power_button (groupId= 1)`）后 `wm_finish_activity ... remove-task`。成立条件（票 #6）：锁屏前已上屏 + 窗口级保活 + 重投抢在 BAL 判定之前 |
| E4 | subscreencenter 抢回时机与恢复 | logcat `SUB_SCREEN_ON/OFF` + 应用日志 | ✅ 见「票 #6」「票 #7」：`SUB_SCREEN_OFF` + `SCREEN_OFF` 在 17ms 内合并成一次 `LaunchDashboard`，三轮复跑都出现「信号 → 效果 → 投送」这一段；**失败条件**：效果发出但投送被 E3 的两条原因拦下。**新观测**：背屏手势（底部上滑 Recents）会把 Dashboard 顶掉且应用不会自动恢复（11:24 轮：`SubScreenCenter_GestureInputHelper ... startRecentAnimation, topApp: com.rearcue.poc`） |
| E5 | 背屏自动息屏间隔（无保活） | `dumpsys display` state 轮询 | ✅ 见「票 #6」：**没有**「亮屏保持 N 秒」的窗口，主屏一锁就交 AOD（间隔 = 0s）；票 #7 的采样同样在 +5s 内看到 `DOZE/DOZE → OFF/OFF` |
| E6 | 保活（窗口级声明）效果与功耗 | E5 + 保活对比 | ⚠️ 见「票 #6」「票 #7」：票 #6 实测窗口级声明把背屏 group 唤醒并守住（`Started waking up... groupId=1 why=ON_BECAUSE_OF_APPLICATION`，全程无周期唤醒）；票 #7 三轮**未复现**（display group 被 power off，见 E3）。WakeLock/透明唤醒 Activity 仍不做（E5 表明没有可保的亮屏窗口） |
| E7 | 通知移除 → Dashboard 退出 → 原生背屏恢复 | `04-drive.ps1 -Scenario e7` | ✅ 见「票 #5」「票 #7」：`removed → ExitDashboard → Dashboard detach 实例数=0 → dumpsys Display #1 回到 SubScreenLauncher`，票 #7 复现 5/5 绿，全程无手动干预 |
| E8 | Shizuku 断开降级 / 恢复重挂 | `03-shizuku.ps1 -Restart`（kill -9 + 本机 starter） | ✅ 见「票 #6」「票 #7」「票 #8」：杀 server 后应用不崩溃、进程 pid 不变、**通知照常处理**（票 #7：`listener-kept-working=True`）；server 可重启（pid 15468→15924）。**「恢复后自动重投」票 #8 打通并实测**（原先的「pingBinder 恒 false」是本应用 manifest 写错权限，不是 starter 的锅，见「票 #8」）。**成立条件**：主屏未处于锁屏稳态（锁屏稳态下背屏投送两条通道都被 HyperOS 拒，见 E3；票 #8 的实测在未锁屏状态下做）。**失败条件**：Shizuku 运行时授权未给（`granted=false`）时兜底命令通道仍不可用，但「恢复重投」由 binder 上线触发，不受影响 |
| E9 | SYSTEM_ALERT_WINDOW 覆盖窗口能否上背屏 | `tools/ex` 的 `06-overlay.ps1`（一条命令：加窗口 → 判定 → 撤窗口 → 采集归档，票 #10） | ❌ 见「票 #10」：**HyperOS 的背屏窗口策略只放行系统应用**——`WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display` → 应用侧 `BadTokenException ... permission denied for window type 2038`；display #0 对照组（同代码、同授权）加窗成功且 dumpsys 可见。**失败条件**：①未授权（appops deny → `E9-BLOCKED-PERMISSION`，应用侧 `canDrawOverlays=false` 明确报告）；②被系统拒绝（addView 异常）；③被背屏策略挡（本机实测：非系统应用一律 deny，`miui.rear.policy` / MIUIOP 授权都救不了窗口路径）。证据目录：`poc-logs/20260922-123134-overlay/`（背屏主轮）、`20260922-122827-overlay/`（display #0 对照）、`20260922-123244-overlay/`（未授权）、`20260922-124030-overlay/`（无此屏对照）、`20260922-122608-overlay/`（首轮，见其 `erratum.md`） |
| E10 | 覆盖窗口锁屏后是否还在背屏 | `tools/ex` 的 `07-lock-compare.ps1`（`ex.ps1 -Task overlay-lock`：上锁一次、双通道同轮观察，票 #11） | ⛔ 见「票 #11」：**前提被 E9 挡死，本机不可测（E10-BLOCKED-BY-E9）**——窗口在**未锁屏**时就加不上背屏（deny + `BadTokenException` 同 E9），「锁屏态窗口被清掉」在本机无从触发：被清掉的从来是 Activity 通道的 Dashboard，不是覆盖窗口。**失败条件**：①E9 门槛不过（本机恒真）；②门槛若开：`E10-CLEARED`（窗口在观察内消失，含消失时刻）/ `E10-PASS`（全程在屏）——判定词表已就位，门槛变化后同一条命令直接作答。证据目录：`poc-logs/20260922-125937-overlay-lock/`（主轮）、`20260922-125426-overlay-lock/`（首轮，被指纹唤醒污染）——两轮各留 `erratum.md` |
| E11 | 窗口在屏期间背屏是否保持 ON（FLAG_KEEP_SCREEN_ON） | 同 E10 同轮逐点采样 `dumpsys display` 背屏 state | ⛔ 见「票 #11」：**同被 E9 挡死（E11-BLOCKED-BY-E9）**——无窗口可保活。同轮记录的无窗口事实：+5s 离开 ON（`DOZE`），+10s 起 `DOZE_SUSPEND` 直到 +92s 观察结束（与 E5「主屏一锁背屏即离 ON」一致）；首轮（125426）观察被 +6.9s 的**外部指纹唤醒**污染（`Waking up power group from Dozing ... FINGERPRINT:finishCallBack` → `SCREEN_ON`/`SUB_SCREEN_ON` 触发重投），其 +10s 后的采样不是锁屏态事实（见其 erratum）。**失败条件**：①E9 门槛不过（本机）；②门槛若开：`E11-LOST`（窗口在屏时背屏离开 ON）/ `E11-PASS`。证据目录：同 E10（`poc-logs/20260922-125937-overlay-lock/` 主、`20260922-125426-overlay-lock/` 首轮，两轮各留 `erratum.md`——125937 的标注其 e11 文案尾部的 `System.Object[]` 拼接瑕疵），无窗口时的背屏 state 采样在主轮 `e10-samples.txt` |

| E12 | 周期注入定向背屏的唤醒键能否让背屏锁屏后保持 ON | `tools/ex` 的 `08-wake-keepalive.ps1`（`ex.ps1 -Task wake-keepalive`：同一 session 先跑不开保活基线段、再跑开保活段，逐点采样背屏/主屏 state；注入循环在设备侧以 shell uid 跑，间隔 = `-WakeIntervalMs` 参数，票 #16） | ✅ 见「票 #16」：**500ms 间隔两轮复现 + 5000ms 间隔均 E12-PASS**（保活段 60s 窗内背屏全程 ON；同轮对照组 +4s/+7s/+6s 离开 ON、结束 `DOZE_SUSPEND/DOZE_SUSPEND`）；**30000ms 间隔 E12-LOST / E12-IGNORED**（+6s 照旧离开 ON，+26s 被针拉回）。代价事实：注入显示定向，保活段主屏全程 OFF。**失败条件**：①唤醒键被忽略＝`E12-LOST`+`E12-IGNORED`（离开 ON 时刻与对照组一致，30000ms 轮实测）；②只延迟熄屏＝`E12-LOST`+`E12-DELAYED-ONLY`（离开 ON 显著后移但窗内仍离开，词表就位、本轮未触发）；③注入命令失败＝`E12-INJECT-FAILED`（tick 缺失 / `input` 报错 / 窗内针数 <50% 期望，判据是 tick 日志+ps 不是退出码）；④对照组基线不成立＝`E12-NO-BASELINE`（本就不熄、无从判定）；⑤外部污染（指纹唤醒 / 人按电源）→该轮作废、样本单列 `erratum.md`（162618 轮实测）。证据目录：`poc-logs/20260922-163828-wake-keepalive/`（主轮）、`20260922-164319-wake-keepalive/`（复现轮）、`20260922-164628-wake-keepalive/`（5000ms 轮）、`20260922-165022-wake-keepalive/`（30000ms 弱保活轮，见其 `erratum.md`）、`20260922-162618-wake-keepalive/`（污染轮，见其 `erratum.md`）、`20260922-155740-wake-collect/`（fixture 采集轮）、`20260922-162427-wake-keepalive/`（aborted，见其 `erratum.md`） |
| E14 | 锁屏稳态下任务搬运事务能否把 Dashboard 搬上背屏 | `tools/ex` 的 `09-task-move.ps1`（`ex.ps1 -Task task-move`：解锁态准备 + 对照组 → 上锁 → 事务搬运 + 逐点采样，票 #18） | ✅ 见「票 #18」：**E14-PASS**——锁屏稳态 `service call activity_task 51 i32 <rootTaskId> i32 1` 真的把 `t12988@d0` 搬上背屏（+25s 采样 `t12988@d1 owner=dashboard rear=ON/ON`；rear-awake 变体 +49s 同样落上），对照组（解锁态同一条命令）双向成功，锁屏段 task-move 相关拒绝行 0 条。**注意**：MRSS/REAREye 记录的事务号 **50 在本 Android 16 构建是静默空操作**，本机实测是 **51**（采集轮扫码，回执不判、落位才算）。**失败条件**：①`E14-REJECTED`（被系统拒，引拒绝日志原文；词表就位、本机未触发）；②`E14-TXN-BROKEN`（对照组也失败 ⇒ 事务号/用法错误，锁屏结论无从判定）；③`E14-NO-TASK`（锁前无 Dashboard 任务，运行前告警）；④`E14-TASK-GONE`（任务在事务前被收走）/ `E14-NO-EFFECT`（事务跑了但没落背屏且无拒绝行）/ `E14-RUN-INVALID`（外部污染 → 该轮作废、erratum 单列）。证据目录：`poc-logs/20260922-181414-task-move/`（判定轮，含 erratum——`locked-deny-lines` 噪声误计的勘误）、`20260922-174125-task-move-collect/`（fixture 采集轮 + 事务号实测） |


人工检查点（票 #7 起由脚本记录到 `docs/poc-logs/<session>/photo-checkpoints.md`，照片放 `docs/poc-logs/manual-photos/`）：
①Dashboard 首次上屏 ②锁屏后背屏 30s/5min ③AOD 抢回瞬间。票 #7 三个拍点都到了，用户人眼验收为「锁屏后是小米原生背屏」（照片未留档）。

## 票 #17 验收：shell uid 覆盖窗口探针（SH-UID，2026-09-22 实测）

链路：`tools\ex\ex.ps1 -Task shuid-overlay`（`10-shuid-overlay.ps1`）一条命令 = javac + d8 现场构建设备侧探针
（`tools/ex/device/shuid-overlay/ShuidOverlayProbe.java`）→ 推 dex 到 `/data/local/tmp/` → **对照组**（同一份代码、
同一次运行：加窗到 display 0）→ 一次性加/撤（`remove` 模式）→ **背屏加窗**（问题本体）→ register 检查段（固定最后跑）
→ 设备事实判定 → 归档 + `summary.md`。探针经 `app_process` 以 **shell uid 2000** 跑；窗口胶水按「窗口上下文（E9 同款）
→ display 上下文 → 无定向」三策略依次尝试、逐条记录，窗口标题 `RearCueShUidProbe`；全程**不装 APK、不按 KEYCODE_POWER**
（手机保持解锁——下一票依赖；`stay_on_while_plugged_in` 跑前设置、跑后恢复，判定轮 before/after = 15/15）。
证据目录：`poc-logs/20260922-193237-shuid-overlay/`（判定轮，含 summary/scenario-notes）、`20260922-192356-shuid-collect/`
（fixture 采集轮）、`20260922-192941-shuid-overlay/`（工具性误判轮，见其 erratum）、`20260922-185749 / 190505 / 191525 /
191803-shuid-collect/`（bring-up 采集轮，各留 erratum）。

**结论先说**：**SH-UID-WINDOW-INCONCLUSIVE——对照组（display 0）也没加上，无法归因背屏门**；
对票 #12 重开条件② 的答案是**部分验证**：`app_process` 裸进程这条路径**不翻案**（且死得比预想更早），但票面点名的
**真 Shizuku UserService 路径本票未验**（见下方偏差①），口子没有全关。shell uid 窗口死在比背屏门更早的一道墙上，
E9 的 `Not allow non-system app ... add system_window on rear display` **根本没到**：

- **窗口注册墙（设备事实，逐条原文）**：uid 2000 的 `app_process` 进程向 display 0 与背屏加 `TYPE_APPLICATION_OVERLAY`
  全部被拒，三策略逐条尝试、同一原因（判定轮 rear 段探针自身输出，pid=24571）：
  ```
  probe: attempt strategy=window-context failed reason=java.lang.IllegalStateException: Unknown pid=24571 uid=2000
  probe: attempt strategy=display-context failed reason=java.lang.IllegalStateException: Unknown pid=24571 uid=2000
  probe: attempt strategy=plain failed reason=java.lang.IllegalStateException: Unknown pid=24571 uid=2000
  ```
  系统侧同链（`logcat-shuid-rear-system.txt` 原文）：
  ```
  09-22 19:32:52.098  5157  9703 W WindowManager: attachWindowContextToDisplayArea: calling from non-existing process pid=24571 uid=2000
  09-22 19:32:52.103  5157  7043 E WindowManager: Window Manager Crash java.lang.IllegalStateException: Unknown pid=24571 uid=2000
  ```
  display 0 对照段（pid=24432）逐字同形态 ⇒ 失败与背屏无关，卡在「进程不在 ATMS 进程表」这一层。
- **注册墙（想注册也不行）**：app attach 握手（`ActivityThread.attach(false)` → `attachApplication`）对未注册的
  shell 进程不是注册而是**直接杀进程**——register 段输出停在 `probe: register attempt method=attachApplication`
  （无 `probe: done`，进程消失；前台复跑时 shell 侧原文 `Killed`）。「被认作系统应用」无从谈起。
- **dumpsys 事实**：各阶段持窗快照 `dumpsys window windows` 均无 `RearCueShUidProbe`（判定行 `control : found=False` /
  `rear : found=False`；`mDisplayId`/`ty=`/归属 uid 因窗口不存在而无值，如实报）。
- **对票 #12 重开条件② 的直接答案：部分验证**——**裸进程（`app_process`）路径不翻案**：「shell uid 加窗被认作系统应用」
  在该路径上不成立，窗口在到达背屏窗口策略之前就进不了窗口系统。**但真 Shizuku UserService 进程未实测**（票面点名的是
  UserService；本票探针是同 uid 的 `app_process` 裸进程，偏差①）——它是否也在 ATMS 进程表外、吃同一道注册墙，只能靠
  「改应用 debug UserService + 重装 APK」的另一条小实验作答。因此重开条件②**不能勾掉**，只能收窄为「真 UserService 路径
  待验」。条件若变（构建放行未注册进程 / 真 UserService 注册成功），`ex.ps1 -Task shuid-overlay` 一键复跑即可按同一词表
  作答（PASS/BLOCKED 分支、E9 拒绝行引用均已就位）。

**票 #17 收口补测（2026-09-22，`poc-logs/20260922-2315-usvc-proc/`）：条件② 再收窄一次。** 真 Shizuku UserService
进程（`com.rearcue.poc:shizuku`，`ps -A` 行 `shell  9662  1  ...  com.rearcue.poc:shizuku`，`/proc/9662/cmdline` =
`com.rearcue.poc:shizuku`）**不在 ATMS 进程表内**：`dumpsys activity processes` 对该 pid 零匹配（表内唯一 RearCue
记录 `*APP* UID 10333 ProcessRecord{... 9824:com.rearcue.poc/u0a333}` 属应用 uid）。机制推断（AOSP 语义、非设备事实）：
上文注册墙 `calling from non-existing process` 查的正是 ATMS 进程表成员资格、与 spawn 路径无关——真 UserService 与裸
`app_process` 在墙前同类；且墙对 display 0 与背屏一样挡（220336 轮对照臂同墙），这条路根本走不到背屏策略面前。
**票 #12 重开条件② 据此收窄为：除非构建放行未注册进程的 windowContext 注册（= 条件① 的口径），条件② 单独不再构成
翻案依据**；真要翻案，最小实验仍是「真 UserService 进程内同款 addView + display-0 对照」（需 AIDL 加方法 + 重装 APK +
`USER_SERVICE_VERSION`+1）。判定词维持 `SH-UID-WINDOW-INCONCLUSIVE`（词表不改、不超证据）。

**与票面写法的偏差（如实记录）**：①「经 Shizuku UserService」字面语义未走——探针是 `adb shell app_process`，与
Shizuku UserService **同 uid 身份（2000）**，binder 链路（app → Shizuku → UserService）未实测（票 #16 同款等价边界）；
真 UserService 的进程是否在 ATMS 注册表中，本票**未实测**（备选 (b) 要改应用 + 重装 APK，被本票约束 7 禁止）。②验收词
预期 PASS/BLOCKED 二选一，实际落在词表预定义的 `SH-UID-WINDOW-INCONCLUSIVE`（对照组失败），不硬判。③AC① 的
「引用系统侧日志原文」以「进程注册墙」原文兑现——背屏门拒绝行不存在（门未被走到），这本身就是设备事实。

失败条件词表（`10-shuid-overlay.ps1` 一键判定）：

- `SH-UID-WINDOW-PASS` — 背屏加窗成功且 `dumpsys window windows` 可见（词表就位，本机不可达）。
- `SH-UID-WINDOW-BLOCKED` — 被背屏窗口策略拒，verdict 引系统侧原文（`Not allow non-system app ...` 分支就位、以票 #10
  的 E9 拒绝行做回归 fixture；本票未触发——那道门没到）。
- `SH-UID-WINDOW-INCONCLUSIVE` — 对照组 display 0 也失败 / 无任何拒绝事实可归因背屏门（**本轮实测**）。
- `SH-UID-RUN-INVALID` — 外部污染或探针自身故障（词表就位；192941 轮的 RUN-INVALID 是脚本工具性误判，见其 erratum）。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 判定 SH-UID-WINDOW-PASS / SH-UID-WINDOW-BLOCKED，引用系统侧日志原文（设备事实判定） | `20260922-193237-shuid-overlay/shuid-overlay.txt` 的 `sh-uid` 行 = SH-UID-WINDOW-INCONCLUSIVE（词表预定义的对照组失败分支，不硬判）；系统侧原文引进程注册墙两行（上文），背屏门拒绝行不存在 = 门未被走到；判定读 dumpsys 窗口段 + 系统行 + 探针输出，命令退出码从不参与 | ⚠️ **票面两词未兑现**（code review 抓出）：设备事实落到第三词 INCONCLUSIVE，按「不硬判」纪律不能强选 PASS/BLOCKED——判定词表与判定链兑现、验收词未兑现，如实记偏差 |
| ② findings 票 #12 重开条件② 标记已验（含结论） | 「票 #12 验收」重开条件 2 追记：**部分验证**（裸进程路径不翻案 / 真 UserService 未验）+ 结论与证据指向 | ⚠️ 以「部分验证」代替「已验」（code review 抓出结论越界）：被测进程类别与条件②点名的不一致，按设备事实不能勾掉，条件收窄为「真 UserService 路径待验」 |
| ③ session 归档 + summary.md | 7 个 session 目录：判定轮 2 个含 `summary.md`/`scenario-notes.md`；4 个 bring-up 采集轮各留 `erratum.md`；fixture 采集轮 192356 无 erratum（无勘误可记）；**review 后补记**：5 个 `-RawOnly` 采集轮的 `summary.md` 于收口时补齐（标注补记，原始产物不动） | ✅（summary 补记见各目录头部说明） |

本轮踩到并已修的点：

- **`,$arr` + `@()` 包装陷阱又咬一口**：`@()` 包住 `Get-ExWakePollution` 的 `,$arr` 返回值，**空**结果变成「含空数组的
  单元素数组」，`.Count` 读成 1 ⇒ 192941 轮凭空判出「1 external hit(s)」外部污染、判定误落 RUN-INVALID（该轮 erratum
  已勘误：三个污染锚 0 命中）。修法：不包 `@()`，与 `09-task-move.ps1` 用法对齐。
- **if 表达式分支经管道边界会把空数组展平成 $null**：`Get-ExRearCueSummary` 的 `$lines = if (...) {...} else {@(Get-ExLogcat)}`
  在应用日志为空的运行里绑定报 `Cannot bind argument ... it is null`；改经 List 收集。
- **`ActivityThread.attach(false)` 会害死调用进程**：对 ATMS 未注册的 shell 进程，`attachApplication` 换来的不是注册
  而是 SIGKILL（191525/191803 两轮三段探针全灭在 `probe: context ok` 之后；前台复跑 shell 侧原文 `Killed`）——register
  探针因此固定放**最后一步**、以 `<tryRegister> on` 显式开启，窗口事实先产出。
- **app_process 的 VM 打完 `probe: done` 不退出**（binder 线程吊着，`pidof app_process` 残留可见）：探针 `main` 尾部强制
  `System.exit(0)`，场景开跑前按 cmdline 清 stray 探针进程。
- **SYSTEM_ALERT_WINDOW / MIUIOP 授权对这道墙毫无作用**：给 `com.android.shell` / `android` / `com.rearcue.poc` 全部
  置 allow 后拒绝行一字不差（bring-up 诊断）——appops 按应用包计，与 uid 2000 的裸进程无关（与票 #10 已知事实一致）。
  **设备状态遗留（如实记录）**：这三个包的 SYSTEM_ALERT_WINDOW appops 仍是 allow（bring-up 置上后未恢复；该拒绝路径
  与 allow/deny 无关，恢复动作反而可能动到 #10 未授权对照轮的基线，故不回改）；`stay_on_while_plugged_in` 前后 15/15 已恢复。
- **真机块改名≠fixture（code review 抓出）**：初版把票 #10 的 E9 窗口块 `-replace` 标题后当正面 fixture 用——这属于
  「造假数据」；已改回**真名真块**（`-Title 'RearCueOverlayProbe'` 直接解析真机原文），唯一拿不到真机态的展示分支
  （`mDisplayId=1`）明确标注「SYNTHETIC EDGE STATE -- NOT a fixture」。合成行只许覆盖真机输出没有的边缘态，且必须标注。

## 票 #18 验收：锁屏首投事务探针（E14，2026-09-22 实测）

链路：`tools/ex ex.ps1 -Task task-move`（`09-task-move.ps1`）一条命令 = **解锁态**准备 Dashboard 任务（E1 链）→ 对照组（解锁态跑锁屏段的**同一条**事务：C0 移出背屏、C1 移回背屏）→ 摆位 `t12988@d0` → `KEYCODE_POWER` 上锁（设备时钟打点）→ 锁屏稳态事务搬运 + 逐点采样（任务归属 / 背屏 owner / 背屏 state / 应用最后一条日志）→ rear-awake 变体（E12 机制先唤醒背屏再搬）→ 归档。事务 = `service call activity_task 51 i32 <rootTaskId> i32 <displayId>`（moveRootTaskToDisplay）。证据目录：`poc-logs/20260922-181414-task-move/`（判定轮，含 summary/scenario-notes/erratum）、`20260922-174125-task-move-collect/`（fixture 采集轮 + 事务号实测）。

**结论先说**：**E14-PASS——锁屏稳态下任务搬运事务能把 Dashboard 任务真的搬上背屏**。与 `am start --display`（锁屏稳态必被 `ActivityStarterImpl: rearDisplay check locked -> deny`）相反，这条事务在锁屏稳态**没有**吃到任何 task-move 相关拒绝行，落位事实为证：

- **对照组排除「事务号/用法错误」**：C0（移出背屏）、C1（= 锁屏段完全相同的命令）解锁态都真的动了任务栈 ⇒ 事务号 51 与参数序 `<taskId> <displayId>` 正确。
- **锁屏稳态落位**：锁前 `t12988@d0` → A3 事务后 +25s 采样 `t12988@d1 owner=dashboard rear=ON/ON`，`task-end-placement : d1`、`rear-end-state : ON/ON`；B2（rear-awake 变体）+49s 同样落上；A2/B1 反向（搬到 display 0）同样生效。`service call` 回执只存证、不参与判定。
- **拒绝行为 0**：锁屏段全程没有 `rearDisplay check locked` / `aborted activity` / `Permission Denial` 形态的 task-move 拒绝行（归档 verdict 行 `locked-deny-lines : 10` 是旧分类器把无关 `SettingsProvider`/`Java_Reflection` SecurityException 噪声误计进 deny 桶，见该轮 `erratum.md`；分类器锚已收紧，噪声行做回归 fixture）。
- **本轮撞出的反驳性事实（code review 抓出，直接影响 E3 前提与 #20）**：锁屏瞬间应用的信号重投**被放行、且完全不需要事务**——上锁（设备时钟 18:14:41）后 1.7s 应用日志（`logcat-rearcue.txt` 原文）：
  ```
  09-22 18:14:42.751 21654 21654 I RearCue : 投送确认：Dashboard 已在屏 displayId=1
  09-22 18:14:42.751 21654 21654 I RearCue : 背屏信号 miui.intent.action.SUB_SCREEN_OFF → LaunchDashboard(1)
  ```
  于是 A0 段 +2s 起任务就已经在背屏（`task=t12988@d1 owner=dashboard`）——`task-on-rear-samples : first=+2s` 就是这么来的，**不是事务落的位**；事务的落位证据是 A2→A3 与 B1→B2（锁前 `t12988@d0` → 事务 → +25s/+49s 才回到背屏）。这条事实把「锁屏瞬间重投必被 BAL 拦」（E3 的解释之一）再次打成**时序竞态**：票 #6 第三轮见过「锁屏窗口内重投被 allow」、票 #7 三轮见的是 BAL 拦截，本票证明窗口内重投**可以**无人工干预成功——#20 go/no-go 必须把这条算进「锁屏首投」的可行性评估。
- **事务号是实测定的，不是抄的**：MRSS/REAREye 记录的 **code 50 在本 Android 16 构建是静默空操作**（回执像成功、落位不动，采集轮 raw-11/raw-13）；对一次性可弃任务扫码（raw-14）得 **code 51** 才是 moveRootTaskToDisplay，双向验证后才用到 Dashboard 任务。`cmd activity display move-stack` 是本机真搬运的地面真值。
- **机制事实（设备日志）**：任务搬运会 destroy+recreate 被搬的 Activity（`Dashboard detach 实例数=0` → `RearDashboardActivity onCreate display=<新屏>`），task id 跨搬运稳定 ⇒ 落位按 task id 判读，不按实例数。

失败条件词表（`09-task-move.ps1` 一键判定）：

- `E14-PASS` — 锁屏稳态事务后任务真的落在背屏（本轮实测）。
- `E14-REJECTED` — 被系统拒绝，verdict 内引用拒绝日志原文（词表就位，本机未触发）。
- `E14-TXN-BROKEN` — 对照组解锁态同一事务也失败 ⇒ 事务号/用法错误，锁屏结论无从判定（防「假阴性」；此情形手机不会被锁上）。
- `E14-NO-TASK` — 锁屏前没有可用的 Dashboard 任务（运行前告警，不硬跑）。
- `E14-TASK-GONE` — 任务在锁屏事务执行前就被系统收走（判据：采样里 `task=absent`）。
- `E14-NO-EFFECT` — 事务跑了但任务始终没到背屏、且无任何拒绝行（无从归因，如实报）。
- `E14-RUN-INVALID` — 外部污染（指纹唤醒 / 人按电源 / 碰手机）→ 该轮判定作废、样本单列 `erratum.md` 重跑干净轮（本轮 `pollution : none detected`）。

**与票面写法的偏差（环境/安全所迫，如实记录）**：本机是安全锁屏（PIN/指纹），adb 解不开，`KEYCODE_POWER` 上锁后手机会留在锁屏态等人来解锁——因此实验是**一次性**的：所有需要解锁态的段（准备、对照组）都在上锁前跑完，prep/对照失败就在**按电源键之前**停（手机保持可用）；跑完手机留在锁屏是预期。事务号扫描只对**一次性可弃任务**（本应用 debug MainActivity 任务）下手，防误伤 Dashboard 任务；`-TxnCode`/`-ObserveSeconds`/`-SampleSeconds`/`-SettleSeconds`/`-NoRearWake` 全部参数化。两个票外能力的取舍：**rear-awake 变体（Phase B）**把「目标屏没电」与「锁屏门拒」分开归因（一次 E12 式唤醒键 + 重复事务，`-NoRearWake` 可关）；**事务号自动扫码**只在 `-TxnCode` 不动任务时触发、只对可弃任务发未知事务——两者都是为了「假阴性/假阳性不进结论」，不承载判定语义。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 判定 E14-PASS / E14-REJECTED（含拒绝日志原文），设备事实判定 | `e14-task-move.txt` 的 `e14` 行 = E14-PASS（引用 +25s 采样原文与落位）；落位判读 `dumpsys activity activities` 的 task placement，拒绝行引系统日志原文；`service call` 回执只存证 | ✅ |
| ② 对照组：解锁态同一事务应成功，排除事务号/用法错误 | C0 + C1（C1 = 锁屏段的同一条命令）解锁态均落位成功；事务号 51 的实测过程在采集轮 raw-14/raw-15 | ✅ |
| ③ session 归档 + summary.md；findings 记录结论与失败条件 | `20260922-181414-task-move/`（summary/scenario-notes/erratum）+ `20260922-174125-task-move-collect/`（summary 补记 + collection-notes）+ 本节 + 矩阵 E14 行 | ✅ |

本轮踩到并已修的点：

- **事务号会漂移，回执不判**：MRSS 的 code 50 在本构建是**静默空操作**（Parcel 回执照样像成功）——「service call 没报错」和「事务生效」是两回事，必须用落位事实核实事务号（扫码只对可弃任务下手）。
- **拒绝行分类器会被噪声灌爆**：无关的 `SettingsProvider`/`Java_Reflection` SecurityException、MIUI `updateSignalInfo not allowed` 都带 deny 形态词，旧锚把它们算进 `locked-deny-lines`（10 条全噪声）；锚收紧为上下文相关的 `SecurityException`/`Permission Denial`、`Not allow non-system app`、`not allow ... rear display`，本轮噪声行做回归 fixture（`logcat-task-move-noise.txt`）。
- **`'...' -f $a, $b` 直接当 .NET 方法实参会被解析成两个实参**（`Add(('...' -f $a), $b)`）→ FormatError 只丢那一行、流程继续（scenario-notes 少一段，erratum 已补原文）；写盘处的格式化表达式一律**括成单个实参**再传（本票修法就是加括号，参数仍是内联表达式）。
- **PowerShell 逗号优先级老坑再现**（票 #16 同款）：`'a' + $x + 'b', $y` 把注释行与回执压成一行（采集轮 raw-21/22，文件保持原样，fixture 逐字提取不受影响）。

## 票 #16 验收：唤醒保活探针（E12，2026-09-22 实测）

链路：`tools/ex ex.ps1 -Task wake-keepalive` 一条命令 = 同一 session 两段同协议对照——**基线段**（不开保活：`KEYCODE_POWER` 上锁 + 逐点采样背屏/主屏 state）→ **保活段**（设备侧 sh 循环注入定向背屏的唤醒键 `input -d 1 keyevent KEYCODE_WAKEUP`，间隔 = `-WakeIntervalMs` 参数，循环连续跨过锁屏时刻 → 同样时长逐点采样）→ 判定 + 归档 + `summary.md`。注入循环的起停取舍记在每轮 session 的 `summary.md`（scenario notes）：取 **adb shell 后台 sh 循环**（`nohup sh /data/local/tmp/wake-keepalive.sh` 起、stop 文件停，与 Shizuku UserService 同为 shell uid 2000 身份），不用应用侧 UserService——E12 是纯显示电源问题，不需要应用/ binder 生命周期，PC 只做起停与采样。**本探针不装应用、不依赖 Shizuku 进程**。

**结论先说**：**「周期注入定向背屏的唤醒键能让背屏锁屏后保持点亮」成立（E12-PASS）**——500ms 间隔两轮复现（163828 / 164319：保活段 60s 观察窗内背屏全程 ON；同轮对照组 +4s / +7s 离开 ON、结束 `DOZE_SUSPEND/DOZE_SUSPEND`），5000ms 间隔同样全程 ON（164628）；**间隔放到 30000ms 则守不住**（165022：+6s 照旧离开 ON、+26s 被针拉回 ON ⇒ `E12-LOST` / `E12-IGNORED`）。代价事实：注入是显示定向的，三轮保活段**主屏全程 OFF**（无连带点亮副作用）。

1. **机制链路（每环都有设备日志，主轮 163828 原文）**：锁屏真的断掉背屏组 → 257ms 后第一针唤醒键把它重新叫醒 → 此后整窗再无 `Waking up` 行（组未再睡，与采样全程 ON 一致）：
   ```
   09-22 16:39:55.891  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000, ...)
   09-22 16:39:56.148 5157 10112 I PowerGroup: Waking up power group from Dozing (groupId=1, uid=1000, reason=WAKE_REASON_WAKE_KEY, details=android.policy:KEY)...
   ```
   注入循环「真的在跑」是设备事实三重证据（`inject-running : True (ticks-in-window=116, expected~120, input-errors=0, ps-seen=True, wake-key-traces=1)`）：设备侧 tick 日志逐针记录 `input` 退出码、`ps -A -o PID,NAME,args` 里可见 `sh /data/local/tmp/wake-keepalive.sh` 循环进程、系统日志有唤醒键到达电源层的行。
2. **对照组排除「本来就不熄」**：同轮、同协议、只差注入的基线段在三轮干净轮分别 +4s / +7s / +6s 离开 ON，`never ON again, ending DOZE_SUSPEND/DOZE_SUSPEND`——与票 #11 的「+5s 离开 ON、+10s DOZE_SUSPEND」同量级（本轮 2s 采样粒度、无 Dashboard 在屏前提）。`baseline-valid : True`，两段的锁也都经 `Powering off display group ... (groupId= 1` 核实。
3. **定向性 / 代价**：保活段全程 `main=OFF/OFF`（`main-side-effect : main display stayed dark`），窗内无 groupId=0 的 `WAKE_REASON_WAKE_KEY` 行——`input -d 1` 只作用于背屏组；对照：不带 `-d 1` 的 `KEYCODE_WAKEUP` 唤醒的是 groupId=0（同轮 `16:39:41.120` 行）。
4. **失败条件词表**（`08-wake-keepalive.ps1` 一键判定；离开 ON 的细分在 `e12-class` 行）：
   - `E12-PASS` — 观察窗内背屏未离开 ON（500ms×2 / 5000ms 三轮实测）。
   - `E12-LOST` + `E12-IGNORED` — 离开 ON 时刻与对照组一致（±2 个采样间隔）：保活在该间隔下没拦住熄屏（30000ms 轮实测，+6s 照旧离开）。
   - `E12-LOST` + `E12-DELAYED-ONLY` — 离开 ON 显著后移但窗内仍离开：只延迟熄屏（词表就位，本轮各间隔未触发，留给后续调参直接作答）。
   - `E12-INJECT-FAILED` — 注入循环没跑起来 / `input` 报错 / 窗内针数 <50% 期望（判据 = tick 日志 + ps 快照，不看命令退出码）。
   - `E12-NO-BASELINE` — 对照组基线不成立（背屏本就不离开 ON，E12 无从判定）。
   - 外部污染（指纹唤醒 / 人按电源等）→ 该轮判定作废、样本单列 `erratum.md`（162618 轮实测：`FINGERPRINT:UnlockFinishT` ×2 + `lastUserActivityEvent=touch` 的 power_button；判据锚 `android.policy:FINGERPRINT` 与「不是自记按压时刻的 power_button 转换」）。
5. **留给 E13**：保活生效时 Dashboard 能否守住是 E13 的问题——本探针只判显示电源状态，未投 Dashboard；30000ms 轮顺带显示「离开 ON 后被针拉回」的恢复路径存在（+26s 回 ON），E13 的重投时序可以利用它。

**与票面写法的偏差（环境所迫，如实记录）**：约束 7 的「两段之间解锁回稳态」在本机不可执行——该机是**安全锁屏（PIN/指纹）**，adb 解不开（`wm dismiss-keyguard` 无效、uiautomator 对锁屏返回 null root，票 #7 同记），且本轮跑票全程手机处于锁屏稳态（开跑前预检把手机锁上了，见报告）。两段的复位因此降级为「唤醒 + dismiss 尝试 + settle + 要求背屏 ON 起步」，且**两段同协议**（同一起点：keyguard up、背屏 ON、`KEYCODE_POWER` 上锁），同轮对照有效性不受影响；绝对基线与「从未锁稳态起步」的运行可能有细微差别（本轮 +4~7s 离开 ON，与票 #11 的 +5s 同量级）。另外 `-Task wake-keepalive` **不跑 `01-install`**（E12 判定不需要应用，也避开了 MIUI USB 安装弹窗的人工风险），E13 若要带 Dashboard 上屏再恢复安装步骤。

**注入通道与票面「经 Shizuku」的偏差边界（code review 抓出，影响 #21）**：本探针的注入循环跑在
adb shell 的 shell uid 2000 上，与 Shizuku UserService **同 uid 身份**，但「经 Shizuku 循环注入」的字面链路
（binder → UserService → 注入）**未实测**——等价性只在 uid 层面成立。E12 的结论（唤醒键能保活背屏）不依赖
注入通道，但 #21 若把保活循环落进产品，必须改经 Shizuku UserService 实跑，并用 `ex.ps1 -Task wake-keepalive`
同口径复测一次，把通道差异从「推断等价」变成「实测等价」。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 一键产出 E12-PASS / E12-LOST 判定，依据是背屏 state 采样等设备事实 | `ex.ps1 -Task wake-keepalive`；各轮 `e12-wake-keepalive.txt` 的 `e12` 行（E12-PASS ×3 / E12-LOST ×1，全部引用 `dumpsys display` 采样时刻、PowerGroup 行与 tick 计数；命令退出码从不参与判定） | ✅ |
| ② 同轮含不开保活对照组（复现 +5s 离开 ON、+10s DOZE_SUSPEND 量级） | 每轮 `e12-samples-baseline.txt`（+4s / +7s / +6s 离开 ON → `DOZE_SUSPEND/DOZE_SUSPEND`，同轮同协议）+ `baseline-valid : True` | ✅ |
| ③ 新增纯解析函数配 Pester fixture（fixture 取自真机输出） | `ExCommon.psm1` 新增 `Get-ExWakeSampleFacts` / `Format-ExWakeSampleLine` / `Get-ExWakeTickFacts` / `Get-ExPowerGroupEvents` / `Get-ExWakePollution`；新增 19 例 Pester（红→绿）；fixture 6 份全部真机原文（采集轮 20260922-155740-wake-collect ×4、票 #11 首轮归档切回 ×1、污染轮 20260922-162618 ×1） | ✅ |
| ④ session 归档 + summary.md；findings 记结论与失败条件 | 7 个 session 目录（4 实验轮 + 污染轮 + fixture 采集轮 + aborted 轮；三个非实验/作废轮各留 `erratum.md`）；每轮 `summary.md` 含判定块与 scenario notes（含循环起停取舍）；本节结论 + 失败条件词表 + 矩阵 E12 行 | ✅ |
| ⑤ 保活间隔是命令参数，不写死 | `-WakeIntervalMs`（`ex.ps1` 原样透传；500 / 5000 / 30000ms 三档实测，脚本逻辑内无固定间隔） | ✅ |

本轮踩到并已修的点：

- **logcat 缓冲回绕会吃掉 T0 标记**：注入循环 2 针/秒 ×60s 就足以把 main 缓冲冲出整个运行起点（162618 轮 `pc-e12-power-*` 标记整体消失 → `baseline-lock-poweroff: False` 的**工具性误判**，而设备事实其实齐全）。T0 与按压时刻改为设备侧 `date` 直读 + 循环 tick 文件，logcat 标记降级为旁证——判定的时间轴不再依赖 logcat 存活。
- **指纹唤醒的判据不止一个变体**：票 #11 记的 `FINGERPRINT:finishCallBack` 只是其一，本轮污染是 `FINGERPRINT:UnlockFinishT`；判据锚定 `android.policy:FINGERPRINT`，并把 power_button 转换（含 power-off）列为污染候选、按自记按压时刻 3s 归因（`Get-ExWakePollution` 两类 + 场景侧归因）。
- **机制措辞不进判定文案**：`e12-class` 初版写了「the wake key was ignored」，按约束改为只报时刻事实（165022 留 `erratum.md` 标注，判定词与数字不变）。
- **KEYCODE_POWER 是拨动键**：息屏时按它是「唤醒」不是「上锁」（采集轮保活段曾因此失真）；脚本在按压前后都校验 `mWakefulness`，必要时补按一次。
- **距离传感器遮挡会吃掉 `KEYCODE_WAKEUP`**：手机面朝下（测背屏的常态姿势）时 `BaseMiuiPhoneWindowManager: Going to sleep due to KEYCODE_WAKEUP/KEYCODE_DPAD_CENTER: proximity sensor too close` 立即回睡；复位兜底改用 KEYCODE_POWER 拨动唤醒，并留按压记录。
- **PowerShell 老坑的新变体**：①数组字面量里 `'a' + $x + 'b', 'c'`——逗号优先级高于 `+`，两段头部被拼成一行（采集轮 fixture 头部可见痕迹）；②空管道 `@($output)` 是含 `$null` 的单元素数组，`Invoke-Adb` 空输出会让下游 `[string[]]` 绑定报 "it is null"（已改为过滤 null）；③测试里用 `@()` 包住 `,$arr` 约定的返回值会把内层数组数成 1 个元素。
- **整个 .ps1 的括号笔误 = 整脚本不执行**，而 `ex.ps1` 的下一步照跑（162427 session 只剩 collect 产物，已留 erratum）；脚本落地前先用 `Parser::ParseFile` 做语法检查，本轮已固化进流程。
- **基线有效性判据不能挂在会回绕的日志上**（code review 抓出）：初版 `baselineValid` 还要求一条 PowerGroup logcat 行佐证上锁，而该缓冲在注入负载下会回绕——162618 轮因此把采样事实齐全的对照误判 `E12-NO-BASELINE`，判词自相矛盾（"control leg does not show the rear leaving ON" 与 "rear left ON at +6s" 同句并存）。已改为只认采样事实（`RearFirstNonOnSec` 有值即基线成立），PowerGroup 行降级为旁证；162618 归档不回改，在其 `erratum.md` 标注。


## 票 #12 验收：探针收口与 go/no-go（2026-09-22）

**决策（一句话）**：**no-go——覆盖窗口通道不做**；背屏指示的主路径仍是 Activity 通道（应用内投送为主、Shizuku 命令兜底），
spec 0002 设想的「覆盖窗口兜底/主通道」不落地，「锁屏闲置时背屏出指示」另立票在 Activity 通道上解。

**依据**（全部是设备事实，逐条见实验矩阵 E9/E10/E11 行与「票 #10」「票 #11」）：

- **E9 一票否决**：非系统应用的覆盖窗口在**未锁屏**这一最宽松前提下就加不上背屏——
  `WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display`；
  同代码、同授权的 display #0 对照加窗成功，排除权限/窗口类型/实现问题。三条失败条件全部实测在案。
- **E10/E11 被 E9 挡死、本机不可测**（E10/E11-BLOCKED-BY-E9）：窗口从未到达过背屏，「锁屏可见」「保活」无从谈起；
  spec 0002 依赖它们成立才落实现，前提直接不成立。
- **两轮同次运行内的 Activity 对照**（每次运行的锁屏段都带 Activity 通道）：锁屏后 1.3s（125426）/ 1.4s（125937）被系统收走——要解的问题是 Activity 通道的
  锁屏存活/锁屏首投，不是再造一条通道；覆盖窗口通道比 Activity 通道死得更早一级（加窗时刻就失败）。
- **spec 0002 的机制前提被证伪**：「覆盖窗口不走 `ActivityStarterImpl` 锁屏策略 ⇒ 锁屏闲置时也可能上屏」——
  它确实不走 Activity 的门，但它有自己的门（HyperOS 背屏窗口策略），而这道门只放行系统应用。

**与 spec 0002 实现决策的对齐**（收口后以什么为准）：

| spec 0002 实现决策 | 收口后 |
|---|---|
| 通道优先级：Activity 优先、覆盖窗口作兜底；若 E9–E11 证明更稳可提升为主通道 | 改为**仅 Activity 通道**：应用内 `setLaunchDisplayId` 为主、Shizuku 命令为兜底（票 #4/#8 现状不变）；覆盖窗口兜底取消，story 17「覆盖窗口不可用回落 Activity」退化为「只有 Activity」 |
| 权限（`SYSTEM_ALERT_WINDOW` + appops）/ 窗口参数 / Compose 渲染胶水 / Backend 窗口生命周期 | 全部不落地；`OverlayProbe` 与 `OVERLAY_ADD/REMOVE` 旁路只留在 debug 探针（`tools/ex` 复跑用），不进产品路径 |
| 保活：窗口级 `FLAG_KEEP_SCREEN_ON`（覆盖窗口） | 随通道作废；背屏保活仍以票 #6 的 Activity 窗口级声明为准（注意：该声明的锁屏存活票 #7 三轮未复现，见 E3/E6——保活现状不等于锁屏存活已解，后者另票） |
| 「失败即交付」 | 生效：交付物 = 探针脚本（`tools/ex` 的 `06-overlay.ps1` / `07-lock-compare.ps1` + `ExCommon` 解析层）+ findings 结论 + 失败条件，实现部分不做 |
| 术语：落地后在 CONTEXT.md 补「覆盖窗口通道」 | 通道不落地、不新增独立术语；行文简称「覆盖窗口通道」= Overlay Window 作投送通道的设想（已否决），已并入 CONTEXT.md 的 `Overlay Window` 词条 |

**no-go 重开条件**（什么条件变了可以重开，满足任一条即可重开本链，判定仍走设备事实）：

1. **HyperOS 背屏窗口策略放宽**：系统更新或新机型上，`ex.ps1 -Task overlay` 一键复跑显示
   `Not allow non-system app ... add system_window on rear display` 不再出现，且 `dumpsys window windows`
   里非系统应用的探针窗口真的落在背屏（判定词表已就位）。
2. **shell uid 加窗被认作系统应用**：票 #10 留的唯一未验证口子——以 Shizuku UserService（shell uid 2000）加窗是否绕过
   「non-system app」判定。当前判断是大概率不行（窗口归属调用进程 uid，2000 不是系统应用），且要在 UserService 进程里
   另写 WindowManager 胶水；要翻案先验这条。
   **【部分验证 · 票 #17 · 2026-09-22】裸进程路径不翻案，真 UserService 路径未验（条件②收窄、不勾掉）**——
   shell uid 窗口死得比预想更早：uid 2000 的 `app_process` **裸进程**对 display 0 与背屏的 `TYPE_APPLICATION_OVERLAY`
   加窗全部被 WMS 以
   `WindowManager: Window Manager Crash java.lang.IllegalStateException: Unknown pid=<pid> uid=2000` 拒绝
   （窗口上下文 / display 上下文 / 无定向三策略皆然），背屏的「non-system app」门**根本没到**；想给进程补注册
   （`ActivityThread.attach(false)` → `attachApplication`）则进程直接被杀。**但票面点名的 Shizuku UserService 本票没验**——
   探针是同 uid 的裸进程，真 UserService 是否也在 ATMS 进程表外未实测（code review 抓出的结论越界，已收窄）；
   「以 Shizuku UserService 加窗是否被认作系统应用」留待「改应用 debug UserService + 重装 APK」的小实验作答。
   本票词表判定 `SH-UID-WINDOW-INCONCLUSIVE`（对照组 display 0 同样失败，无法归因背屏门），
   证据 `poc-logs/20260922-193237-shuid-overlay/`，详见「票 #17 验收」。
3. **应用以系统身份发布**：拿到系统签名 / 预置成系统应用。与「不 Root」的产品约束冲突，仅当该约束改变时才成立。

**不构成重开条件**（E9 已实测排除）：MIUI「显示在其他应用上层」开关、`miui.rear.policy`、MIUIOP 数值授权——背屏 deny 与它们都无关。

**本票即终点**：#13（覆盖窗口最小闭环）/#14（锁屏兜底与降级）不开工，打 `wontfix` 标签并关闭；spec 0002 的主诉求
（锁屏闲置出指示）仍然成立，另行立票走 Activity 通道，不在本链内。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① E9/E10/E11 三行都有结论与失败条件，且指向原始证据目录 | 实验矩阵 E9/E10/E11 行（各含失败条件 + 证据目录指向）+「票 #10」「票 #11」两节的原文引用 | ✅ |
| ② 明确写下 go/no-go 与理由；no-go 列出「什么条件变了可以重开」 | 本节「决策」+「依据」四条 +「no-go 重开条件」三条 | ✅ no-go |
| ③ 结论与 spec 0002 的实现决策对齐（通道优先级若需调整，写清改成什么） | 本节「与 spec 0002 实现决策的对齐」表（通道优先级 → 仅 Activity 通道）+ spec 0002 顶部状态条 | ✅ |
| ④ no-go 时本票即终点，交付物 = 探针脚本 + findings 结论 | `tools/ex/06-overlay.ps1`、`tools/ex/07-lock-compare.ps1`（探针脚本，可一键复跑）+ 本节/矩阵/票 #10/#11（findings 结论）；#13/#14 不开工、打 `wontfix` 标签并关闭 | ✅ |

## 票 #10 验收：覆盖窗口准入探针（E9，2026-09-22 实测）

链路：`tools/ex ex.ps1 -Task overlay` 一条命令 = 安装（含 appops 授权）→ debug 广播 `OVERLAY_ADD` 加最小覆盖窗口 → 设备事实判定 → `OVERLAY_REMOVE` 撤窗口 → 采集归档（`-OverlayDisplayId` 可整链路指定别的屏做对照组）。探针实现只在 debug source set（`OverlayProbe`，`createWindowContext` + `TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN|KEEP_SCREEN_ON`），不承载 Dashboard 内容。证据目录：`poc-logs/20260922-123134-overlay/`（背屏，主）、`poc-logs/20260922-122827-overlay/`（display #0 对照）、`poc-logs/20260922-123244-overlay/`（未授权对照）、`poc-logs/20260922-124030-overlay/`（指定不存在屏 → 应用内失败对照）、`poc-logs/20260922-122608-overlay/`（首轮观察，脚本修复前，见其 `erratum.md`）。

**结论先说**：**「SYSTEM_ALERT_WINDOW 覆盖窗口上背屏」不成立——HyperOS 的背屏窗口策略只放行系统应用**，与本应用是否声明 `miui.rear.policy`、是否授予 `SYSTEM_ALERT_WINDOW`/MIUIOP 无关：

```
09-22 12:31:47.946  5157 10107 D WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display
09-22 12:31:47.949  6308  6308 W RearCue : overlay add failed reason=BadTokenException: Unable to add window android.view.ViewRootImpl$W@4b8f9f -- permission denied for window type 2038
```

三条失败条件全部实测在案（分类由 `06-overlay.ps1` 自动判定）：

| 条件 | 触发方式 | 判定 | 证据 |
|---|---|---|---|
| 未授权 | `appops set com.rearcue.poc SYSTEM_ALERT_WINDOW deny` | `E9-BLOCKED-PERMISSION`；应用侧明确报告 `overlay add failed reason=no-system-alert-window canDrawOverlays=false`，不是静默失败 | `123244-overlay/e9-overlay.txt` |
| 被系统拒绝 | `06-overlay.ps1 -DisplayId 99`（指定不存在的屏；授权正常、无背屏策略行） | `E9-SYSTEM-REJECTED`，`reason=no-display requested=99` 进应用日志 | `124030-overlay/e9-overlay.txt` |
| 被背屏策略挡 | 正常授权后向背屏加窗 | `E9-REAR-POLICY-BLOCKED`（系统行原文引用进 verdict）；窗口不出现，`dumpsys window windows` 无 `RearCueOverlayProbe` | `123134-overlay/e9-overlay.txt` |

**display #0 对照组排除了其它解释**：同一块代码、同一份授权，指到主屏（`-DisplayId 0`）就成功——`overlay added display=0`，`dumpsys window` 里出现 `Window{... u0 RearCueOverlayProbe} mDisplayId=0 ... ty=APPLICATION_OVERLAY appop=SYSTEM_ALERT_WINDOW`，撤窗后消失（`E9-PASS`）。且主屏上本就有微信 `FloatingWindow`（同 `APPLICATION_OVERLAY` 类型）常驻，证明第三方覆盖窗口在主屏完全可用——**deny 是背屏特有的窗口策略**，不是权限、不是窗口类型、不是本应用的实现问题。另：`appops set SYSTEM_ALERT_WINDOW allow` 会连带把 `MIUIOP(10033)` 置 allow（appops 输出可见），背屏照样 deny——MIUI 的悬浮窗开关不是这道门的钥匙。

对 spec 0002 的影响：E10（锁屏可见）/E11（保活）的前提「覆盖窗口能上背屏」在本机不成立，按 spec 的「失败即交付」处理——覆盖窗口通道不落地，主路径仍是 Activity 通道（解锁态可用，E1）。E10/E11 探针的实际收口与 Activity 通道锁屏对照见「票 #11 验收」。后续若要翻案，唯一未验证的口子是「以 shell uid（Shizuku UserService）加窗是否算 system app」——窗口属于调用进程的 uid，shell uid（2000）大概率同样不是「系统应用」，且 UserService 进程里跑 WindowManager 需要另写胶水，未实验。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 安装步骤自动授予 SYSTEM_ALERT_WINDOW（appops），未授权明确报告 | `01-install.ps1` 装完 `appops set ... allow` + `Get-ExOverlayPermission` 复核进产物（`01-install.txt` 的 `overlay-op` 行，授权路径实测三轮全 allow）；未授权路径：场景判 `E9-BLOCKED-PERMISSION`、应用侧 `canDrawOverlays=false` 明确进日志（`123244`）——安装脚本的 ERROR 分支在代码里，本轮未在真机触发（appops set 无法主动失败），以未授权对照轮证明「明确报告」语义 | ✅ |
| ② debug 旁路能加/撤最小覆盖窗口并指定背屏 | `DebugCommandReceiver` 新增 `OVERLAY_ADD`/`OVERLAY_REMOVE`（`--ei displayId` 可指定，缺省 = 运行时识别的背屏）；对照组实测加/撤成功 | ✅ |
| ③ 一条命令完成 加窗口→判定→撤窗口→采集归档 | `.\tools\ex\ex.ps1 -Task overlay`（本轮 12:31:34 一条命令跑完：安装 → 授权 → 加窗 → 判定 → 撤窗 → `summary.md`）；收口后 `-OverlayDisplayId <id>` 把同一条链路整链指到别的屏 | ✅ |
| ④ 判定来自设备事实，不是命令退出码 | 判定读 `dumpsys window windows`（窗口在不在目标屏）+ 系统侧 WindowManager 行（`Not allow non-system app ... rear display`）+ 应用日志的失败原因；广播退出码从未参与判定 | ✅ |
| ⑤ findings 记下 E9 结论（含三类失败条件）与证据目录 | 本节 + 实验矩阵 E9 行 + 五个 `poc-logs/*-overlay/` 目录（含两份 `erratum.md` 事后标注） | ✅ |

本轮踩到并已修的点：

- **`am broadcast` 的 int extra 必须用 `--ei`**：`Invoke-ExDebugAction` 原来对所有 extra 发 `--es`（字符串），接收端 `getIntExtra` 拿到默认值，`-DisplayId 0` 的对照组**静默退化成投背屏**（应用日志 `display=rear` 才暴露）。已按值类型选 `--ei/--es`，这类「参数没送到」比命令失败更隐蔽，值得记。
- **系统拒绝行不能当放行证据**：HyperOS 的策略行 `Not allow non-system app ... add system_window on rear display` **包含** `add system_window on rear display` 子串，解析器不做「Not allow」排除就会把 deny 记成 add 命中（首轮 `system-window-log: 1 hit(s)` 就是这个坑）。fixture 改为照抄真机行后测试锁死。
- **归档不回改、用 erratum 标注**：脚本在实验中途迭代时，已归档 session 的 verdict 文案可能出自旧版脚本（122827 的 PASS 文案写着 rear display、122608 的 verdict 是修复前分类）——原始输出一律不动，补 `erratum.md` 说明差异，findings 指向修复后 session 为准。


## 票 #11 验收：锁屏可见与保活探针（E10/E11）+ Activity 通道对照（2026-09-22 实测）

链路：`tools/ex ex.ps1 -Task overlay-lock` 一条命令 = 安装 → 授权 → Activity 基线（一条 shell 通知 → Dashboard 上背屏）→ `OVERLAY_ADD` 尝试加窗（E9 门槛）→ `log -t RearCue pc-lock-issued` 设备时钟打点 + KEYCODE_POWER 上锁 → 逐点采样（窗口在不在 / 背屏 state / 归属 / 应用最后一条日志）→ 解锁尝试 + 清理 → 采集归档。证据目录：`poc-logs/20260922-125937-overlay-lock/`（第二轮，主）、`poc-logs/20260922-125426-overlay-lock/`（首轮观察，脚本修复前，见其 `erratum.md`）。同一条命令跑出两轮，判定一致。

**与票面写法的偏差（有意为之）**：AC① 的「overlay 场景加锁屏段」落成了独立场景（`07-lock-compare.ps1` / `-Task overlay-lock`），没有塞进 `06-overlay.ps1`——06 保持 E9 准入探针单一职责，锁屏段复用同一条 `OVERLAY_ADD` 旁路与同一个 session 约定，AC⑤ 的一键复跑 / 新 session 目录不受影响。

**票面前提「在 E9 通过的前提下」在本机不成立**，按 spec 0002 的「失败即交付」收口：探针脚本 + findings 结论 + 失败条件，覆盖窗口通道不落地。三段独立结论：

1. **E10 锁屏后窗口存活：不可测（E10-BLOCKED-BY-E9）**。窗口在**未锁屏**时就被背屏窗口策略拒之门外（`WindowManager: Not allow non-system app ... add system_window on rear display`，票 #10），「锁屏态窗口被清掉」这个失败条件在本机**无从触发**——被清掉的从来不是覆盖窗口，是 Activity 通道的 Dashboard。具体日志（加窗时刻即失败，125937 原文）：
   ```
   09-22 12:59:56.204  5157  7125 D WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display
   09-22 12:59:56.206 12659 12659 W RearCue : overlay add failed reason=BadTokenException: ... permission denied for window type 2038
   ```
2. **E11 背屏保持 ON：不可测（E11-BLOCKED-BY-E9）**，但同轮记录了无窗口时的背屏事实：+5s 离开 ON（`DOZE`），+10s 起 `DOZE_SUSPEND` 到 +92s 观察结束未再 ON（与 E5 一致）。首轮（125426）的观察中途被**外部指纹唤醒**污染（打点后 +6.9s：`PowerGroup: Waking up power group from Dozing ... details=android.policy:FINGERPRINT:finishCallBack`，随后 `SCREEN_ON`/`SUB_SCREEN_ON` 信号触发重投、背屏随整机回到 ON）——其 +10s–+21s 采样是醒机后的观测，**不是锁屏态事实**，干净观察以 125937 为准。
3. **与 Activity 通道的差异（同一次运行内对照）**：Activity 通道锁屏后 **1.3s（125426）/ 1.4s（125937）**被收走——设备时钟：`pc-lock-issued`（12:59:59.882）→ 第一条 `Dashboard detach 实例数=0`（13:00:01.263）；系统侧同链：`PowerGroup: Powering off display group due to power_button (groupId= 1)`（13:00:00.265）→ `wm_finish_activity: [...RearDashboardActivity,remove-task]`（13:00:01.242）。落在票 #7 记的 1–5s 区间，复现成立。**差异一句话：覆盖窗口通道比 Activity 通道死得更早一级**——它的失败在加窗时刻（解锁态就被拒），Activity 通道能上屏、只是锁屏后 1–5s 被系统收走；在本机「锁屏闲置时背屏可见」两条通道都到不了，覆盖窗口通道连对照资格都没有。

失败条件汇总（`07-lock-compare.ps1` 判定词表，一键复跑即重现）：

- E10/E11：`BLOCKED-PERMISSION`（SYSTEM_ALERT_WINDOW 未授权）/ `BLOCKED-BY-E9`（窗口未达目标屏——本机恒真，verdict 内引用 deny 原文）/ `E10-CLEARED`（含消失时刻）/ `E11-LOST`（含离开 ON 时刻）/ `PASS`。后三类在本机不可达，留给门槛变化后的设备直接回答（翻案口子见「票 #10」末段）。
- Activity 对照：`ACT-REMOVED-IN <x.x>s`（打点→detach 的设备时钟差，毫秒级）/ `ACT-SURVIVED`（全程在屏，票 #6 行为）/ `ACT-NO-BASELINE`（锁前没上屏，运行前告警）。
- 「锁屏态窗口被清掉」的日志形态（本机只有 Activity 版，供后续比对）：`Dashboard detach 实例数=0`（应用侧）+ `PowerGroup: Powering off display group due to power_button (groupId= 1)` → `wm_finish_activity ... remove-task`（系统侧）。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① overlay 场景加锁屏段：上锁后逐点采样「窗口是否在、背屏 state、应用最后一条日志」 | `07-lock-compare.ps1` 采样循环；产物 `e10-samples.txt`（17 点 × 5s，逐点 `window=/owner=/rear=/last=`）+ `e10-e11-lock.txt` | ✅ |
| ② 同轮跑一次 Activity 通道锁屏对照，记录它多久被收走 | 同一次运行内：1.3s / 1.4s（设备时钟打点→detach），采样 +5s 归属丢失佐证 | ✅ |
| ③ 输出三段独立结论 | 本节三条 + `e10-e11-lock.txt` 的 `e10`/`e11`/`compare` 三行独立 verdict | ✅ |
| ④ findings 记下结论与失败条件（含「锁屏态窗口被清掉」的具体日志） | 本节（结论 / 失败条件词表 / 日志原文）+ 实验矩阵 E10/E11 行 | ✅ |
| ⑤ 实验可一键复跑，产物落到一个新的 session 目录 | `ex.ps1 -Task overlay-lock` 同一条命令跑出两个 session（125426 → 125937），每次新建 `poc-logs/<时间戳>-overlay-lock/` | ✅ |

本轮踩到并已修的点：

- **数组字面量里嵌 pipeline 不展平**：`@('a', ($x | ForEach-Object ...))` 里 pipeline 是**嵌套数组**，`Write-ExArtifact` 的 `[string[]]` 强转把整段日志 join 成一行（125426 的三个日志段同病，票 #10 五个 session 的 `e9-overlay.txt` 也是）。已改 `List.Add` 逐行写（06/07 都修），归档不回改、erratum 标注。
- **`,$samples.ToArray()` 包装陷阱**：pscustomobject 属性赋值再加逗号包一层，`Samples[-1]` 取到整个数组、成员访问被枚举展开，拼进字符串就是 `System.Object[]`（125937 的 e11 文案尾部）。已改扁平数组 + 回归测试锁死。
- **logcat pid/tid 是双空格**：剥前缀锚 `\S+ \S+ \d+ \d+ `（单空格）匹配不上，`last=` 字段带整条 logcat 头。锚改 `\s+` 系列。
- **判定文案不写机制猜测**：E11 文案初版写「followed the main screen」，同轮采样实际是「离开 ON → 回 ON → 再离开」——文案改为只报事实（何时离开 ON / 是否回 ON / 结束态），机制留给 findings 解释。
- **机制归因也要过设备事实**：findings 初版把 125426 的 +10s 回 ON 归因「锁窗重投」，review 用同一 session 的系统日志反驳（`FINGERPRINT:finishCallBack` 外部唤醒触发的重投，不是锁屏态行为）——findings 的机制解释同样受「设备事实判定」约束，已改写。


## 票 #8 验收：Shizuku 恢复后自动重投（2026-09-22 实测）

链路：Shizuku binder 上线（`addBinderReceivedListenerSticky`）→ `ShizukuShell` 报「兜底通道可用」→ `AppContainer` 喂
`DashboardEvent.FallbackAvailable` → `DashboardCore` 按当前 Icon Set 产出 `LaunchDashboard` → `RearDisplayBackend` 投送。
原始证据：`poc-logs/ticket8-e8-shizuku-recovery-evidence.txt`（含修复前的 Permission Denial 基线与逐条 logcat）。

**结论先说**：**「Shizuku 恢复后无需任何手动操作，当前 Icon Set 自动重投背屏」成立**；
票 #6 记的阻塞根因（server 由第三方 starter 以裸 shell uid 拉起）**是误判**——真因是本应用自己写错的两处声明，改完不需要人工启动 Shizuku。
**与票面「验证方法」的偏差（有意为之）**：票 #8 原本要求「以正常权限重装/重启 Shizuku（无线调试或 root）」再复跑；
查清真因后这一步**不需要**——本机的第三方 starter 拉起的 server 在 provider 权限改对后立刻可用（同一台设备、同一个 starter 复测），
所以没有重装 Shizuku，也没有引入新的启动方式。仍然需要人工的只有一处：首次在 Shizuku 授权框里放行本应用（一次性）。

1. **客户端 provider 权限写错**（`app/src/main/AndroidManifest.xml`）：`rikka.shizuku.ShizukuProvider` 用
   `moe.shizuku.manager.permission.API_V23` 保护。Shizuku server 跑在 shell uid（uid 2000）里，它把 binder 交回应用时**要打开这个 provider**，
   而 shell 不持有那个 dangerous 权限 → `ContentProviderHelper: Permission Denial: opening provider ... uid=2000 requires ... API_V23`。
   官方文档（Shizuku-API README「Acquire the Binder」）要求的是 `android.permission.INTERACT_ACROSS_USERS_FULL`（本机实测 shell `granted=true`）。
   改对后同一个 starter 拉起的 server 立刻交付 binder：`ShizukuProvider: binder received` → `Shizuku server 上线 server=true`。
   这一条编译期、单测、安装都不报错，**只有真机实验看得见**，所以补了 `ShizukuClientProviderManifestTest` 守契约。
2. **UserService 写成了 Android Service**（`rear/.../RearShellService.kt`）：Shizuku 的 UserService **必须是 `IBinder`**（继承 AIDL `Stub`），
   server 用 `app_process` 自己 new 这个类（README「UserService」提到得直白）。写成 `Service` 的实测症状是
   `ShizukuServiceStarter: ClassCastException: ... cannot be cast to android.os.IBinder` + `System.exit called, status: 1`——
   授权、`pingBinder()` 都正常，但 `userService=false` 永远绑不上，兜底命令一次也跑不了。改成 `IRearShell.Stub()` 并去掉 manifest 里那个
   假的 `<service>` 声明后：`UserService 已连接` + `ps` 里出现 shell uid 的 `com.rearcue.poc:shizuku` 进程。
3. **任务栈回读校验两边都不成立**（`RearProjectionCommands` / `RearProjectionVerifier`）：真机
   `dumpsys activity activities | grep -A2 'Display #1'` 只能捞到 Task 头（Dashboard 的 `topResumedActivity` 在第 3 行之后），
   而且 `ActivityRecord` 打的是**短名** `com.rearcue.poc/.rear.RearDashboardActivity`，全名 `pkg/pkg.rear.RearDashboardActivity` 根本不出现——
   于是「回读任务栈才算数」这条闸门恒判 false。窗口放宽到 `-A8`、匹配同时认全名与短名之后，`onDisplay=true（Shizuku 兜底）` 第一次出现。
   顺带把应用内确认的轮询也换成同一条带 grep 的命令：原先每次轮询都把整份 `dumpsys activity activities` 灌进 logcat
   （`ShizukuShell` 会把命令输出原样写进日志），修后同一场景 logcat 共 107 行。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 杀 Shizuku：不崩溃、监听照常、Dashboard 不受影响（不误判 Degrade） | `kill -9 <shizuku_server>` 后应用 pid 不变（21533）、全缓冲区无 FATAL/ANR；`Shizuku server 掉线` → `兜底通道掉线 → Dashboard 不受影响（应用内投送）`；`dumpsys` Display #1 仍是 `RearDashboardActivity` | ✅ |
| ② Shizuku 恢复后**无需手动操作**自动重投 | 恢复前状态：`state AppState(iconSet=[com.rearcue.poc], ... lastDetail=已退出（结束 1 个界面）)`、Display #1 是原生 SubScreenLauncher；`start-shizuku.sh` 拉起 server 后：`Shizuku server 上线 server=true granted=true` → `兜底通道恢复 → LaunchDashboard(1)` → `投送确认：RearDashboardActivity 已创建 displayId=1` → Display #1 回到 Dashboard（12:10:50.863 → .906，43ms） | ✅ |
| ③ 兜底通道**真的**跑通了命令（票 #4/#6 一直没跑通的那条） | 应用在后台时应用内投送被拦 → `sh [am start --display 1 --activity-reorder-to-front -n ...] exit=0` → `onDisplay=true（Shizuku 兜底，原因：应用内投送未获确认）`；任务栈里 `launchedFromUid=2000 launchedFromPackage=com.android.shell` | ✅ |
| ④ findings / 实验矩阵收口 | 本节 + 矩阵 E8 行 + `poc-logs/ticket8-e8-shizuku-recovery-evidence.txt` | ✅ |

本轮实现要点：

- **重投决策回到 `DashboardCore`**：新增 `DashboardEvent.FallbackAvailable`（授权成功 / server 上线各报一次），产出「按当前 Icon Set 幂等重投」。
  原实现把这套判断放在 `AppContainer.onFallbackChanged` 里，跟「决策在 core、Android 层只搬运」的约定不一致，也没法在 JVM 上测。
- **UserService 形状与 AIDL**：`IRearShell` 补上 Shizuku 保留事务 `destroy() = 16777114`（不实现会留下 shell uid 的僵尸进程），
  方法全部显式给 id（AIDL 要求 all-or-nothing）。
- **测试 fixture 要照抄真机输出**：校验器新增的单测用真机 `grep -A8 'Display #1'` 的逐行原文，老的合成 fixture 也改成真机的短组件名。
  之前那份合成 fixture 把组件名写成全名，正好把「真机只有短名」这个事实掩盖掉了——这类测试必须能红在真机上会红的点上。

## 票 #7 验收：PC 一键实验自动化 + findings 收口（2026-09-22 实测）

脚本集在 `tools/ex/`（用法见 `tools/ex/README.md`）：`ex.ps1` 一条命令 = 01 安装 → 02 授权 → 03 拉起 Shizuku →
04 驱动 E1/E7/E3 → 05 采集汇总；每步也能单独跑。判定不靠「命令没报错」，而是解析设备事实
（`dumpsys activity activities` 里 display #1 归谁 + 应用自己打的 logcat），解析层有 Pester 单测
（`.\tools\ex\ex.ps1 -Task selftest`，32 例，fixture 全部取自本机真机输出）。

| 验收标准 | 证据 | 结论 |
|---|---|---|
| ① 单命令完成 安装+授权+拉 Shizuku+发通知+采集 | `poc-logs/20260922-115044-all/`（`01-install.txt`、`02-authorize.txt`、`03-shizuku.txt`、`e1-drive.txt`、`e7-drive.txt`、`logcat-*.txt`、`dumpsys-*.txt`、`state.txt`、`summary.md`、`screenshots/`） | ✅ 一条 `.\tools\ex\ex.ps1 -Task all -Scenario e1,e7` 从头跑到尾：重装（自动点掉 MIUI 弹窗）→ 复授权 → 确保 Shizuku 在跑 → 两枚 Allowlist 通知自动上屏（E1 6/6）→ 撤通知自动下屏（E7 5/5）→ 采集汇总。锁屏那一步（E3）会锁手机，单独跑，证据在 `20260922-113847-probe-visible/` |
| ② E1–E8 矩阵全部有结论（含失败条件） | 上面「实验矩阵（E1–E8 收口）」 | ✅ E1/E2/E5/E7 为绿灯复现；E3/E6 本轮**未复现**票 #6 的锁屏存活，失败条件与日志已写进对应行；E4 的「信号→效果」复现、投送被同一原因拦下；E8 的「不崩溃 + 监听照常 + 可重启」复现，「恢复后自动重投」仍被 binder 权限阻塞 |
| ③ 3 个人工拍照点记录在案 | `poc-logs/<session>/photo-checkpoints.md`（三个拍点的时刻、应拍内容、目标文件名、当时的自动证据）+ `poc-logs/manual-photos/` | ⚠️ 三个拍点都走到了（①在 11:51 那轮，②③在 11:32 那轮，文件名与目录见 `manual-photos/README.md`）；**照片未留档**（用户 2026-09-22 确认「拍不了，人眼验收」），观察结论按人眼记录：锁屏后是小米原生背屏。spec 的 5 分钟锁屏观察本轮未重跑（票 #6 有 5 分钟实测），本轮最长 120s |

### 归档的原始证据（`docs/poc-logs/`）

| 目录 | 内容 |
|---|---|
| `20260922-115044-all/` | 一条命令全链路跑（安装 → 授权 → Shizuku → E1/E7 → 采集），含 `summary.md` 与主屏截图 |
| `20260922-111615-drive/` | E1 复现（`-HoldDashboard`，供拍照点①） |
| `20260922-111732-drive/` | E7 复现（撤通知 → 下屏 → 原生背屏） |
| `20260922-112432-drive/` | E3 锁屏观察（此时已用 adb 授 `MIUIOP 10020`；背屏手势顶掉 Dashboard 的采样也在这轮） |
| `20260922-113847-probe-visible/` | 锁屏探针：主屏可见时锁屏 → BAL 拦重投 + 背屏 display group 被断电（E3 失败条件） |
| `20260922-114327-e8/` | E8 终稿：杀/重启 Shizuku，应用不崩溃、监听照常 |

### 这次跑通的链路（session `20260922-115044-all`）

- E1：`cmd notification post`（com.android.shell）+ debug 广播 `POST_TEST` → `posted → LaunchDashboard(2)` →
  `project iconSet=[com.android.shell, com.rearcue.poc] -> 应用内投送已发出 displayId=1` → `Dashboard attach 实例数=1`
  → dumpsys `Display #1 topResumedActivity=com.rearcue.poc/.rear.RearDashboardActivity`（`e1-drive.txt` verdicts 6/6 True）。
- E7：`CANCEL_TEST` → `UpdateIconSet(1)`（不重投）；`CANCEL_PACKAGE com.android.shell` → `ExitDashboard` →
  `exit 结束在屏 Dashboard=1` → `Dashboard detach 实例数=0` → `Display #1` 回到 `com.xiaomi.subscreencenter/.SubScreenLauncher`
  （`e7-drive.txt` verdicts 5/5 True）。
- E8（session `20260922-114327-e8`）：`kill -9 shizuku_server`（pid 15924 → 空）→ 应用 pid 不变、无 FATAL/ANR，且期间仍处理通知
  （`removed com.rearcue.poc → ExitDashboard`）；`start-shizuku.sh` 重启后新 pid 16288 出现。

### 本轮踩到并已固化进脚本的环境坑

1. **MIUI 的 USB 安装确认弹窗**：`adb install` 会弹「USB安装提示 / 正在通过USB安装此应用，是否继续？」，
   8 秒不点就按拒绝处理，报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。
   脚本用 `uiautomator dump`（`exec-out ... /dev/tty` 一次往返）找到「继续安装」按钮并点掉
   （fixture：`tools/ex/tests/fixtures/ui-usb-install-dialog.xml`）。
2. **重装会清掉 MIUI 逐应用授权**：通知使用权、MIUI「锁屏显示」（数值 appop `10020`）都要在装完后重新授予。
   `MIUIOP 10020` 未授权时，锁屏瞬间系统直接 `wm_finish_activity ... remove-task`，日志伴生
   `ActivityRecordImpl: MIUILOG- Show when locked PermissionDenied pkg : com.rearcue.poc`；
   授权方式（本机实测有效）：`adb shell appops set com.rearcue.poc 10020 allow`（`appops get` 里显示为 `MIUIOP(10020)`）。
3. **MIUI 自启动没有 adb 接口**（`cmd appops set ... AUTO_START` 报 `Unknown operation string`）：force-stop 后
   系统拒绝重绑通知监听，脚本用 `cmd notification disallow_listener/allow_listener` 强制重绑兜底。
4. **安全锁屏 adb 解不开**（`wm dismiss-keyguard` / MENU 键都无效）：锁屏稳态下投送被
   `ActivityStarterImpl: rearDisplay check locked -> deny` 或 BAL 拦下，脚本在开跑前检查并告警，避免把系统策略记成应用缺陷。
5. **PowerShell 5.1 会把无 BOM 的 .ps1 当 GBK 解码**（与票 #4 的 AIDL/manifest 同一类坑）：脚本源码只用 ASCII，
   要匹配设备上的中文界面文字时用 `[regex]::Unescape('\uXXXX')` 运行时还原。
6. **背屏手势会顶掉 Dashboard 且应用不自动恢复**：11:24 那轮锁屏后约 1 秒，背屏上的触摸触发了
   `SubScreenCenter_GestureInputHelper ... triggerGesture ... startRecentAnimation, topApp: com.rearcue.poc`，
   原生 SubScreenLauncher 回到 display #1（该轮的系统日志当时没归档，只有 `112432-drive/e3-lock.txt` 的采样为证：
   界面 detach 后 owner 一直是 native）；应用侧只看到 `Dashboard detach 实例数=0`，没有任何信号可依。
   ⇒ 下一票可考虑：把「背屏界面是否还在」做成周期性/事件性自检（当前只在 Icon Set 变化时自愈）。

### E3/E6 未复现的差别（写给下一票）

票 #6 成功那轮的关键日志是 `WindowManager: Started waking up... (groupId=1 why=ON_BECAUSE_OF_APPLICATION)`
（应用窗口把背屏 group 唤醒并守住）；本轮三轮复跑拿到的是
`PowerGroup: Powering off display group due to power_button (groupId= 1, ...)`，即背屏 group 跟着主屏一起断电。
两者之间差的是什么还没定位，已知的两个候选：

- **BAL 时序**：锁屏瞬间的重投必须抢在系统把应用判为不可见之前（本轮 `Background activity launch blocked! ... callingUidHasVisibleActivity: false`，
  即使主屏上仍是 `com.rearcue.poc/.ui.MainActivity`）。票 #6 那轮恰好赢了这一毫秒级窗口。
- **MIUI 逐应用授权**：`MIUIOP 10020`/`10008` 等在本轮重装后被重置，虽然已用 adb 补授（10020=allow），
  但仍未复现；其余几个 MIUIOP（10008/10017/10021/10022/10045/10053）本轮也一并授过，同样没复现。

建议下一票专门做「锁屏窗口内的重投时序」实验（例如用 `SYSTEM_ALERT_WINDOW` 覆盖窗口绕过 BAL，spec 0001 的
Further Notes 里已把它列为候选出路），并把 MIUI 逐应用授权的完整清单固化成安装后置步骤。



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
| ④ 杀 Shizuku：应用不崩溃、监听照常；Shizuku 恢复后自动重投 | `kill -9 shizuku_server` 后应用进程 pid 不变、无 FATAL/ANR，且继续处理通知事件（`posted/removed com.android.shell tracked=36→35`）；重启 server（官方 `start-shizuku.sh`）后本应用仍 `pingBinder=false`（见下） | ✅ **前半**（不崩溃 + 监听照常）本回合成立；**后半**（恢复后自动重投）本回合无法验证 → **票 #8 补齐并实测通过**；`pingBinder=false` 的真因也随之查清：不是本机 server 的启动方式，而是本应用 manifest 里 provider 权限写错（本回合的判断有误，见「票 #8」） |

**第一轮的 `⛔` 样本为什么不成立**：那轮 Dashboard 是 shell 手工投的、核心 iconSet 为空（信号到达时应用侧决策是「无需重投」），
锁屏后 5s 内就被 AOD 顶掉。第三轮补上了关键一环——**锁屏瞬间的重投**（信号驱动、应用内投送、被 allow），
这才是保住背屏的原因；两轮的差别不是「系统允许与否」，而是「锁屏窗口内有没有重投 + 界面有没有 keep-screen-on」。

**E5 实测细节**：背屏**没有**独立的息屏倒计时——主屏一锁，subscreencenter 立刻把背屏交给 AOD（`Sub_Screen_Aod` wakelock，DOZE_SUSPEND），
所以「按实测间隔决定唤醒频率」这个前提不存在；保活只能做成「界面在屏期间不让它被交出去」，即窗口级声明，不需要也不该做周期唤醒。

**E8 未验证部分的根因（票 #6 原先的判断已被票 #8 推翻）**：当时看到 `Permission Denial: ... uid=2000 requires moe.shizuku.manager.permission.API_V23`
就归因到「server 由第三方 starter 以裸 shell uid 拉起」，其实权限拒绝说的是**本应用 provider 的 `android:permission` 写错了**——写成 API_V23（dangerous，shell 不持有），
官方文档要求 shell 持有的 `android.permission.INTERACT_ACROSS_USERS_FULL`。改对之后不需要任何人工启动 Shizuku：同一个第三方 starter 拉起的 server 立刻把 binder 交回应用
（`ShizukuProvider: binder received` → `server=true`）。详见「票 #8」。

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
  【票 #27 补记】该状态可检测（`MIUIOP(10008)`+`MIUIOP(10053)` 双 allow ⇔ 已放行）、设置页入口已实测
  （`miui.intent.action.OP_AUTO_START`），检测口径与降级判定见「票 #27 验收」。

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
- **进程被杀后系统拒绝重绑监听**：`AutoStartManagerService: MIUILOG- Reject service` + `NotificationListeners: AutStart Unable to bind notification listener service`——MIUI 自启动白名单没放行；`cmd appops set com.rearcue.poc AUTO_START allow` 在本机无效（`Unknown operation string`），需要用户在 MIUI 设置里开「自启动」。【票 #27 补记】自启动状态**可检测**（`appops get <pkg>` 的 `MIUIOP(10008)`+`MIUIOP(10053)`，两 op 双 allow ⇔ 白名单在册）、设置页**可一键直达**（`miui.intent.action.OP_AUTO_START` → `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`），见「票 #27 验收」。
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

**Shizuku 通道（票 #8 已打通，本条是当时的误判记录）**：当时把 `Permission Denial: ... uid=2000 requires moe.shizuku.manager.permission.API_V23`
归因到「第三方 starter 以裸 shell uid 拉起 server」，实际是本应用 `ShizukuProvider` 的 `android:permission` 写错了（详见「票 #8」）。票 #8 改成官方要求的
`android.permission.INTERACT_ACROSS_USERS_FULL` 后，同一个第三方 starter 拉起的 server 就能把 binder 交回应用，兜底命令通道实测跑通（`onDisplay=true（Shizuku 兜底）`）。

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

## 票 #19 验收：E13 锁屏存活（保活生效时锁屏后 Dashboard 留在背屏，2026-09-22 实测）

一句话结论：**成立**。判定 **E13-PASS**——保活跨锁后 Dashboard 在背屏全程在屏（60s 观察窗 27 个采样点 owner=dashboard 无一丢失，应用设备时钟链在锁点打点后 0 次 `Dashboard detach`），对照无保活时锁屏后 1.3–1.4s 被收走（票 #11，已钉 fixture `logcat-survive-cleared-*`）。

| 项 | 证据 |
|---|---|
| 基线上屏 | pre-lock `rear=ON/ON main=ON/ON owner=dashboard`（shell 通知 + debug `POST_TEST` 触发，无手动；`project iconSet=[com.tencent.mm] -> 应用内投送已发出 displayId=1` → `RearDashboardActivity onCreate display=1`） |
| 锁真的生效 | `lock-marker` 打点 `09-22 22:47:58.613` + `PowerGroup: Powering off display group due to power_button (groupId= 1 ...) 09-22 22:47:59.171`；锁后主屏 `OFF/OFF`、`wakefulness while locked: Dozing` |
| 保活真的在跑 | tick 日志 118 次注入 / 60s 窗（期望 ~120）、0 input 错误、`ps` 见 `wake-keepalive.sh`；`WAKE_REASON_WAKE_KEY` 痕迹 1 条 |
| 存活（主证据：设备时钟链） | 打点后 `cleared-after-lock : False`（0 次 `Dashboard detach`）；`reproject-after-lock : True`（+1s，2 条 effect：锁窗信号重投，`投送确认：Dashboard 已在屏 displayId=1`） |
| 存活（佐证：同轮采样） | 27/27 采样 `rear=ON/ON owner=dashboard`；`rear-behavior : rear stayed ON through the whole keep-alive watch`；`main-side-effect : main display stayed dark` |
| 外部污染 | `pollution : none detected`（归因：本脚本每次注入的设备时钟戳 ±3s + 唤醒键翻转豁免，`Select-ExExternalPollution`） |
| 归档 | `docs/poc-logs/20260922-224734-lock-survive/`（summary.md、e13-lock-survive.txt、e13-samples.txt、e13-wake-ticks.txt、e13-power-group.txt 等；erratum.md = summary chain facts 的采集截断勘误，判定与证据不受影响） |

失败条件（再现即重判）：①观察窗内应用 logcat 现 `Dashboard detach` 且无回归（E13-CLEARED/E13-NO-RETURN）；②owner 丢失（E13-CLEARED）；③注入循环断流/报错（E13-INJECT-FAILED，保活前提不成立）；④锁没真到背屏 group（E13-NO-LOCK：T0 后无 PowerGroup group-1 断屏行 = 无锁可守，PASS 永远不可达，code review 后加的判定门槛）；⑤指纹或人按电源等外部唤醒（E13-RUN-INVALID + erratum.md，样本不得入结论）。

判读边界（如实记）：①时刻口径——T0 打点先于电源键按压（防被收走抢跑在打点前），`lock-press-lag` 行记录打点到 PowerGroup 断屏的延迟（判定轮 0.56s）；1.3–1.4s 对照同用打点锚，锚同径直接可比，「从锁落地算」的换算值 verdict 里也给。②污染归因——落在本脚本注入 ±3s 内、或 `WAKE_REASON_WAKE_KEY` 后 200ms 内的翻转，都被认作自注入；恰巧落在这些窗口里的人手按压无法区分，翻案先看 `e13-power-group.txt` 原文。

已知边界（如实记）：保活走 adb shell uid 2000 注入 `input -d 1 keyevent KEYCODE_WAKEUP`（与 Shizuku UserService 同 uid 级，票 #16 偏差口径不变）；观察窗 60s（`-ObserveSeconds` 可调）；无保活对照腿复用票 #11 归档（fixture `logcat-survive-cleared-*`），不重跑（deviation：对照只做文献对照、非同轮）。

## 探针收口与 go/no-go（票 #20，2026-09-22）

**决策：go（保活路线走、锁屏首投做）**。spec 0003 闸门 = 「E12/E13/E14 任一不过即失败交付」；E12 是 E13 的前置（都过），E14 独立判定（过），shell uid 小探针非阻塞。逐条结论与证据目录：

| 探针 | 结论 | 决策依据（设备事实） | 证据目录 |
|---|---|---|---|
| E12 保活（票 #16） | **E12-KEEP**：周期注入定向背屏唤醒键可让背屏锁屏后保持 ON | keep 腿 62s 全程 ON/ON；对照腿 +5s 离开 ON、+10s 进 DOZE_SUSPEND；500ms/5000ms 有效、30000ms 守不住（间隔是参数不是机制） | `poc-logs/20260922-220601-wake-keepalive/`（判定轮）、`20260922-220129-wake-keepalive-collect/`（fixture 采集） |
| E13 锁屏守住（票 #19） | **E13-PASS ×2**：保活生效时锁屏后 Dashboard 全程留在背屏 | 60s 窗 0 detach、26–27/27 采样 owner=dashboard、锁后 +1s 自重投；对照 1.3–1.4s 被收走（票 #11 同锚可比）；lock-press-lag 0.53–0.56s 折算不影响结论 | `poc-logs/20260922-224734-lock-survive/`、`20260922-230719-lock-survive/` |
| E14 锁屏首投（票 #18） | **E14-PASS**：锁屏稳态任务搬运事务（code **51**，MRSS 的 50 在本构建是静默空操作）真把 Dashboard 搬上背屏 | +25s/+49s 采样 `t12988@d1 owner=dashboard rear=ON/ON`；锁屏段 task-move 拒绝行 0；与 `am start --display` 被拒互补（BAL 是时序竞态非硬墙，见票 #18） | `poc-logs/20260922-181414-task-move/`、`20260922-174125-task-move-collect/` |
| shell uid 加窗（票 #17，非阻塞） | **SH-UID-WINDOW-INCONCLUSIVE**；票 #12 条件② 部分验证 + 收窄 | 注册墙两行 verbatim（`calling from non-existing process` / `Unknown pid`）；真 UserService 类也不在 ATMS 进程表（`dumpsys activity processes` 零匹配）；背屏门拒绝行不存在 = 门未被走到 | `poc-logs/20260922-193237-shuid-overlay/`、`20260922-2315-usvc-proc/` |

**决策去向**：实现开工两条——①Wake Keep-alive（票 #21，E12/E13 为门）；②锁屏首投（票 #22，E14 为门）。
取舍「用周期轮询换可见」记 **ADR 0003**（废止 spec 0001 story 20 的非轮询原则；三要件：循环注入是
日常运行形态难逆转、未来读者会以为违反 story 20 需要上下文、耗电/发热与锁屏可见是真实权衡且强度可调）。

**重开/退守条件（逐条可验，no-go 的回头路）**：
1. 保活代价不可承受：票 #21 的耗电/发热实测在强度最低仍日常挂着心疼（user story 10）→ 退守「仅解锁态收口」+ 兜底交付清单（spec 0003 no-go 形态：探针脚本 + findings 结论 + 失败条件已在库，实现撤下）。
2. 系统侧变脸：HyperOS 更新后唤醒注入被拒/失效（E12 失败条件形态复现）→ `ex.ps1 -Task wake-keepalive` 一键复跑重判，不再默认 go。
3. 非轮询手段可用：第三方应用对背屏显示组实测常亮成立 → 换手段、撤销 ADR 0003 的取舍。
4. 票 #12 重开条件②（shell uid 加窗）：已收窄为「除非构建放行未注册进程的 windowContext 注册（= 条件① 口径），条件② 单独不构成翻案依据」；翻案实验 = 真 UserService 进程内同款 addView + display-0 对照。

各结论可追溯：判定词与失败条件词表见「票 #16/#17/#18/#19 验收」各节，证据目录见上表；四条探针均一键可复跑（`ex.ps1 -Task wake-keepalive|lock-survive|task-move|shuid-overlay`）。

## 票 #21 验收：Wake Keep-alive 实现（应用保活锁屏守住 Dashboard，2026-09-23 实测）

一句话结论：**核心成立**。判定 **E13-PASS（-AppKeepAlive 回归轮）**——保活换由**应用自己的 `WakeKeepAlive`**（Shizuku 定向 `input -d 1 keyevent KEYCODE_WAKEUP`）驱动，锁屏后 Dashboard 全程留在背屏（60s 窗 26/26 采样 owner=dashboard，锁点打点后 0 次 `Dashboard detach`，零外部污染）；生命周期「exit 后无残留」有设备事实；**代价实测 ⚠️ 待补**（见下）。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 回归场景可复跑、锁屏后 Dashboard 在背屏、且由 APP 保活驱动 | ✅ | `ex.ps1 -Task lock-survive -AppKeepAlive`（新增 `-AppKeepAlive` 回归模式：不跑 `wake-keepalive.sh`，只凭应用 `wake-keep-alive start|ok|stop|fail` 日志 + `WAKE_REASON_WAKE_KEY` 痕迹判活）。判定轮 `poc-logs/20260923-001402-lock-survive/`：E13-PASS；`app keep-alive : start=True heartbeats=11 max-ticks=110 fails=0`（tick 证据是应用自己的计数，脚本 tick 文件为 0 = 没有脚本循环）；26/26 采样 `rear=ON/ON owner=dashboard`；`cleared-after-lock : False`；`main-side-effect : main display stayed dark`；`pollution : none detected` |
| ② 保活循环生命周期正确（跟随投送启停、不残留） | ✅ | 同轮退出取证 `e13-exit-residual.txt`：`keep-alive-stopped : True`（`wake-keep-alive stop ticks=...` 停止行属于本次 exit、残留窗内心跳 0、背屏唤醒键翻转 0；锚点 = 空集**之前**读的设备时钟 `exit-t0`，残余判定以停止行时刻为界）。代码路径：`project()` 启动 → `exit()`/投送失败/`refresh()` 无背屏即停 → `stop()` 幂等 + `shutdownNow`（每 tick 独立命令，无残留 shell 进程） |
| ② 进程重建后按当前 Icon Set 恢复 | ✅（带边界） | **跨轮实测**：`20260923-005405-kill-recover` 00:54:18 `am force-stop` 真杀（pid 20654→无）→ 00:55:27 `20260923-005525-wake-cost` 的 `POST_TEST` 显式广播把进程拉活 → 按活跃通知重同步 Icon Set → 投送上屏 → 保活跑完 300s keep 腿（全程 owner=dashboard）。**边界（如实记）**：①强停（force-stop，stopped 态）后**光靠新通知唤不醒**（`20260923-005405` 实测 REBUILD-NO-LISTENER：pid 全程 `<none>`，监听重绑被 stopped 态挡住）——显式广播/手动启动可复活；②`am kill` 杀不动本应用（绑定的监听服务把它钉住，`kill-mode` 行记录了回落）——这本身是韧性事实；③OOM/崩溃式死亡（不带 stopped 态）的恢复未单独构造。一键场景 `ex.ps1 -Task kill-recover`（`-KillMode am-kill|force-stop`）可复跑 |
| ③ 强度可调 | ✅ | `intervalMs` 为 `@Volatile`、自调度泵每 tick 读取（运行中可调）；debug 动作 `ACTION_WAKE_INTERVAL`（`--el ms <ms>` 设/回读）；JVM 测试「运行中调强度立即生效」「重复 start 不建第二条循环」等 7 条全绿 |
| ③ 代价实测（耗电/发热） | ✅ | 判定轮 `docs/poc-logs/20260923-005525-wake-cost/`（**COST-ESTIMATED-DRAIN**，两腿状态全守住、keyguard 全程在，`wake-cost.txt` 全数据）：**发热**（实测）keep 腿 −0.6°C / idle 腿 −0.4°C（两腿都在降温=环境主导），keep−idle **−0.2°C**——保活的边际发热在本测量精度（±0.5°C 量级噪声）内**测不出**；**耗电** `Charge counter` 差（实测）：keep 5000µAh/300s = **60mAh/h**，idle 1000µAh/300s = 12mAh/h，**边际 ≈ 48mAh/h ≈ 0.8%/h**（6183mAh 电池）。判读边界：插着 USB（`on-charger=True`）故判定词取保守的 ESTIMATED-DRAIN——但计数器确实漂了（满电时电池仍在补负载），上列差值是实测口径；Android 模型估算另列（本应用 CPU 仅 ~0.9mAh/h，系统侧唤醒处理不在应用账上）。**默认间隔定案材料**（ADR 0003）：500ms 边际 ~48mAh/h；E12 实测 5000ms 亦守得住（成本约 ÷10）——省电优先可调 5000ms，`cost-recommendation` 行在案，`DEFAULT_INTERVAL_MS=500` 维持暂定、把定案留给机主取舍。真·断电复测：拔线重跑 `ex.ps1 -Task wake-cost`（脚本自动选指标）。5000ms 档的直接对比轮 `20260923-012305-wake-cost` **COST-INVALID**（跑时手机在被使用：keyguard 中途掉、主屏点亮、背屏归属被占，keep 腿状态没守住）——数字归档不作结论，待手机闲置约 10 分钟时重跑 `-WakeIntervalMs 5000` 补 |
| ④ DashboardCore / RearDisplayBackend 接口不变、测试全绿 | ✅ | 两接口冻结未动（`git diff c3621c4` 无签名变化）；`gradlew test` 全绿（rear 新增 `WakeKeepAliveTest` 7 条 + 命令形状钉死 1 条）；`ex.ps1 -Task selftest` 140/140（新增 `Get-ExBatteryFacts`/`Get-ExPowerEstimateLines`/`Test-ExKeyguardLockedText` delegate 判锁共 6 条） |
| ⑤ 决策进 ADR | ✅ | `docs/adr/0003-periodic-wake-injection-keeps-rear-visible.md`（三要件 + 失效条件）；实现与之一致：循环注入绑定 Icon Set 生命周期、注入物 = 定向背屏唤醒键 |

保活注入形状被测试钉死（写错定向即红）：`rear/src/test/.../RearDisplayLocatorTest.kt` 新增「保活注入是定向背屏的唤醒键（票 #21，写错定向即红）」断言 `wakeKeyCommand(1)` == `input -d 1 keyevent KEYCODE_WAKEUP`（不带 `-d` 的裸 KEYCODE_WAKEUP 会翻主屏电源态，E13 实测过）。

失败条件（再现即重判/重开）：①回归轮 E13-CLEARED/NO-RETURN（应用保活没守住）；②`app keep-alive` 行出现 `fails>0` 或心跳断流（E13-INJECT-FAILED）；③`e13-exit-residual.txt` 的 `keep-alive-stopped : False`（exit 后还有心跳/唤醒痕迹 = 残留循环）；④代价实测后「强度最低仍日常挂着心疼」（票 #20 重开/退守条件①：退守仅解锁态收口 + 兜底交付清单）；⑤`kill-recover` 轮 REBUILD-NO-LISTENER/-NO-REPROJECT/-NO-KEEPALIVE（进程重建恢复不成立，AC② 重开）。

判读边界（如实记）：①代价数据已实测落档（见③行），但断电（真·放电）口径仍是「待复测」——插电时 `Charge counter` 的漂移是电池补负载的净效应，拔线复跑 `ex.ps1 -Task wake-cost` 可得纯放电数；「耗电/发热可承受」的最终取舍在机主（48mAh/h 边际 vs 5000ms 省电档）；②tick 证据是应用自报计数（心跳行 `wake-keep-alive ok ticks=N`，每 10 tick 一条），与系统侧 `WAKE_REASON_WAKE_KEY` 痕迹、每 2s 的背屏/归属采样互证——保活的执行者本来就是应用，自报计数是第一人称证据，独立交叉验证靠后两者（多数 tick 不留 PowerGroup 痕迹——背屏已亮时唤醒键是 no-op，E13 同口径）；③回归轮的外部污染判定沿用 E13 词表（电源键±3s 归因 + 唤醒键翻转 200ms 豁免）；APP 保活模式下注入不打脚本戳，翻转豁免是唯一归因锚，恰落在豁免窗的人手按压无法区分——翻案先看 `e13-power-group.txt` 原文；④发热两腿同降（环境主导）说明 5min 腿对 0.x°C 级热效应灵敏度不足，热口径的复测建议拉长腿或控温环境。

污染轮与环境轮（**不入结论**，仅存档）：`poc-logs/20260923-001000-lock-survive/`（erratum.md：3 条外部命中 = 一次主屏指纹唤醒 `WAKE_REASON_UNKNOWN details=android.policy:FINGERPRINT` 打在 group 0，背屏链未受扰但按纪律作废）；`poc-logs/20260922-233947-lock-survive/`（erratum.md：签名变更重装清掉 Shizuku 授权 + 通知监听，基线根本没起来，与实现无关；顺带固化两个环境坑：MIUI 自启动 `appops set ... 10008 allow`、亮屏距离传感器防误触 `settings put global enable_screen_on_proximity_sensor 0`）。

## 票 #27 验收：自启动/监听健康探针（2026-09-23 实测）

一句话结论：**两个事实问题都有答案**——Q1 检测**可行**（口径 = `appops get <pkg>` 的 `MIUIOP(10008)` + `MIUIOP(10053)` 模式，
设置页开关实测翻转时两 op 同步翻）；Q2 跳转入口**可行且精确**（action `miui.intent.action.OP_AUTO_START` + category
`android.intent.category.DEFAULT`，或显式 component `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`，
实测落到自启动管理页）。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 检测手段实测结论（可行/不可行 + 证据，可行时给出可复用的检测口径） | ✅ **可行（shell 侧实测）**，口径见下 | `poc-logs/20260923-025010-autostart-toggle/`（因果轮 toggle diff）+ `20260923-024303/024439-autostart-collect/`（检测面 sweep）+ `20260923-030247-autostart-rearcue2/`（本应用行 ground truth） |
| ② 跳转入口实测成功/失败留痕（精确 component/action） | ✅ action 变体实测 **JUMP-PASS**（三证）；负对照实测 **JUMP-NO-TASK** 真样本 | `poc-logs/20260923-024736-autostart-jump/`（topResumedActivity + mCurrentFocus + 页面 dump）+ `20260923-032215-autostart-collect/am-start-missing.txt`（`Error type 3` 原文） |
| ③ 降级判定明确写入 findings | ✅ | 本节「降级判定」 |
| ④ 原始日志切片与复现步骤归档 poc-logs | ✅ | 各 session 的 `summary.md`/`erratum.md`（复现步骤与取舍）+ 判定 fixture（`tools/ex/tests/fixtures/`：`appops-miuiops-chatgpt-*.txt`、`ui-autostart-*.xml`、`am-start-autostart-*.txt`，全部真机原文切片）+ `ex.ps1 -Task autostart-probe` 一键复跑 |
| ⑤ 不改产品代码 | ✅ | git diff 仅 `docs/` + `tools/ex/` |

**Q1 检测口径（可复用）**：`adb shell appops get <pkg> 10008` 与 `appops get <pkg> 10053`（或一次 `appops get <pkg>` 读全量）——
**两个 op 都 `allow` ⇔ MIUI「自启动」白名单在册**（设置页该行 `checked=true`、在「允许」段）。实测根据：

- **因果（toggle diff，ChatGPT 行，`20260923-025010`）**：设置页开关 OFF 一次 ⇒ `MIUIOP(10008) allow→ignore` **且**
  `MIUIOP(10053) allow→ignore`，全量对照其余 op 一个没动（`MIUIOP(10021)` 等嫌疑排除）；UI 分段计数同步 允许6→5 / 禁止120→121。
- **相关（跨包对照 + 本应用 ground truth）**：UI「允许」段抽样（微信 / ChatGPT / Shizuku）均 10008+10053 双 allow；「禁止」段（百度贴吧）双 ignore；
  **本应用行 `checked=false`**（`20260923-030247` 的 `ui-rows.xml`，RearCue 行）↔ 当时 `MIUIOP(10053): ignore` 对上。
- **被排除的检测面（设备事实）**：①named op 不存在——`appops get com.rearcue.poc AUTO_START` = `Error: Unknown operation string: AUTO_START`
  （与票 #7 `cmd appops set ... AUTO_START` 同因）；②LBE provider 无第三方读面——`content query --uri
  content://com.lbe.security.miui.autostartmgr`（及 `/autostart`、`/autostart/<pkg>`、`/packages`、`/query?...`）全部 `No result found.`，
  其 `miui.permission.READ_AND_WIRTE_PERMISSION_MANAGER` 为 `prot=signature|privileged`（`20260923-024439` 的 `raw-dumpsys-pkg-lbe.txt`）；
  ③settings 三表无 per-app autostart 键。
- **口径边界（如实记）**：①以上是 **shell 侧**实测；app 内读数走同一 AppOpsService（`AppOpsManager.checkOpNoThrow(10008|10053, myUid, pkg)`，
  int 形参是公开 API），但**本票冻结产品代码、app 内调用未实测**（偏差①）。②`appops set` 可单独写任一 op ⇒ 两 op 能被人为写岔
  （票 #21 就手工 `appops set ... 10008 allow` 过）：**两 op 不一致时只能报「状态存疑」，不得报健康**（本应用 2026-09-23 凌晨实测出现过
  10008=allow / 10053=ignore 的岔态，后被并行代理重装清回默认双 ignore）。

**Q2 跳转入口（精确）**：

- **action**：`am start -a miui.intent.action.OP_AUTO_START -c android.intent.category.DEFAULT` —— 实测 **JUMP-PASS**（`20260923-024736`：
  `topResumedActivity` 与 `mCurrentFocus` 均为 `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`，
  页面 dump 标题「自启动管理」+ `auto_start_list` + 允许6/禁止120）。
- **component**：`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`（同一 activity；resolver 表原文在
  `20260923-024439-autostart-collect/raw-dumpsys-pkg-seccenter.txt`：`miui.intent.action.OP_AUTO_START` filter → 该 activity + DEFAULT category）。
- **失败词表**（`16-autostart-probe.ps1` 一键判定）：`JUMP-PASS` / `JUMP-NO-TASK`（负对照真样本：`Error type 3` +
  `Error: Activity class {...} does not exist.`）/ `JUMP-WRONG-PAGE`（起了但不是自启动页）/ `JUMP-NO-EFFECT`（无起无错）。
  判定读 `topResumedActivity` + 页面 dump；`am start` 回执/退出码不判（对静默 abort 回执同样显示 `Starting:`，负对照实测可见）。
- app 内跳转（产品用法）：`Intent(AutostartAction).addCategory(DEFAULT)`（或显式 component）+ `FLAG_ACTIVITY_NEW_TASK`，`resolveActivity` 可预检。

**降级判定（spec 0004「检测不可行则降级」）**：

- 检测口径在 shell 层成立 ⇒ 默认形态是「检测 + 手动跳转按钮」。
- 出现任一情形时，横幅**必须降级为「仅手动跳转按钮 + 明示文案」，且绝不显示「健康」**：①app 内 `AppOpsManager` 读数拿不到
  或与 UI 明显不一致（app 内路径未实测，偏差①）；②10008 与 10053 不一致（外部 `appops set` 痕迹 ⇒ 状态存疑）；
  ③出现 allow/ignore 之外的第三态（default/ask 等）。
- 跳转按钮不依赖检测可用性；文案只陈述事实（「未检测到自启动放行」/「状态存疑」），**不伪造健康状态**。

失败条件词表（再现即重判）：`DETECT-TRACKS-SWITCH`（本轮因果形态）/ `DETECT-NO-TRACK`（开关翻了、op 不动 ⇒ 口径失效，Q1 重开）/
`DETECT-SWITCH-UNFLIPPED`（tap 没翻开关 ⇒ 该轮不作数）/ `DETECT-SWITCH-UNREACHABLE`（行找不到：锁屏/未装/改名）；
恢复侧 `RESTORED` / `RESTORE-FAILED`（UI `checked` 态与 op 模式必须都回原样）。另注：MIUI 该页自述会「智能优化不常用应用的自启动权限」——
白名单状态可能被系统自动改写，跨轮结论都要先读当轮 ground truth。

**与票面写法的偏差（如实记录）**：①「能否被**本应用**检测」的字面语义是 app 内调用——本票纯探针、产品代码一行不改，实测口径落在
shell 侧（`appops get` 与 app 内 `AppOpsManager` 是同一 AppOpsService binder，机制等价性是推断非实测，票 #16/#17 同款等价边界口径）；
app 内实测留给横幅实现票，判词按「shell 实测可行 + app 内路径未验」记。②因果轮翻的是**他应用行**（ChatGPT，同页面同控件、包语义相同）；
本应用行的 toggle diff 以判定轮 `ex.ps1 -Task autostart-probe` 复跑补齐（跑成见其 `autostart-probe.txt`），未补前以「ChatGPT 因果 +
本应用行 ground truth 相关」为证。③ChatGPT 行当场复原因行重排未即刻完成，最终以 `appops set com.openai.chatgpt 10008|10053 allow`
复原到 toggle 前的双 allow（`20260923-032215` 的 `appops-chatgpt-post-restore.txt`）；UI 分段的目视复核留待机主人眼。

一键复跑：`ex.ps1 -Task autostart-probe`（`16-autostart-probe.ps1`：检测面 → 跳转候选（action / component / 负对照）→ 自身开关 diff →
复原校验；**需要手机解锁**）；只读子集 `16-autostart-probe.ps1 -NoToggle`。解析层 `Get-ExMiuiOpFacts` / `Compare-ExMiuiOpFacts` /
`Get-ExUiSwitchRow` / `Get-ExAutostartPageFacts` / `Get-ExAutostartJumpFacts` 配 Pester（fixture 全部真机原文切片；
`ex.ps1 -Task selftest` 160/160）。

## 票 #22 验收：锁屏首投（E14 任务搬运事务，2026-09-23 实测）

一句话结论：**成立**。判定 **FIRSTCAST-PASS**——锁屏稳态（无 Active Notification、背屏无 Dashboard、keyguard 在）来一条白名单通知，**+4s** Dashboard 上背屏，路由 = **E14 任务搬运事务**（`service call activity_task 51`，taskId=13024）；观察窗 12/13 采样 owner=dashboard；清通知后退出交还原生（`firstcast-exit : native-returned True`）。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 锁屏中来白名单通知 → 背屏出图标（设备事实） | ✅ | 判定轮 `docs/poc-logs/20260923-011503-lock-firstcast/`：`firstcast : FIRSTCAST-PASS`；`owner=dashboard 12/13 samples; first at +4s`；`task-move app facts : attempts=2 last-word=OK task-id=13024 on-display=true creates=2 txn-raws=2`；锁真到背屏 group（PowerGroup group-1 power-off=True）、keyguard 全程在、`pollution : none detected` |
| ② 事务被拒安全降级不崩、失败条件明确记录 | ✅ | 失败词与 E14 词表同口径（NO-TASK / REJECTED / TXN-BROKEN / NO-EFFECT + NO-EVIDENCE 未测档），`task-move word=...` ASCII 锚进应用日志、`Get-ExTaskMoveAppFacts` 纯解析 + Pester；每步失败只记日志 + `projected=false`（`failProjection` 统一出口，绝不抛异常）。失败侧实测有两轮真样本：`20260923-010632`/`011205`（FIRSTCAST-REJECTED / -NO-EVIDENCE：进程被上一轮强停 + Shizuku UserService 未绑，词形如实分开） |
| ③ tools/ex 一键可复跑 + session 归档 | ✅ | `ex.ps1 -Task lock-firstcast`（`15-lock-firstcast.ps1`，含 fallback-ready 门、E1 驱动法触发、双时钟锚、污染豁免、`firstcast.txt`/`firstcast-samples.txt` 归档）；三轮 session 齐（010632 环境轮、011205 就绪门前行、011503 判定轮） |
| ④ 通知清空后退出交还原生 | ✅ | `firstcast-exit : native-returned True`（清通知 → ExitDashboard → owner 回 native）；且任务搬运搬走的 root task 由 `exit()` 归还主屏（`task-move hand-back`，code review 后补的 AC） |
| ⑤ `am start --display` 锁屏不走；事务号 51 | ✅ | 锁屏兜底直达任务搬运（`projectViaShellFallback` 判 keyguard）；建任务只走默认屏 `am start -n`（背屏门不参与）；事务号 51 由测试钉死（`service call activity_task 51 i32 <taskId> i32 <displayId>`，写错即红）；本轮 5 条系统拒绝行（`rearDisplay check locked` 等）正是 in-app `--display` 直投被拒的实录——被拒路径不走，只作对照证据 |

失败条件（再现即重判/重开）：①观察窗 owner 永不为 dashboard 且词=NO-TASK/-NO-EFFECT/-REJECTED（对应各自失败语义）；②`task-move hand-back` 缺失或失败（搬走的任务滞留背屏 = 交还不干净）；③事务号语义变化（HyperOS 更新后 code 51 不再是 moveRootTaskToDisplay：`NO-EFFECT` 突增）→ E14 的候选号扫描流程（09-task-move）复测定号；④出现 `firstcast-exit : native-returned False`（清通知后 Dashboard 赖着不走）。

判读边界（如实记）：①`task=t<id>@d<display>` 采样字段在判定轮显示 `absent`（组件名匹配口径问题，`Get-ExTaskPlacement` 用全名 vs dumpsys 短名）——不影响判定（owner + 应用 `task-id=13024` 是主证据），字段修复留给 harness 下一轮；②图标像素本身是应用 Compose UI 的事，设备事实证到「Dashboard（含 Icon Set 内容日志）在背屏」，人眼级证据按 photo-checkpoints 惯例补拍；③route 字段区分「事务」与「应用内窗口竞态」——锁窗内 in-app 直投偶有竞态成功（E13 +1s 自重投同源），本轮 route=事务（in-app 被拒 5 行在案）；④建任务步 `am start -n` 会先把 Dashboard 建在默认屏（锁屏时在 keyguard 后/上），事务成功即搬走，事务失败时界面留主屏——本轮未观测到失败路径的可见性，留作已知风险。

## 票 #25 验收：主屏界面翻新（主题令牌 + AMOLED 纯黑 + 主屏安全区，2026-09-23 实测）

一句话结论：**成立**。判定 **MAIN-UI-PASS**——主屏调试/引导页翻新为 AMOLED 纯黑 + 单一强调色（accent `#4D9FFF`），语义化令牌（颜色/间距/图标尺寸 + 形状/触控/动效）落全项目、无逐屏 hex；内容全程落在平台 WindowInsets 安全区（`safeDrawing` + `getRoundedCorner()` 折算，零硬编码机型数字）；实机截图挖孔/四角/手势条无遮挡；调试旁路与 adb 命令语义逐条原词面。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 语义化设计令牌落全项目（颜色/间距/图标尺寸），AMOLED 纯黑 + 单一强调色 | ✅ | `rear/src/main/kotlin/com/rearcue/poc/design/DesignTokens.kt`（RearCueColors / RearCueSpacing / RearCueIconSize / RearCueShape / RearCueTouch / RearCueMotion）+ `RearCueTheme.kt`（Material3 调色板取自令牌）；主屏全量走令牌；背屏 3 处 hex（纯黑/白/0xFF222222）已换令牌（排版体系归票 #26）；对比度独立计算：正文 17.2:1、次要 8.2:1、accent 7.7:1、error 7.6:1 |
| ② 主屏安全区：挖孔 150px / 圆角 / 手势条（平台 WindowInsets） | ✅ | `design/SafeArea.kt`（`WindowInsets.safeDrawing` + `WindowInsets.getRoundedCorner()` 折算留白，取 max(布局 gutter, 折算留白)）；`poc-logs/20260923-030129-main-ui-refresh/insets-window.txt`（cutout Rect(0,150-0,0)、RoundedCorner r=190 ×4、NAVIGATION_BAR 52px）；截图 01（竖屏）/02（横屏）无遮挡 |
| ③ design_review 清单逐条核对 | ✅（刻意例外已列） | 同目录 `design-review.md`：12 项逐条（对比度/触控 ≥48dp/按压 100ms/状态完备/375dp 小屏横竖屏/无障碍/语义令牌/间距节奏）；例外=Phosphor 图标库离线不可取→Material Symbols Outlined、单主题无浅色侧、reduced-motion 未接、加载态不适用、outline 2.0:1 为装饰描边 |
| ④ 既有调试功能语义不变 | ✅ | `logcat-rearcue.txt`：`debug post test notification` / `手动投送背屏` / `手动退出背屏 Dashboard` / `debug cancel test notification` / `state ...` 五条词面与 `DebugCommandReceiver` 一致；按钮动作与分支逐条未动（仅「退出背屏 Dashboard」在未投送时呈禁用态 + 明示文案，属状态完备而非语义变化） |
| ⑤ 实机视觉验收留痕（挖孔/边缘无遮挡） | ✅（空态/禁用态实拍待补） | `screenshots/01-main-empty-portrait.png`、`02-main-empty-landscape.png`（挖孔/圆角/手势条全程无遮挡）+ insets 两份 dump + `session.md`；空态/禁用态/图标态/可用态四张待机主解锁手机后补拍（安全锁 adb 解不开，`erratum.md` 在案） |
| ⑥ gradlew test 全绿，145 例基线不回退 | ✅ | `gradlew test` BUILD SUCCESSFUL；实测 89 个 @Test（core 35 / rear 31 / notification 20 / app 3；Android 变体双跑=123）与改前基线逐例相同、一条未删——票面「145」口径与 gradle 实测计数不一致，属口径差异不是回退 |

失败条件（再现即重判/重开）：①屏幕代码重新出现硬编码 hex/间距/图标尺寸（绕过令牌）；②换机型后安全区遮挡复现（圆角折算未跟上）；③正文对比度 <4.5:1 或次要 <3:1；④调试旁路/adb 命令词面或行为变化；⑤`gradlew test` 红例或少例。

判读边界（如实记）：①对比度是 PC 侧 WCAG 独立计算（非实机光度测量）；②横屏只验了空置一图（02），旋转后已复原 `user_rotation`/`accelerometer_rotation`；③触控目标以 Compose `heightIn(min=48dp)` + uiautomator bounds（÷3.25）双证，后者随空态补拍轮落档；④取证工具坑（screencap `-d` 不合法、ui dump 抓错窗、息屏不出图、互斥锁旧收尾写法）全部在 `erratum.md`；⑤禁用态只施于「退出背屏 Dashboard」（未投送=无可退出对象），其余调试按钮恒可用以保旁路可达；⑥`screen_off_timeout` 取证后统一恢复 60000ms（原值读取为空，取常用值）。

## 票 #29 验收：GreezeManager 冻结探针（2026-09-23 实测）

一句话结论：**探针收口**。判定轮 **FZ-REPRODUCED / EVT-HELD-UNTIL-THAW / THAW-NOT-BY-BROADCAST**——应用退后台后 ~5s 被 GreezeManager 冻结（`add uid = 10336 adj` → `FZ uid = 10336 ... success !`，冻结落在 **pid 级** `cgroup.freeze`）；被冻期间白名单通知事件**一条都不到**、全部压到解冻瞬间**同毫秒补投**（两枚实测压 74.8s / 63.3s，解冻后 2–3ms 到齐，事件不丢）；显式广播（`am broadcast` 到 debug 接收器）**不能**解冻、事件照压。应对手段清单见 §3（只交事实；**防冻结实现另立票**，与 spec 0004 Out of Scope 一致）。

链路：`tools/ex ex.ps1 -Task freeze-probe`（`16-freeze-probe.ps1`，`-FreezeTrigger tobg|sleep|auto`）一条命令 = 控制腿（未冻实时交付探针，兼作监听活性证明）→ 复现（HOME 退后台；`sleep` 变体 = KEYCODE_SLEEP 灭屏冻）→ 冻结窗内两枚真实白名单通知（`cmd notification post`，tag 运行级唯一）+ `cgroup.freeze` 逐点采样 → 解冻探针（显式广播 → KEYCODE_WAKEUP → Activity Start）→ 背屏可见豁免腿 → 应对手段事实采集 → 归档。**时间线口径（单一时钟）**：post 标记（`log -t RearCue pc-freeze-post-n*`，紧挨 post 命令之前写入）与应用 `posted` 交付行同为 logcat 设备时钟；延迟 = 交付时刻 − 标记时刻（上界多算一次 adb 往返）；冻结窗 = cgroup 采样 wire（uid 级 + pid 级 `/sys/fs/cgroup/apps/uid_*/[pid_]cgroup.freeze`）；GreezeManager FZ/THAW 行与 `dumpsys greezer` history 为同链佐证。

1. **复现条件（判定轮 20260923-035230 原文链 + 触发面/豁免面实录）**：
   - 退后台 ~5s 冻结（与 `fz_timeout=5000 (persist.sys.gz.fztimeout)` 同量级）：`09-23 03:52:56.344 GreezeManager: add uid = 10336 adj` → `03:52:56.348 FZ uid = 10336 reason =from system success !`；同刻 cgroup 采样 `uid=0 pid=1`（**uid 级恒 0，冻结在 pid 级**——票 #5 记的「cgroup.freeze=1」以 pid 路径为准）。reason 词不唯一：票 #5 轮 `tobg`、本判定轮 `from system`，同现象；**判定以 cgroup 采样为准，reason 词只作描述**。
   - **触发面**（history + logcat 实录）：`tobg`（030402 轮，HOME 后 +6.5s）、`from system`（035230 轮，退后台 5s）、`screen off`（**必冻 2/2**：03:08:07、03:20:09）、`check binder`（新进程起后 4s，00:05:22）。**不稳定面（如实记）**：032045/034557 两轮同样退后台 15s+ **未冻**（cgroup 恒 0、greezer 无 FZ 行）；可辨差异是进程/绑定新旧（冻结轮 030402/035230 的进程均为刚重建的新进程），**未定论**；要稳定复现用 `-FreezeTrigger sleep`。
   - **豁免面**：在屏可见即不冻——投屏时 `GreezeManager: setSubScreenUid uid=10336`，随后 `Uid 10336 was show on screen, skip it`（030402 轮 03:04:58.691；034557 轮 Dashboard 在背屏 30s 窗内 0 冻结）。
   - **解冻面**（实录 reason 词）：`Display_On`（亮屏，判定轮实测两次）、`Activity Start`（030402 实测）；他 uid 实录另有 `screen on`/`broadcast`/`alarm`/`provider`/`adj`/`PACKET`/`Excute Service`（history corpus）。解冻后**~5s 再冻**（035230 轮 03:54:16 / 03:54:27 两次复冻实录）。

2. **被冻期间通知事件表现（判定轮时间线，单一时钟，毫秒级）**：

   | 事件 | 标记时刻 | 交付时刻 | 延迟（交付−标记） |
   |---|---|---|---|
   | 控制腿（未冻）ctl-c1 | 03:52:35.312 | 03:52:35.615 | **0.3s** |
   | 冻结窗 post-n1 | 03:52:56.751 | 03:54:11.576 | **74.8s** |
   | 冻结窗 post-n2 | 03:53:08.304 | 03:54:11.577 | **63.3s** |

   - 冻结窗（FZ 03:52:56.348 → THAW 03:54:11.574，75.2s）内事件交付 = **0**（wire 56 采样点全冻 + 应用侧零 `posted` 行）；THAW 后 **2ms / 3ms** 两枚齐发 ⇒ 「延迟到解冻才投递」精确到毫秒成立，且**事件不丢**（补投完整，与票 #5「同毫秒补投」互证）。
   - 显式广播探针（03:53:57.288 `pc-freeze-thaw-bcast` + `am broadcast STATE`）：12s 无 THAW、事件照压 ⇒ **显式广播探活不通**（注意区分：history 里他 uid 有 `THAW reason : broadcast`——系统侧广播能解冻，shell 显式广播实测不能）。KEYCODE_WAKEUP（03:54:11.367）→ `THAW ... reason : Display_On`，1s 内解冻并补投。
   - 失败条件词表（`16-freeze-probe.ps1` 一键判定）：`FZ-REPRODUCED` / `FZ-NOT-REPRODUCED` / `FZ-RUN-INVALID`；`EVT-HELD-UNTIL-THAW` / `EVT-DELIVERED-LIVE` / `EVT-LOST` / `EVT-NO-LISTENER` / `EVT-NOT-APPLICABLE`；`THAW-BY-BROADCAST` / `THAW-NOT-BY-BROADCAST` / `THAW-NOT-APPLICABLE`；`FZ-SKIP-VISIBLE` / `FZ-FROZEN-ON-REAR` / `FZ-NO-REAR-BASELINE`。

3. **应对手段清单（逐条注明依据与代价预估；只交事实，不实现——实现另立票）**：

   | # | 手段 | 依据（设备事实） | 代价预估 |
   |---|---|---|---|
   | 1 | Dashboard 在屏即免冻（可见豁免） | `setSubScreenUid uid=10336` + `Uid 10336 was show on screen, skip it`（030402/034557 实测 30s+ 免冻） | 与「Active Notification 清空即交还原生背屏」直接冲突（story 2 语义作废）；背屏被 Dashboard 长期占用；Takeover 后豁免即消失 |
   | 2 | 周期亮屏针顺带解冻 + 对账（Wake Keep-alive 天然踩中 `Display_On` 解冻） | 判定轮 `THAW reason : Display_On` + 补投 2–3ms 实测；`input -d 1` 定向唤醒键只动背屏组（票 #16） | 指示延迟上限 ≈ 一个保活周期 + 批量补投；耗电 ~48mAh/h（票 #21，500ms 档）；只在 Dashboard 在屏期有效 |
   | 3 | 前台服务常驻 | listener 绑定带 FGS flag（`dumpsys activity processes`：`ConnectionRecord{... CR FGS !PRCP ...}`）但 035230 轮照样冻 ⇒ FGS 免冻**未证实** | 常驻通知噪音、与纯黑极简产品观冲突；需产品代码 + 专门实测 |
   | 4 | AlarmManager / 系统侧广播自解冻 | history 他 uid 实录 `THAW reason : alarm`、`reason : broadcast`（系统侧触发可解冻）；显式 shell 广播实测不通（THAW-NOT-BY-BROADCAST） | 本应用未实测（需产品代码排 alarm）；解冻窗 ~5s 即复冻（035230 实录）⇒ 周期必须短，复杂度/耗电高 |
   | 5 | MIUI 用户侧开关（自启动 + 省电无限制） | `cmd appops set ... AUTO_START allow` 实测 `Unknown operation string`（无 adb 接口）；数值 appop `MIUIOP(10008)/(10020)` 可授但**重装重置**（票 #7）；自启动真正解的是进程死后监听重绑被 `AutoStartManagerService: Reject service` 拒（票 #5/#6） | 一次性手动、换机/重装重做；对**冻结**本身是否豁免未实测 |
   | 6 | 解冻即对账 + 可用性横幅（延迟可解释化） | 事件不丢、解冻 2–3ms 补投（判定轮）；`getActiveNotifications` 快照可令 Icon Set 立即收敛（票 #5 snapshot 语义已有） | 延迟仍在，只是不再「悄悄失效」；与 spec 0004 横幅路线同向，实现归横幅票 |
   | 7 | 不做实现、文档告知延迟来源 | fz_timeout ~5s 冻结 + 解冻即补投 = story 19 的「延迟从何而来」已可解释 | 「手机闲置时来消息」主路径仍是延迟指示（票 #5 原始阻断未消除） |

4. **验收表**：

   | 验收标准 | 证据 | 结论 |
   |---|---|---|
   | ① 复现步骤 + 原始日志切片归档 poc-logs | 判定轮 `poc-logs/20260923-035230-freeze-probe/`（freeze-probe.txt 判定、freeze-timeline.txt 时间线、freeze-samples.txt wire、logcat-greeze.txt + dumpsys-greezer.txt 系统侧、freeze-environment.txt + freeze-countermeasures.txt 环境/手段事实、summary.md）；复现步骤 = `ex.ps1 -Task freeze-probe`（可 `-FreezeTrigger sleep` 稳定复现）；可见豁免轮 `20260923-030402` / `-034557`；五个工具坑轮各留 erratum.md（030402/032045/033533/033746/034104） | ✅ |
   | ② 被冻期间通知事件表现（时间线口径明确） | §2 表 + 口径声明（单一时钟、延迟=交付−标记、冻结窗=采样 wire），毫秒级补投 | ✅ |
   | ③ 应对手段清单（依据 + 代价预估） | §3 表，7 条逐条指向证据 | ✅ |
   | ④ findings 回填 + 注明实现另立票 | 本节；**防冻结实现另立票**（立票参考优先级：⑥ 横幅对账 > ② 保活顺带解冻 > ① 语义冲突大 > ③④ 未证实） | ✅ |
   | ⑤ 不改产品代码（git diff 仅文档/脚本） | diff = `tools/ex/`（16-freeze-probe.ps1、ex.ps1、05-collect.ps1、ExCommon.psm1、tests/fixtures）+ docs（本节、poc-logs、errata） | ✅ |

5. **判读边界与偏差（如实记）**：
   - tobg 触发**不稳定复现**（本轮 2/4）；判定轮链条是「HOME →（Display_On 解冻 blip）→ 5s 后 `add uid = 10336 adj` + FZ」，与票 #5 的 `tobg` 词同现象不同 reason 词；稳定复现请用 `-FreezeTrigger sleep`（screen off 冻结 2/2）。
   - 判定轮的背屏可见豁免腿被 keyguard 打断（sleep 路径带出锁屏，判 `FZ-NO-REAR-BASELINE`；c2 又落进复冻窗、成为第二组压投样本）；豁免结论以 030402/034557 两轮为准。**设备遗留状态：跑完手机留在锁屏（安全锁 adb 解不开），需人解锁。**
   - **`cmd notification post` 的 adb 拼参坑（影响他票脚本）**：`adb shell` 把 argv 空格拼接，`post -t 'RearCue ex' <tag> <text>` 到设备成 `post -t RearCue ex <tag> <text>` ⇒ 标题只吃 `RearCue`、**tag 恒为 `ex`**、所有实验通知塌缩到同一个 key `0|com.android.shell|2020|ex|2000` 互为更新、无 Post 事件（票 #3 更新语义）。票 #21 的 `14-kill-recover.ps1` / `12-wake-cost.ps1` 等同款写法同坑（其判定恰好不依赖 `posted` 行故未暴露）；本票只修自己的探针 + 记录事实，**未动他票脚本**——建议随修复票统一改单 token 写法。
   - 冻结是 pid 级 `cgroup.freeze`（uid 级恒 0）；`oom_score_adj` 冻结前后恒 0，不构成判定输入（仅 wire 遥测）。
   - `dumpsys greezer` 是可靠证据源（history 带 ISO 时标，不受 logcat 缓冲回绕影响；另有 per-uid 统计：本应用 `frozenTime/activeTime rate: 0.02`、`thawReason: {Activity Start/2, adj/4, Display_On/3, Excute Service/4}`）；解析层 `Get-ExGreezeEvents` 同时认 logcat / history 两种行形。

   本轮踩到并已修的工具坑（详见各轮 erratum.md）：`$pid` 是只读自动变量、管道过滤器 `$_ -match` 覆写 `$Matches`、`@(Wait-ExLog)` 空数组包装使门恒真、重绑舞步压着 9~13s 重绑窗口跑、通知 tag 跨轮复用（更新语义）、adb 空格拼参拆词。Pester **162/162**（新增 22 例；fixture 全部真机原文：logcat-greeze-freeze / dumpsys-greezer-history / logcat-freeze-timeline / logcat-freeze-held / freeze-samples-run / freeze-samples-frozen）。`gradlew test` 全绿（145 例基线不动，产品代码零改动）。

## 票 #28 验收：可用性横幅（自启动引导，2026-09-23 实测）

一句话结论：**决策链全通、app 内检测实测可行（票 #27 偏差①收口）**；「一键跳转」的 **tap 级取证被安全锁阻塞**，
按票 #25 先例列入「待补实拍」（跳转入口本身沿用票 #27 JUMP-PASS 三证）。横幅显隐 = DashboardCore 纯决策
（新事件 `AutostartStatus`/`ListenerHealth` → 新效果 `ShowUsabilityBanner(reasons)`/`HideUsabilityBanner`），
Android/Compose 层零决策搬运（`readAutostartState` 读数→事件、效果→`AppState.usabilityBanner` 状态）。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 横幅显隐决策以事件→效果 JVM 单测钉死（出现/消失/健康不打扰三态） | ✅ | `core/src/test/.../DashboardCoreTest.kt` 新增 11 例（出现×3 形态、消失×3、健康不打扰、幂等、原因集叠加/收窄、不干扰投送）；降级判定 `AutostartJudgeTest.kt` 5 例（双 allow/双 ignore/写岔/第三态/读不到） |
| ② 实机验收链（关闭→出现→一键跳转→返回复查→恢复消失） | ✅ 决策层全通；**tap 级待补** | `poc-logs/20260923-043040-usability-banner/`：`chain-02`（`autostart DENIED → ShowUsabilityBanner(AUTOSTART_DENIED)`）→ `chain-01`/04:33 轮（`autostart GRANTED → HideUsabilityBanner`，含 ON_RESUME 复查行）；跳转按钮 tap 三证待补（安全锁），入口=票 #27 JUMP-PASS 三证 |
| ③ 横幅不遮挡 Icon Set 与关键状态、触控 ≥48dp（票 05/02 令牌） | ✅ 代码层；**主屏实拍待补** | 纵向流式布局（横幅插入 Header 与 Icon Set 卡之间，无 overlay）；触控 `RearCueTouch.minTarget`（48dp）+ 全票 #25 令牌；主屏 dump/截图待补（安全锁，`02-ui-banner.xml` 仅有背屏窗片段） |
| ④ 检测不可行时降级判定落地（仅手动跳转 + 明示文案，绝不显示健康） | ✅ | 降级形态实录 `chain-04`（写岔）/`chain-05`（第三态 default）：`ShowUsabilityBanner(AUTOSTART_IN_DOUBT)`，文案「状态存疑…仅提供手动跳转」；`AutostartJudge` 对读不到/写岔/第三态一律 `IN_DOUBT` |
| ⑤ gradlew test 全绿、基线不回退 | ✅ | `gradlew test` BUILD SUCCESSFUL；实测 105 个 @Test（core 51 = 原 35 + 新 16 / rear 31 / notification 20 / app 3），原 89 例一条未删（票面「145」沿票 #25 口径差异注记） |

**app 内检测实测（票 #27 留的偏差①：app 内 `AppOpsManager` 路径未验）⇒ 可行**：

- **读数通道与 SDK 坑**：`AppOpsManager.checkOpNoThrow(int, int, String)` 读 `MIUIOP(10008)/MIUIOP(10053)`——
  **compileSdk 36 的 stubs 已移除 int 形参**（只剩 String op 变体，而 MIUI 数值 op 无 op 名），运行时框架类仍带该
  方法，实现经反射调用（`app/.../autostart/AutostartSupport.kt`），入口不可得/异常即降级（绝不报健康）。
- **四态与 shell ground truth 同轮对照全一致**：双 ignore→`DENIED`、双 allow→`GRANTED`、写岔→`IN_DOUBT`、
  第三态（default）→`IN_DOUBT`（`chain-01/02/04/05` 的 `appops get` 原文 vs 应用 logcat 判词逐态对上）。
  shell↔app 同源（同一 AppOpsService）至此为**实测**而非推断。
- **监听健康事件**同框实录：`listener-disconnected → ShowUsabilityBanner(AUTOSTART_IN_DOUBT+LISTENER_UNHEALTHY)`、
  `listener-connected → ShowUsabilityBanner(...)`（原因集收窄、横幅不隐藏，`chain-07/08`）。

失败条件（再现即重判/重开）：①`DETECT-IN-APP-MISMATCH`（app 内判词与同轮 shell 不一致）；②`BANNER-STUCK`
（GRANTED 后横幅不消失）/`BANNER-FLAP`（健康态出现或重复弹出）；③`JUMP-NO-TASK`（按钮 tap 后 action 与 component
双败）；④`ON-RESUME-NO-RECHECK`（真·前后台切换后复查行缺失——本轮锁屏脚本轮 chain-03/06 各中一次，属锁屏模拟
局限，见 erratum）。

判读边界（如实记）：①本轮在**安全锁态**下取证（机主在睡、PIN 无人可解）：主屏截图/ui dump/按钮 tap 均不可得，
**待补实拍清单 = ①跳转按钮 tap 三证 ②主屏横幅截图（出现/降级/消失三态）③跳转按钮 ui dump bounds（≥48dp）④横幅
不遮挡 Icon Set 与关键状态的主屏目检**（与票 #25 的 4 张空态实拍、票 #27 的本应用行 toggle diff 并列同一队列）；
②「关闭/恢复自启动」以 `appops set` 驱动（与 MIUI 设置页开关同源，票 #27 toggle diff 已证因果），UI 开关不进本轮；
③锁屏态 `HOME` 不退后台、`am start` 偶发不触发 `ON_RESUME`（chain-03/06 复查行缺失）；同链路复查行在 04:33 轮与
chain-01 在案；④取证工具坑续档：ui dump/screencap 抓错窗（E14 任务搬运把 root task 搬去背屏，第二次踩坑）、pwsh 管道落盘
GBK 编码、互斥锁被并行代理长占用（不绕锁）——详见 `poc-logs/20260923-043040-usability-banner/erratum.md`；
⑤设备状态：自启动双 allow、监听已连接已就位；**遗留** `screen_off_timeout=600000`（原值 60000）因互斥锁被占
未及恢复，锁释放后 `settings put system screen_off_timeout 60000` 一条即收口。

## 票 #24 验收：Wake Keep-alive 5000ms 定档 + 代价轮补测（2026-09-23 实测）

一句话结论：**成立（带一条机制性重实现）**。默认注入间隔定档 **5000ms**（JVM 钉死 + README 纠偏），5000ms 干净代价轮判读 **COST-ESTIMATED-DRAIN**（补上票 #21 的 COST-INVALID 缺档），「默认 5000ms」代价**守住可接受代价红线**（明确表态见下）；默认配置锁屏 60s 窗背屏全程 ON 实测在案。中途撞出并解决一条机制事实：**GreezeManager 在锁屏后 ~1.5–4s 冻结应用进程，应用侧保活泵在 5000ms 节奏下必被冻死**——注入定时因此下放到 shell uid 的设备侧自驱循环（冻结免疫 + `pidof` 看门狗不残留），5000ms 默认这才真正端到端可用。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 默认注入间隔 5000ms 以 JVM 单测钉死（判例 WakeKeepAliveTest） | ✅ | `WakeKeepAlive.DEFAULT_INTERVAL_MS = 5000L` + 测试「默认注入间隔定档 5000ms」（常量、构造默认值、启动命令携带默认强度 `echo '5000 5.000'` 三处一起钉）；`gradlew test` 全绿（保活 seam 8 条）。实机面同证：`20260923-044316` 应用日志 `wake-keep-alive start displayId=1 intervalMs=5000`（打在 WAKE_INTERVAL 调试动作**之前** = 出厂默认值）。可调语义不变：强度可调测试在位；WAKE_INTERVAL glue 修好后实测 `ms=4000` 真调得动（`20260923-035523-install` 轮 `GLUE\|` 行，修复前是静默 no-op，见「踩到并已修」） |
| ② 5000ms 干净代价轮完成且判读非 COST-INVALID | ✅ | 判定轮 `docs/poc-logs/20260923-045534-wake-cost/`：**COST-ESTIMATED-DRAIN**（`leg-states-ok : keep=True idle=True`、keyguard 两腿全程在、keep 腿 5/5 采样 `rear=ON/ON owner=dashboard`、idle 腿 5/5 `owner=native`，`wake-cost.txt`/`wake-cost-samples.txt` 全数据）。采集口径与既有代价轮一致（300s×2 腿、一次锁、60s 采样、`dumpsys battery`+batterystats 前后读数）；轮内手机空载（跑前 10min 闲置 settle + Global\RearCueDevice 全程持有 + 无人碰手机） |
| ③ 「默认 5000ms」代价定档结论（明确表态红线） | ✅ | **明确表态：守住可接受代价红线**。红线口径 = 票 #20 退守条件①（「代价实测在强度最低仍日常挂着心疼」）。5000ms 档实测（045534）：**发热** keep −2 dC / idle +3 dC、keep−idle **−5 dC**——零增温可归因（边际发热在噪声内测不出，方向反而是凉，票 #21④ 同口径）；**耗电** `Charge counter` keep **0** µAh/300s vs idle 1000 µAh/300s——边际差为负 = 落在计数器 **1000µAh 步进**分辨率以内，可给出**上界 ≤12mAh/h ≈ ≤0.19%/h**（6183mAh 电池）；按注入节奏比率（tick 数 = 500ms 档的 1/10）外推 **~5mAh/h（~0.08%/h）量级（推算，非实测）**。对照 500ms 档（票 #21，48mAh/h = 0.8%/h）低一个量级以上 ⇒ **日常挂着不心疼，红线守住**。应用侧 CPU 账几乎为零（batterystats：本应用 UID keep 腿 0.000108mAh/300s，系统侧唤醒处理不在应用账上，票 #21 同口径） |
| ④ 实机验证：默认配置下锁屏 60s 窗内背屏保持 ON（口径同 CONTEXT.md 的 Wake Keep-alive 实测边界） | ✅ | 判定轮 `20260923-044316-lock-survive`（`ex.ps1 -Task lock-survive -AppKeepAlive`，默认 5000ms、一次锁、60s 窗、2s 采样）：**28/28 采样 `rear=ON/ON owner=dashboard`**、`rear-behavior : rear stayed ON through the whole keep-alive watch`、主屏全程 OFF（`main-side-effect : main display stayed dark`）、`cleared-after-lock : False`、`pollution : none detected`；保活真的在跑 = 设备侧循环自报心跳 `ticks=10`/`ticks=20`（跨过冻结期照打）。同口径复现：`20260923-035612`（30/30 ON）。⚠️ 044316 的 `e13`/`keep-alive-stopped` 两行有锚污染误计（见该轮 `erratum.md`，按收紧锚重读 = start=True、心跳 2、真失败 0），事实链与 60s 结论不受影响 |
| ⑤ README 模块表纠偏（保活状态列与实现一致） | ✅ | 模块表「`rear` 韧性」行改写：Wake Keep-alive（`WakeKeepAlive`，默认注入间隔 5000ms、可调，随投送启停）✅ 已实现，不再标「预留」 |
| ⑥ gradlew test 全绿，145 例基线不回退 | ✅ | `gradlew test` BUILD SUCCESSFUL 全绿；`ex.ps1 -Task selftest` **146/146**（145 基线 + 1 条本轮锚污染回归 fixture，一条未回退） |

**「默认 5000ms」代价定档结论（写死一句话）**：5000ms 档的耗电/发热代价**守住可接受代价红线**（边际发热测不出、边际耗电 ≤0.19%/h 上界、推算 ~0.08%/h），`WakeKeepAlive.DEFAULT_INTERVAL_MS = 5000` 定档成立；真·断电 COST-MEASURED 复测是精度升级项，不阻塞定档。

**机制事实：GreezeManager 锁屏冻结 vs 保活节奏（供冻结探针票 / 票 #29 应对手段清单第②条）**：

- **冻结原文**：`GreezeManager: FZ uid = 10336 reason =new process success !`（锁屏 PowerGroup 断屏后 ~1.5–4.3s 内必到）；解冻 `THAW uid = 10336 pid = [...] reason : adj caller : 1000`（亮屏/广播等 adj 事件）。证据：`poc-logs/20260923-032337-lock-survive/logcat-system-rear.txt`（03:24:00.271 FZ → 03:25:22.144 THAW，同窗应用日志静默 84s、应用侧保活第 4 拍被冻没；该轮 setup 段带一个被杀孤儿轮的残留通知影响，见 `20260923-031954-wake-cost` erratum——FZ/THAW 系统行与 tick 断流不受其影响）；`20260923-035612-lock-survive`（03:56:35.627 FZ，干净轮独立复现——**该轮背屏全程 ON 仍被冻**，冻结与背屏亮灭无必然关系）。
- **节奏相关性（对照）**：应用侧泵 500ms 档不被冻死——票 #21 归档 `20260923-001402-lock-survive`（intervalMs=500、126 条 tick 跨锁、零 FZ 行）与 `20260923-005525-wake-cost`（300s keep 腿全程 ON、零 FZ 行）；5000ms 档三轮全冻死（032337/034746/035612）。**归因边界（如实记）**：5000ms 三轮的设置路径都是「锁屏稳态下锁屏首投（任务搬运）」、500ms 两轮是「解锁态 E1 投送」——节奏与设置路径未完全解耦，正交实验留给冻结探针票；但「应用侧泵会被冻死」本身三轮可复现。
- **白名单挡不住**：MIUI 自启动（MIUIOP 10008=allow）、`RUN_ANY_IN_BACKGROUND=allow`、`dumpsys deviceidle whitelist +com.rearcue.poc` 三者齐上仍 FZ（035612 轮实测）。
- **解法（本票落地）**：注入定时下放 **shell uid 设备侧自驱循环**（`RearProjectionCommands.wakeLoopStartCommand`：nohup 后台 sh，注入物仍是同一条 `input -d 1 keyevent KEYCODE_WAKEUP`；uid 2000 不吃这道冻结，E12 探针同身份先例）；`pidof com.rearcue.poc` 看门狗保「不残留」（进程消失即自杀，只有冻结才继续值守），stop 文件走优雅退出，间隔文件每拍重读保「可调」。冻结免疫实测：044316 循环心跳跨冻结期照打、背屏 60s 全程 ON；045534 代价轮 keep 腿 300s 全程守住。

失败条件（再现即重判/重开）：①代价轮再现 COST-INVALID（腿状态没守住 / keyguard 中途掉）；②5000ms 复测 60s 窗背屏离开 ON（E12-LOST 词形）；③设备侧循环心跳断流，或 `am force-stop` 后循环还在打针（pidof 看门狗失守 = 残留，票 #21 AC② 重开）；④默认值回漂（JVM 钉死测试红）；⑤代价上界翻倍复现（拔线 COST-MEASURED 轮测出 >0.4%/h 边际）→ 红线重议。

判读边界（如实记）：①代价轮插着 USB（`on-charger : True`）⇒ 判定词取保守的 COST-ESTIMATED-DRAIN；`Charge counter` 差是实测口径但分辨率 **1000µAh/步**（500ms 与 5000ms 两轮的 keep/idle 差刚好互换极性即其噪声形态），真·断电 COST-MEASURED 复测待拔线轮；发热两腿差 −5dC（keep 反而更凉）= 环境主导，0.x°C 级热效应灵敏度不足（票 #21④ 同口径）。②keep 腿处理 = 应用**自己的** WakeKeepAlive（其定时器在设备侧循环——同一条注入命令、同一 shell uid 2000 身份、同一 5000ms 间隔）；041515 轮曾用 `-ShellKeepAlive`（E12 探针循环代打）跑过一轮并 **COST-INVALID** 归档（idle 腿被冻结应用卡死，见下③）。③腿边界的 **thaw nudge**（`12-wake-cost.ps1`：主屏 KEYCODE_WAKEUP ~8s 后回锁）是冻结环境下的必要协议步——冻结的应用处理不了边界清场（041515 实测 `idle established: owner=dashboard` → idle 腿全污染）；nudge 落在两个测量窗**之外**（keep-after 读数在边界前、idle-before 在 settle + `batterystats --reset` 后），采集口径不受影响。④通知面判读依据（票 #29 跨票核查）：`cmd notification post` 的 `-t 'RearCue ex'` 空格拆词使 tag 恒为 `ex`，但 12-wake-cost 全程只发**一条** shell 通知、清场按包（`CANCEL_PACKAGE com.android.shell`）不挑 tag、判定词只读 rear/owner/keyguard 采样**不数通知**——5000ms 代价轮不受拆词影响；多通知协议（14-kill-recover）由票 #29 另修。⑤044316 的 `e13`/`keep-alive-stopped` 判定行有锚污染误计（见该轮 erratum）；040157 的 E12 复测轮判 E12-NO-BASELINE 是对照腿被残留 Dashboard 窗口抬起（E12 原口径要求「无 Dashboard 在屏」），**不作** 5000ms 因果证据（其 keep 腿 `inject-running : True`、13 针/60s 的注入事实可读）。⑥044316/045534 的基线都建在 keyguard-secure 锁屏态下（锁屏首投任务搬运自动接棒，`task-move word=OK`），与票 #21 的解锁态 E1 基线路径不同、状态链（owner=dashboard）同口径。

本轮踩到并已修的点：

- **`$build` 变量覆盖 `[switch] $Build`**（PS 变量不分大小写）：`01-install.ps1` 构建日志一赋值，参数绑定就把数组强转 `SwitchParameter` 报错、整链断掉（`20260923-025048-install` erratum）；改名 `$buildLog`。
- **`@(Wait-ExLog ...)` 又踩 `,$arr`+`@()` 陷阱**：`Start-ExApp` 把「监听没连上」读成 `.Count=1`，静默跳过 disallow/allow 重绑兜底（`20260923-030656` 的 NO-BASELINE 根因之一）；改裸捕获（findings 已两次记载的老坑）。
- **重装重置 MIUI 自启动（MIUIOP 10008）= 监听永不重绑**（AutoStartManagerService 拒服务）：`01-install` 补 10008 重授，与 10020 同段同记。
- **WAKE_INTERVAL 调试动作是静默 no-op**：tools/ex 发 `--ei`（int extra），接收端 `getLongExtra` 类型不匹配、返回默认值——票 #21 起「调强度」从未真调过（所有归档轮的 intervalMs 其实都是当时的默认值，`20260923-034746` erratum 勘误了它的假「500ms」行）；`DebugCommandReceiver` 改 int/long 都收，实测 `ms=4000` 真调得动（035523 `GLUE|` 行）。
- **保活命令文本含全部日志锚词形** → `ShizukuShell` 的 `sh [<命令>]` 原文行被解析成幽灵心跳/失败/停止（044316 判定行因此误报）；`Get-ExAppKeepAliveFacts` 与 `11-lock-survive` 残留判定的匹配全部收紧到 ` : wake-keep-alive `（tag 分隔符锚），本轮原文行**逐字**做回归 fixture（Pester 146/146）。
- **12-wake-cost 腿边界加 thaw nudge**（判读边界③）；`cost-recommendation` 文案「currently provisional 500ms」随定档改为「pinned to 5000ms by ticket #24」；`-ShellKeepAlive` 处理开关留作工具能力（对齐 `-AppKeepAlive` 先例）。
- **env.md 的互斥锁收尾写法会翻车**：`finally { if ($m.WaitOne(0)) { $m.ReleaseMutex() }; $m.Dispose() }` 对已持锁的线程是递归 +1 再 −1，净持有 1 ⇒ 进程退出即 abandoned，**下一位 `WaitOne()` 直接吃 AbandonedMutexException 断掉实验体**（本轮首跑实测）。全程实机轮改用「catch AbandonedMutexException = 已获锁（.NET 语义本就授锁）+ 一次 WaitOne 对一次 ReleaseMutex」；env.md 建议同步（已报 parent）。

## 票 #26 验收：背屏安全区 + 漂移边界（E15，2026-09-23 实测）

一句话结论：**成立**。判定 **SAFE-AREA-PASS**（`E15-SAFE-PASS` ×5 帧 + `E15-DRIFT-PASS` ×3 组）——背屏 Dashboard 的时间与 Icon Set 全程落在内容安全矩形 **[296, 97, 807, 475]**（= 显示 904×572 减左侧相机带296、四边再离圆角 97；可用区 **608×572** ✓ ⊆ 验收基准（含圆角余量））内，防烧屏漂移到极限位（±8px）不越界；几何约束是纯 Kotlin（`DisplaySafeArea.resolve`：cutout 矩形 + 圆角半径 + 漂移幅度 → 内容安全矩形 + 漂移边界），cutout/圆角**运行时从 DisplayCutout/RoundedCorner 读取**（应用日志 `rear-safe-geometry` 原文：`DisplayGeometry(width=904, height=572, cutouts=[PxRect(left=0, top=0, right=296, bottom=572)], cornerRadius=97, driftAmplitude=(8,8))`），渲染层零决策照单执行（`rear-safe-place` 逐分钟留痕布局框 + 漂移 + 等比缩放 + 落位矩形）。

| 验收项 | 结论 | 证据 |
|---|---|---|
| ① 几何约束纯 Kotlin 单测覆盖开孔在左/在上、圆角、漂移组合（RearDisplayLocatorTest 纯函数风格） | ✅ | `rear/src/test/.../DisplaySafeAreaTest.kt` **24 例**：开孔在左（本机 904×572/左带 296/r=97 → [296,97,807,475]）、开孔在上（主屏 1220×2656/挖孔 150/r=190 → [190,190,1030,2466]）、右/下对称、部分高度左带、居中开孔、圆角 0/97/190、cutout 与圆角取 max、漂移幅度 0/8/超额收紧/负值、漂移四角轮驻 + 万分钟不出界扫描、clampDrift、fitScale；离圆角距离用角部不可用区边界采样断言 **≥97（恰压线）**；本机数字照 RearDisplayLocatorTest 判例直接进断言 |
| ② 实机内容全程在安全矩形内（可用区 608×572、离圆角 ≥97），截图留痕 | ✅ | `poc-logs/20260923-043417-rear-safe-area/`：`measure.txt` 三帧 `E15-SAFE-PASS`（bbox=[362,136,716,458]/[378,136,732,458]/[378,152,732,474]，最小边距 1px）+ `measure4.txt` 两帧（Icon Set 两枚形态）`E15-SAFE-PASS`；截图 = `screencap -d <SurfaceFlinger display id>` 真背屏帧（screenshots/01..03、08..09）；runtime contentRect [296,97,807,475] ⊆ 可用区 [296,0,904,572]（=608×572），离圆角最小距离 97（单测采样证明，bbox 更在其内） |
| ③ 实机漂移到极限位仍不出界（留痕） | ✅ | 逐分钟 `rear-safe-place` 留痕 drift=(±8,±8) 四角轮驻（相邻分钟都是极限位）；相邻拍 bbox 位移 **dx=16,dy=0 / dx=0,dy=16 / dx=0,dy=−16** 与调度逐拍一致（measure.txt / measure4.txt），全部帧仍在安全矩形内 |
| ④ 外观用票 02 语义化令牌，与主屏一致 | ✅ | `RearDashboardActivity` 全量走 `RearCueTheme` + RearCueColors/RearCueSpacing/RearCueIconSize/RearCueShape（图标退化块同主屏 clip+border 形态）；无新增令牌、无硬编码 hex |
| ⑤ 实机验收证据归档 poc-logs 惯例 | ✅ | `docs/poc-logs/20260923-043417-rear-safe-area/`（session.md / erratum.md 8 条 / evidence*.ps1 可复跑 / measure*.ps1 逐帧像素判定 / logcat / dumpsys 原文 / screenshots）；两轮作废补拍的反面记录（04..07 帧 + measure2/3）保留 |
| ⑥ gradlew test 全绿，基线不回退 | ✅ | `gradlew test` BUILD SUCCESSFUL；实测 **113 个 @Test/变体 = 既有 89（core 35 / rear 31 / notification 20 / app 3）+ 新增 24**，既有 89 例一条未删（票面「145」口径差异同票 #25 记录） |

失败条件（再现即重判/重开）：①任一帧 bbox/`placed` 越出内容安全矩形（`E15-OUT-OF-SAFE`）；②换机型/系统更新后 `rear-safe-geometry` 读数不再与 DisplayCutout 一致、或读不到几何（glue 采集失效 → 内容零决策但无约束上屏）；③`driftFor`/`clampDrift` 语义改动但万分钟扫描测试被删（漂移越界无回归网）；④`gradlew test` 红例或少例；⑤屏幕代码绕过语义令牌出现硬编码。

判读边界（如实记）：①漂移调度（四角轮驻、相邻分钟都在极限位）是本票定的实现（票面只规定「幅度」输入与「不越界」输出），为取证可判定性选了分钟粒度极限位轮驻；②「离圆角 ≥97」的口径 = 内容安全矩形到**圆角切掉的角部不可用区**的距离（单测采样断言恰压线 97=半径本身）；③Icon Set 实拍到 **两枚**（本应用 + com.android.shell；Allowlist 另三枚是微信/QQ/飞书，不代发），多枚折行/缩放由 `fitScale` + 单测覆盖；④取证在 PIN 锁屏稳态做的，投送走的是锁屏首投任务搬运（票 #22 产品路径，erratum 3），解锁态常规投送未复测；⑤两轮补拍（evidence2/3）作废：force-stopped 静默吃广播 + 共用设备包竞争/冻结队列迟到投递，反面记录保留（erratum 7/8），measure 以 `E15-RUN-INVALID` 失败式兜底挡住了假判定；⑥取证工具勘误：`screencap -d` 合法 id 是 **SurfaceFlinger display id**（`dumpsys SurfaceFlinger --display-id`），票 #25 erratum 的「-d 不合法」系逻辑 id 口径所致，已更正（erratum 1）。


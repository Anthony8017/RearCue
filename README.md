# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。GPL-3.0 开源（见 [LICENSE](LICENSE)）。

- 术语表：[CONTEXT.md](CONTEXT.md)
- 决策记录：[docs/adr/](docs/adr/)
- POC 规格：[docs/specs/0001-rear-display-notification-poc.md](docs/specs/0001-rear-display-notification-poc.md)
- 设备实验记录：[docs/poc-findings.md](docs/poc-findings.md)

## 模块布局（对齐 spec 0001 的模块边界）

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | `DashboardCore` 纯 Kotlin 状态机——事件→效果，并对外暴露 `iconSet` / `contentPage` 只读视图；内容页选择、WFA 例外、兜底与日志契约 | ✅ 票 #2/#3/#128/#132/#134（含实机验收） |
| `:notification` | `NotificationRepository` 纯 Kotlin Shade-visible Notification 汇聚（按 notification key 去重、连接时全量对账）+ `ShadeVisibilityDump` / `ShadeVisibleNotificationGate` / `ShadeVisibilityProbeScheduler` 下拉栏可见性解析、路由与探测编排（ADR 0007） | ✅ 票 #3 |
| `:rear` | `RearDisplayBackend` 接口 + HyperOS 实现（背屏识别、投送、更新、退出）、`RearDashboardActivity`（纯黑 Dashboard、通知页/Agent 页交叉淡入、Detail View、手势边界；图标强调光晕已退役）、`RearDashboardHost`（进程内上/下屏句柄）、Shizuku UserService | ✅ 票 #4/#5；内容页 #128/#133/#134（已实机验收） |
| `:app`（Android 壳 `com.rearcue.poc`） | `RearNotificationListener`（监听胶水）、`AppContainer`（进程接线 + 效果→动作搬运）、`ShadeVisibilityMonitor`（Shizuku 在线时读取 SystemUI 当前通知集合）、主页（Icon Set 可视 + 状态摘要 + 开发者选项折叠区〔调试旁路收编〕）、设置页（充电动画总开关） | ✅ 票 #3/#4/#5；内容页接线 #132；Allowlist 管理随票 #98 删除 |
| `rear` 韧性（锁屏/AOD/保活/Degrade/监听自愈） | Wake Keep-alive（`WakeKeepAlive`：周期注入定向背屏唤醒键，默认注入间隔 5000ms、可调，随投送启停）、Takeover 监听、Degrade 决策（`DashboardCore`）、监听自愈（`ListenerProbe` → `RequestRebind`：已授权未连接时 ON_RESUME 自动重绑） | ✅ 已实现（票 #6/#21；保活默认 5000ms 定档：票 #24；监听自愈：票 #32/#33） |

JVM 单测 seam 四个：`DashboardCore`（事件→效果，含 Icon Set 决策、内容页选择与日志契约）、`NotificationRepository`（监听回调→变更事件）、`ShadeVisibilityDump` / `ShadeVisibleNotificationGate` / `ShadeVisibilityProbeScheduler`（SystemUI dump 解析、fail-open 路由、防抖/合并/超时编排）、`:rear` 的纯 Kotlin 部分（背屏 flag 判定、投送命令、上屏校验、`DisplaySafeArea` 显示几何〔安全矩形/漂移边界/等比缩放〕、`WakeKeepAlive` 起/停/调强度的命令形状与不残留契约）。都不含 Android 框架依赖——`WakeKeepAlive` 的日志锚（`wake-keep-alive start|fail|...` 词形契约）经构造注入，logcat 实现收口在 HyperOS 后端；Android 层只做「系统信号 → 事件 → 效果/状态」的搬运，不做决策。「接线层不写 JVM 测试」的口径指含 Android 框架依赖的胶水；零 Android 依赖的纯翻译函数（如 `toCoreEvents`）有 JVM 判例（先例 `TilePolicyTest`、`NotificationEventWiringTest`）。内容页日志锚（toggle/fallback/WFA/reset/crossfade）由 `DashboardCore.LOG_CONTENT_PAGE_CONTRACT` / `ContentPageLogContract` 冻结，JVM 判例逐字面断言。

## 构建

minSdk = targetSdk = 36（Android 16），Kotlin + Jetpack Compose，Gradle Kotlin DSL + version catalog（`gradle/libs.versions.toml`）。

```powershell
$env:JAVA_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew test          # JVM 单测：:core / :notification / :rear 纯 Kotlin seam（+ :app manifest）
.\gradlew :app:assembleDebug
```

工具链位于 `C:\Users\13691\AppData\Local\RearCue-tools\`（JDK 17 / Android SDK / Gradle 8.14.3，不入库）。

## 内容页切换（spec 0013）

背屏常态显示通知页或 Agent 页之一（Content Page）；两页都有内容时默认通知页，点按背屏空白区域在
两页间切换，交叉淡入淡出约 180ms、不响不震。通知到达、Agent 新输出都不自动翻页；唯一自动例外是
Waiting-for-Approval（插队到 Agent 页、解决前不可手动切走、解决后回原页）；当前页内容消失时兜底到
另一页，之后内容恢复不自动切回；退屏/重投重置为默认页。核心决策全在 `DashboardCore`，背屏 UI
只上报空白点按并按 `contentPage` 投影渲染。规格见 [docs/specs/0013-content-pages.md](docs/specs/0013-content-pages.md)。

验收：JVM 判例 `ContentPageTest` / `ContentPageLogContractTest` 已全绿；实机链（2026-09-28，`failed=0 inconclusive=7`，无 FAIL）
用 [docs/poc-logs/20260928-spec0013-content-pages/README.md](docs/poc-logs/20260928-spec0013-content-pages/README.md) 的判定表与
[drive-acceptance.ps1](docs/poc-logs/20260928-spec0013-content-pages/drive-acceptance.ps1) 复跑：判定表实机列已回填，
3 项人工目检项、3 项「通知页在本机清不空」前置项与 1 项需真 AgentRoster 的项记为 INCONCLUSIVE，不把未跑项当通过。


## 通知图标动效（spec 0015）

Icon Set 的每一枚图标（图标本体 + 数字角标一起）加入场与退场动效，位置变化平滑过渡：新图标约
0.25 秒轻回弹入场、退场收缩淡出、其余图标补位滑动、行数与「6 枚 ↔ 7 枚」档位变化时整组平移缩放；
最后一个图标退场时屏幕陪着把退场演完再交还（退屏宽限上限 1 秒，窗内又来通知就取消退屏），手动退屏
不等待。三种情况不演退场：只在「+N」里的溢出应用被清掉、Detail View 正开着的那枚被清掉、角标数字
变化。背屏 Dashboard 与主屏总览页（`IconSetCard`）共用同一套时长与弹性取值（`IconSetMotion.kt`），
决策仍全在 `DashboardCore`（UI 只做前后两帧的包名对账）。规格见
[docs/specs/0015-icon-animations.md](docs/specs/0015-icon-animations.md)（编号说明：**0014 已被 #135
《Spec 0014：Shade-visible》占用且正文只存在于 issue，`docs/specs/0014-*.md` 至今缺失——本 spec 收口时记录该空档**）。

验收：JVM 判例 `--rerun-tasks` 全绿（core 214、rear debug/release 各 152、app 48）；实机链（2026-09-29，
`failed=0 inconclusive=3`，无 FAIL）用
[docs/poc-logs/20260929-115451-spec0015-icon-animations/README.md](docs/poc-logs/20260929-115451-spec0015-icon-animations/README.md)
的判定表与 [drive-acceptance.ps1](docs/poc-logs/20260929-115451-spec0015-icon-animations/drive-acceptance.ps1) 复跑：
每个动效场景都有连续录制抽帧 + 日志锚词形（`icon enter/exit <pkg>`、`exit grace start|cancel|end`）双证；
3 项 INCONCLUSIVE 是「机主真实通知在册 ⇒ Icon Set 清不空 / 1 行态触不到」的前置项（不把未跑项当通过，
`-GraceOnly` 备好一条补跑命令）。

## 背屏会话选择（spec 0016）

背屏 Agent 页的会话标识行单击 → 全窗浮层会话列表：首行「自动」，其余合并 ZCode / Codex / Claude
三来源（来源标记；同目录重名附会话号尾 4 位，无工作区则只用尾 4 位），等待确认置顶；点条目即锁定
（与主屏同一把 Session Lock，写盘持久）、关闭并回实时跟随，点列表外或再点标识行关闭，不超时自动关，
有会话进等待确认时列表自动让位关闭。桥来源的锁**断线期间保留**，每条链路上线后按只读「在册快照」
（`GET /snapshot`）对账一次，确认确实不在册才清锁写盘；ZCode 沿既有「任务表消失即清」口径。决策全在
`DashboardCore`（`agentPicker` 投影 + 分源清锁），背屏 UI 只渲染。规格见
[docs/specs/0016-session-picker.md](docs/specs/0016-session-picker.md)。

实机验收后机主定夺两项修订（2026-09-29，票 #160/#161，随 spec 0016 一并落地）：**列表最多完整展示
3 行**，其余在列表内滚动，底部恒留一条看得见的空白关闭带（点它即「点列表外」关闭，不必再去找相机带
那条看不见的空白）；**会话标识行固定在屏幕顶部**，长正文跟随/回看时入口不被滚走（Detail View 的
「标题 + 正文整体居中」不受影响）。

验收：JVM 判例全绿（含 `AgentPickerTest`、`SessionLockTest` 分源对账、`BridgeEventCodecTest`、
`BridgeRelayClientSnapshotTest` 环回桥、`AgentPickerLogContractTest` 锚词冻结、`AgentPickerParamsTest`
几何口径）；实机回环冒烟
（2026-09-29，判定表 14 项：PASS 11 / 部分 PASS 1 / INCONCLUSIVE 1 / 未跑 1）见
[docs/poc-logs/20260929-174000-spec0016-session-picker/README.md](docs/poc-logs/20260929-174000-spec0016-session-picker/README.md)，
cloudflared 真隧道复测（判定表 8 项：PASS 6 / 见注 1 / 未跑 1）见
[docs/poc-logs/20260929-175900-spec0016-tunnel/README.md](docs/poc-logs/20260929-175900-spec0016-tunnel/README.md)
——验收中发现并修复一处真缺陷（桥事件显式 JSON `null` 被解成字符串 `"null"`，背屏列表出现标题「null」）；
未跑项为「第二条 Codex CLI 会话」单独立项（会真跑一次模型调用、动到机主额度）。

## 手工验收（票 #3 链路：通知 → Icon Set）

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell cmd notification allow_listener com.rearcue.poc/.notify.RearNotificationListener
adb shell pm grant com.rearcue.poc android.permission.POST_NOTIFICATIONS
adb logcat -s RearCue            # 观测 icon-set 变化
```

主页实时显示 Icon Set（Shade-visible 应用的应用图标）与状态摘要（监听/通道/可见通知数）；右上齿轮进设置页（充电动画总开关）。「打开通知使用权设置」「发测试通知」「清除测试通知」「投送到背屏」「退出背屏 Dashboard」收编在主页底部「开发者选项」折叠区（后两个是绕过自动流转的调试旁路）。PC 侧可用 `adb shell cmd notification post -t RearCue <tag> '<text>'` 模拟 `com.android.shell` 通知（注意：同 tag 再发一次是**更新**既有通知、不产生新的 Post 事件；spec 0007 起内容变了会另报内容更新——横幅刷新重计时、Icon Set 不重计，同内容则完全无事件）。逐条验收步骤见 [docs/poc-findings.md](docs/poc-findings.md) 的「票 #3 / #4 / #5 验收」。

## 背屏投送（票 #4）

投送主路径是**应用内** `ActivityOptions.setLaunchDisplayId(<背屏 displayId>)`：不需要 shell，背屏准入由 manifest 的 `miui.rear.policy=1` 决定（E2 实测：缺它必被系统 aborted）。Shizuku（shell uid）保留为兜底通道。

背屏 displayId 不硬编码：按「非默认 + `FLAG_PRESENTATION` + `FLAG_OWN_DISPLAY_GROUP`」在运行时识别（本机实测掩码 16515 / 16779 ⇒ displayId=1）。

```powershell
adb shell dumpsys activity activities | Select-String 'Display #1'   # 复核是否上屏
```

Shizuku 通道只要 server 在线即可用（客户端 provider 的权限必须是 shell uid 持有的 `android.permission.INTERACT_ACROSS_USERS_FULL`，写成 `moe.shizuku.manager.permission.API_V23` 会让 server 回调被拒、`pingBinder` 恒 false——票 #8 实测）；首次使用需在 Shizuku 授权框里放行本应用，未授权时投送自动走应用内路径，不阻塞。

## 自动上/下屏（票 #5）

通知事件驱动，不需要点按钮：首条通知 → `DashboardCore` 出 `LaunchDashboard` → 上屏；Icon Set 变化 → `UpdateIconSet`（只广播给背屏界面，不重新投送）；末条通知消失 → `ExitDashboard` → `RearDashboardHost` 结束背屏界面，原生背屏恢复。

- **下屏不用 `am force-stop`**：监听服务与背屏 Dashboard 同进程，force-stop 会把监听一起杀掉，之后就没人能自动上屏了。
- **投送通道**判据是「运行时识别到背屏」而不是 Shizuku 连接（应用内投送不需要 Shizuku）；术语见 [CONTEXT.md](CONTEXT.md)。
- **通知可见范围**（票 #98 + ADR 0007）：应用内不再有 Allowlist——「哪些应用可通知」由系统「读取、回复和控制通知」页裁量；在此之上，Icon Set 只消费 **Shade-visible Notification**：Shizuku 在线时读取 SystemUI 当前 `NotifCollection` 精确校准（下拉栏隐藏、用户已划掉的不上背屏），Shizuku 不可用、dump 形状变化、探测超时或解析 key 与在册集完全不相交时 fail-open（全部在册即可见，优先不漏消息）。**锁屏态过滤器 `KeyguardCoordinator` 不算隐藏**（ADR 0008）：Shade-visible 的判据是「解锁状态下会列出」，锁屏只是挡一下，删掉会让背屏丢掉刚到的通知。spec 0005 的名单语义已废止（留档 `docs/poc-logs/20260926-024500-spec0005-allowlist-chain/` 与该 spec 顶部标注）。
- **锁屏可更新性**（ADR 0008 / issue #143）：主屏灭屏后 GreezeManager 约 5s 冻结应用进程，冻住期间通知回调压在队列、背屏停在冻前那一帧。设备侧 Wake Keep-alive 循环因此多一条**冻结唤醒**腿：每拍读 pid 级 `cgroup.freeze`，为 1 就 `am start` 无 UI 空转页 `ThawNudgeActivity`——系统为启动 Activity 先解冻进程，压住的事件随即补投（延迟上界 ≈ 一个循环间隔）。回归循环：`tools/ex/23-locked-icon-update.ps1`（判定词 `LOCKED-PASS` / `LOCKED-NO-EVENT` / `LOCKED-NO-ICONSET` / `LOCKED-CORE-ONLY`）。
- 已知真实使用阻断（归票 #6）：HyperOS 会把后台应用冻结（`GreezeManager`，冻结期间通知事件延迟到解冻——表现与应对手段清单见「票 #29 验收」）；进程被杀后 MIUI 需要「自启动」白名单才肯重绑通知监听（已授权但未连接时，应用会在 ON_RESUME 自动 `requestRebind` 自愈〔票 #32/#33〕；自启动被拒仍走横幅引导〔票 #28〕）。验收细节见 [docs/poc-findings.md](docs/poc-findings.md)「票 #5 验收」。

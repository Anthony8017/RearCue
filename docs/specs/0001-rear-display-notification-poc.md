# Spec: 背屏通知指示 POC（RearCue）

## Problem Statement

手机平放桌面（主屏朝下）时，机主看不到微信/QQ 等有待处理通知：主屏朝下、原生背屏只有装饰性 AOD，不告知"哪个 App 在等我"。想不看手机翻面就知道有没有通知、是哪几个 App，而现有方案要么需要 Root/LSPosed，要么（MRSS）已停更且不可定制。

## Solution

RearCue POC：不 Root 的 Android 应用，经 Shizuku 把自定义 Dashboard 投送到小米 17 Pro 背屏——纯黑背景、时钟、Icon Set（每个存在 Active Notification 的 Allowlist App 恰好一枚图标，无数字）。通知全部消失后 Dashboard 自动退出，背屏恢复原生样式；Shizuku 不可用时安全 Degrade，恢复后自动重投。

## User Stories

1. As a 机主, I want 手机平放主屏朝下时背屏显示哪些 App 有待处理通知, so that 不翻手机就知道有没有事找我。
2. As a 机主, I want 微信存在 Active Notification 时背屏出现微信图标, so that 一眼看出微信有消息。
3. As a 机主, I want QQ 存在 Active Notification 时背屏出现 QQ 图标, so that 一眼看出 QQ 有消息。
4. As a 机主, I want 多个 App 同时有通知时图标并列显示, so that 不遗漏任何来源。
5. As a 机主, I want 图标只表达"有/无"不带数字, so that 界面极简且不暗示未读压力。
6. As a 机主, I want 背屏同时显示当前时间, so that 平放时背屏兼作时钟。
7. As a 机主, I want 最后一条通知消失后背屏回到原生样式, so that 无通知时不占用背屏。
8. As a 机主, I want 主屏锁屏后背屏指示仍然存在, so that 锁屏不看手机也能收到指示。
9. As a 机主, I want 原生背屏（AOD）抢回显示后 Dashboard 能自动恢复, so that 系统行为不会永久顶掉指示。
10. As a 机主, I want 不 Root 就能用, so that 不牺牲保修、支付安全和系统更新。
11. As a 机主, I want 只依赖 Shizuku 而非常驻电脑, so that 日常使用完全在手机侧闭环。
12. As a 机主, I want Shizuku 掉线/手机重启后应用不崩溃、通知监听照常, so that 后台状态不丢。
13. As a 机主, I want Shizuku 恢复后 Dashboard 自动重新投送, so that 无需手动干预。
14. As a 机主, I want 背屏界面纯黑并周期微移内容, so that 省电且防烧屏。
15. As a 机主, I want 背屏只显示 App 图标不显示消息内容, so that 旁人看不到隐私。
16. As a 机主, I want 应用自带"发测试通知"入口, so that 无需真来消息即可验证链路。
17. As a 开发者, I want 背屏通过 Display flags 自动识别而非硬编码 displayId, so that 系统更新或换机不炸。
18. As a 开发者, I want 全部投送/保活/准入的 HyperOS 专有操作收口在 RearDisplayBackend, so that HyperOS 更新时只改一处。
19. As a 开发者, I want 决策逻辑（事件→效果）是纯 Kotlin 可 JVM 单测, so that 不插手机也能回归状态机。
20. As a 开发者, I want 保活不用 100ms 暴力唤醒轮询, so that 不照搬 MRSS 的高功耗方案。
21. As a 开发者, I want 手机连 PC 后一键完成安装/授权/发通知/采日志, so that 实验可自动化复跑。
22. As a 开发者, I want 每轮实验的现象、失败条件和 logcat 落盘归档, so that 结论可追溯。
23. As a 贡献者, I want 项目 GPL-3.0 开源, so that 机制研究可合法复用（ADR-0002）。
24. As a 贡献者, I want 代码自行实现不搬运 MRSS, so that 不继承停更项目的架构包袱。

## Implementation Decisions

- **模块边界**（对齐 Read me，术语见 CONTEXT.md）：
  - notification：NotificationListenerService → NotificationRepository，产出 Active Notification 事件流
  - core：DashboardCore 纯 Kotlin 状态机——JVM 测试 seam 之一（事件→效果 + `iconSet` 只读视图）
  - rear：RearDisplayBackend 接口 + HyperOS/Shizuku 实现（投送、TaskController、WakeController、Takeover 监听）
  - ui：RearDashboardActivity（背屏 Dashboard）+ 主屏调试 Activity（状态可视 + 测试通知）
- **DashboardCore**：输入事件 `NotificationPosted/Removed(pkg)`、`Allowlist`、`ProjectionReady/Unavailable`（投送通道，见 CONTEXT.md；票 #4 实测应用内投送不需要 Shizuku，故判据是「识别到背屏」而非 Shizuku 连接）、`TakeoverDetected`；输出效果 `LaunchDashboard(iconSet)` / `UpdateIconSet` / `ExitDashboard` / `Degrade(保持监听)`，并暴露 `iconSet` 只读视图供主屏调试页展示。Android 层只做事件→效果的胶水。
- **背屏识别**（ADR-0001 范畴）：非默认 + INTERNAL + `FLAG_PRESENTATION` + `FLAG_OWN_DISPLAY_GROUP`（本机实测 displayId=1, 904×572@450dpi, 左 cutout 296px）。
- **投送**：优先 Shizuku UserService（shell uid）执行 `am start --display <id>`；兜底将既有 task 经 `IActivityTaskManager.moveRootTaskToDisplay` 迁移（shell `service call activity_task` 已验证服务存在）；不硬编码 transaction 编号，优先 binder 直调。
- **准入**：APK `<application>` 声明 `miui.rear.policy=1` meta-data + 背屏 Activity `miui` 值，进系统背屏白名单（免 hook）。
- **保活顺序**：①实测无保活息屏间隔 → ②透明唤醒 Activity（`FLAG_TURN_SCREEN_ON|FLAG_KEEP_SCREEN_ON` + `setShowWhenLocked`）→ ③SCREEN_BRIGHT WakeLock；周期 `KEYCODE_WAKEUP` 仅作最后兜底且频率按实测间隔定。
- **Takeover 恢复**：监听 `miui.intent.action.SUB_SCREEN_ON/OFF` 与系统 SCREEN_ON/OFF 广播触发重投。
- **Degrade**：投送通道不可用（识别不到背屏）时进入 Degrade（监听照常、不投送）；`ProjectionReady` 事件到达即重投当前 Icon Set。Shizuku 掉线本身不触发 Degrade（应用内投送不需要它），其兜底/恢复语义归票 #6。
- **下屏**：末条通知消失 → `ExitDashboard` → 结束背屏 Dashboard 界面（进程内 `RearDashboardHost`），**不 force-stop**——监听服务同进程，force-stop 会连它一起杀掉，此后没人能再自动上屏（票 #5 实测）。
- **Allowlist（POC）**：微信、QQ、本应用、com.android.shell（PC 自动化测试通道）。
- **工程**：包名 `com.rearcue.poc`，minSdk/targetSdk 36（Android 16），Kotlin + Jetpack Compose + Shizuku-API；本机 JDK17 + cmdline-tools 构建。
- **PC 自动化**：实验脚本集完成 install/授权 listener（`cmd notification allow_listener`）/发通知（`cmd notification post`）/dumpsys+logcat 采集，结果写 findings 文档。

## Testing Decisions

- 好测试只测外部行为：DashboardCore 单测断言"事件序列 → 效果序列"，不断言内部状态表示。
- JVM seam 两个（票 #3 引入第二个）：DashboardCore（事件→效果，含 Icon Set 决策）与 NotificationRepository（监听回调→变更事件，含 key 去重与连接时全量对账）。notification/rear/ui 的 Android 胶水不造 JVM 假 shell，由设备实验 E1–E8（PC 脚本 + 3 个人工拍照点：首次上屏、锁屏 30s/5min、AOD 抢回瞬间）覆盖。
- 先例：无（新代码库），本 spec 建立 JVM 单测 + 设备实验矩阵两层基线。

## Out of Scope

- App 内部真实未读数（以 Active Notification 为准）
- 非小米/非 HyperOS 3 设备适配（接口预留）
- 开机自启、免 PC 的 Shizuku 自启引导
- 设置界面、Allowlist 编辑 UI
- Icon Set 溢出交互/动画打磨（上限单行 4 枚 + "＋N"）
- SubScreenCenter 原生卡片/MAML 注入（ADR-0001 否决）
- 正式产品化（发布、崩溃收集、多语言）

## Further Notes

- 设备基线与机制研究结论见 `docs/poc-findings.md`（17 Pro / Android 16 / OS3.0.319.0.WBLCNXM）。
- 已知风险：HyperOS 更新可能改变背屏白名单/service call 行为（MRSS 因此停更）；HyperOS 3.0.304+ `screencap -d 1` 抓不到背屏息屏画面，视觉验证依赖拍照。
- 术语以 `CONTEXT.md` 为准；决策见 `docs/adr/0001`（Route A）、`docs/adr/0002`（GPL-3.0）。

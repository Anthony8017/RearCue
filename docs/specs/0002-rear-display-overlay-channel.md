# Spec 0002：锁屏存活与锁屏首投 —— 背屏覆盖窗口通道

## Problem Statement

手机平放、主屏锁着的时候来了消息，背屏什么都不显示——「锁屏闲置中来通知 → 背屏出指示」这条**主路径至今走不通**：
HyperOS 在 keyguard 锁定稳态下拒绝第三方应用在背屏启动 Activity（票 #6 实测），后台启动限制（BAL）又拦掉应用内 `startActivity`。

而且现在连「先上屏、后锁屏」也守不住：票 #7 三轮复跑，Dashboard 都在锁屏后 1–5 秒被系统收走（锁屏瞬间的重投输给 BAL，
随后背屏 display group 跟着主屏一起断电）。票 #6 曾观察到的「锁屏后一直在背屏」本轮无法复现。

机主要的其实很简单：**手机闲置（甚至已锁屏）时，背屏能告诉他哪几个 App 在等他，并且一直看得到。**

## Solution

给背屏 Dashboard 增加第二条投送通道：**`SYSTEM_ALERT_WINDOW` 覆盖窗口**（`TYPE_APPLICATION_OVERLAY` 加到 display #1）。

覆盖窗口不是 Activity：不走 `ActivityStarterImpl` 的背屏白名单与锁屏策略，也不受 BAL 约束，因此**锁屏闲置时也可能把指示画上背屏**；
窗口级 `FLAG_KEEP_SCREEN_ON` 让背屏在指示在屏期间不跟着主屏熄灭。

先用三个探针把未知验掉（E9 准入 / E10 锁屏可见 / E11 保活），通过就把覆盖窗口落成 `HyperOsRearDisplayBackend`
内部的一条通道——**`RearDisplayBackend` 接口与 `DashboardCore` 都不改**；不通过就把探针脚本与失败条件写进 findings 收口，不硬上实现。

## User Stories

1. As a 机主, I want 锁屏闲置时微信来了通知背屏就出现微信图标, so that 不用翻手机、不用解锁就知道有人找我
2. As a 机主, I want 锁屏闲置时 QQ 来了通知背屏出现 QQ 图标, so that 一眼看出哪个 App 在等我
3. As a 机主, I want 多个 App 同时有通知时图标并列显示, so that 不遗漏任何来源
4. As a 机主, I want 锁屏状态下背屏指示不被系统清掉, so that 抬眼看一眼始终有效
5. As a 机主, I want 指示在屏期间背屏不自动熄灭, so that 不用唤醒手机就能读到
6. As a 机主, I want 通知全部消失后背屏立刻交还原生界面, so that 没通知时不占背屏
7. As a 机主, I want 覆盖窗口不破坏背屏原有手势与触摸, so that 背屏还能正常用
8. As a 机主, I want 背屏上只显示图标不显示消息内容, so that 旁人看不到隐私
9. As a 机主, I want 不 Root 就能用, so that 不牺牲保修、支付安全和系统更新
10. As a 机主, I want 只依赖 Shizuku 而非常驻电脑, so that 日常使用在手机侧闭环
11. As a 机主, I want 主屏解锁或手机重启后状态自动回到正确, so that 不用手动收拾
12. As a 机主, I want 没给「显示在其他应用上层」权限时应用不崩、功能安全降级, so that 权限缺失不变成故障
13. As a 开发者, I want E9/E10/E11 三个未知各有独立判定, so that 失败时能说清卡在哪一条
14. As a 开发者, I want 探针结论用设备事实判定（不是「命令没报错」）, so that 结论可信
15. As a 开发者, I want 通道选择收口在 RearDisplayBackend 实现内部, so that 接口与 DashboardCore 保持不变（spec 0001 story 18）
16. As a 开发者, I want 两条通道共用同一份 Icon Set（IconSetFeed）, so that 背屏内容不漂移
17. As a 开发者, I want 覆盖窗口通道不可用时自动回落到 Activity 通道, so that 已跑通的上屏/下屏链路不受影响
18. As a 开发者, I want 探针能在 `tools/ex` 里一键复跑, so that 结论可回归、可追溯（票 #7 的脚本集）
19. As a 开发者, I want 覆盖窗口的加/撤有明确生命周期（进程重建后按当前 Icon Set 重加）, so that 不出现「通知在但背屏空」
20. As a 贡献者, I want 机制结论与失败条件写进 findings, so that 后续票不重复踩坑

## Implementation Decisions

- **通道不加接口方法**（用户已确认的缝）：覆盖窗口是 `RearDisplayBackend` 实现内部的第二条通道，
  `project/update/exit` 内部决定用 Activity 还是覆盖窗口；`DashboardCore` 与 `RearDisplayBackend` 接口签名不变。
  选择策略待实验定，默认：Activity 通道优先，覆盖窗口作为「锁屏闲置 / 应用内投送被 BAL 拦」时的兜底；
  若 E9–E11 证明覆盖窗口在解锁态同样稳，可提升为主通道（本票先按兜底实现）。
- **权限**：声明 `SYSTEM_ALERT_WINDOW`，用 `adb shell appops set com.rearcue.poc SYSTEM_ALERT_WINDOW allow` 授权
  （HyperOS 逐应用授权在重装后会被重置，`tools/ex` 的安装步骤固化这一步，沿用票 #7 的做法）；未授权即视为通道不可用。
- **窗口**：`TYPE_APPLICATION_OVERLAY`，经 `createDisplayContext(display)` 取该屏的 `WindowManager` 添加；
  窗口参数显式声明 `FLAG_NOT_FOCUSABLE`、`FLAG_LAYOUT_IN_SCREEN`、`FLAG_KEEP_SCREEN_ON`；
  是否再加 `FLAG_NOT_TOUCHABLE`（让背屏手势穿透 / 还是窗口吃掉触摸）由实验决定并写进结论。
- **渲染**：复用 Dashboard 的 Compose 内容（时间 + Icon Set）与 `IconSetFeed`；覆盖窗口需要自带
  lifecycle owner / saved state（非 Activity 窗口承载 ComposeView 的标准做法），这是本票唯一的 Android 侧新胶水。
- **准入**：manifest 的 `miui.rear.policy=1` 不动（E2 结论：必要且充分，那是 Activity 的门）。
  实验要单独判定 MIUI 是否对覆盖窗口另有门控（对照：SYSTEM_ALERT_WINDOW 授权 / 未授权两组）。
- **保活**：只用窗口级 `FLAG_KEEP_SCREEN_ON`，不做周期唤醒轮询（spec 0001 story 20）。
  `setTurnScreenOn` 是 Activity API，覆盖窗口点不亮背屏；若实验证明锁屏态必须主动点亮才可见，
  本票只记录该事实与代价，唤醒方案另开票。
- **生命周期**：Backend 内部状态机管窗口的加/撤——有 Icon Set 且通道可用 → 加；空集或 `exit()` → 撤；
  进程重建后按当前 Icon Set 重加（等价于票 #5 的 `update()` 自愈语义）。
- **实验 seam**：`tools/ex` 新增 `overlay` 场景（E9 准入 / E10 锁屏可见 / E11 保活），判定复用票 #7 的解析层
  （`dumpsys display` 的背屏 state、`dumpsys activity activities` 的 display #1 归属、logcat 事件 + 系统行），
  按需补判定词表（例如系统侧的 `add system_window on rear display`）。
- **失败即交付**：三条探针任一不过，本票交付物退化为「探针脚本 + findings 结论 + 失败条件」，
  实现部分不做——先证伪再决定要不要改架构。

## Testing Decisions

- 好测试只测外部行为：事件序列 → 效果序列，或设备事实 → 判定。不断言内部实现细节。
- **不新增 JVM seam**（用户已确认）：`DashboardCore` 行为不变，既有单测即回归网（先例：`core` 模块的
  `DashboardCoreTest`，断言的是效果序列）。
- 若新增纯解析函数（覆盖窗口是否在屏 / 背屏是否保持 ON / 窗口是否被系统移除），沿用 `tools/ex` 解析层风格：
  文本进、事实出，配 Pester fixture 测试（先例：`tools/ex/tests/ExCommon.Tests.ps1`，32 例，fixture 取自真机输出）。
- 设备实验覆盖 Android 胶水（先例：票 #7 的 `04-drive.ps1` + `05-collect.ps1`）：
  - **E9 准入**：背屏在 SYSTEM_ALERT_WINDOW 授权后能否被加上覆盖窗口（对照未授权组），判定 = 窗口是否真的在 display #1。
  - **E10 锁屏可见**：主屏锁屏后窗口是否还在背屏（对照今天的 Activity 通道 1–5 秒掉屏）。
  - **E11 保活**：窗口在屏期间背屏 state 是否保持 ON（`dumpsys display`），以及主屏解锁/通知清空后是否正常撤下。
- 每次实验归档一个 session 目录并写 `summary.md`（先例：`docs/poc-logs/<时间戳>-<任务>/`）。

## Out of Scope

- 通知内容与未读数（仍以 Active Notification 为准）
- 非小米 / 非 HyperOS 3 设备适配
- 免 PC 的 Shizuku 自启、MIUI 自启动白名单引导（人工步骤，票 #5/#7 已记录）
- 覆盖窗口的交互设计（点按打开 App、滑动清除等）
- 周期唤醒轮询保活（MRSS 式 100ms `KEYCODE_WAKEUP`）
- 「背屏手势把界面顶掉后自动恢复」（票 #7 记录的独立问题，另开小票）
- 正式产品化（发布、崩溃收集、多语言）

## Further Notes

- 现状证据：`docs/poc-findings.md` 的「实验矩阵」E3/E4/E6 行与「票 #7」小节；锁屏 1–5 秒掉屏的原始证据
  `docs/poc-logs/20260922-113847-probe-visible/`（BAL 拦重投 + `PowerGroup: Powering off display group due to
  power_button (groupId= 1)`）。
- 票 #6 的成功对照（窗口级声明曾经救活过背屏）：`WindowManager: Started waking up... (groupId=1
  why=ON_BECAUSE_OF_APPLICATION)`。
- 机制线索：HyperOS 的 `services.jar` 里有 `" add system_window on rear display` 这条日志，说明背屏存在一条
  WindowManager 侧的窗口通道（票 #6 的下一步清单里已列为候选）。
- 已知风险：MIUI 可能对覆盖窗口另有门控（未验证）；背屏手势/系统策略可能清掉窗口；`SYSTEM_ALERT_WINDOW`
  在 HyperOS 上可能需要在设置里手动开「显示在其他应用上层」。
- 术语以 `CONTEXT.md` 为准（Dashboard / Takeover / Degrade / 投送通道）；本票若落地覆盖窗口通道，
  需在 `CONTEXT.md` 的「投送通道」条目下补一条术语（例如「覆盖窗口通道」）。

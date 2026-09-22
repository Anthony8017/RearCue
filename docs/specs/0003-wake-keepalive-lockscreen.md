# Spec 0003：锁屏存活重攻 —— 唤醒保活 + Activity 通道（探针先行）

## Problem Statement

手机放着、主屏锁着时来了消息，背屏什么都不显示——「锁屏闲置中来通知 → 背屏出指示」这条主诉求至今没解决。
覆盖窗口路线（spec 0002）已被设备事实证伪（背屏窗口策略只放行系统应用），但问题本身没变：机主想要的仍是
**手机闲置（甚至锁屏）时，抬眼就能看到背屏告诉他哪几个 App 在等他，并且一直看得到。**

已有的基础：Activity 投送通道在解锁态稳定（E1 6/6、E7 5/5）；锁屏后 Dashboard 1.3–1.4 秒被系统收走，
两个原因是背屏随主屏断电 + 锁屏瞬间重投被后台启动限制（BAL）拦掉。

## Solution

不换投送通道（仍是 Activity 投送），换**保活**思路：参考 MiRearScreenSwitcher（MRSS）的机制——
经 Shizuku 循环注入定向背屏的唤醒键，让背屏在指示期间保持点亮（Wake Keep-alive，见 CONTEXT.md）。
若背屏不断电，锁屏瞬间掉屏的两个原因之一即消失，Dashboard 有守住的可能（票 #6 曾观察到一次
「锁屏后一直在背屏」，不可复现，需探针定论）。

先用探针把三个未知验掉，通过才谈实现，任一失败即交付（探针脚本 + findings 结论 + 失败条件）：

- **E12 保活**：周期唤醒键能否让背屏在锁屏后保持点亮（对照：今日 +5s 离开 ON、+10s 进 DOZE_SUSPEND）。
- **E13 锁屏守住**：保活生效时，锁屏后 Dashboard 是否留在背屏（对照：今日 1.3–1.4s 被收走）。
- **E14 锁屏首投**：锁屏稳态下用任务搬运事务（MRSS 的另一条上屏路径）能否把 Dashboard 搬上背屏
  （`am start` 已实测被拒，与调用方 uid 无关；这条事务是未验证口子）。

另附一条一次性小探针（不阻塞主线）：以 shell uid（Shizuku UserService）加覆盖窗口是否被认作系统应用——
票 #12 留的唯一重开条件，大概率不行，验完写进 findings。

## User Stories

1. As a 机主, I want 锁屏闲置时背屏一直显示哪个 App 在等我, so that 不用翻手机、不用解锁就知道有人找我
2. As a 机主, I want 锁屏中来微信/QQ 通知时背屏出图标（尽力而为，以探针事实为准）, so that 锁屏时来的消息也有机会看到
3. As a 机主, I want 多个 App 同时有通知时图标并列显示, so that 不遗漏任何来源
4. As a 机主, I want 指示在屏期间背屏不熄灭, so that 抬眼看一眼始终有效
5. As a 机主, I want 主屏解锁后状态自动回到正确, so that 不用手动收拾
6. As a 机主, I want 通知全部消失后背屏立刻交还原生界面, so that 没通知时不占背屏
7. As a 机主, I want 背屏上只显示图标不显示消息内容, so that 旁人看不到隐私
8. As a 机主, I want 不 Root 就能用, so that 不牺牲保修、支付安全和系统更新
9. As a 机主, I want 只依赖 Shizuku 而非常驻电脑, so that 日常使用在手机侧闭环
10. As a 机主, I want 保活不过分耗电（强度可调）, so that 日常挂着不心疼电量
11. As a 机主, I want 权限缺失或 Shizuku 掉线时不崩、安全降级, so that 故障不变成手机问题
12. As a 机主, I want 手机重启后状态自动回到正确, so that 不用手动重开什么
13. As a 开发者, I want E12/E13/E14 三个未知各有独立判定, so that 失败时能说清卡在哪一条
14. As a 开发者, I want 探针结论用设备事实判定（不是「命令没报错」）, so that 结论可信
15. As a 开发者, I want 探针能在 tools/ex 里一键复跑并归档 session, so that 结论可回归、可追溯
16. As a 开发者, I want 失败即交付, so that 先证伪再决定要不要改架构
17. As a 开发者, I want DashboardCore 与 RearDisplayBackend 接口保持不变, so that 已跑通的上/下屏链路不受影响
18. As a 开发者, I want 保活强度可调并记录代价（耗电/发热实测数据）, so that 可见性与省电可权衡
19. As a 开发者, I want shell uid 加窗探针给出明确答案, so that 票 #12 的重开条件有结论
20. As a 贡献者, I want 机制结论与失败条件写进 findings, so that 后续票不重复踩坑

## Implementation Decisions

- **探针先行、失败即交付**（沿用 spec 0002 模式）：E12/E13/E14 任一不过，交付物退化为探针脚本 +
  findings 结论 + 失败条件，实现部分不做。E12 是 E13 的前置；E14 独立于 E12/E13 判定。
- **通道不改**：`RearDisplayBackend` 接口与 `DashboardCore` 都不动。唤醒保活是投送通道之外的
  「让背屏保持点亮」的手段，不是第二条通道；spec 0002 no-go 后通道决策维持「仅 Activity 通道」
  （应用内投送为主、Shizuku 命令兜底）。
- **保活机制候选**：经 Shizuku shell 循环注入定向背屏的唤醒键（MRSS 机制；GPL 项目只学机制、不抄代码）。
  间隔做成可调参数，POC 不定死 MRSS 的 100ms，实测调优并记录代价。
- **E14 的锁屏首投**：用任务搬运事务（moveRootTaskToDisplay 的 binder 事务，MRSS 的第二条上屏路径）
  作为未验证口子；已知 `am start --display` 在锁屏稳态被 ActivityStarter 拒（与调用方 uid 无关），
  事务搬运绕不绕得过由此探针作答，不做机制猜测。
- **shell uid 加窗探针**：一次性小探针，验 Shizuku UserService 加窗是否被背屏窗口策略认作系统应用。
  失败为预期内（票 #10 结论：窗口属于调用进程 uid），不阻塞主线，答案写进 findings 的重开条件。
- **实验 seam 沿用 tools/ex**：新增纯解析函数（背屏 state 采样、事务搬运结果、唤醒键事件判定）
  文本进、事实出，配 Pester fixture；每个探针一条一键命令、归档一个 session 目录。
- **保活循环若落地**：属 rear/Shizuku 层胶水（循环启停跟随 Icon Set 生命周期），`DashboardCore` 不改，
  等价于 spec 0002 确认过的缝。
- **ADR 条件**：E12 通过后，「用周期轮询换可见」推翻 spec 0001 story 20 的非轮询原则，届时补一份 ADR
  记录取舍；E12 不通过则不产生该决策。

## Testing Decisions

- 好测试只测外部行为：设备事实 → 判定，或事件序列 → 效果序列。不断言内部实现细节。
- **不新增 JVM seam**（沿用 spec 0002 决定）：`DashboardCore` 行为不变，既有单测即回归网
  （先例：`core` 模块的 `DashboardCoreTest`，断言效果序列）。
- 新增纯解析函数沿用 tools/ex 解析层风格：文本进、事实出，配 Pester fixture 测试
  （先例：`tools/ex/tests/ExCommon.Tests.ps1`，fixture 取自真机输出，不造假数据）。
- 设备实验覆盖 Android 胶水（先例：票 #10 的 `06-overlay.ps1`、票 #11 的 `07-lock-compare.ps1`）：
  - **E12**：锁屏 + 保活开/关对照，逐点采样背屏 state（判定：保活下背屏是否离开 ON）。
  - **E13**：保活生效 + 上锁，观察 Dashboard 在屏时长（判定：守住 / 被收走及收走时刻）。
  - **E14**：锁屏稳态下事务搬运（判定：Dashboard 是否真的落到背屏，含系统侧日志）。
  - 判定一律以设备事实（dumpsys/logcat/系统侧日志）为准，不以命令退出码为准（E9 的先例）。
- 每次实验归档一个 session 目录并写 `summary.md`（先例：`docs/poc-logs/<时间戳>-<任务>/`）。

## Out of Scope

- 覆盖窗口通道（已 no-go，重开条件见票 #12 验收）
- 通知内容与未读数（仍以 Active Notification 为准）
- 非小米 / 非 HyperOS 3 设备适配
- 免 PC 的 Shizuku 自启、MIUI 自启动白名单引导（人工步骤，票 #5/#7 已记录）
- Dashboard 的交互设计（点按打开 App、滑动清除等）
- 省电调优到日常可用水准（POC 只把间隔做成可调并记录代价）
- 「背屏手势把界面顶掉后自动恢复」（票 #7 记录的独立问题，另开小票）
- 正式产品化（发布、崩溃收集、多语言）

## Further Notes

- 机制事实（_research 的 MRSS 克隆，只学机制不抄代码）：保活 = 经 Shizuku shell 循环注入
  定向背屏的唤醒键（约 100ms 一次，多个服务内有循环，注释与实际间隔不一致）；上屏 = `am start --display`
  与任务搬运事务并用。本票的 E12/E14 即对这两个机制在本机（HyperOS 3）的可行性的独立验证。
- 现状证据：`docs/poc-findings.md` 的「票 #11 验收」（Activity 锁屏 1.3–1.4s 被收走、背屏 +5s 离开 ON）
  与「票 #12 验收」（覆盖窗口 no-go 与重开条件）。
- 已知风险：唤醒键可能被 HyperOS 忽略或只短暂延迟熄屏；锁屏稳态的启动限制与调用方 uid 无关，
  E14 可能同样被拒；周期唤醒的耗电/发热代价未知；票 #6 的单次存活观察不可复现，可能与外部指纹唤醒混淆
  （票 #11 首轮曾被指纹唤醒污染，见其 erratum）。
- 术语以 `CONTEXT.md` 为准（Wake Keep-alive / Dashboard / 投送通道 / Takeover）；保活结论落定后
  按需更新「唤醒保活」条目。

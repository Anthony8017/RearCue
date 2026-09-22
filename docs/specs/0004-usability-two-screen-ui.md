# Spec 0004：真实使用可用性（票 #6）与两屏 UI 美化/安全区适配

状态：draft → ready-for-agent。术语一律见 CONTEXT.md（Main Display 主屏 / Rear Display 背屏 / Dashboard / Icon Set / Allowlist App / Active Notification / Degrade / Projection Channel / Wake Keep-alive / Lock-screen First Cast / Takeover / Native Rear Screen）。遵守 ADR 0001/0002/0003。

## Problem Statement

机主在真实使用中遇到两类问题。第一类是**背屏指示会悄悄失效**：HyperOS 在后台冻结本应用（GreezeManager）时通知事件被压到解冻才投递；进程被杀后 MIUI 需要「自启动」白名单才肯重绑通知监听。机主既不知道要去开自启动、也看不见当前是否处于这种亚健康状态，只能发现「背屏不亮了」。第二类是**界面粗糙且有遮挡风险**：主屏调试页是裸布局，背屏 Dashboard 的内容没有针对相机开孔与圆角的硬约束，防烧屏漂移也可能把内容漂到不可用区域——在小米 17 Pro 上背屏左侧三分之一是相机带，内容一旦越界就被相机模组挡住。

## Solution

从机主视角：

- 主屏出现**可用性状态横幅**：监听/自启动状态异常时自动现身，一键跳转 MIUI 自启动设置页；返回后自动复查，恢复正常自动消失。健康时不打扰。
- HyperOS 冻结行为被**探针摸清**并在文档中给出表现与应对手段清单，机主至少知道延迟从何而来。
- 两屏统一为 **AMOLED 纯黑 + 单一强调色**的设计语言：主屏调试/引导页翻新，背屏 Dashboard 保持纯黑极简但排版成体系；所有元素严格落在安全区内——主屏避开顶部挖孔/圆角/手势条，背屏只用右侧 608×572 可用区，防烧屏漂移永不越界。
- **Wake Keep-alive 默认间隔定档 5000ms**（不调设置即锁屏守住 Dashboard），并补跑 5000ms 干净代价轮，把耗电/发热代价写成定档数据。

## User Stories

1. 作为机主，我希望 Allowlist App 的 Active Notification 到达时背屏自动出现 Dashboard，以便不翻手机就知道有消息。
2. 作为机主，我希望 Active Notification 清空时背屏自动交还 Native Rear Screen，以便背屏不残留噪音。
3. 作为机主，我希望进程被杀后通知监听能自动重绑，以便清理内存后背屏指示不失效。
4. 作为机主，我希望在主屏看到通知监听/自启动状态是否健康，以便不用猜「背屏为什么不亮」。
5. 作为机主，我希望状态异常时出现可用性引导横幅，以便问题被主动暴露而不是事后发现。
6. 作为机主，我希望横幅一键跳转 MIUI 自启动设置页，以便不在系统设置里到处翻。
7. 作为机主，我希望从设置页返回后状态自动复查，以便立刻确认修复是否生效。
8. 作为机主，我希望状态恢复后横幅自动消失，以便界面不长期悬挂警告。
9. 作为机主，我希望横幅出现/消失不遮挡 Icon Set 与关键状态、触控目标 ≥48dp，以便不误触、不漏看。
10. 作为机主，我希望主屏界面以 AMOLED 纯黑 + 单一强调色呈现，以便夜间使用不刺眼且省电。
11. 作为机主，我希望主屏一切内容避开顶部挖孔、四角圆角与底部手势条，以便任何元素都不被相机开孔或边缘遮挡。
12. 作为机主，我希望背屏 Dashboard 的时间与 Icon Set 只落在右侧可用区，以便内容不被左侧相机带挡住。
13. 作为机主，我希望防烧屏漂移的任何时刻都不越出安全区，以便漂移过程中不出现半截图标或半截数字。
14. 作为机主，我希望背屏与主屏共用同一套视觉语言，以便两屏观感一致、没有拼凑感。
15. 作为机主，我希望 Dashboard 时间/图标与主屏正文在深色下对比度达标（正文 ≥4.5:1、次要 ≥3:1），以便强光和暗光下都可读。
16. 作为机主，我希望图标风格统一（同一图标集、统一线性/填充与尺寸令牌），以便界面专业可信。
17. 作为机主，我希望 Wake Keep-alive 默认以 5000ms 间隔注入唤醒键，以便不改任何设置就能在锁屏下守住 Dashboard。
18. 作为机主，我希望保活的耗电/发热代价有实测定档数据，以便判断这个代价值不值得。
19. 作为机主，我希望了解应用被 HyperOS 冻结时的确切表现（事件延迟到解冻），以便理解通知指示为何迟到。
20. 作为机主，我希望可行的防冻结应对手段被整理成清单，以便后续决定是否继续投入。
21. 作为机主，我希望 Degrade（Projection Channel 不可用）状态在主屏可见，以便知道何时会自动重投。
22. 作为机主，我希望 UI 翻新不改变既有调试旁路（投送/退出按钮）与 adb 调试命令语义，以便已有脚本和习惯继续可用。
23. 作为机主，我希望界面文案统一使用项目术语（Dashboard、Icon Set 等），以便对照文档理解每个状态。
24. 作为开发者，我希望新增决策逻辑以事件→效果的纯 Kotlin 形式钉死在 JVM 单测里，以便回归不靠手点。
25. 作为开发者，我希望安全区/漂移边界计算是纯函数并有单测覆盖各种开孔组合，以便换机型只需换输入。
26. 作为开发者，我希望本轮实机实验（5000ms 代价轮、冻结探针、自启动探针）全部留痕归档，以便结论可复核。
27. 作为开发者，我希望既有 145 例单测一条不回退，以便重构有安全网。
28. 作为开发者，我希望 README 模块表与实现同步（保活不再是「预留」），以便新读者不被误导。

## Implementation Decisions

- **模块改动**：`:core`（DashboardCore 事件/效果词汇扩展）、`:rear`（纯 Kotlin 显示几何约束 + WakeKeepAlive 默认间隔）、`:app`（主题令牌、两屏 Compose 界面翻新、可用性横幅、MIUI 自启动设置页跳转、状态检测适配）；`:notification` 不动。
- **单 seam 原则**：不新增任何测试 seam。横幅显隐是纯决策，收进现有最高 seam——DashboardCore 的事件→效果接口（新事件：自启动状态、监听健康；新效果：显示/隐藏可用性引导）；Android/Compose 层保持「系统信号→事件、效果→动作」的零决策搬运。
- **显示几何约束**是纯 Kotlin 模块（属 `:rear` 现有纯 Kotlin seam）：输入 display cutout 矩形、圆角半径、漂移幅度，输出内容安全矩形与漂移边界。运行时从系统 DisplayCutout 读取，不硬编码（沿用背屏识别不硬编码 displayId 的先例）；实测数字只作验收基准：背屏面板 904×572、cutout 全高左带 296px、圆角 97、可用区 608×572；主屏 1220×2656、顶部挖孔 150px、圆角 190、底部手势条 52px。
- **主屏安全区**用平台 WindowInsets（displayCutout/safeDrawing），不自算几何；「严格安全区」标准：内容与漂移全程在安全矩形内，不做「视觉居中、仅关键元素避让」。
- **Wake Keep-alive 默认间隔定档 5000ms**，仍可调（现有间隔可调语义不变）。依据 CONTEXT.md 实测边界：500ms/5000ms 在 60s 窗内全程 ON，30000ms 守不住。
- **自启动检测是未知数**：先探针（能否检测 MIUI 自启动白名单状态、自启动设置页的精确入口），检测不可行则降级为「仅手动跳转按钮 + 明示文案」，不伪造健康状态。
- **GreezeManager 冻结**：只做探针——复现条件、被冻时事件表现、应对手段清单；不做实现，结论另立票。
- **本轮交付的实机实验**：5000ms 干净代价轮重跑（补票 #21 的 COST-INVALID 缺档；轮内设备须空载）、冻结探针、自启动探针。产出按仓库惯例落 poc-logs/findings。
- **视觉体系**：AMOLED 纯黑 + 单一强调色，设计令牌经 ui-ux-pro-max（jetpack-compose）产出并全项目语义化（颜色/间距/图标尺寸令牌），不逐屏硬编码；触控目标 ≥48dp，按压反馈 80–150ms；禁止 emoji 作结构图标，用矢量图标集；空/错误/禁用态齐全。
- **架构约束不变**：投送主路径仍是 Activity 投送（ADR 0001），保活仍是周期注入定向背屏唤醒键（ADR 0003，本 spec 只调默认间隔，不开新 ADR），许可 GPL-3.0（ADR 0002）。
- **验收留痕**：每项功能实机验收（adb 复核上屏/insets/状态、poc-logs 归档），沿用 E 系列探针与验收段落惯例。

## Testing Decisions

- **好测试只测外部行为**：事件→效果（seam 1）、输入几何→输出边界（seam 3）；不断言实现细节、不快照 Compose UI 树、不测 Android 框架回调本身。
- **被测模块**：
  - `:core` DashboardCore——新事件（自启动状态/监听健康）→ 横幅显隐效果；既有 LaunchDashboard/UpdateIconSet/ExitDashboard 回归。判例：DashboardCoreTest 中 Allowlist 运行时变更的事件→效果测试（收窄/清空/扩容三态），横幅决策照同一模式。
  - `:rear` 纯 Kotlin——显示几何约束（cutout 带在左/上、圆角半径、漂移幅度组合 → 安全矩形与漂移边界）；WakeKeepAlive 默认间隔。判例：RearDisplayLocatorTest（背屏 flag 判定）、WakeKeepAliveTest。
  - `:notification` 回归不动：NotificationRepositoryTest。
- **Compose/Android 层不写 JVM 决策测试**（本无决策）；以实机验收清单 + ui-ux-pro-max 的 design_review 检查项（对比度、触控目标、安全区、深浅色独立验证）替代。
- **基线**：`gradlew test` 全绿，145 例一条不回退；新增测试补在既有 seam 的既有测试类旁。

## Out of Scope

- **通知追踪管理**（Allowlist App 增删 UI + 持久化）——已定为下一轮主题。届时 DashboardEvent.Allowlist 的事件→效果语义已有测试在位，只需补持久化、接线与设置页。
- **防冻结实现**——待冻结探针结论另立票；本 spec 只交事实与手段清单。
- **单条通知增删/屏蔽**——术语层面不存在「追踪中的通知」这种对象（Active Notification 是系统事实，Allowlist App 的增删粒度是应用），本 spec 及后续均不做。
- **Overlay Window 第二投送通道**——票 #12 已 no-go，不翻案。
- **Instrumentation / Compose UI 测试框架引入**。
- **产品化主页与设置页**——与下一轮白名单管理一起做，本轮只翻新既有两屏界面 + 引导横幅。

## Further Notes

- 两屏几何为 2026-09-22 实机探针原始事实：背屏（displayId 1）904×572、450dpi、installOrientation=1、cutout=左侧全高带 Rect(0,0–296,572)、safe inset 仅 left=296、圆角 r=97、无系统栏 insets；主屏（displayId 0）1220×2656、520dpi、挖孔 Rect(573,0–647,150)、safe top=150、圆角 r=190、底部手势条 52px。换机型时几何约束模块的输入随 DisplayCutout 自动变化，但验收数字仅对本机成立。
- Wake Keep-alive 语义边界（500/5000/30000ms 实测）与 Lock-screen First Cast 失败词表（NO-TASK/REJECTED/TXN-BROKEN/NO-EFFECT）沿用 CONTEXT.md，本 spec 不改词。
- 「绝对不点亮主屏」仍未定论（CONTEXT.md 记载），本轮代价轮若顺带取得干净观察则回填 findings，不作为承诺项。
- README 模块表纠偏（Wake Keep-alive 已实现却标「预留」）随本轮顺手修。

# Spec 0015：通知图标出现/消失动效——轻回弹入场、收缩退场、平滑补位

状态：已与机主 grill 收口（2026-09-29，两轮 17 问，frontier 空，均按推荐定案）；#146（退屏宽限）/#147（入场）/ #148（退场）/ #149（档位重排）均已实现并经集成分支合并（PR [#151](https://github.com/Anthony8017/RearCue/pull/151)，待审查），JVM 判例在集成分支上全绿；#150 收口完成并**已实机验收**（2026-09-29 12:2x–12:5x，见下方「收口记录」与验收记录：`failed=0 inconclusive=3`，三项 INCONCLUSIVE 全是「机主真实通知在册让 Icon Set 清不空 / 1 行态构造不出」的环境前置，不是产品缺陷）。术语采用 CONTEXT.md 的 Icon Set、Shade-visible Notification、Active Notification、Detail View、Content Page、Notification Highlight。编号说明：**0014 已被 #135《Spec 0014：背屏通知以下拉栏可见集合为准（Shade-visible）》占用（正文只存在于 issue，`docs/specs/0014-*.md` 至今缺失——本 spec 收口时记录该编号空档，未代为补档）**，故本 spec 取 0015。

跟踪：[Issue #145](https://github.com/Anthony8017/RearCue/issues/145)，子票 [#146](https://github.com/Anthony8017/RearCue/issues/146)、[#147](https://github.com/Anthony8017/RearCue/issues/147)、[#148](https://github.com/Anthony8017/RearCue/issues/148)、[#149](https://github.com/Anthony8017/RearCue/issues/149)、[#150](https://github.com/Anthony8017/RearCue/issues/150)；PR [#151](https://github.com/Anthony8017/RearCue/pull/151)（待审查）。
验收记录：[docs/poc-logs/20260929-115451-spec0015-icon-animations/README.md](../poc-logs/20260929-115451-spec0015-icon-animations/README.md)；驱动脚本：[drive-acceptance.ps1](../poc-logs/20260929-115451-spec0015-icon-animations/drive-acceptance.ps1)。

## Problem Statement

机主在背屏 Dashboard 上看通知时，图标只能「硬跳」：收到通知，图标凭空出现在网格里，其余图标同时瞬移补位；清掉通知，图标凭空消失，剩下的又瞬移一次。当前实现里整组图标帧是整体重算、按绝对坐标重画的，前后两帧之间没有任何过渡，所以一次通知增减在屏幕上表现为两处跳变。机主的诉求是让图标的出现与消失有动效，并接受以此带来的补位滑动。

## Solution

给 Icon Set 的每一枚图标（图标本体 + 数字角标一起）加入场与退场动效，并让位置变化平滑过渡：

- **入场**：新图标以约 0.25 秒的轻回弹出现（轻微超出目标尺寸再收回），角标同步淡入。沿用仓内既有的弹性风味（Detail View 展开同一族），不做夸张的弹跳。
- **退场**：图标收缩淡出。
- **补位**：其余图标平滑滑到新位置；最新通知把某应用挪到左上时也滑过去，不再瞬移。
- **档位重排**：行数与溢出提示变化（1 行 ↔ 2 行、6 枚 ↔ 7 枚、充电让位电量数字）时，整组平移缩放到位。
- **最后一个图标**：屏幕陪着把退场演完再交还系统；宽限窗上限 1 秒，其间又来通知就取消退屏。手动退屏（Quick Tile / 手势）不等待，立即交还。
- **不做退场的三种情况**：只在「+N」里的溢出应用被清掉（只换数字）；Detail View 正开着时被清掉的图标；数字变化（1→2）本身。
- 背屏 Dashboard 与主屏总览页（Icon Set 同一份事实源）用同一套动效参数，观感一致。
- 与既有 3 秒 Notification Highlight 各管各，只保证叠加时不打架。
- 同一时刻多个图标各自独立演，允许重叠；不响不震。

## User Stories

1. 作为机主，我希望新通知的图标在背屏上有出现动效，以便一眼注意到刚才来了哪条通知。
2. 作为机主，我希望出现动效是轻回弹而不是夸张弹跳，以便通知密集时画面不显得吵闹。
3. 作为机主，我希望角标数字跟着图标一起出现，以便看到的是一枚完整应用图标而不是「先有图、后冒数字」。
4. 作为机主，我希望通知被清掉时图标收缩淡出，以便消失不像凭空蒸发。
5. 作为机主，我希望剩余图标平滑补位而不是瞬移，以便一次通知增减在屏幕上只是一次连续变化。
6. 作为机主，我希望最新通知对应的应用挪到左上角时是滑过去的，以便不看到它「闪一下换个地方」。
7. 作为机主，我希望图标数量在 1 行与 2 行之间变化时整组平滑移动，以便网格换挡不像画面抽搐。
8. 作为机主，我希望第 7 个应用出现或消失时整组的缩放是连续的，以便六格容量边界的切换不刺眼。
9. 作为机主，我希望充电时让位电量数字的移动也是平滑的，以便插电瞬间图标不会硬挪一下。
10. 作为机主，我希望最后一条通知清掉时，背屏陪着把退场演完再消失，以便最后一次消失不是硬跳。
11. 作为机主，我希望这个「多留一瞬」不超过一秒，以便不觉得屏幕卡着不走。
12. 作为机主，我希望退场宽限期内又来了新通知就不退屏，以便密集通知不会把屏幕关了又开。
13. 作为机主，我希望手动退屏（快捷开关／手势）仍然立即交还屏幕，以便我说了关就关。
14. 作为机主，我希望充电持有、Agent 页持有、Detail View 打开时的行为一个字都不变，以便动效不影响既有使用习惯。
15. 作为机主，我希望只在「+N」里的溢出应用被清掉时不演退场，以便屏幕上没有它的位置就不假装它刚在那儿。
16. 作为机主，我希望正在看的 Detail View 对应的图标被清掉时只是详情正常收起，以便不出现两套动效叠在一起的乱象。
17. 作为机主，我希望同一个应用的角标数字从 1 变 2 时不触发动效，以便密集通知时画面不一直动。
18. 作为机主，我希望多枚图标同时出现时各自演各自的，以便短时间内连来几条通知也能看到每一条的反馈。
19. 作为机主，我希望主屏总览页的图标有同一套动效，以便两处观感一致、不产生「背屏比主屏灵活」的困惑。
20. 作为机主，我希望动效不发声、不震动，以便背屏贴在床头或桌上时不打扰。
21. 作为机主，我希望出现动效不额外加闪光或变色，以便不和既有的 3 秒呼吸高亮抢注意力。
22. 作为机主，我希望动效不改变图标的尺寸与六格容量规则，以便图标还是刚才那个大小、还是能放六个。
23. 作为机主，我希望防烧屏的每分钟微移仍然是瞬时的，以便不每分钟看到一次整组滑动。
24. 作为机主，我希望切到 Agent 页时通知页的整体交叉淡入行为不变，以便内容页切换手感不回归。
25. 作为维护者，我希望退屏宽限是决策层的唯一行为变化，以便它有 JVM 判例可断言、不靠目视兜底。
26. 作为维护者，我希望动效渲染不新增跨模块契约、不新增持久化字段，以便背屏 UI 继续零决策。
27. 作为维护者，我希望补位几何继续走既有纯几何 seam，以便「图标数量与尺寸档位」的既有判例不回归。
28. 作为维护者，我希望退屏宽限有日志锚词形契约，以便实机验收链能断言，不靠截图取证。
29. 作为维护者，我希望动效在实机上的手感（时长、回弹幅度、帧率、发热）有归档证据，以便后续调整有基线。
30. 作为维护者，我希望 spec 文件的镜像与验收归档由收口票完成，以便状态条与证据指针一致。

## Implementation Decisions

- **单一决策 seam**：唯一的行为变化收口在 DashboardCore 的「事件 → 效果」出口——**退屏宽限**。图标集清空且无任何持有理由时，不立刻 `ExitDashboard`，而是记下「空集起始时刻」，像既有持有理由那样只发 `UpdateIconSet` 让屏幕留在屏上继续呈现；宽限窗内每个事件都重新判定（新通知到达即取消退屏），窗满则发 `ExitDashboard`。宽限上限取核心常量，按「大于一次退场动效、小于用户可感知的迟滞」设定，以 1 秒为上界。时钟沿用既有可注入虚拟时钟 seam（`nowMs`），不新增第三个 seam。
- **不扩效果契约**：不新增 `DashboardEffect` 子类型、不做「UI 播完动画再回执 core」的反向握手、不新增持久化字段、不新增设置项。退屏宽限是纯计时决策，core 不感知动画是否播完。
- **退屏路径不回归**：手动退屏（ManualExit）不等宽限、立即交还；充电持有、Agent 持有、Detail 持有、Content Page 的既有投/撤规则与切页手感全部不变；被 Takeover / 断连的既有交还路径不变。
- **零成本路径**：未在屏、无图标集等既有短路路径不得因宽限而多留一帧。
- **背屏 UI 零决策**：入场/退场/补位由 UI 对比投送通道前后两帧的 Icon Set 自行推导，以应用包名作稳定身份（不用下标）；退场需要多留一帧的旧帧快照，退出中的条目不参与新增的几何重算；正在淡出的图标在该帧内容为空时按「已呈现的图标集为空」处理，避免重叠渲染。
- **补位几何**：退出中的图标仍占格位，其余图标的新位置按既有纯几何 seam（Icon Set 的排布计算）算，改动只落在「同一帧内位置如何过渡」，不放宽 spec 0012 的图标尺寸、角标比例与六格容量规则。
- **动效参数**：入场与补位用同族缓动，约 0.25 秒、幅度轻微（回弹幅度以「看得出、不喧哗」为准，实现时以既有 Detail View 弹性参数为基准调校）；退场为同族反向的收缩淡出。两屏共用同一套参数。防烧屏的每分钟全局漂移不算位置变化，保持瞬时。
- **两屏各自落地**：背屏是自定义整帧排布，主屏总览是流式排布（同一份 AppState 投影），两处渲染机制不同但共用同一套时长与缓动取值；主屏改动不得影响主屏其它区块。
- **日志锚**：新增图标入场触发的日志锚与退屏宽限进入/到期的日志锚，按项目 LOG_*_CONTRACT 惯例冻结词形（ASCII 前缀，tools/ex 验收链按词形读）；既有锚词形不变。
- **不新增**：新 ADR、新依赖（`androidx.compose.animation` 已在传递依赖内）、新权限、新手势、减少动效（reduced motion）开关、静音/震动反馈。

## Testing Decisions

- 好测试只测外部行为：事件序列 → 效果序列 + 只读投影；不断言内部字段、Compose 节点、动画实现或缓动数值。
- 主 seam：DashboardCore。Prior art：DashboardCoreTest（虚拟时钟 `nowMs`）、ContentPageTest、AgentArbitrationTest、SessionLockTest。新增宽限判例覆盖：最后一条通知清掉后宽限窗内不退屏、窗满退屏、窗内新通知取消退屏且随后重新计时、窗内 Detail 打开/Content Page 兜底等既有持有理由仍优先、手动退屏不等待、未在屏或无图标集不产生多余效果。
- 几何 seam：Icon Set 排布的纯几何计算。Prior art：IconGridTest。新增或扩展判例覆盖：退出中的图标仍占格位时其余图标的新位置、档位切换后的整组尺寸与居中不回归 spec 0012 的尺寸/容量断言。
- 渲染不写 JVM 测试：入场回弹、退场收缩、补位即时长与手感，按 spec 0008/0009/0012/0013 判例走实机目视验收（`screencap` 单帧约 200ms，抓不住 0.25 秒级动效），证据归档 docs/poc-logs。
- 新增日志锚词形后，实机验收链按词形断言入场与退屏宽限路径；既有锚词回归检查。
- 既有测试更新：凡断言「最后一条通知清掉 → 立即 ExitDashboard」的既有判例必须改写为宽限语义，不得丢失既有语义回归。

## Out of Scope

- 改变通知来源与可见性（ADR 0007、spec 0014）、Active Notification / Shade-visible Notification 语义、Icon Set 的排序规则（时间倒序）。
- 改变图标尺寸、角标样式与六格容量规则（spec 0012）。
- 改变 Notification Highlight 的呼吸时长/冷却窗、Detail View 的点开即消与居中排版、Charging Animation 与电量数字、Content Page 切换手感（spec 0013）。
- 改变 Agent Mirror（ADR 0005/0006）、Session Lock、Waiting-for-Approval 脉冲、Approval Glow、滚动跟随。
- 改变投送/撤屏、Posture Gate、Wake Keep-alive、Shizuku 兜底、冻结唤醒（ADR 0008）。
- 新增设置项、持久化字段、减少动效开关、声音/震动反馈、新手势、新依赖、新 ADR。
- 主屏总览页的其它区块与整体视觉。

## Further Notes

- 2026-09-29 grill-with-docs 两轮 Q1–Q17 全部按推荐定案：Q1 背屏＋主屏都做；Q2 图标＋角标（数字变化不动画）；Q3 补位一起做平滑滑动；Q4 轻回弹（约 0.25 秒，超一点再收回）；Q5 各自独立演、允许重叠；Q6 与 3 秒呼吸高亮各管各；Q7 退场演完再交还屏幕（上限 1 秒）；Q8 溢出应用的消失不演、只换「+N」数字；Q9 详情当口被清掉不演；Q10 两屏同一套参数；Q11 手动退屏不等待。
- 已确认的连带影响：网格档位切换时整组平移缩放（这正是「补位也做动效」的直接后果，与 spec 0012「同档内尺寸不随条数忽大忽小」不冲突）；0.25 秒级动效无法用截图取证，验收走日志锚 + 连续抓帧／录屏 + 目测。
- **Spec 0014 的文件镜像至今缺失**（#135 正文只在 issue）。本 spec 的文件镜像由收口票产出为 `docs/specs/0015-*.md`，收口票同时记录这一编号空档。
- CONTEXT.md 不新增词条：本改动不产生新的领域术语（「退屏宽限」是核心常量与实现概念，不入领域词汇表）。

## 收口记录（票 #150，2026-09-29）

**实现**（均已并入集成分支 `spec/0015-icon-animations`，PR #151 待审查）：

- #146 退屏宽限：`DashboardCore.exitGraceUntilMs` / `ExitGraceElapsed` / `EXIT_GRACE_MS=250` + `AppContainer.scheduleExitGraceWakeUp()` 到点唤醒；锚 `exit grace start|cancel|end`。
- #147 入场：`IconSetMotion.kt` 的 `IconSetMotionLayer`（0.25s 轻回弹、角标同进度淡入）、`rememberIconSetEnteringApps`；锚 `icon enter <pkg>`。
- #148 退场：`IconSetExitLedger` + `presentedOrder`（退场条目占格不占容量）、退场角标沿用旧计数；锚 `icon exit <pkg>`。
- #149 档位重排：`IconGrid.tierFrameOf/tierTransitionFrame` + `TierSpec`（= MoveSpec，0.9/700）驱动整组缩放/平移，`+N` 常驻节点淡入淡出。
- 两屏共用同一套参数：背屏 `DashboardContent` 与主屏 `IconSetCard` 都走 `IconSetMotionLayer`；漂移仍是整层瞬时位移，不进位置动画对账。

**JVM 判例**（`review/0015-fixes`，`--rerun-tasks`）：core 212、rear debug 152 / release 152、app 48，全绿 0 失败 0 跳过
（关键类：`DashboardCoreTest`、`IconGridTest`、`IconSetMotionTest`、`IconMotionLogContractTest`、`ContentPageTest`、`SessionLockTest`）。

**评审修复（`review/0015-fixes`，2026-09-29）**：

- 删除零消费的 `ENTER_DURATION_MS`，把「入场约 0.25 秒」的调校基准移入 `EnterScaleSpec` KDoc；`IconSetExitLedger.frame` 现在复用 `exitingApps`，退场对账只保留一条路径。
- 日志契约按 `ContentPageLogContract` 先例收口为单一聚合名 `DashboardCore.LOG_ICON_MOTION_CONTRACT = IconMotionLogContract.CONTRACT`，删除两个单锚常量；`icon enter <pkg>` / `icon exit <pkg>` 词形逐字未变。
- `IconGrid` 抽出私有 `tierCells`，`tierFrameOf` 与 `tierTransitionFrame` 共用同一套窗口落点组装；`IconGridTest` 13/13 原断言未放宽。
- 验收链补 `17a`（`detail open com.rearcue.poc` → `detail close com.rearcue.poc`，POST_TEST 同一枚点开再收起）与 `17b/17c`（通知页 ↔ Agent 页一次 `content page ...` 往返），并重新跑完整实机链。
- **验收基建边界**：`FIXTURE_NOTIF` 与 `AppContainer.debugInjectFixturePosted/Removed` 是 debug-only 验收基建，spec 0015 正文未把它列为产品能力；release 无调用方，按仓内 Debug Bypass 先例接受。
- **退场时长与宽限**：`ExitScaleSpec` KDoc 与验收 README「遗留观察」已写明两者同为 250ms 级、UI 起播晚约一帧（最坏 ~16ms），被截的是 `alpha=scale` 已 ≤ 约 0.06 的不可见尾帧；本轮不改参数，将来调 `EXIT_GRACE_MS` 或 `ExitScaleSpec` 一处即可。

**实机验收**（小米 17 Pro / HyperOS 3）：评审修复轮 `run 2026-09-29 13:20:39`，`failed=0 inconclusive=3`，无 FAIL；
新增 `17a–17c` 全 PASS，完整表见 `acceptance-summary.txt` 最后一段与验收记录
[docs/poc-logs/20260929-115451-spec0015-icon-animations/README.md](../poc-logs/20260929-115451-spec0015-icon-animations/README.md)；
首轮 12:44 结果保留为历史。覆盖：入场（图标＋角标）、多枚同时入场、补位滑动、最新位挪动、6 枚↔7 枚边界、
充电让位、相机带避让、退场、溢出应用消失不演退场、Detail 当口静默移除、角标 1→2 不动效、
与 3 秒呼吸高亮叠加、主屏总览同一套观感；既有锚（`highlight breath start|end`、`detail open/close`、`content page ...`）
已在同轮回归。

**3 项 INCONCLUSIVE（构造不出的前置，不是产品缺陷）**：

1. 最后一条图标退场 + 退屏宽限（10）与宽限窗内新通知取消退屏（11）：本机 Icon Set 里常驻 4 个机主真实通知
   App，按纪律不得清理 ⇒ 空集不可构造 ⇒ `exit grace start/cancel/end` 三锚无法产生。复跑入口
   `drive-acceptance.ps1 -GraceOnly`（需机主先清掉那几个应用的通知）。
2. 1 行 ↔ 2 行换挡（5）：行数换挡要跨 3↔4 枚，同上原因触不到 3 枚上限；纯几何由 `IconGridTest` 判例覆盖，
   整组平移/缩放的动画表现分别由第 3/7 与第 6 条腿真机覆盖。

**编号空档记录**：**0014 已被 #135《Spec 0014：背屏通知以下拉栏可见集合为准（Shade-visible）》占用，其正文至今
只存在于 issue，`docs/specs/0014-*.md` 缺失**。本 spec（0015）收口时如实记录，未代为补档；补档若要落库，
宜由 #135 的收口票产出，避免与 issue 正文漂移。
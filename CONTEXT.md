# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。

## Language

**Main Display（主屏）**:
手机正面的主屏幕（displayId 0），与 Rear Display 相对。本项目的主屏 UI 分为主页（状态总览）与设置页两层。
_Avoid_: 前屏、正面屏、大屏

**Rear Display（背屏）**:
手机背部的物理副屏，系统侧 displayId 为 1。本项目一切"投送到背屏"均指此屏。
_Avoid_: 副屏、后屏、second screen

**Camera Band（相机带）**:
Rear Display 上相机模组占据的挖孔带——本机为左侧全高 296px 竖条；画进带内的内容被相机模组挡住、肉眼不可见（截屏仍截得到带内像素，判「避没避」不能只看截图）。
_Avoid_: 摄像头区域、挖孔区、safe inset 混称

**Native Rear Screen（原生背屏）**:
`com.xiaomi.subscreencenter` 运管的原生背屏界面（SubScreenLauncher、AOD 等）。
_Avoid_: 小米背屏、subscreen center 混称

**Takeover（抢回）**:
原生背屏（常因 AOD/锁屏，reason="aod"）重新占据背屏显示、顶掉 Dashboard 的行为。
_Avoid_: 覆盖、抢占

**Dashboard**:
本项目投送到背屏的自定义界面，以纯黑为底；常态仅 Icon Set（spec 0008 起：不显示时间、无横幅；
issue #101 起 ≥2 条通知时图标呈纯图标网格），叠加 Notification Highlight 瞬态与 Detail View 临时视图；
充电时整屏绿色水位图示（Charging Animation）。
_Avoid_: 背屏 UI、AOD、表盘

**Debug Bypass（调试旁路）**:
绕过自动流转的手动入口（投送到背屏/退出背屏 Dashboard/测试通知等）与既有 adb 调试命令语义；产品化后收进主页的开发者选项折叠区，保持可用不删。
_Avoid_: 与「兜底通道」混称——兜底通道指 Shizuku 投送路径，调试旁路指人工入口

**Active Notification（活动通知）**:
已发出且尚未被移除的状态栏通知，以 NotificationListenerService 视角为准；不代表 App 内部未读数。
_Avoid_: 未读消息、unread count

**Allowlist App（白名单应用）**:
曾指允许触发背屏图标的应用，由机主在设置页增删、持久化在设备本地（首启种子为 POC 五枚：微信、QQ、飞书〔com.ss.android.lark〕、本应用、com.android.shell〔自动化发通知用〕）。
票 #98 起本概念整体删除：「哪些应用可通知」的裁量交由系统「读取、回复和控制通知」页（Android 12+ 原生能力，系统层对被关掉的应用直接不送达监听服务）——应用内不再有名单可管理，Icon Set 对到达的通知不做任何应用级过滤。
留档防误引：读到 spec 0005 的名单语义时以本条为准。
_Avoid_: 在应用内实现「通知白名单」、把系统页的裁量说成本应用功能、追踪中的通知/追踪列表

**App Picker（应用选择器）**:
曾指设置页里挑选新 Allowlist App 的候选清单（可桌面启动的应用，图标+应用名）；随白名单删除（票 #98）退役。
_Avoid_: 全量应用列表、已安装应用列表混称

**Icon Set（图标集）**:
Dashboard 上显示的图标集合——每个存在 Active Notification 的应用恰好一枚图标（不过滤：
可见范围由系统「读取、回复和控制通知」页裁量，票 #98），按**时间倒序**排列
（最新通知的 App 在左上，重复通知把它挪到最新）。
呈现两档（issue #101）：恰 1 条通知时一枚图标（点开看 Detail 正文）；≥2 条通知时切**纯图标网格**
（不显示正文，点开单条再看）——图标恒定 96dp 不随条数缩放（超框整组收口归 fitScale）、
每格右上角标、每行 3 个整组水平居中、最多 2 行 6 个、溢出在网格下方居中「+N」徽标。
角标数字 = 该 App 的 Active Notification 条数（口语称「未读数」，**不是** App 内部未读数）；
spec 0007/0008 的「未读数/数字角标永不实现」判例由 issue #101 反转（2026-09-28）。
Dashboard 的内容之一（另有 Notification Highlight、Detail View 与 Charging Animation），不再是背屏唯一内容（spec 0008 起）。
_Avoid_: 把角标数字当应用内部未读数

**Degrade（降级）**:
投送通道不可用时的状态：通知监听与图标集照常维护，仅停止投送 Dashboard；通道恢复后自动重投。
_Avoid_: 离线模式

**Projection Channel（投送通道）**:
把 Dashboard 送进背屏的能力，POC 期以「运行时识别到背屏」为可用判据——应用内投送不需要
Shizuku（票 #4 的 E1 实测），Shizuku 只是兜底通道，掉线不改变能否投送。
_Avoid_: Shizuku 连接、投屏权限

**Overlay Window（覆盖窗口）**:
`TYPE_APPLICATION_OVERLAY` 的系统窗口，spec 0002 设想的第二条投送通道的载体（行文简称「覆盖窗口通道」，
即以此窗口为载体的候选投送通道，已否决，不作独立术语）。
E9 实测（票 #10）：HyperOS 的背屏窗口策略只放行系统应用，非系统应用的覆盖窗口上不了背屏
（主屏不受影响）；该通道在本机不成立，主路径仍是 Activity 投送。
E10/E11（票 #11）：因前置失败，锁屏可见/保活在本机不可测（E10/E11-BLOCKED-BY-E9）——
窗口在未锁屏时就加不上背屏，与锁屏无关；同轮 Activity 通道锁屏后 1.3–1.4s 被系统收走。
票 #12 go/no-go 定为 **no-go**：不落第二条通道，主路径维持 Activity 投送；重开条件见
`docs/poc-findings.md` 的「票 #12 验收」。
_Avoid_: 悬浮窗（MIUI 语境的「悬浮窗」开关不是这道背屏门的钥匙）、用「覆盖」单字指 Takeover

**Wake Keep-alive（唤醒保活）**:
让背屏在指示在屏期间保持点亮（不离开 ON、不进 DOZE）的手段总称。spec 0001 story 20 只认可非轮询手段；
周期注入唤醒键（MRSS 式）原被 spec 0002 列为 out of scope，覆盖窗口通道 no-go 后重新进入候选，
E12/E13 实测通过后经 ADR 0003 定案采用（story 20 的非轮询原则就此废止）。
E12 实测语义（票 #16）：以 shell uid 周期注入**定向背屏**的唤醒键（`input -d 1 keyevent KEYCODE_WAKEUP`），
注入期间锁屏后背屏可保持 ON——实测 500ms/5000ms 间隔在 60s 窗内全程 ON，30000ms 间隔守不住
（离开 ON 时刻与不注入一致，其后被针拉回 ON）；注入是显示定向的，三轮干净轮的保活段主屏全程 OFF
（采集轮的 KEYCODE_POWER 复位段曾点亮主屏，非干净观察；「绝对不点亮主屏」未定论）。指手段本身时用本词，
「保活住了」要带上注入间隔与观察窗的实测边界。
_Avoid_: 与「保活轮询」「KEEP_SCREEN_ON」混称

**Lock-screen First Cast（锁屏首投）**:
锁屏稳态（无 Active Notification、背屏无 Dashboard）下来一条通知时，把 Dashboard 送上背屏的那次投送。
它不是「重投」：`am start --display` 路径在锁屏下被 ActivityStarter 的 `rearDisplay check locked -> deny` 硬拒
（票 #6/E3、票 #18/E14 实测每次如此），走的是 E14 验证过的**任务搬运事务**（`service call activity_task 51`
= moveRootTaskToDisplay；MRSS 记的 50 在本构建是静默 no-op），把**带 Dashboard 的 root task** 搬上背屏；
通知清空后退出要把搬走的 root task 归还主屏（`task-move hand-back`），背屏交还原生界面。
失败词与 E14 词表同口径：NO-TASK（没有带 Dashboard 的任务，**不搬**空任务）/ REJECTED（有系统拒绝行）/
TXN-BROKEN（服务端回执文本说通道坏了，**不看进程退出码**）/ NO-EFFECT（无拒绝行仍未上屏）。
_Avoid_: 「锁屏投送」泛指（那是锁窗重投，另一件事）、把 `am start -n` 建任务步说成 `am start --display`

**Projection Session（投送会话）**:
一次把 Dashboard 投送上背屏的完整动作：从发起投送到判定收口——含锁屏首投的任务搬运、回读任务栈确认、
归还搬走的任务。判定结论即「Cast Verdict」；投送会话之外的代码不接触任何 HyperOS 专有命令。
_Avoid_: 投送流程、投送方法、project 调用

**Cast Verdict（投送判定）**:
一次投送的终态结论，词表与「锁屏首投」失败词同口径：OK（已上屏）/ NO-TASK（没有带 Dashboard 的任务，**不搬**）/
TXN-BROKEN（服务端回执文本说通道坏了）/ NO-EFFECT（无拒绝行仍未上屏）/ CHANNEL-DOWN（投送通道不可用，不产生 word 锚）。
判读只认服务端回执文本与回读任务栈，**不看进程退出码**；前四个词形是 tools/ex 判定链的契约，不可改。
_Avoid_: 投送结果 code、布尔成功/失败

**Presence（在屏事实）**:
Dashboard 当前在不在背屏的事实，取值三态：Absent（无界面）/ LaunchPending（已发出、上屏途中）/ OnScreen（已确认在屏）。
全项目只承认这一个在屏事实的来源；「投送已发出」不是「已上屏」。
_Avoid_: 用「在屏」指「投送已发出」、把实例存在说成在屏

**Posture Gate（倒扣门控）**:
用户开关（设置页「倒扣才显示」，票 #100；出厂与升级后默认**关**）。**关（默认）= 门控旁路**：
正放/倒扣都照常自动投送，在屏内容不因姿态撤下——开机即用，不需要理解门控概念；
**开 = 门控生效**：以接近传感器判定「主屏朝下」作为自动投送的前置条件：非倒扣不投，
倒扣且 Icon Set 非空时补投，翻正时撤下（仅自动投的）。切换即时生效、设置持久化；
开关只作用于自动路径，手动/充电豁免与 Agent 受门语义不变。
含姿态稳定窗，投与撤都要过防抖；开关关着时姿态照常进读数（只读状态行），只是不参与门控。
_Avoid_: 用「倒扣检测」泛指传感器事实本身、把稳定窗说成轮询、把默认档说成「开」

**DND Follow（勿扰跟随）**:
曾指 spec 0006 的自动路径门控：DND 开启 → 不投送并撤下已在屏 Dashboard，DND 结束且 Icon Set 非空补投。
已随票 #99 删除（2026-09-28）：勿扰不再影响背屏——DND 期间通知照常自动投送、已在屏的不撤下，应用侧
也没有 DND 监听（`AppState` 不再有 `dndActive`）。
留档防误引：读到 spec 0006/0007/0008/0010 的「DND 关才投」「DND 撤下」「豁免 DND Follow」时以本条为准。
_Avoid_: 勿扰门控、DND 屏蔽、把「勿扰期间静默」当现行行为、向机主要求勿扰开关

**Quick Tile Entry（快捷开关入口）**:
控制中心快捷开关（QS tile）承载的正式手动投送/退出入口：无 Dashboard→投送，有→退出；锁屏态可用。
手动投送豁免 Posture Gate，也不被自动逻辑撤下（自动投的自动撤，手动投的手动撤）。
_Avoid_: 与 Debug Bypass（App 内调试旁路）混称、把 tile 说成自动化触发器

**Notification Feed（通知横幅）**:
曾指 Dashboard 顶部横幅显示最新一条通知（spec 0007 落地）；spec 0008 起横幅撤下背屏——
常态仅图标，内容移入 Detail View，本词退役。留档防误引：读到 spec 0007 的横幅语义时以 spec 0008 为准。
_Avoid_: 继续用本词指 Detail View（点开详情是另一对象）

**Privacy Mode（隐私模式）**:
曾指横幅的内容遮蔽档位（spec 0007）；随横幅撤下退役（spec 0008）——Detail View 点开即本人主动行为，
直接显示全文，无遮蔽档。
_Avoid_: 延伸到 Detail View 的内容显示

**Auto-dismiss（自动销毁）**:
曾指横幅的显示时限（spec 0007）；随横幅撤下退役（spec 0008）——Detail View 由再点按收起、
所示通知被清除自动收起，无时限。
_Avoid_: 作用到 Detail View 或 Icon Set

**Detail View（通知详情）**:
点按 Icon Set 中某枚图标后展开的通知全文视图：显示该 App **最新一条** Active Notification 的
标题与内容，**不显示应用名**；标题恰为应用名且正文非空时连标题行一并省略（正文界面不显示软件名称）；
卡片纯黑底铺满整个背屏、不避相机带（spec 0009 起），但可读文字**左缘**让开相机带（见 Camera Band）、
**右距屏缘 8px 视觉值排满**（不做 DPI 换算），文字垂直位置进入屏幕圆角弧区时右距自动外扩、
永不被圆角切角（票 #97；与 Agent Mirror 同一纯函数出口，0011 的「右留空 150」判例作废）；
图标放大淡出、卡片弹性展开（过渡动效是产品要求）。
再点按收起；**点开即消除所示通知**（2026-09-28 grilling：系统通知栏同步 cancel、仅此最新一条，
该 App 尚有剩余则图标保留角标 −1、无剩余则图标消失）；自发清除**豁免**「所示通知被清除自动收起」
（否则详情闪现即关），详情保留至用户点按收起，收起后不自动切次新一条；外部清除仍自动收起；
无隐私档、无限时、不做多条堆叠列表。可行性前置 Rear Tap 真机验证。
_Avoid_: 通知列表、历史回看、与 Notification Feed 混称

**Rear Tap（背屏点按）**:
背屏 Dashboard 上的点按交互能力。POC 票 #7 曾观测背屏触摸触发原生手势把 Dashboard 顶掉，
spec 0007 据此把背屏触控列为不做；spec 0008 反转并前置真机验证票——若系统劫持不可行，
退路为背屏纯展示、全文在主屏 App 查看。
_Avoid_: 与原生背屏手势（SubScreenCenter 的 Recents 上滑）混称

**Notification Highlight（通知高亮）**:
新通知到达瞬间的背屏强调动效，触发规则一句话：「新通知到达（含同 key 内容更新）⇒ 整屏呼吸约 3 秒」。
呼吸中/冷却中（30 秒）再来通知不重复呼吸，但新到 App 的图标照常加入高亮；
图标高亮用统一暖白强调色，熄灭时机＝该 App 的 Detail View 被点开看过即熄，
未看则通知被清除时熄。呼吸是视图级效果——只绑投送就绪；姿态门只管投/撤
（票 #65 定案：正放手动/充电等豁免源在屏时到达照常呼吸）；重连快照重建不呼吸。
无常驻动画、无应用内开关，亮度跟随系统。
_Avoid_: 「高亮到横幅销毁」旧语义（横幅已撤）、呼吸灯（硬件指示灯混称）、图标数字角标

**Charging Animation（充电动画）**:
充电时铺在 Dashboard **最底层的绿色水位背景**：水面对应当前电量比例（自底部向上被绿色水填充），
水面轻微荡漾（水的质感，非 3D 重力液体）；绿色覆盖整个背屏、不避相机带（spec 0009 反转
spec 0008 的「相机带不染色」）；白色大号数字显示电量、置于右下角，字体为现代细体（spec 0009 起）。
**背景层语义（2026-09-28 grilling）**：充电期间长期显示、与内容无竞争——Icon Set、Detail View、
Agent Mirror 照常叠在水面之上（Icon Set 光晕保持可见、通知浮于水面是其特例）；满电（100%）时
水面贴顶，屏幕**上沿**呈现持续荡漾的波浪线，即水位到顶的自然终点，不另设满电 UI；
拔电即刻开始**渐隐**撤背景（无停留延迟，带渐变动画，非瞬间硬切）。
替代 spec 0007 的 2D 闪电（spec 0008 反转）。
插电仍是独立投送触发源（无通知时也投），不受 Posture Gate 管；
拔电且 Icon Set 空即退；应用内总开关默认开。
_Avoid_: 与 MRSS 全屏 3D 重力液体实现混称、把插电触发说成通知路径、把水位当与内容竞争的“整屏内容”

**Agent Mirror（Agent 镜像）**:
Dashboard 的第五种内容：只读镜像电脑上 AI agent 会话的**会话输出流**。
接入路径（2026-09-28 grilling 定案）：ZCode 直连官方远控中继（零安装，ADR 0005 不变）；
**Codex 与 Claude Desktop 共用一个 PC 桥**（hooks 事件 + 会话文件 tail 归一，经隧道送手机，
两者同做、不分二期；桥开机自启常驻）。Claude Desktop 仅镜像 **Code/Cowork 标签会话**
（即 Claude Code 会话，hooks/落盘可用）；**Chat 标签会话不镜像**（无输出流可编程观测，硬接易碎）。
输出**实时自动滚动跟随**，上滑打断进入历史回看，右下浮动按钮（↓）恢复实时滚动（回看中不打断、
不提示新消息）；可回看历史**仅限当前会话**（反转原「不做对话翻页历史」）。
呈现：不显示「工作中/等待确认/空闲」等状态词与
动作行（Waiting-for-Approval 以非文字视觉标记提示——现为会话标识行脉冲，专用标记另票落地），正文最大化；
内容选择优先级：**Waiting-for-Approval > 通知内容 > Agent Mirror**——有通知时显示通知，
无通知时显示 agent；agent 侧**连接在线即显示**（空闲也显示，屏上为最近一段会话输出），
断连才回落黑底（2026-09-28 grilling Q12）。
正文水平留白与 Detail View 同一规则、同一纯函数（左缘避相机带、右距屏缘 8px 排满、
垂直位置进圆角弧区右距自动外扩，票 #97）。
不做遥控、不做跨会话翻页、不做打码档（spec 0008 的 Privacy Mode 先例：本人设备直显）。
_Avoid_: 聊天窗口、遥控器、与 Notification Highlight（通知瞬态）混称、状态词文案

**Session Lock（会话锁定）**:
机主在主屏 Agent 设置区手动指定 Agent Mirror 显示哪个会话；列表保留「自动」档且**默认自动**
（＝spec 0010 的最近活跃＋等待插队现状），选具体会话即锁定、随时可切回。
锁定只定「显示谁」，不改变接管门槛——锁定的会话空闲时背屏照旧回落常规内容（锁会话不锁屏）；
任何会话进入 Waiting-for-Approval 时临时插队显示、处理完回锁；
锁定跨 App 重启保留，锁定的会话在电脑端消失则自动清除退回自动。
_Avoid_: 手动选台、含糊的「对话选择」、把锁定说成驻屏

**Approval Glow（确认光带）**:
Waiting-for-Approval 时机缘亮起的背屏边缘环绕灯带：持续点亮＋亮度呼吸起伏，处理完即灭；不响不震。
与会话标识行 3 秒脉冲分工——脉冲是「到达瞬态」提示，光带是「尚未处理」的持续提示；
仅等待确认一态有光带（工作中/空闲不亮），只在该状态存续期间存在，不违反「无常驻动画」原则。
_Avoid_: 呼吸灯（Notification Highlight 避用词）、氛围灯、常驻灯带

**Waiting-for-Approval（等待确认）**:
agent 停下等待用户批准/输入的状态。在背屏内容选择中永远优先——多会话并存时插队显示，
并触发点亮提醒（点亮数秒，不响不震）。与「空闲」（无进行中回合且无等待）是两个状态。
_Avoid_: 空闲、把「回合结束」误当等待

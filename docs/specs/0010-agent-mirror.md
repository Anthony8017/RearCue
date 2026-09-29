# Spec 0010：Agent Mirror——背屏只读镜像 PC agent 会话（一期 ZCode）

状态：已与机主 grill 收口（2026-09-27，五轮 14 问，frontier 空，均按推荐定案）。
决策依据：ADR 0005（离家通道：复用 ZCode 官方中继优先、tunwg 回退）已立；
CONTEXT.md 已新增 Agent Mirror / Waiting-for-Approval 两词条（随本 spec 一并入库）。
落地回填（2026-09-28，票 #103–#106）：Session Lock（#103 core 仲裁＋V4Bridge 订阅跟随＋清锁＋Debug 注入、
#104 主屏会话列表与状态行）与 Approval Glow（#105 光带参数纯函数＋背屏渲染层）已落分支
`spec/session-lock-glow`；收尾票 #106＝本 spec 修订＋实机端到端验收归档
`docs/poc-logs/20260928-133000-106-session-lock-e2e/`（光带专轮截图另见 `docs/poc-logs/20260928-approval-glow/`）。
本次修订同步移除 Out of Scope 的「多会话手动切换 UI」——该决定已被 Session Lock（见下）推翻。
同步 origin/main（2026-09-28 grilling #112/#113，随 #122 合入本分支）：#112 接管门槛重定义为
「连接在线即显示」（空闲也显示最近会话输出、断连才回落）；#113「去状态词」删背屏状态词/动作行，
顶部只留一行低对比会话标识——下文相关故事与渲染口径按此修订（标 #112/#113 已变更）。

**收口后修订（2026-09-29，均已在 origin/main）**：下文凡写「连接在线即显示」「断连才回落／失联即
失去本页内容」者，均被后两票取代，**以本段与 CONTEXT.md 为准**：

- **票 #166 断线保留**：agent 理由改为**有在册会话**（不再要求链路在线）。桥断时 Agent 页保留、
  内容停在最后一帧；收回改走机主手动退出、投送通道降级、姿态门、投送抢回等既有路径。
  原「断连即理由消失、零打扰回落」作废。
- **票 #165 链路状态点**：会话标识行旁一个**非文字状态点**显示 PC 桥链路状态（已连接＝accent 实心点、
  连接中/重连中＝次要灰点、未配置/停用不画），与主屏设置页状态行、主页概览读同一份
  `BridgeLinkStatus`（「一份事实三处显示」）。
- 另有两票落在本 spec 的呈现面（细节见 spec 0016）：**#161 会话标识行固定屏幕顶部**（长正文跟随/回看
  时入口不被滚走，Detail View 的「标题＋正文整体居中」不变）、**#160/#156 会话选择浮层**（点标识行
  开列表，3 行 + 底部可见关闭带）。

实机验收归档：`docs/poc-logs/20260929-194600-spec0010-link-status-retention/`（#165/#166 判定表 10 项）。

## Problem Statement

机主在电脑上用 AI agent（ZCode 桌面版为主、Codex 次之）跑长任务；agent 常停下来等确认/等输入，
机主在忙别的察觉不到，回头一看白等几十分钟。手机平时扣在电脑旁，背屏只有通知图标与充电动画，
看不到 agent 在干嘛；离家（手机走流量）时更是完全失联。现有方案（Agents Anywhere、HAPI、Happy 等）
全是「手机端独立 app」路线，没人用背屏；Codex 移动版只覆盖 ChatGPT 桌面 App 发起的会话，
机主实际用的 VSCode 扩展/桌面版不在其内，第三方也借不到它的通道（2026-09-27 调研定案）。

## Solution

RearCue 新增 Dashboard 第五种内容「Agent Mirror」：**只读**镜像电脑上 AI agent 会话的
**会话输出原文（不打码）**为正文主体——背屏不显示状态词与动作行（#113），顶部只留一行
极小、低对比的会话标识（workspace，分辨镜像的是哪个会话）。

一期只做 ZCode：RearCue 手机端**直连 ZCode 官方「Web 远程控制」中继**（复刻其私有协议，
zemote/zflow 复刻先例），机主把桌面端弹窗里的链接**一次性粘贴**进 RearCue 完成配对；
**PC 端零安装**；在家/离家同一条链路——天然满足离家可用（Q10=B 的定案）。
（**#166 已变更**）有在册会话即显示（空闲也留屏显示最近一段会话输出；桥断时按**断线保留**停在最后一帧、不再回落，
收回走机主手动退出/通道降级/姿态门）；Waiting-for-Approval
永远优先插队＋两层在屏强调（会话标识行约 3 秒脉冲＝到达瞬态，#113 起脉冲宿主为标识行；
Approval Glow 边缘光带＝仅等待确认存续期持续点亮并起伏、处理完即灭，均不响不震）。
多会话默认「自动」档（最近活跃＋等确认插队，原状不变），机主可在主屏 Agent 区锁到指定会话
（Session Lock，票 #103/#104，只定显示谁、不改接管门槛）。中继协议被 ZCode 官方改断时按 ADR 0005 回退 tunwg 自建通道（另行立项）。
二期接 Codex（PC 桥＋tunwg，另立 spec）。

## User Stories

**配对与设置**

1. 作为机主，我想把 ZCode 桌面端「远程控制」弹窗里的链接粘贴到 RearCue 主页完成一次性配对，配好后不再管连接。
2. 作为机主，我想在主页 Agent 区看到连接状态（未配对/已连接/断开）与当前镜像的 ZCode 工作区名，以便知道背屏镜像是否活着。
3. 作为机主，我想要一个总开关（默认开，仿充电惯例），关掉即断开、背屏回现状，以便完全掌控这个功能。
4. 作为机主，我想随时「解除配对」清掉本机凭据，以便不再用时干净退出。

**背屏显示**

5. 作为机主，agent 回合进行中时我想背屏自动切到 Agent Mirror（无需任何操作），扣着手机也能瞥见它在干活。
6. 作为机主，我想一眼看到 agent 的工作状态（工作中/等待确认/空闲），以便决定要不要回去看它。（**#113 已变更**：背屏不再显示状态词——正文即状态，等确认另有光带/脉冲提示）
7. 作为机主，agent 用工具时我想看到一行「当前动作」（如在改哪个文件/跑什么命令），以便掌握进度。（**#113 已变更**：动作行随去状态词删除，进度看会话输出正文）
8. 作为机主，agent 回复后我想在背屏读到最新一条回复的原文（不打码），以便不回主屏也能看结论。
9. 作为机主，回合结束且无等待确认时我想背屏自动回落常规内容（Icon Set/充电动画），不被空闲会话长期占屏。（**#112 已变更**：有在册会话即显示、空闲也留屏显示最近输出；**#166 再变更**：桥断也保留、不回落——收回改走机主手动退出/通道降级/姿态门）

**等待确认**

10. 作为机主，agent 进入等待确认时我想 Agent Mirror 立刻插队显示并保持，直到我处理或会话推进，以便绝不错过。
11. 作为机主，等待确认发生而背屏在显时我想看到数秒视觉强调（脉冲约 3 秒；**#113 已变更**：宿主移到会话标识行），不响不震，以便余光即可察觉。
12. 作为机主，等待确认一直没被处理时我想背屏边缘有一圈持续亮着、亮度轻微起伏的光带（Approval Glow），处理完即灭，以便不用凑近读小字也知道它还在等我。
13. 作为机主，多个 ZCode 会话同时跑时我想背屏显示最近活跃的那个、有等待确认的永远优先，以便一台手机管多线。

**会话锁定（Session Lock，回填票 #103/#104）**

14. 作为机主，我想在主屏 Agent 区的会话列表里把背屏锁到指定会话（保留「自动」档且默认自动、随时可切回），以便多会话并跑时稳定盯住一条。
15. 作为机主，锁定只管「显示谁」——锁定的会话空闲照常回落、别的会话等待确认仍插队、处理完回锁、锁定的会话在电脑端消失自动退回自动，以便锁定不变成驻屏或死锁。

**优先级与门控**

16. 作为机主，充电且 agent 活跃时我想 Agent Mirror 优先于充电动画、结束后回到充电动画，以便工作时信息优先。
17. 作为机主，agent 触发的自动显示受 Posture Gate 管（倒扣才投、翻正即撤），以便姿态语义与通知一致。（**#100 已变更**：现为用户开关，默认关＝姿态不拦；仅开关开启时本条成立）
18. 作为机主，DND 开着时 Agent Mirror 照常显示（自己开的工作监控不算打扰），不想看用总开关/Quick Tile，以便写代码开勿扰不断镜像。（DND 门已随 #99 删除后本条自动恒成立——不再有「DND 关着才挡」的可能）
19. 作为机主，Quick Tile 手动投送/退出语义不变（豁免门控、不被自动逻辑撤下）。

**连接与容错**

20. 作为机主，手机离家走流量时镜像照常工作、与在家无差别，以便出门也能瞄背屏。
21. 作为机主，断网/中继不可达时背屏安静回落常规内容、App 自动重连，不弹错、不闪屏。
22. 作为机主，ZCode 升级改协议导致断链时 RearCue 只是安静回落（其余功能零牵连），以便坏也坏得局部。

**隐私与安全**

23. 作为机主，配对凭据只存本机私有存储，界面不回显完整凭据、日志不打印，以便泄露面最小。
24. 作为机主，RearCue 对 ZCode 严格只读（绝不发送 prompt/按键/批准），以便绝无误操作风险。

**开发与验收**

25. 作为开发者，我想先跑真中继连通性 spike（E1，GREEN/RED 判据），不可行即按 ADR 0005 转 tunwg 立项再继续，以便不把协议风险浇进地基。
26. 作为开发者，我想协议层（配对握手/帧编解码/快照与增量→AgentSessionState 归一化）全部纯 JVM 可测（录制向量＋mock 中继），以便无真中继也能回归。
27. 作为开发者，我想 Agent Mirror 的内容仲裁走 DashboardCore 既有 JVM 缝（事件进→判决出，像通知/充电一样），以便显示语义可锁死。
28. 作为开发者，我想 Debug Bypass 增加「注入伪 agent 状态」命令，adb 一条命令在实机演示/验收各状态，沿 tools/ex 惯例。
29. 作为开发者，我想渲染参数（状态×屏几何→布局参数）抽纯函数 JVM 测（沿 WaveColumns/ChargingWater 判例），动效本身走实机验收。
30. 作为开发者，`gradlew test` 既有基线 0 失败不回退。

## Implementation Decisions

- **模块**：新纯 Kotlin 模块 `:agent`（中继协议客户端、配对凭据模型、AgentSessionState 归一化、重连退避）；`:core` DashboardCore 扩事件与仲裁；`:app` 主屏 Agent 设置区＋薄搬运（仿 charging/ 惯例）；`:rear` 新 AgentFeed 单例＋AgentMirrorView＋点亮强调。声明 INTERNET 权限——全 App 首个网络权限。
- **传输**：OkHttp WebSocket（新依赖）；端点常量 wss://zcode.z.ai/ws，endpointOrigin 可切换 wss://zcode.chatglm.site/ws；传输可注入，测试用 MockWebServer。
- **协议**（2026-09-27 逆向调研，zemote/zflow 先例佐证）：mobile 角色注册（链接取 sid+passHash）→ auth_challenge(nonce) → proof=HMAC-SHA256(passHash,"nonce|role|deviceSid") base64url → data 通道 rpc-frame 分片/重组/CRC32/ack；close code 语义表（含 4013：同一时刻仅一机）；Conversation V4 snapshot/delta → AgentSessionState 的精确映射由 E1 spike 定并回填本 spec。
- **AgentSessionState**：status ∈ Working / WaitingForApproval / Idle；currentAction（最近运行中工具的单行摘要）；latestReply（最新助手文本原文）；workspace 名；updatedAt。多会话归「最近活跃」，WaitingForApproval 插队。
- **仲裁（DashboardCore）**：新增 sealed 事件 AgentSessionUpdated / AgentConnectionChanged；优先级 WaitingForApproval > Working（最近活跃）> Charging Animation > Icon Set 常态；断连回落既有内容（**#112 已变更**：Idle 不再回落——有在册会话即显示、含空闲残影，无在册会话的纯连接不投；**#166 再变更**：链路断开也**不再回落**——理由是「有在册会话」，断线冻结内容并由链路状态点提示，见票 #165）。CastSource 新增 **AGENT**：受 Posture Gate 管、**豁免 DND Follow**（机主自启监控非外部打扰；Quick Tile 语义不变）。（**#99/#100 已变更**：DND Follow 已删，「豁免 DND Follow」成空话；Posture Gate 现为用户开关默认关）
- **触发源**：agent 回合开始成为独立自动投送触发源（无通知时也投），走 AGENT 源的姿态门语义。
- **连接生命周期**：进程内单例客户端，指数退避重连；后台存活沿用 ADR 0004（MILLET 省电无限制），不加前台服务、不加常驻通知。
- **凭据**：DataStore 私有存储；界面不回显完整凭据、日志不打印；解除配对即清除。
- **渲染**：AgentMirrorView 沿 spec 0009 惯例（DesignTokens 语义令牌、DisplaySafeArea 几何、全屏含相机带）；等待确认的强调分两层——会话标识行约 3 秒脉冲（#113 起宿主为标识行；到达瞬态、30 秒冷却，沿 Notification Highlight 的克制语言）＋ **Approval Glow** 边缘环绕光带（票 #105，见下），均不响不震、不构成常驻动画。
- **主屏**：AgentSettingsSection（粘贴链接、连接状态行、总开关默认开、解除配对），落 MainActivity 设置区（仿 ChargingSettingsSection）；票 #104 追加会话列表＋状态行（自动置顶、点选锁定/解锁，AppState 三投影 agentState/sessionLock/roster）。
- **Session Lock（票 #103/#104，2026-09-28 回填）**：多会话默认「自动」档（最近活跃＋等确认插队，原状逐字不变）；点选具体会话即锁定。锁只改「显示谁」：`DashboardEvent.SessionLock` 进 core，投/撤仍走理由与门控统一出口——锁定会话空闲即回落常规内容（锁会话不锁屏、别的会话再忙也不顶班），任何会话等确认仍临时插队、处理完回锁。V4Bridge 订阅跟随锁定会话（锁在册即订、换向绕过 30 秒换向节流）；`TaskListParser.parseAll` 交全量会话键，锁定会话从任务表消失由 core 自动清锁退回自动（日志词形 `session lock cleared <sessionId>`，接线层同帧写盘）。偏好 DataStore 持久化跨 App 重启保留。Debug Bypass 增 `SESSION_LOCK --es sessionId <id|auto>`（验收链票 #106）。
- **Approval Glow（票 #105，2026-09-28 回填）**：「是否亮」收口纯函数 `AgentMirrorParams.approvalGlow(status, w, h)`——仅 Waiting-for-Approval 返回非 null（工作中/空闲不亮、离开即灭），几何只定描边宽度（短边比例折算夹紧），渲染层零决策照单执行；与会话标识行 3 秒脉冲分工（脉冲＝到达瞬态，光带＝尚未处理的存续提示）。

## Testing Decisions

- 好测试只测输入→输出。三个纯逻辑面：①协议层「字节→状态」（录制向量，含调研期已独立复算验证的 HMAC 测试向量）；②DashboardCore 仲裁「事件→判决」（插队/优先级/回落/门控豁免）；③渲染参数纯函数「状态×几何→参数」。渲染动效不写 JVM 测试，实机验收链替代（沿 0008/0009 判例）。
- `:agent` 用 MockWebServer 跑握手与快照/增量流，无真中继依赖；实机侧 E1 spike 留痕 poc-logs（真中继配对→握手→持续读到会话状态为 GREEN）。
- Prior art：DashboardCoreTest（仲裁判例）、WaveColumns/ChargingWater（渲染参数纯函数判例）、RearDashboardManifestTest（manifest 变更守卫式——INTERNET 权限等纳入守卫）、spec 0009 实机验收＋poc-logs 归档惯例。
- 回填（票 #103–#106）：Session Lock 仲裁判例 `SessionLockTest`（锁定显示、等确认插队回锁、任务表消失自动清锁、同档幂等）；会话列表状态归一 `AgentStateLogicTest`；V4Bridge 锁订阅绕节流 `V4BridgeTest`；光带参数 `AgentMirrorParamsTest`（仅 WAITING 非 null、描边夹紧）。实机验收链沿 Debug Bypass（`SESSION_LOCK` 注入＋`AGENT_STATE` 伪状态）跑「默认自动 → 锁定 → 他会话等待插队 → 处理回锁 → 不在册清锁 → 光带亮灭」，ZCode 真链路同场在线，证据归档 `docs/poc-logs/20260928-133000-106-session-lock-e2e/`。

## Out of Scope

- Codex 接入（二期，PC 桥＋tunwg，另立 spec）。（**已落地**：PC 桥见 spec 0016 与 `tools/bridge/`——Codex 与 Claude 共用一条桥、隧道走 cloudflared quick tunnel 自动推送；tunwg 未启用，ADR 0005 的回退条件未触发）
- tunwg 回退通道实现（ADR 0005 触发条件成立才立项）。
- 遥控——发消息/批准/按键（永不在此 spec）。
- 离家推送兜底（飞书等；一期不做，后补不伤架构）。
- 对话翻页历史、打码档。（原列此的「多会话手动切换 UI」已移除：Session Lock 落地推翻该决定，见票 #103/#104。）
- 息屏时主动点亮背屏（Wake Keep-alive 只保亮不唤醒；主动唤醒二期验证）。
- 主屏 Widget、常驻通知、前台服务。
- 多台手机同时连接（中继协议一次一机）。

## Further Notes

- 链路与选型依据见 ADR 0005 及其调研表（ZCode 中继复用 / tunwg / Tailscale / frp / Cloudflare Tunnel 对比、Codex 官方通道封闭的实证）。
- 已知取舍：RearCue 连上后机主自己的手机浏览器远控 ZCode 不能同时用（中继一次一机）；私有协议无 SLA、v4 版本化风险；断链只影响本功能，回退路径已预置。
- QR 链接生命周期（是否过期、桌面刷新二维码是否作废已配对的）由 E1 spike 澄清并回填本节。
- 门控偏离说明：AGENT 源豁免 DND Follow（与 CHARGING 同权；**#99 已删 DND 门，该豁免自动恒成立**）但保留 Posture Gate（**#100 改用户开关默认关**，仅开关开启时生效）——「写代码开勿扰」是主场景，机主自启的监控不算外部打扰；不想看有关/Quick Tile 两条退出路。
- 一期不做的推送：等确认兜底推送走飞书机器人是纯增量，任何时候可加，不依赖本期架构。

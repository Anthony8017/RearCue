# Spec 0010：Agent Mirror——背屏只读镜像 PC agent 会话（一期 ZCode）

状态：已与机主 grill 收口（2026-09-27，五轮 14 问，frontier 空，均按推荐定案）。
决策依据：ADR 0005（离家通道：复用 ZCode 官方中继优先、tunwg 回退）已立；
CONTEXT.md 已新增 Agent Mirror / Waiting-for-Approval 两词条（随本 spec 一并入库）。

## Problem Statement

机主在电脑上用 AI agent（ZCode 桌面版为主、Codex 次之）跑长任务；agent 常停下来等确认/等输入，
机主在忙别的察觉不到，回头一看白等几十分钟。手机平时扣在电脑旁，背屏只有通知图标与充电动画，
看不到 agent 在干嘛；离家（手机走流量）时更是完全失联。现有方案（Agents Anywhere、HAPI、Happy 等）
全是「手机端独立 app」路线，没人用背屏；Codex 移动版只覆盖 ChatGPT 桌面 App 发起的会话，
机主实际用的 VSCode 扩展/桌面版不在其内，第三方也借不到它的通道（2026-09-27 调研定案）。

## Solution

RearCue 新增 Dashboard 第五种内容「Agent Mirror」：**只读**镜像电脑上 AI agent 会话——
工作状态（工作中/等待确认/空闲）、当前动作一行、最新一条回复原文（不打码）。

一期只做 ZCode：RearCue 手机端**直连 ZCode 官方「Web 远程控制」中继**（复刻其私有协议，
zemote/zflow 复刻先例），机主把桌面端弹窗里的链接**一次性粘贴**进 RearCue 完成配对；
**PC 端零安装**；在家/离家同一条链路——天然满足离家可用（Q10=B 的定案）。
回合结束且无等待确认即回落常规背屏；Waiting-for-Approval 永远优先插队＋在屏视觉强调
（点亮数秒、不响不震）。中继协议被 ZCode 官方改断时按 ADR 0005 回退 tunwg 自建通道（另行立项）。
二期接 Codex（PC 桥＋tunwg，另立 spec）。

## User Stories

**配对与设置**

1. 作为机主，我想把 ZCode 桌面端「远程控制」弹窗里的链接粘贴到 RearCue 主页完成一次性配对，配好后不再管连接。
2. 作为机主，我想在主页 Agent 区看到连接状态（未配对/已连接/断开）与当前镜像的 ZCode 工作区名，以便知道背屏镜像是否活着。
3. 作为机主，我想要一个总开关（默认开，仿充电惯例），关掉即断开、背屏回现状，以便完全掌控这个功能。
4. 作为机主，我想随时「解除配对」清掉本机凭据，以便不再用时干净退出。

**背屏显示**

5. 作为机主，agent 回合进行中时我想背屏自动切到 Agent Mirror（无需任何操作），扣着手机也能瞥见它在干活。
6. 作为机主，我想一眼看到 agent 的工作状态（工作中/等待确认/空闲），以便决定要不要回去看它。
7. 作为机主，agent 用工具时我想看到一行「当前动作」（如在改哪个文件/跑什么命令），以便掌握进度。
8. 作为机主，agent 回复后我想在背屏读到最新一条回复的原文（不打码），以便不回主屏也能看结论。
9. 作为机主，回合结束且无等待确认时我想背屏自动回落常规内容（Icon Set/充电动画），不被空闲会话长期占屏。

**等待确认**

10. 作为机主，agent 进入等待确认时我想 Agent Mirror 立刻插队显示并保持，直到我处理或会话推进，以便绝不错过。
11. 作为机主，等待确认发生而背屏在显时我想看到数秒视觉强调（呼吸/脉冲式），不响不震，以便余光即可察觉。
12. 作为机主，多个 ZCode 会话同时跑时我想背屏显示最近活跃的那个、有等待确认的永远优先，以便一台手机管多线。

**优先级与门控**

13. 作为机主，充电且 agent 活跃时我想 Agent Mirror 优先于充电动画、结束后回到充电动画，以便工作时信息优先。
14. 作为机主，agent 触发的自动显示受 Posture Gate 管（倒扣才投、翻正即撤），以便姿态语义与通知一致。
15. 作为机主，DND 开着时 Agent Mirror 照常显示（自己开的工作监控不算打扰），不想看用总开关/Quick Tile，以便写代码开勿扰不断镜像。
16. 作为机主，Quick Tile 手动投送/退出语义不变（豁免门控、不被自动逻辑撤下）。

**连接与容错**

17. 作为机主，手机离家走流量时镜像照常工作、与在家无差别，以便出门也能瞄背屏。
18. 作为机主，断网/中继不可达时背屏安静回落常规内容、App 自动重连，不弹错、不闪屏。
19. 作为机主，ZCode 升级改协议导致断链时 RearCue 只是安静回落（其余功能零牵连），以便坏也坏得局部。

**隐私与安全**

20. 作为机主，配对凭据只存本机私有存储，界面不回显完整凭据、日志不打印，以便泄露面最小。
21. 作为机主，RearCue 对 ZCode 严格只读（绝不发送 prompt/按键/批准），以便绝无误操作风险。

**开发与验收**

22. 作为开发者，我想先跑真中继连通性 spike（E1，GREEN/RED 判据），不可行即按 ADR 0005 转 tunwg 立项再继续，以便不把协议风险浇进地基。
23. 作为开发者，我想协议层（配对握手/帧编解码/快照与增量→AgentSessionState 归一化）全部纯 JVM 可测（录制向量＋mock 中继），以便无真中继也能回归。
24. 作为开发者，我想 Agent Mirror 的内容仲裁走 DashboardCore 既有 JVM 缝（事件进→判决出，像通知/充电一样），以便显示语义可锁死。
25. 作为开发者，我想 Debug Bypass 增加「注入伪 agent 状态」命令，adb 一条命令在实机演示/验收各状态，沿 tools/ex 惯例。
26. 作为开发者，我想渲染参数（状态×屏几何→布局参数）抽纯函数 JVM 测（沿 WaveColumns/ChargingWater 判例），动效本身走实机验收。
27. 作为开发者，`gradlew test` 既有基线 0 失败不回退。

## Implementation Decisions

- **模块**：新纯 Kotlin 模块 `:agent`（中继协议客户端、配对凭据模型、AgentSessionState 归一化、重连退避）；`:core` DashboardCore 扩事件与仲裁；`:app` 主屏 Agent 设置区＋薄搬运（仿 charging/ 惯例）；`:rear` 新 AgentFeed 单例＋AgentMirrorView＋点亮强调。声明 INTERNET 权限——全 App 首个网络权限。
- **传输**：OkHttp WebSocket（新依赖）；端点常量 wss://zcode.z.ai/ws，endpointOrigin 可切换 wss://zcode.chatglm.site/ws；传输可注入，测试用 MockWebServer。
- **协议**（2026-09-27 逆向调研，zemote/zflow 先例佐证）：mobile 角色注册（链接取 sid+passHash）→ auth_challenge(nonce) → proof=HMAC-SHA256(passHash,"nonce|role|deviceSid") base64url → data 通道 rpc-frame 分片/重组/CRC32/ack；close code 语义表（含 4013：同一时刻仅一机）；Conversation V4 snapshot/delta → AgentSessionState 的精确映射由 E1 spike 定并回填本 spec。
- **AgentSessionState**：status ∈ Working / WaitingForApproval / Idle；currentAction（最近运行中工具的单行摘要）；latestReply（最新助手文本原文）；workspace 名；updatedAt。多会话归「最近活跃」，WaitingForApproval 插队。
- **仲裁（DashboardCore）**：新增 sealed 事件 AgentSessionUpdated / AgentConnectionChanged；优先级 WaitingForApproval > Working（最近活跃）> Charging Animation > Icon Set 常态；Idle 或断连回落既有内容。CastSource 新增 **AGENT**：受 Posture Gate 管、**豁免 DND Follow**（机主自启监控非外部打扰；Quick Tile 语义不变）。
- **触发源**：agent 回合开始成为独立自动投送触发源（无通知时也投），走 AGENT 源的姿态门语义。
- **连接生命周期**：进程内单例客户端，指数退避重连；后台存活沿用 ADR 0004（MILLET 省电无限制），不加前台服务、不加常驻通知。
- **凭据**：DataStore 私有存储；界面不回显完整凭据、日志不打印；解除配对即清除。
- **渲染**：AgentMirrorView 沿 spec 0009 惯例（DesignTokens 语义令牌、DisplaySafeArea 几何、全屏含相机带）；等待确认的视觉强调沿 Notification Highlight 的呼吸语言（约 3 秒、克制、无常驻动画）。
- **主屏**：AgentSettingsSection（粘贴链接、连接状态行、总开关默认开、解除配对），落 MainActivity 设置区（仿 ChargingSettingsSection）。

## Testing Decisions

- 好测试只测输入→输出。三个纯逻辑面：①协议层「字节→状态」（录制向量，含调研期已独立复算验证的 HMAC 测试向量）；②DashboardCore 仲裁「事件→判决」（插队/优先级/回落/门控豁免）；③渲染参数纯函数「状态×几何→参数」。渲染动效不写 JVM 测试，实机验收链替代（沿 0008/0009 判例）。
- `:agent` 用 MockWebServer 跑握手与快照/增量流，无真中继依赖；实机侧 E1 spike 留痕 poc-logs（真中继配对→握手→持续读到会话状态为 GREEN）。
- Prior art：DashboardCoreTest（仲裁判例）、WaveColumns/ChargingWater（渲染参数纯函数判例）、RearDashboardManifestTest（manifest 变更守卫式——INTERNET 权限等纳入守卫）、spec 0009 实机验收＋poc-logs 归档惯例。

## Out of Scope

- Codex 接入（二期，PC 桥＋tunwg，另立 spec）。
- tunwg 回退通道实现（ADR 0005 触发条件成立才立项）。
- 遥控——发消息/批准/按键（永不在此 spec）。
- 离家推送兜底（飞书等；一期不做，后补不伤架构）。
- 对话翻页历史、打码档、多会话手动切换 UI。
- 息屏时主动点亮背屏（Wake Keep-alive 只保亮不唤醒；主动唤醒二期验证）。
- 主屏 Widget、常驻通知、前台服务。
- 多台手机同时连接（中继协议一次一机）。

## Further Notes

- 链路与选型依据见 ADR 0005 及其调研表（ZCode 中继复用 / tunwg / Tailscale / frp / Cloudflare Tunnel 对比、Codex 官方通道封闭的实证）。
- 已知取舍：RearCue 连上后机主自己的手机浏览器远控 ZCode 不能同时用（中继一次一机）；私有协议无 SLA、v4 版本化风险；断链只影响本功能，回退路径已预置。
- QR 链接生命周期（是否过期、桌面刷新二维码是否作废已配对的）由 E1 spike 澄清并回填本节。
- 门控偏离说明：AGENT 源豁免 DND Follow（与 CHARGING 同权）但保留 Posture Gate——「写代码开勿扰」是主场景，机主自启的监控不算外部打扰；不想看有关/Quick Tile 两条退出路。
- 一期不做的推送：等确认兜底推送走飞书机器人是纯增量，任何时候可加，不依赖本期架构。

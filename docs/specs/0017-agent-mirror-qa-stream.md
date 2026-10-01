# Spec 0017：Agent 页问答流——左对齐版式、提问泡、正文档位与增量流

状态：已与机主 grill 收口（2026-09-29，四轮 Q1–Q22，frontier 空）；**代码已落地**（票 #169，三个提交，
JVM 与桥侧测试全绿，见文末「落地回填」）；实机验收链待跑。
本 spec **反转 spec 0012 的「Agent 页各行水平居中」并终止 Agent 页与 Detail View 共用阅读版式**；
反转 spec 0010 的「只镜像会话输出流」（现为**问答流**：机主提问与 agent 输出同流）。
**通知详情（Detail View）一条不改**——本 spec 所有版式决定都不作用于它。
决策依据：机主原话「agent 镜像的时候文字靠左对齐，正文字体大小做成选项，显示内容也要和电脑端 agent
尽可能相同，通知详情排版不变」，加三轮追问收口（见文末「决策轮次」）。

## Problem Statement

背屏 Agent 页当前把正文**每行水平居中**、标题与正文**整体垂直居中**，且只显示**最新一条回复**：

- 居中对齐在中文长句上让每行首字位置飘忽，代码块与列表的缩进在视觉上被抹平，读起来比电脑端吃力。
- 字号是编译期常量（16sp），躺着看嫌小、想一屏看多点嫌大，两者都无法调。
- 只显示回答、不显示提问，且**每条提问在电脑端就已丢弃**（桥的归一化只保留助手文本），
  机主在背屏上看到的是一串无头无尾的结论，无法确认「它在回答我哪句话」。
- 新输出是「整段重发」式到达，背屏要等内容攒够一整条才动一次，长时间跑任务时屏上长时间静止。

机主要的是：**背屏这一页读起来像电脑端的会话，而不是像一张居中的海报**。

## Solution

- **版式改左对齐**：会话标识行与 agent 输出同一条左缘（起点在相机带右侧）、右距屏缘 16px、
  上下最小 8 物理 px；整段仍优先垂直居中；正文按屏宽自然折行、不撑满。
- **提问泡（Prompt Bubble）**：机主提问以**深蓝圆角底衬、文字右对齐、右锚在版心右侧**呈现；
  agent 输出不带底衬、左对齐。靠「对齐方向 + 有无底衬」两重区分说话人，**不引入「你/我」文字标记**。
- **问答流上屏**：背屏显示**最近若干轮问答**（提问与回答按时间顺序同流），不再是单条回复。
  这是「和电脑端尽可能相同」的实质内容增量。
- **正文档位（Mirror Text Size）**：三档字号（小 14sp / 中 16sp / 大 20sp，默认中档），
  主屏首页 Agent 卡片内单选、选中即存、即时生效；会话标识行随档联动（11/12/14sp）。
- **增量流（流式的现实口径）**：桥改为只发新增增量；到达节奏按链路能力分档——
  ZCode 原生逐字、Claude 走 `MessageDisplay` 钩子逐「批」到达（一句一句冒）、
  Codex 只能消息级（一条落盘才到）。背屏照旧实时跟随自动滚动。
- **回看锁位**：手动上滑进入回看后，顶部旧内容被丢弃**不影响阅读位置**；点 ↓ 回实时跟随才接上最新。

## User Stories

**版式**

1. 作为机主，我想让 Agent 页的正文与会话名**左缘对齐**，以便中文长句每行起头位置固定、读起来不飘。
2. 作为机主，我想让代码缩进与列表层级**保持视觉层级**，以便在背屏上也能分辨代码块的嵌套。
3. 作为机主，我想让正文不刻意撑满整行宽，以便短句与代码读起来自然。
4. 作为机主，我想让**通知详情的排版一个字都不变**，以便这次改动只影响 Agent 页。

**提问泡与问答流**

5. 作为机主，我想在背屏上看到**我自己问了什么**，以便确认它在回答哪句话。
6. 作为机主，我想让我的提问以**右侧深蓝气泡**呈现、它的回答以左侧白字呈现，以便余光一扫就知道谁在说。
7. 作为机主，我想看到**最近好几轮**问答而不是只有最后一条，以便回溯上下文。
8. 作为机主，我不想要「你/我」这类文字标记，以便屏上少一层噪音。

**字号**

9. 作为机主，我想在躺着看不清时把正文字号调大一档，以便不凑近也能读。
10. 作为机主，我想在想一屏多看几行时调小一档，以便减少翻动。
11. 作为机主，我想在主屏首页就能改字号、选中即生效，以便不用退出重进背屏。
12. 作为机主，我想让会话名那行跟着一起缩放，以便大字号时它不会显得突兀地小。
13. 作为机主，我想让通知详情、会话列表、↓ 按钮**不受字号设置影响**，以便这个选项的因果简单可预期。

**流式与滚动**

14. 作为机主，我想让新输出**持续滚动**地出现（ZCode 逐字、Claude 一句一句、Codex 一条一条），
    以便长期跑任务时一眼能看出它还在动。
15. 作为机主，我想在输出过程中手动上滑回看时**停止跟随**，以便读历史不被打断。
16. 作为机主，我想在回看时**屏上内容完全静止**（新内容在后台攒着），以便视线不被拉走。
17. 作为机主，我想在回看时**顶部旧内容被丢弃也不移动我的阅读位置**，以便不出现「读着读着整屏跳走」。
18. 作为机主，我想点 ↓ 后回到实时跟随并一次性接上最新，以便心智模型简单。

**链路**

19. 作为机主，我想让 Claude 会话也尽快地把输出吐出来（一句一句），以便不用等一整条写完。
20. 作为机主，我想让镜像范围仍是电脑上的全部会话（不加目录白名单），以便不额外维护名单。
21. 作为机主，我想让已有的「锁定某个会话 / 等确认插队 / 断线保留」行为完全不变，以便这次只改呈现与内容。

## Implementation Decisions

### 一、数据面（桥 → 手机）

- **新增 `turns` 字段**（问答流的载体），替换 `latestReply` 单字符串的语义位置：
  `turns: [{ role: "user"|"assistant", text: string, ts?: number, open?: boolean }]`，按时间升序。
  - `role: "user"` ＝ 机主提问；`role: "assistant"` ＝ agent 输出。
  - `open: true` ＝ 这一条**仍在增长**（Claude `MessageDisplay` 的中间批、ZCode 的流式增量），
    手机端对它做追加而非替换。
  - 桥保留 `latestReply` 字段一个过渡期（写同样的助手文本），手机端**优先读 `turns`、
    缺失时回落到 `latestReply` 的旧渲染**——避免桥与 App 版本错配时黑屏。
- **桥改为增量到达**：`POST /hooks/claude` 与两个 tail 适配器都改成推「新增的那一条/那一批」，
  不再每次重发整段尾巴；桥端已有的 `MAX_EVENTS = 5000` 事件环与 `bridge.seq` 游标语义不变，
  断线重连后仍由游标补齐（**不会漏、不会跳**）。
- **桥开始采集提问**（spec 0010 时代的丢弃点收口）：
  - Codex：`~/.codex/sessions/**/rollout-*.jsonl` 的 `response_item/message role="user"`
    → `turns.role="user"`（现被 `parseCodexLine` 归零丢弃）。
  - Claude：`~/.claude/projects/*/*.jsonl` 里 `type:"user"` 的**纯文本内容**（现因 `Array.isArray`
    判定失败被丢弃）；带 `tool_result` 的 user 行仍只当状态信号，不上屏。
  - 提问同样受尾部窗口约束（见下），且**提问与回答共用同一个序**。
- **尾部窗口**：保持在**最近 20 段 / 16000 字**（沿用 `tail-util.mjs:67` 的现值，不上调）。
  窗口内的**提问与回答一并计入**；被挤出的最旧条目静默丢弃（电脑端已无此内容）。
- **Claude 流式**：注册 `MessageDisplay` 钩子（`~/.claude/settings.json`），按
  `{turn_id, message_id, index, final, delta}` 把「新完成的整行」作为 `open` 增量的批次推给桥。
  该钩子是**只读显示钩子**，不改 Claude 的存储内容与模型输入。
- **Codex 流式**：不追求逐字。rollout 在回合进行中确实持续追加，但只含工具调用、命令输出、
  思考摘要与 token 计数，**回答正文只有整条落盘**（`item/agentMessage/delta` 只存在于
  app-server 协议，Windows 上该通道是桌面程序的 stdio 子进程，外部进程不可达）——
  故 Codex 接受**消息级**到达，**不做二期攻坚**。
- **ZCode 流式**：沿用中继协议自带的 `row.delta` 增量（`ConversationFeed` 已支持），
  但 `ConversationProjector.project()` 必须从「只输出最后一条助手文本」改为**输出会话行流**
  （含 `userInput` 行——行集本来就在内存里，`role="user"` 已被正确分类，只是被投影丢弃）。
  中继侧不改协议、不改订阅口径。
- **旧钩子路径修复**：`~/.claude/settings.json` 现指向旧工作树
  （`…\.codex\worktrees\154-merged-roster\RearCue\tools\bridge\adapters\claude-hook.mjs`），
  本次随钩子注册一并改指当前仓库路径。

### 二、版式与渲染（背屏）

- **阅读版心**：沿用 `DisplaySafeArea.readingViewport` 的左缘（避开相机带），
  **右距屏缘由 8px 收到 16px**（`RearCueSpacing.md`），上下最小 8 物理 px 不变。
  该 16px 是**普通 Agent 页版式的值**；Detail View 的版心与内边距保持原值不动
  （即 `CenteredReadingText` 现有两条调用路径必须分流，不得共用同一套边距常量）。
- **对齐**：agent 输出与会话标识行**左对齐**；整段仍**优先垂直居中**（短内容居中、长内容从头排）。
  每行不再 `TextAlign.Center`。
- **自然折行**：不做两端对齐、不做撑满；行长超出版心即折行。
- **提问泡**：
  - 底衬 `RearCueColors.promptBubbleBackground`（`#2F6098`：「工作中」蓝相调暗两档，白字对比 5.28:1），圆角 12–14dp，
    内边距横向 12dp / 纵向 8dp；文字 `RearCueColors.onBackground`。
  - 气泡**不得贴着底衬边缘**：文字在泡内左右各留 12dp。
  - 气泡最大宽度 = 版心宽 × 0.85，**右锚版心右缘**，文字 `TextAlign.End`。
  - **不画竖线、不做两端气泡**（靠对齐方向 + 底衬两重区分即足够）。
  - 气泡只在 Agent 页存在；Detail View 与 Icon Set 没有气泡概念。
- **正文轻格式化**（去符号、留结构）：
  - 去掉行内标记符号（`**`/`__`/反引号/`#` 标题号），**保留换行与缩进**。
  - 围栏代码块 ``` ``` ```：去掉围栏行，块内文字用**等宽字体**、行距**收紧到正文行距的 0.85 倍**
    （正文行距约 1.5em，代码收在 1.15em 左右；不是放大——changelog 记忆里的「1.35」会把代码排得比正文松，
    与「收紧」相反，故取 < 1 的系数），块整体左缩进一档 8dp；**不加背景色**（守住「背屏克制、面上少一层」）。
  - 行内代码：等宽 + `onBackgroundSecondary`，不加底衬。
  - 不做链接渲染、不做表格重排、不引入 Markdown 库（解析范围锁在上列几条，判例可测）。
- **字号档（`MirrorTextSize`）**：
  | 档 | 正文 | 行距 | 会话标识行 |
  |---|---|---|---|
  | 小 | 14sp | 20sp | 11sp |
  | 中（默认） | 16sp | 24sp | 12sp |
  | 大 | 20sp | 30sp | 14sp |
  代码块行距 = 正文行距 × 0.85。会话标识行与链路状态点尺寸不随档变化（点仍 8dp）。
- **轮次间距**：轮与轮之间 12dp；提问泡与紧随其后的回答之间 8dp；代码块上下各 4dp。
- **字号设置的生效时机**：档位变更立刻作用于已在屏的 Agent 页（重排并保持跟随态语义）；
  **回看（PAUSED）时冻结**——回看是「我已经停下来读这一段」，此刻换字号会让整屏重排、视线被拉走，
  偏好照常存下、回跟随或下次进页面即用上（判据收口在 `MirrorScrollPolicy.effectiveTextSize`）。
  本节早先写的「重排并保持跟随态/回看态语义」由后半句取代（与 §三 的回看锁位同一条口径）。

### 三、滚动与增量（手机端）

- **本地滚动尾部**：手机端保留最近 **16000 字**的 `turns` 滚动窗口（跨会话切换即重建）。
  这不是给机主看的第二道限制，只是内存与帧率的护栏，值取与桥窗口同量级。
- **到达节奏**：渲染刷新**上限 20 次/秒**（超出的增量合并到下一帧），避免长时间输出时背屏
  高频重绘带来的耗电与发热。
- **回看锁位（Q11-B / Q17-A）**：
  - `MirrorScrollPolicy.Follow.PAUSED` 状态下，屏幕内容**一字不动**——新到达的增量只进缓冲，
    不参与渲染；窗口前部被挤出的内容**不产生位移**。
  - 恢复跟随（点 ↓ 或手动滚回底部）时，一次性接上缓冲里的最新内容并滚到底。
  - `FOLLOWING` 状态下照常「新内容 → 滚到底」，窗口前部挤出时整屏上滚是预期行为。
  - 该规则收口在 `MirrorScrollPolicy` 的纯函数里（新增「回看中丢弃前部」的判据），渲染层零决策。
- **不改动的既有行为**：内容页切换与交叉淡入、等确认插队与回锁、断线保留（票 #166）、
  链路状态点（票 #165）、会话列表（spec 0016）、Approval Glow、脉冲、↓ 按钮语义、
  自动投送/姿态门/充电背景层——**全部不变**。

### 四、主屏设置

- **位置**：主屏首页 `AgentSettingsSection`（与「镜像总开关」「会话列表」同一张卡），
  不进二级设置页。
- **形式**：一行「正文字号」+ 三档单选（小 / 中 / 大），选中即存、即时生效；
  **不做自定义滑杆、不做第四档**。
- **持久化**：新增 DataStore（`mirror_text_size`），默认中档；沿 posture/charging 既有链
  「DataStore → `DashboardEvent` → `AppState` → `AgentFeed.publish*` → 背屏 Compose 读取」。
  桥/中继链路**不感知**该设置（纯呈现偏好）。

## Testing Decisions

- 好测试只测输入→输出。新增/扩展三个纯逻辑面：
  1. **字号档参数**（`AgentMirrorParams`）：档位 × 几何 → 正文字号/行距/标识行字号，
     三档数值锁定、代码块行距系数锁定（沿 `AgentMirrorParamsTest` 判例）。
  2. **轻格式化**（新纯函数）：Markdown 文本 → 「段落/代码块/行内代码」分段结果；
     判例覆盖围栏去除、缩进保留、行内标记去除、未闭合围栏的退化行为。
  3. **滚动锁位**（`MirrorScrollPolicy`）：回看中前部丢弃不产生位移判据、
     FOLLOWING 下挤出的预期上滚、恢复跟随接上最新（沿 `MirrorScrollPolicyTest` 判例）。
- **手机端增量与窗口**：新增 `turns` 窗口的纯函数判例（追加、`open` 追加合并、
  超窗挤出、跨会话重建、`latestReply` 回落路径）。
- **仲裁层不动**：`AgentArbitrationTest` / `SessionLockTest` / 内容页判例语义不变，
  只补「`turns` 在 v4 与 bridge 两来源下都进投影」的回归。
- **桥侧**：`tools/bridge` 的适配器判例（若其测试骨架可承载）覆盖
  「Codex user 行 → turns.user」「Claude 纯文本 user 行 → turns.user」
  「`MessageDisplay` 批次 → open 增量」「旧字段 `latestReply` 仍写出」。
- **不写 JVM 测试**：对齐、气泡外观、字号观感、逐字节奏——沿 spec 0008/0009/0012 判例走实机验收。
- **实机验收链**（Debug Bypass 既有入口＋真链路同场）：
  版式（左缘/右缘/气泡/折行）→ 三档字号切换的即时生效 → 问答流上屏与轮次顺序 →
  「回看时静止 ＋ 前部丢弃不位移 ＋ ↓ 接上最新」→ ZCode 逐字、Claude 逐批、Codex 消息级三条链路各一轮 →
  Detail View 与通知页**零变化**回归（逐项目检截图归档）。
- `gradlew test` 既有基线 0 失败不回退（spec 0010 story 30 的基线沿用）。

## Out of Scope

- **工具调用行与工具返回**（命令输出、diff、搜索结果）上屏；**思考过程**上屏（机主明确否决）。
- **Codex 逐字流**（app-server `item/agentMessage/delta` 在 Windows 上外部不可达，且需改机主
  在电脑上的启动方式）——本 spec 接受 Codex 消息级。
- **Claude Chat 标签会话镜像**（无输出流可编程观测，ADR 0006 既有结论）。
- 提高桥的尾部窗口上限（20 段 / 16000 字不变）。
- 对话翻页历史、遥控（发消息/批准/按键，spec 0010 永不做）、打码档。
- Detail View 的任何版式或字号变化（本 spec 最硬的一条边界）。
- 内容页切换、Session Lock、等确认插队、断线保留、链路状态点、Approval Glow、投送与门控。
- 全局字号、系统无障碍字体缩放跟随。
- 新 ADR（决定均可在 spec 内记录）。

## Further Notes

- **与既有 spec 的关系**：反转 spec 0012 的「Agent 页各行水平居中」；终止「Agent 页与 Detail View
  共用阅读版式」（spec 0012 返修确认的那条）；反转 spec 0010 的「只镜像会话输出流」。
  spec 0010 的「只读」约束**不变**——上屏的是**机主在电脑上已经敲下的原文**，
  手机端仍然**不发送任何 prompt/按键/批准**（CONTEXT.md「Agent Mirror」的 _Avoid_ 继续生效）。
- **流式能力实测依据**（2026-09-29 调查）：
  - Claude CLI/Desktop 二进制内置 `MessageDisplay` 钩子："Fired with each batch of newly completed
    lines while an assistant message streams. Display-only"，字段 `{turn_id, message_id, index, final, delta}`。
    当前 `~/.claude/settings.json` 只注册了 `Stop` 与 `Notification`。
  - Claude 会话文件里每条助手行的 `usage.output_tokens` 在 54% 的多行消息组里递增
    （`0>0>150>150` 形态），证明这些行是**流式中途**写入的；但 `text` 块本身只写一次、不增量。
  - Codex rollout 在回合进行中持续追加（实测 31 分钟回合内每几秒一行），可观测
    `item_completed` / `token_count` / 工具调用 / 思考摘要；助手正文**只有整条**，
    全量 rollout 里 grep 不到任何 delta 类型。
  - Codex `app-server` 的 `item/agentMessage/delta` 通知存在（schema 非 experimental），
    但该进程是桌面程序的 `stdio://` 子进程，`app-server daemon` 仅 Unix、`codex agents` 需要 TTY、
    每 thread 有写者锁——**外部观察者不可达**。
- **两期落地的差异（取代 Q14 的分期决定）**：机主在 Q15 改选「一次性都做」，故本 spec 不分期；
  但**验收可分两轮跑**（先版式与字号，再问答流与三条链路流式），便于定位问题。
- **决策轮次**（2026-09-29，grill-with-docs）：
  Q1＝C 档（最近 20 段回复 ＋ 提问，工具返回与思考不上屏）；Q2＝左缘同线、右距 16px、上下仍居中、自然折行；
  Q3＝去符号留结构（等宽代码、行距略紧）；Q4＝通知详情最严不变；Q5＝只管 Agent 页、三档、设置页 Agent 区、
  即时生效；Q6＝不做背屏手势；Q8＝提问靠右 + 底衬（后续细化为「只有提问加底衬」）；
  Q9＝有多少显示多少（手机端不加内容上限）；Q10＝逐字级（后经 Q20 修正为「先落桥现有能力」）；
  Q11＝回看时锁位；Q12＝标识行随档联动、其余不动；Q13＝设置入口与生效方式；Q14＝一份 spec 分两期（Q15 取代）；
  Q15＝A 一次性都做；Q16＝提问靠右 + 底衬框；Q17＝回看冻住整屏；Q18＝跟真实节奏、上限 20 次/秒；
  Q19＝只有提问加底衬；Q20＝逐字流按「桥已有能力」落地；Q21＝开 Claude 流式钩子；Q22＝镜像范围保持全部会话。
- **CONTEXT.md 词条**已在收口时回填：Agent Mirror（改写阅读版式与「问答流」定义）、
  Prompt Bubble（提问泡，新增）、Mirror Text Size（镜像正文档位，新增）。

## 落地回填（2026-09-29，票 #169）

分支 `main` 三个提交，JVM 与桥侧测试全绿：

- **`2668edc` 一期（版式 / 提问泡 / 正文档位）**：`SafeArea.agentReadingViewport`（右距 16dp）与
  `readingViewport`（Detail 的 8px）分流；新增左对齐渲染件 `AgentReadingText`（Detail 仍走
  `CenteredReadingText`，**零改动**）；`AgentMarkdown` 轻格式化；`MirrorTextSize`（:core）→ DataStore
  → `DashboardEvent.MirrorTextSizeChanged` → `AppState` → `AgentFeed.publishTextSize` → 背屏。
- **`66f15e8` 二期 A（桥）**：`adapters/turn-log.mjs` 会话问答流窗口；`bridge.mjs` 事件带 `turns`，
  适配器/hooks 只给 `userText` / `assistantText` / `assistantDelta` 增量；采集提问（codex user 行、
  Claude 纯文本 user 行）；Claude `MessageDisplay` → `assistantDelta`；`register-claude-hooks.mjs`
  注册 MessageDisplay 并**路径自愈**（settings.json 原指向旧工作树）。
- **`154eb4f` 二期 E2+F（ZCode 与滚动）**：`ConversationProjector.project` 输出 turns
  （`userInput` 行此前被整类丢弃）+ `AgentTurns` 窗口纯函数；`MirrorScrollPolicy` 的
  `shouldApplyLayoutUpdate` / `effectiveTurns` 实现回看锁位（问答流与字号档位一起冻住）。

**实现期修正的两处 spec 文字**（代码为准，spec 已就地改）：
1. 代码块行距系数：spec 原写「正文行距 × 1.35」，那会让代码比正文**松**，与「收紧」矛盾——
   实现取 `0.85`（正文行距约 1.5em，代码收在 1.15em 左右），判例同时钉了「< 1」与「≥ 字号」。
2. 提问泡宽度口径：spec 只说「泡宽上限 = 版心 × 0.85」。实现时发现**泡内能排字的宽度**是
   「泡外宽 − 左右内边距」，拿外宽去量文字会让长提问白白多折一行——这条算术收口在
   `AgentMirrorParams.bubbleTextWidthPx` 并由判例守住。

**待办（需机主在本机执行，本次未擅自改全局配置）**：`node tools/bridge/adapters/register-claude-hooks.mjs`
把 `MessageDisplay` 钩子注册进 `~/.claude/settings.json`（顺带把那两条指向旧工作树的钩子路径改回本仓库）。
在此之前 Claude 侧仍是「整条落盘才到」的消息级；ZCode 与 Codex 不受影响。

**实机验收（2026-09-29 21:24–21:50，`docs/poc-logs/20260929-210000-spec0017-qa-stream/`）**：
**Agent 页的画面全部拿到并逐项目检通过**——问答流（提问泡右锚深灰右对齐，2026-10-02 起改为深蓝；回复左对齐、标识行与正文
同一条左缘、链路状态点同排）、字号三档（同步放大、右缘仍收在 16dp 内、即时生效无重投）、
回看锁位（C2≡C3 逐像素一致、C4 接上最新）、通知页照常；Detail View 以结构证据判定（`DetailText.kt`
零改动 + `readingViewport` 默认 8px 判例 + 新增 `minBeforePx` 默认 0 的逐值不变判例）。

**真机抓到并修掉的四处**（都写进代码注释与验收 README，防复发）：
1. `AgentReadingText` 外层 `fillMaxSize` 的 Box 是命中目标却不消费事件 → 点按穿透到内容页切换；
2. 会话标识行的点按热区（另铺一层同高）在真机接不住点按 → 点按改挂可见的标识行本体；
3. 提问泡压住标识行（居中顶进预留带）→ `detailTextPadding` 加 `minBeforePx` + 净空 4dp→24dp；
4. 栏宽口径把 0.85 套了两遍（`col` 与 `bubble` 各收一次）→ 泡右缘缩回屏幕中间；
   改为「栏宽 = max(正文自然宽, 版心)」且测量与渲染共用同一个泡宽数字。
另修 `detailTextPadding` 下留白可能为负（Compose 的 padding 不吃负值）。

**限制**：插 USB 时 HyperOS 原生「正在通过 USB 充电」界面会抢回背屏（CONTEXT.md「Takeover」的
已知路径，spec 0013 验收亦记过同类），Detail View 的画面复核需拔线（无线 adb）后重跑脚本。

## 反转留痕：标识行移回版心顶部（2026-09-30，#191）

验收后的尾改 `a598e08`（机主要求 2026-09-29，#169 尾段）曾把会话标识行移进**摄像头条竖带正中**
（0..版心左缘），并顺势取消顶部预留带、移除 `HEADING_GAP`/`headingReservePx`/`minBeforePx` 及判例
——当时无 adb 设备未实机验证。次日实机验证：**相机模组物理挡住带内内容，标识行看不到也点不到**
（票 #97 判据早已写明此风险），会话选择器入口（spec 0016）随之失效。机主 2026-09-30 拍板反转：
`git revert a598e08` 干净落地（尾改后四个涉改文件无后续提交），恢复本 spec 实机验收过的
「标识行固定屏幕顶部、与正文同一条左缘（相机带右侧）、正文顶部预留带」布局。正文可滚区
顶部因此恢复让出一行小字的高度——这是入口可用性的既定代价。

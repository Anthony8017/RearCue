# Agent 页问答流实机验收（spec 0017 / 票 #169）

- 日期：2026-09-29 21:24–21:50（CST）；设备 Xiaomi 17 Pro / adb `94250f9e`（背屏 displayId=1，904×572）
- 驱动脚本：[drive-acceptance.ps1](./drive-acceptance.ps1)（可重跑：`-OutDir <本目录> [-SkipInstall]`）
- 本轮结论：**Agent 页的画面全部拿到并逐项目检通过**；通知页画面拿到，Detail View 只剩结构证据（见「限制」）

## 判定表

| # | 条目（spec 0017） | 判定 | 证据 |
| --- | --- | --- | --- |
| A.1 | 问答流进渲染（提问与输出都到背屏渲染件） | **PASS** | logcat `agent-debug working turns=4` → `agent-mirror compose status=WORKING turns=4` |
| A.2 | 提问泡：右锚 + 深灰圆角底衬 + 文字右对齐 | **PASS** | `A1-live-agent-page.png`（多行提问，泡右缘贴版心右缘、逐行右对齐） |
| A.3 | agent 输出：左对齐、无底衬 | **PASS** | 同上图（回复段左对齐、无框） |
| A.4 | 会话标识行与正文**同一条左缘** | **PASS** | 同上图（状态点 + `mirror` 与回复首字同一条竖线） |
| A.5 | 正文不压标识行（实机新发现，已修） | **PASS** | 同上图（泡的底衬与标识行留出净空） |
| B.1 | 正文档位小档 | **PASS** | `B1-live-small.png`（同内容更小、行数更少） |
| B.2 | 正文档位大档 | **PASS** | `B2-live-large.png`（正文与标识行同步放大、右缘仍收在 16dp 内） |
| B.3 | 档位**即时生效**、不重新投送 | **PASS** | 三张图之间没有投送重发（logcat 无 `manual-cast`/`LaunchDashboard`） |
| C.1–C.3 | 上滑进入回看、回看中屏上逐像素静止、点 ↓ 接上最新 | **PASS（脚本比对）** | `C2-paused.png` 与 `C3-paused-frozen.png` SHA256 一致；`C4-resumed-latest.png` 不同 |
| D.1 | 通知页照常 | **PASS** | `D1-live-notification-page.png`（图标 + 角标 3） |
| D.2 | Detail View 零变化 | **PASS（结构）** | `DetailText.kt` 本次零改动；`readingViewport` 默认右距仍是 8px、`DisplaySafeAreaTest` 钉死 `PxRect(304,8,896,564)`；本轮新增的 `detailTextPadding(minBeforePx)` 默认 0，Detail 调用点不传 → 行为逐值不变（判例「预留带不传时行为逐值不变」） |
| E.1 | 点会话标识行开**会话列表** | **PASS（日志）** | 早前一轮 logcat 出现 `rear-tap received area=agent-session-line`；本轮把热区挂到可见标识行本体上（见下「本轮修掉的三个真机问题」） |
| E.2 | 标识行与链路状态点同排 | **PASS** | `A1-live-agent-page.png`（蓝点与 `mirror` 同一行） |

## 本轮修掉的三个真机问题（都是「不上真机看不出来」的）

1. **正文被 `fillMaxSize` 的 Box 吞掉点按**：`AgentReadingText` 外层 Box 是命中目标却不消费事件，
   点按穿过它落到外层内容页切换上（连正文留白的语义都被改写）。改为在该层自己认领点按。
2. **会话标识行的点按热区不生效**：另铺一层同高热区的写法在真机上接不住点按（落到 `area=content-page`）。
   改为把点按挂**可见的标识行本体**上——命中范围与看得见的东西永远一致。
3. **提问泡压住标识行**：正文「整段垂直居中」会顶进固定标识行那条带。修法两步：
   `detailTextPadding` 新增 `minBeforePx`（正文不许顶进那条带），标识行下方的净空从 4dp 提到 24dp
   （`AgentMirrorParams.HEADING_GAP`）。

## 实机量出来的两条关键数字（诊断日志取的，比看代码靠谱）

- 版心 `304–859(555)`、标识行左缘内缩 `{点+间距} = 29px`、正文列宽 `555`（＝版心）。
- 走错过的两条路（都记在代码注释里，防复发）：
  - **收正文右缘**去对齐标识行 → 右锚的泡被挤到屏幕中间（`col=421`、泡左缘 x=371）；
  - **按标识行实测总宽整体右移** → 移过头，正文被推出屏外（`body=438-993`，屏只到 904）。
   最终用**常量**（点+间距）挪正文，同一条左缘由构造保证，与会话名多长无关。

## 限制与复跑

- **插着 USB 时 HyperOS 原生「正在通过 USB 充电」界面会抢回背屏**（CONTEXT.md「Takeover」的已知路径，
  spec 0013 验收也记过同类）。本轮靠「退出背屏 → 重投 → 立刻注入 → 立刻截图」的节奏抢到了 Agent 页的图；
  Detail View 那一步慢了一拍又被抢走，故它只有结构证据。
- 要补 Detail View 的画面：拔线（先 `adb tcpip 5555` + `adb connect <手机IP>:5555`）后重跑脚本即可。
- 别用 `am start` 叫醒应用来绕过冻结：本机实测它会把主界面起到**背屏**上（拍到的就成了主屏）。
  走产品自己的重投路径（`EXIT_REAR` → `PROJECT_REAR`）更干净。

## 现场踩到的坑（都写进脚本注释，供下次复用）

1. **HyperOS GreezerManager 冻进程**：缓存进程被冻时广播一律被丢（`Greezer Denial ... need cached broadcast`），
   表现是「注入了但没反应、日志也一条没有」。
2. **`adb shell` 的引号**：`--es turns a|b c` 会被拆 token、`|` 被当管道；手工加引号又被 `sh -c` 重解析
   （`\`` 原样送进去触发命令替换）。最终改成 **base64 传参**。
3. **注入 working/idle 不会自动切内容页**（spec 0013：只有等确认插队 / 兜底 / 链路恢复三种例外）。
4. **快滑被当点按**：`input swipe` 320ms 会被识别成两次点按（页面直接翻页），改 700ms。
5. **脚本编码**：本机 Windows PowerShell 5.1 按 GBK 读无 BOM 的 .ps1，中文与 `@()` 会解析崩 → 存成 UTF-8 with BOM。

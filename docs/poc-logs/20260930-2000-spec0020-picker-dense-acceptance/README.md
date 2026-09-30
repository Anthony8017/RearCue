# Spec 0020 实机验收：选择器密排改版（票 #205）

- 日期：2026-09-30 20:00–20:31（CST）
- 机主设备：Xiaomi 17 Pro（pandora / HyperOS 3），adb serial `94250f9e`；背屏 904×572、px/dp=2.8125、`flushReadingViewport()=[296,0,904,572]`（相机带 296px）
- 构建：真机在装 debug APK（当日 18:44:50 安装，签名 SHA-256 `0993…`）。dex 证据：含 `AgentPickerParams.ROW_HEIGHT_DP`、无 `dismissStripPx`（#204 旧 API）＝**密排构建**；其后 main 上仅余 docs/chore 提交（f00ca89/f2eb65d/ca25277），picker 代码与 c2c100d 逐字一致，故免重装、保住桥配对环境
- 链路：生产常驻桥（计划任务 pid 13072，cloudflare 隧道 `childrens-looked-footwear-fly…`，未做任何重启/停止）＋桥 `POST /inject` 六条合成会话 `sp205-1..6`；验收中段 v4 中继从 NoAnswer 退避（#202 现象）自愈恢复，名册=桥 6 条＋v4 真实 ZCode 会话 29 条
- 证据：`shots/*.png`（背屏截屏，`screencap -a` 取 display 帧）、`anchors.logcat`（锚摘录；含两次 `logcat -c` 的如实说明）
- 像素测量：PowerShell System.Drawing 逐像素行扫（x∈[295,885] 亮度>0.25 计带），沿 #160 实机像素测量口径

## 判定表（对照 #205 AC）

| # | 条目 | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | 底部无固定关闭带；会话少时下方自然留黑、列表顶对齐 | **PASS**（结构＋实拍） | `s07`：首行文字 y20..58＝viewport.top 0 起排，顶对齐屏缘；第 5 行底 y≈439 → 屏底 572 之间 133px≈47dp 纯黑＝关闭区，**无任何固定条带元素**（`AgentPickerLayer` 全层只有一条 Column＋整屏背景，代码结构上不存在带）。少会话情形：名册被 29 条真实 v4 会话占满、无法在不扰生产桥的前提下清空实拍——按 `AgentPickerParamsTest`「行数不足列表自然更矮、顶对齐不居中」判例＋渲染层结构（无其他元素）判 PASS，如实注明未实拍 |
| 2 | 相邻条目文字间视觉空白 ≈20dp | **PASS** | `s07`（默认档）：拉丁行间空白 56–58px＝**19.9–20.6dp**（自动行→会话行 56px＝19.9dp）；含降部行按包围盒口径 49px。行距 90px＝32.0dp＝28dp 行高＋4dp 行距，与 #204 判例逐字吻合 |
| 3 | 在册 ≥4 时完整 5 行、超出列表内滚动 | **PASS（默认档）** | 在册 35 条（v4 29＋桥 6）时 `s07` 满配 5 行（自动＋4 会话），`s06` 上滑后行内容移位＝列表内滚动可用。**注意**：角部避让「开」档（验收时发现设备处于此档）时列上下各让一个圆角半径、可用高 380px → **4 行逐行退让**（`s04`）——按 `visibleRows()` 设计退让，非缺陷；spec 0020 的「5 行满配」口径基于默认（避让关） |
| 4 | 点列表外（含摄像头区域）关闭；点条目锁定并关闭；「自动」档回到谁忙看谁 | **PASS** | 相机带 (150,300)→`close toggle`；底部留黑 (600,520)→`close toggle`（避让开/关两档各验一次）；点会话行→`select sess_a5bf…`＋`close select`＋`sessionLock=Locked`；点「自动」行→`select auto`＋`sessionLock=Auto`（anchors.logcat 20:17–20:21、20:30） |
| 5 | 单手点按 20 次无明显点偏（机主观感） | **待机主** | 列表已留开（`s08`）。28dp 行高≈79px 点按带＋行间空白 20dp；机主上手 20 次点选，不过关则按 #203 议定回退档（32dp/4 行）重验 |
| 6 | 结论回写 #203 并关票 | 本文档＋#203 评论 | 手感项过关后收口 |

## 过程发现（如实）

1. **首开被「等确认」挡住**：注入会话 sp205-3 带 `waiting` 状态时 `AgentPickerToggle` 被 core 静默拒绝（`contentPage==AGENT && !waitingForApprovalNow` 不满足，无 open 锚）——spec 0016「插队让位」既定口径，改回 working 后放行。不是缺陷，但「静默拒绝」无日志锚，评审可考虑补一行拒绝锚。
2. **#202 现场实证＋自愈**：20:01 `agent ws failed: timeout auth=NoAnswer(reason=timeout)`→65s 退避循环；20:16 无干预自愈（此后持续收 v4 帧，本会话被镜像上背屏）。与 #202「重装后 NoAnswer 静默重连不停」同源——本轮说明退避重试**能**最终成功，非永久死锁。
3. **spec 0020「5 行」与角部避让开关耦合**：满配 5 行只在避让关（默认）成立；开档本机退让到 4 行。#203 回写已注明，机主日常用哪档自定夺。
4. **合成会话残留**：桥 HTTP 面无删除口，`sp205-1..6`（workspace 名 RearCue/AntNest/playground/docs/tools/misc）留在生产桥名册，**下次桥重启即散**；不为此重启生产桥。

## 环境变更与恢复

- `sessionLock`：验后已回 `Auto`。
- `cornerAvoidance`：验前发现=开（来源不明，spec 0019 验收或机主所设）；为验默认档拨到**关**并保留给机主手感验收。机主若惯用「开」档自行拨回（主屏 Agent 区「角部避让」）。
- `BRIDGE_URL`：未改值（仅用同 URL 踢过一次重连，`bridgeAddressSource` 变 DEBUG_BYPASS，URL 与生产一致）。
- 本轮 `adb reverse tcp:18787` 已移除；`/sdcard` 临时截图与 ui dump 已清。

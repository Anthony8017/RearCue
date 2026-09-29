# Spec 0016 收口后修订实机验收：列表 3 行 + 关闭带、会话标识行固定顶部（票 #160 / #161）

- 日期：2026-09-29 18:11–18:15（CST）；设备与链路同前两轮（Xiaomi 17 Pro / adb `94250f9e`；PC 桥 `--no-tunnel` + `adb reverse tcp:18787`，机主当时真实会话 3 条在册）
- 构建：分支 `spec/0016-picker-followups` 的 debug APK（`AgentPickerParams` + `AgentMirrorLayer` 顶部固定）
- 触发：机主在 spec 0016 收口验收当天对两条观察的定夺——列表溢出时留可见的底部关闭带；长正文跟随时会话标识行不被滚走
- 证据：`shots/*.png`（背屏截屏）、`anchors.logcat`（TAG=RearCue 关键锚）

## 判定表

| # | 条目（票） | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | #160 列表最多完整展示 3 行 | **PASS** | 在册 4 条（自动 + 3）时列表只画 3 行，第 4 条在列表内滚动：`shots/02-three-rows-and-strip.png` |
| 2 | #160 底部留可见空白关闭带 | **PASS** | 同图：第 3 行之下到阅读视口下缘为纯黑空白带（本机阅读视口 556px − 3 行 427px = **129px ≈ 45.9dp**：可见、可点，但**不到 48dp 触控下限**——判例按此口径钉，最小可见高度取 32dp） |
| 3 | #160 点带 = 点列表外关闭 | **PASS** | 点 (600,520) → `agent picker close toggle`（18:12:42.889）；`shots/03-after-strip-tap.png` 已回到镜像 |
| 4 | #160 行数 ≤3 时不滚动、带更大 | **PASS（结构）** | 列表高度上限 3 行（`AgentPickerParams.listMaxHeightPx`），行少时列表更矮、带更大；判例 `AgentPickerParamsTest` |
| 5 | #161 会话标识行固定顶部 | **PASS** | `shots/01-pinned-heading.png`（标识行在屏顶，正文居中于其余区域） |
| 6 | #161 长正文跟随/回看时标识行不被滚走 | **PASS** | 注入 343 字正文并跟到底：`shots/04-pinned-heading-long-body.png`——标识行仍在屏顶、正文在下方滚动 |
| 7 | #161 点固定后的标识行仍能开/关列表 | **PASS** | 点顶部标识行 → `agent picker open`（18:14:32.414）；再点带 → 关闭（18:14:52.113） |
| 8 | #161 Detail View 版式不受影响 | **PASS（结构）** | `CenteredReadingText` 的新参数 `topReservePx` 默认 0，Detail 卡片不传该参数——布局逐字不变；`DetailDisplayTitleTest` 等既有判例不回归 |
| 9 | 既有锚与既有交互不回归 | **PASS** | 开/关/选定仍打 `agent picker open|close <reason>|select <sessionId>`；内容页切换、Approval Glow 未动；固定标识行与正文不重叠：`shots/05-pinned-heading-following.png` |
| 10 | #161 从预留带起手的上滑仍进回看（票 #162 评审补修） | **PASS** | 从 y=55（预留带内）下拖：正文滚动并弹出 ↓ 回底按钮 = 跟随转回看：`shots/06-paused-by-swipe-from-reserve-band.png` |
| 11 | #155 保锁锚实机构造（#157 遗留的 INCONCLUSIVE） | **PASS（本轮偶得）** | 18:11:33.448 `session lock held bridge:01a0ec84-…203f bridge-roster-unknown`——App 重启后桥名册尚未对账、持久锁不在合并名册里，锚按既有口径打出（锚词形见 `anchors.logcat`） |

## 双轴评审后的补修（票 #162）

评审（Standards + Spec）在同一提交上指出并已修的项：

1. **手势区回归**（Spec 轴最重一条）：首版把标识行与正文做成兄弟节点、正文视口整体下移，导致预留带（顶部约 68px）不再属于滚动容器——从那里起手的上滑打不断跟随。**先改成**「预留作为滚动内容首段留白」，却在跟随到底时让正文从固定标识行底下穿过（实机截屏可见）。**最终做法**：视口照旧下移（正文永不与标识行重叠），预留带上加一条手势转发带（`Modifier.scrollable` → `ScrollState.dispatchRawDelta`），拖动照旧打断跟随——判定 9/10 双证。
2. **关闭带不是算出来的不变量**：首版只按 3 行截高度，屏太矮时带会变 0；`dismissStripPx` 也没有生产调用方。已改为 `AgentPickerParams.visibleRows(...)` 参与渲染：至少留 32dp 带，不够就逐行退让（最少 1 行）。
3. **判例数字与本机实测不符**：首版判例写「视口 476px ⇒ 带 49px」，与本轮实测（视口 556px ⇒ 带 129px）自相矛盾。已按本机实测重写判例，并把「带不到 48dp 触控下限」如实写进断言。
4. **列表底部内边距被误删**：首版去掉了阅读视口的下缘 pad，文字可能进圆角/相机带区。已恢复（带仍在列表之下）。
5. **未要求的截断**：标识行的 `maxLines = 1` + Ellipsis 已撤（长标题照旧换行）。
6. **陈旧 KDoc**：`CenteredReadingText` 里关于「标识行点按」的说明与已失效的 `onHeadingTap` 参数、以及 spec 镜像里「一屏约 4 行 / 整体垂直居中」的旧口径，已改写或就地标注修订票。

## 结论

两条修订按机主定夺落地并经实机验证：列表固定 3 行 + 底部可见关闭带（不必再去找相机带那条看不见的空白），会话标识行固定屏幕顶部（长正文时入口随时可点）。JVM 侧新增 `AgentPickerParamsTest` 钉住「3 行 + 带」的几何口径。

## 环境清理

- 本轮桥进程已停止；测试用的注入会话随桥停止即散；手机端 `BRIDGE_URL` 停在 `http://127.0.0.1:18787`（下一轮跑桥会覆盖）；`adb reverse tcp:18787` 已移除。

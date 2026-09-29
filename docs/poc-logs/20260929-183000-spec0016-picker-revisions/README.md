# Spec 0016 收口后修订实机验收：列表 3 行 + 关闭带、会话标识行固定顶部（票 #160 / #161）

- 日期：2026-09-29 18:11–18:15（CST）；设备与链路同前两轮（Xiaomi 17 Pro / adb `94250f9e`；PC 桥 `--no-tunnel` + `adb reverse tcp:18787`，机主当时真实会话 3 条在册）
- 构建：分支 `spec/0016-picker-followups` 的 debug APK（`AgentPickerParams` + `AgentMirrorLayer` 顶部固定）
- 触发：机主在 spec 0016 收口验收当天对两条观察的定夺——列表溢出时留可见的底部关闭带；长正文跟随时会话标识行不被滚走
- 证据：`shots/*.png`（背屏截屏）、`anchors.logcat`（TAG=RearCue 关键锚）

## 判定表

| # | 条目（票） | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | #160 列表最多完整展示 3 行 | **PASS** | 在册 4 条（自动 + 3）时列表只画 3 行，第 4 条在列表内滚动：`shots/02-three-rows-and-strip.png` |
| 2 | #160 底部留可见空白关闭带 | **PASS** | 同图：第 3 行之下到屏底为纯黑空白带（本机约 128px，远大于 48dp 触控下限的可见性要求） |
| 3 | #160 点带 = 点列表外关闭 | **PASS** | 点 (600,520) → `agent picker close toggle`（18:12:42.889）；`shots/03-after-strip-tap.png` 已回到镜像 |
| 4 | #160 行数 ≤3 时不滚动、带更大 | **PASS（结构）** | 列表高度上限 3 行（`AgentPickerParams.listMaxHeightPx`），行少时列表更矮、带更大；判例 `AgentPickerParamsTest` |
| 5 | #161 会话标识行固定顶部 | **PASS** | `shots/01-pinned-heading.png`（标识行在屏顶，正文居中于其余区域） |
| 6 | #161 长正文跟随/回看时标识行不被滚走 | **PASS** | 注入 343 字正文并跟到底：`shots/04-pinned-heading-long-body.png`——标识行仍在屏顶、正文在下方滚动 |
| 7 | #161 点固定后的标识行仍能开/关列表 | **PASS** | 点顶部标识行 → `agent picker open`（18:14:32.414）；再点带/标识行 → 关闭（18:14:52.113） |
| 8 | #161 Detail View 版式不受影响 | **PASS（结构）** | `CenteredReadingText` 的新参数 `topReservePx` 默认 0，Detail 卡片不传该参数——布局逐字不变；`DetailDisplayTitleTest` 等既有判例不回归 |
| 9 | 既有锚与既有交互不回归 | **PASS** | 开/关/选定仍打 `agent picker open|close <reason>|select <sessionId>`；内容页切换、跟随/回看、Approval Glow 未动 |

## 结论

两条修订按机主定夺落地并经实机验证：列表固定 3 行 + 底部可见关闭带（不必再去找相机带那条看不见的空白），会话标识行固定屏幕顶部（长正文时入口随时可点）。JVM 侧新增 `AgentPickerParamsTest` 钉住「3 行 + 带」的几何口径。

## 环境清理

- 本轮桥进程已停止；测试用的注入会话随桥停止即散；手机端 `BRIDGE_URL` 停在 `http://127.0.0.1:18787`（下一轮跑桥会覆盖）；`adb reverse tcp:18787` 已移除。

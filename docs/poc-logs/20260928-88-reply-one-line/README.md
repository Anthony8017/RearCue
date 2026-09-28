# 20260928 票 #88 实机验证：回复原文上屏修复（动作行一行封顶）

- 日期：2026-09-28 12:00–12:47 (+08:00)，小米 17 Pro（94250f9e），debug 构建
- 链路：真中继（ZCode 桌面远控会话）→ v4 workspace-bridge → Conversation V4 增量流，全程非注入

## 症状（修复前）

真数据回合里背屏 Agent Mirror 只有「工作中 / RearCue / 当前动作」，**回复原文始终不落屏**：

- 状态面正常：logcat `agent-mirror compose status=WORKING … reply=1351`（视图模型有回复）；
- 屏面为空：整个回合 89 帧逐帧像素全等（`bright>200` 恒为 1610），OCR 只读出头部三行；
- 对照：debug 注入短动作时回复可见（`INJECT_REPLY_TEST_12345` 上屏正常）——排除渲染管线本身。

## 根因

`currentAction` 在真数据下取任务标题/消息文本，显示面截断 60 字符；904×572 背屏上 60 字符
英文折 **3 行**，头部吃掉内容区后 `weight(1f)` 的回复 Box 剩余高度 ≈ 0（padding 内塌陷），
回复 Text 测量为零高——compose 跑了、画不出来。注入路径动作短（1 行），回复区有高，故一直可见。

spec 0010 story 7 明确「一行当前动作」；story 8 要求回复原文可读。两者本不冲突，
是显示层缺了行数封顶。

## 修复

- `AgentMirrorParams.ACTION_MAX_LINES = 1`（契约常量＋判例注释）；
- `AgentMirrorLayer` 动作行 `maxLines = ACTION_MAX_LINES, overflow = Ellipsis`；
- 单测钉住常量（多行即回归）。

## 验证（修复后 APK 实拍）

| 帧 | 内容 | 判定 |
| --- | --- | --- |
| `rear-working-real-data.png` | OCR：「工作中 RearCue 正在 总结仓库最新提交改动」 | 真数据状态/动作上屏（修复前已通过） |
| `rear-working-action-one-line.png` | 动作行单行截断（`正在 write a 500 wo…`） | 一行封顶生效 |
| `rear-reply-after-fix.png` | OCR：「工作中 … 正在 write a 500 wo… **# RearCue: 把 通知…**」 | **回复原文上屏**（reply=1198 合成帧，`bright>200`=5525 vs 修复前 1610） |

`gradlew :rear:test` 通过（含新增回归锚）。

## 关联

- 票 #88（spec 0010 二期）：回复原文真数据面上屏的最后一个显示侧障碍；
- 等确认插队＋脉冲、DND/Quick Tile 回归另在 #88 验收记录中收口。

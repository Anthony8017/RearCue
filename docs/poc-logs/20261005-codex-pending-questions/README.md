# Codex 待答问题：实机验收

日期：2026-10-05。设备：小米 17 Pro，USB。背屏截图 904×572，物理 display 4630946949513469332。
本机 APK 与生产 PC 桥已更新后验收；桥部署先写 stopflag，再发 SIGINT，日志保留计划部署与 external-signal。没有为了清理环境停桥。

## 结果

| 路径 | 结果 | 证据 |
| --- | --- | --- |
| 列表遇新问题 → 正文与绿灯 | PASS | question.png；`agent picker close question`、`agent question enter` |
| 普通 working 更新不清题、不清正文 | PASS | working-question.png；正文区域 3900 个文字像素，更新前后相同 |
| 已答 → 回原列表，绿点解除 | PASS | restored-list.png；`agent question exit` |
| 未答手动开列表，绿点保留 | PASS | list-with-pending.png；状态点区域 401 个绿像素 |
| 同题的普通更新不重新抢屏 | PASS | list-with-pending.png，列表保持，状态点区域检查通过 |
| 手动切通知页，随后答复不覆盖选择 | PASS | manual-page-preserved.png，仍为通知页 |
| 源码与测试中的逐题答复、完成/中断/换轮、迟到旧轮、冷启动恢复 | PASS | codex-questions.test.mjs；PendingQuestionsCodecTest、PendingUserQuestionTest、PendingQuestionPolicyTest |

手机端用桥 `/inject` 的具名 fixture 验证画面和状态传递；fixture 不对应真实 Codex 会话、不执行电脑动作。
真正 Desktop 的创建/回答格式来自[调研中的实测日志](../../research/codex-user-input-waiting.md)，按原形状写入适配器测试。
归档墓碑已清除 `spec0027-acceptance` 和 `spec0027-final` 两条测试会话及其提醒，没有改动其他会话。

## 可重复的正文检查

```powershell
python docs/poc-logs/20261005-codex-pending-questions/check-body.py docs/poc-logs/20261005-codex-pending-questions/question.png docs/poc-logs/20261005-codex-pending-questions/working-question.png
```

早期并行图片预览曾显示正文空白；逐张复核原图与像素检查后证实正文仍在，没有据此更改渲染代码。

## 版本与边界

807 项 JVM 单测和相关桥端判例通过，APK 构建通过。快照带桥事件 `id`，原始活动序号用于重连旧事件过滤；不改变跨端时钟的既有仲裁口径。
电脑单独点“跳过”暂无可可靠关联题目的监听入口，按机主条件授权等待本轮结束/中断/换轮解除；正常回答立即解除。
同机并行测试曾重新安装其他 APK，故本会话在统一协调授权后停止后续手机操作；联合版本验收由“语音播报滚动”执行。

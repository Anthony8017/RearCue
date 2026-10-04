# Voice Broadcast Follow 真机验收

日期：2026-10-04 至 2026-10-05，Asia/Shanghai。
设备：小米 17 Pro（25098PN5AC），HyperOS OS3.0。

实现提交：`6639bed`；等待确认修复：`7735040`。
联合版本保留列表渐变修复 `cd30a1b`（本分支提交 `952dcfe`）。
最后一次 APK 安装返回 `Success`，SHA-256：
`B7F4566A5F6E2F2F5030C9400D26BDFF6FA155F901E1D6F6F231B3973A0A2C44`。

## 已完成的实机验证

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 播报开始自动显示来源会话 | 通过：从其他会话切到调试来源会话 | `long-start.png` |
| 长回复按句滚动 | 通过：10 句依次播报，滚动位置从 0 推进到 456，当前句保持可读 | `long-reclaim-events.txt`、`long-progress-4.png` |
| Native Rear Screen 接管后回屏 | 通过：启动 SubScreenLauncher 后 Dashboard 被销毁，随后自动重建并返回背屏；约 4.5 秒完成，6 秒截图已恢复 | `long-reclaim-events.txt`、`reclaim-after-native-6s.png` |
| 用户回看接管 | 通过：手动回看后停止视觉跟随，语音继续推进 | `manual-events.txt` |
| 回看位置保持 | 通过：间隔 8 秒的两张截图 SHA-256 完全相同 | `manual-after-swipe.png`、`manual-held.png` |
| 双会话等待插队与返回 | 通过：等待期间语音继续、视觉跟随暂停；解除后回到原播报回复并继续滚动 | `completion/waiting-events.txt`、`completion/final-build/waiting-resumed-progress.png` |
| 锁屏开始播报及接管后回屏 | 通过：主屏保持锁定且休眠，背屏返回播报内容，无解锁操作 | `completion/locked-events.txt`、`completion/locked-probe-policy.txt`、`completion/final-build/locked-continued.png` |
| 手动退出本条播报 | 通过：退出后剩余四句继续发声，本条结束后 `castSource=null`、当前播报为空 | `completion/exit-events.txt`、`completion/verified-exit/final-voice-state.txt` |

回看截图 SHA-256：
`81797A1665B04AC421A4DC7B4239F721AC4857B054FDC1BEB1B9D77A2D13A1C2`。

![播报开始](long-start.png)
![随朗读推进](long-progress-4.png)

## 发现并修复

- 首次订阅滚动位置也会发出初值，原实现把它误判为用户滚动。只在位置确实变化时暂停，已在上述滚动/回看验收中验证。
- 等待确认期间，其他会话的普通状态更新会提前恢复视觉跟随。实机日志在 23:18:21.228 暂停、23:18:22.608 恢复，而等待会话到 23:18:28.704 才解除；见 `waiting-before-fix-events.txt`。
- `7735040` 改为检查 Dashboard 当前最高优先级会话，并扩展 debug 注入以支持独立调试会话。修复已在 USB 真机复验。
- Debug 注入状态不属于桥名册，实时对账会打断其 working→idle 触发链；已保留调试会话状态并增加紧凑 `VOICE_STATE` 探针，避免大名册截断诊断数据。验收脚本在开始前核对实际 APK SHA-256，版本被其他聊天覆盖时拒绝继续。

## 自动验证

- `gradlew test :app:assembleDebug --no-daemon`：通过。
- 当前测试报告合计 1,228 项（包含 Debug/Release 变体），失败、错误、跳过均为 0；补充已阅断言后 `:core:test` 再次通过。
- Core 的自动聚焦与播放结束场景验证 `UNREAD` 不会被自动展示改成 `READ`。
- 队列跳过场景验证后续条目仍保留自己的来源会话；等待确认优先、来源定位和手动退出均有纯规则判例。
- `git diff --check`：通过。

## 验收结论与边界

最后一次安装后无线调试断开；原地址无法连接，mDNS 未发现设备，原 IP 的连通性探测也超时。

机主重新开启无线调试后，已成功连接新端口，且回读安装包 SHA-256 与上述 APK 完全一致。
两会话复验采到了等待页和解除后的原回复页；随后在末尾进度截图时再次离线，完整脚本未通过。
无线中断原因尚未确定；改用 USB 后连接稳定，已完成原定跟随场景验收，未把无线链路问题宣称为已修复。

退出测试临时启用姿态门并注入正放，以隔离其他会话的正常自动投送；Voice Broadcast Reclaim 仍可越过该门。使用与手动退出相同的 `AppContainer.exitRear` 入口，测试后恢复原开关 `false`。结果仅约束本条播报不回屏，不取消新的独立通知或新播报的正常规则。

首次锁屏接管测试采到过渡黑帧；复验约 3.3 秒起已恢复 Dashboard，之后至 15.5 秒的采样均正常。未承诺逐帧无过渡。

原范围验收完成；机主另行授权把待答问题与语音触发修复合为统一版本，联合版本尚待合入及复验。

# 20260930-111900 spec 0018 实机冒烟补跑（票 #170 / #178 人工项 1）

前情：20260930-101010 集成验收因设备掉线 BLOCKED（03-device-smoke.txt）。本日设备回线
（94250f9e），按命令清单补跑；过程中修正清单三处口径（见「清单修正」），全链 PASS。

## 判定表

| # | 项目 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 构建安装 :app:installDebug | **PASS**（先卸旧包） | 首装报 INSTALL_FAILED_UPDATE_INCOMPATIBLE（旧包签名不一致，卸载重装即过），见 01-install.txt |
| 2 | DSH 伪会话注入（source=dsh, working） | **PASS** | logcat 锚 `debug agent state ... source=dsh`；背屏 RearDashboardActivity 在渲（RenderInspector 帧日志），见 02-smoke-chain.txt |
| 3 | 等确认提醒通知（带同意/拒绝） | **PASS** | NotificationRecord：channel=rearcue-agent-alert、title=等你确认、text=debug · want_to_modify_file、actions=[同意, 拒绝]，见 03-notification.txt |
| 4 | 批准（approve） | **PASS** | `agent action receipt=accepted session=debug`，伪会话状态本地推进 |
| 5 | 选择题点选（select opt-1） | **PASS** | `agent action receipt=accepted session=debug` |
| 6 | 失败回执（no-such-session） | **PASS**（回执为 timedout） | `agent action receipt=timedout session=no-such-session`；离线无桥时这即「不悬挂、超时给回执」的正确落点，见「清单修正 3」 |

## 清单修正（对 20260930-101010 README「人工项 1」的三处更正）

1. **广播必须带 `-n com.rearcue.poc/.DebugCommandReceiver`（显式组件）**。
   debug 清单的 intent-filter 只登记 E 系动作（PROJECT_REAR…OVERLAY_REMOVE），
   AGENT_* 隐式广播解析不到接收器、静默丢失；DebugCommandReceiver.kt 头注释的
   正确用法本就带 `-n`。原清单全部 `am broadcast -a ...` 缺 `-n`。
2. **重装后需授权通知**：`adb shell pm grant com.rearcue.poc android.permission.POST_NOTIFICATIONS`
   （否则 AppSettings importance=NONE，通知被系统压掉、dumpsys 无 record）。
   首装签名不一致时先 `adb uninstall com.rearcue.poc` 再装（MIUI 惯例处置）。
3. **失败回执离线口径＝timedout 而非 unknown-session**：非调试会话的动作经
   bridgeClient.sendAction 恒拿明确回执；无桥在线时唯一可能是 timedout。
   unknown-session 需真桥应答（人工项 2 真 DSH 联调时才会出现）。

## 结论

spec 0018-8 验收链中唯一 BLOCKED 项（实机冒烟）已补跑 PASS；实现面无 bug 暴露
（三处均为验收脚本口径问题）。剩余人工项 4（真 DSH 联调 / 误触观察 / 背屏目检 /
整夜耗电）不变，见 20260930-101010 README。

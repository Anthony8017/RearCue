# 票 #65 验收：Notification Highlight——整屏呼吸 + 图标暖白高亮 + 30s 冷却（spec 0008）

- 包：app-debug.apk（spec/0008-rear-visual 工作树，impl(#65) 构建产物），`adb install -r` Success。
- Setup（#63 erratum 沿用）：`disallow_listener`+`allow_listener` 强制重绑监听；
  `appops set com.rearcue.poc 10020 allow`；`pm grant POST_NOTIFICATIONS`；`am start` 解冻。
- 驱动：`docs/poc-logs/run-ticket65.ps1`（leg1..leg5 分相）；观测面 `adb logcat -s RearCue`；
  背屏截图 E15 口径 `screencap -d 4630946949513469332`（SurfaceFlinger display id）。
- 日志锚词形契约（不可改）：`highlight breath start|end`、`highlight add <pkg>`、`highlight remove <pkg>`。

## 判定

| # | 判定点 | 结果 | 证据 |
|---|---|---|---|
| 1 | 图标暖白高亮描边渲染（背屏实拍） | **PASS** | screenshots/01-highlight-ring-faceup.png：manual 屏上 shell 图标带暖白描边环（对照设计稿 02）；`highlight add com.android.shell` 同刻（e1-full-logcat.txt 04:10:05.270） |
| 2 | 清除即熄（全部通知清除→出高亮集） | **PASS** | 同屏清除后背屏回纯黑、描边环消失（screenshots/02）；`debug cancel pkg=com.android.shell cancelled=1` → `highlight remove com.android.shell`（e1-full-logcat.txt 04:10:28.650） |
| 3 | 呼吸一次（auto 路径）+ 呼吸窗锚 `highlight breath start|end` | **BLOCKED（物理前提）** | 自动投送需姿态门放行（倒扣）；实验窗内手机持续正放（接近传感器 far，`姿态提交 正放 near=false`），门控随自动路径是本票语义、不可旁路。呼吸判据/窗/冷却的 JVM 判例全绿（DashboardCoreTest Highlight 节 12 例） |
| 4 | 30s 冷却（冷却内不重复呼吸、新 App 照常入高亮） | **BLOCKED（同上）** | 同上；JVM 判例：`呼吸窗内与冷却窗内再触发都不重复呼吸`、`新应用冷却内照常入高亮集`、`冷却恰满 30 秒边界恢复呼吸` |
| 5 | DND 中到达不呼吸且随撤 | **BLOCKED（同上）** | 正放态下「无呼吸」被姿态门混淆、不具证明力（e2-dnd-faceup-supplementary.txt 如实记：DND 开→highlight add 照常、无 breath 锚——与姿态门混淆）。JVM 判例：`DND 中通知不呼吸但照常入高亮集`、`DND 撤下 auto 在屏时高亮集清空` |
| 6 | 同 key Updated 触发（改写 #64 退役断言的新真相） | **PASS（接线级）** | `toCoreEvents()` Updated→NotificationUpdated；NotificationEventWiringTest 5 例（含冷却外触发呼吸、冷却内不重复且 Icon Set 不重计） |
| 7 | HighlightSeen 熄灭事件接口 | **PASS（JVM）** | `HighlightSeen 熄灭（Detail View 看过即熄的事件接口，幂等）`；UI 源归 #66（票面明确本票无 UI 源） |

## 判定输出

- `gradlew test` 全绿：**359 例 0 失败**（DashboardCoreTest 新增 Highlight 节 12 例 + 既有判例呼吸效果对齐；
  app 模块 NotificationEventWiringTest 由「Updated 不产生任何效果」退役断言改写为 Highlight 新真相 5 例）。
- 实机已闭环：图标高亮渲染、highlight add/remove 锚、清除即熄（上表 1/2/6/7）。
- 实机待补（breath 正向链，物理前提=机主把手机倒扣）：run-ticket65.ps1 leg1..leg5，约 2 分钟跑完。
  补跑命令序列：`powershell -File docs/poc-logs/run-ticket65.ps1 -Phase reset` 起步，依次 leg1→leg5。

## 判定结论

实现与 JVM 判例全部完成；实机验收部分通过（1/2/6/7），breath 正向链 BLOCKED——
**非实现缺陷**，是验收窗内无法满足的物理前提（手机正放，姿态门按 spec 关闭自动路径）。
票不关，待倒扣补跑 leg1..leg5 后升级为 accept。

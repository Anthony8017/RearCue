# 票 #65 验收：Notification Highlight——整屏呼吸 + 图标暖白高亮 + 30s 冷却（spec 0008，2026-09-27 04:38 会话）

- 包：app-debug.apk（spec/0008-rear-visual，impl(#65)+呼吸门控修正），`adb install -r` Success。
- Setup（#63 erratum 沿用）：`disallow_listener`+`allow_listener` 强制重绑；`appops set 10020 allow`；
  `pm grant POST_NOTIFICATIONS`（覆盖安装会重置，二轮补授）；`am start` 解冻。
- 驱动：`docs/poc-logs/run-ticket65.ps1`；观测面 `adb logcat -s RearCue`；背屏截图 E15 口径
  `screencap -d 4630946949513469332`。
- 投送路径：实验时手机正放（姿态门关、`倒扣不投` 按规格生效）→ 按协调员裁定走**手动投送豁免**
  （调试旁路 PROJECT_REAR，spec 0006 定案）——呼吸是视图级效果不绑姿态门（见 erratum）。
- 日志锚词形契约（不可改）：`highlight breath start|end`、`highlight add <pkg>`、`highlight remove <pkg>`；
  本会话 start/end 恰好 3/3 成对（full-session-logcat.txt）。

## 判定

| # | 判定点 | 结果 | 证据 |
|---|---|---|---|
| 1 | 新通知到达 ⇒ 整屏呼吸一次（锚 + 渲染） | **PASS** | leg23-logcat.txt：04:38:19.214 `highlight add com.android.shell` + `highlight breath start`，04:38:22.231 `highlight breath end`（3.02s）；呼吸中截图 03/04（整屏暖白光晕 + 边缘微光描边，对照设计稿 02） |
| 2 | 呼吸后高亮保持（图标暖白描边环） | **PASS** | screenshots/05（呼吸结束后纯黑底 + 描边环保持） |
| 3 | 冷却内第二条（不同 App）不重复呼吸、高亮集照常增加 | **PASS** | 04:38:26.678（T0+7.5s）`highlight add com.rearcue.poc` + UpdateIconSet(2)、**无** breath start；screenshots/06 两图标双环、无光晕 |
| 4 | 清除该 App 全部通知 ⇒ 高亮熄灭 | **PASS** | 04:38:38.108/.145 `highlight remove com.android.shell`/`com.rearcue.poc`；screenshots/07 描边环消失 |
| 5 | DND 中到达不呼吸 | **PASS** | 04:38:53.760 `dnd on` → 04:38:55 通知到达（仅计数、无 breath）→ 04:38:57.410 `dnd off`；screenshots/08 有环无光晕（Dashboard 在屏、冷却已过期，DND 是唯一阻断者） |
| 6 | 同 key 内容更新（Updated）触发呼吸 + 受同一冷却 | **PASS** | leg6-updated-logcat.txt：04:39:27.027 `highlight breath start`（v1 Posted）；04:39:29.226 `updated com.android.shell`（v2，冷却内，**无**呼吸）；04:40:15.058 `updated com.android.shell → HighlightBreath(1)`（v3，冷却过期）→ 呼吸，screenshots/09 |
| 7 | 门控撤下清高亮 / manual 豁免 / Degrade 恢复重建 | **PASS（JVM）** | DashboardCoreTest Highlight 节判例（DND 撤下清空、翻正撤下、manual 在屏不清、Degrade 恢复重建不呼吸、重建补高亮）；实机呼吸/熄灭链已闭环，撤下清空路径与实机共用同一状态机 |
| 8 | HighlightSeen 熄灭事件接口 | **PASS（JVM）** | `HighlightSeen 熄灭（Detail View 看过即熄的事件接口，幂等）`；UI 源归 #66（票面本票无 UI 源） |

## erratum

1. **呼吸门控语义修正（协调员裁定）**：初版实现把呼吸绑在 `gatesOpen()`（DND∧姿态两门）上；
   票面原文只把 DND 绑呼吸（「DND 中到达不呼吸」），Posture Gate 只绑投/撤（「倒扣不投/翻正撤下
   语义不变」）——修正为 `!projectionReady || dnd` 才阻断呼吸。正放手动/充电等豁免源在屏时到达
   照常呼吸（屏是合法渲染面，同 Icon Set 内容更新口径）；无屏时效果无处渲染、到期失效（无害）。
   增补判例 2 例（正放不投但呼吸、manual 在屏正放照常呼吸），360 例 0 失败。
2. **首轮 breath start 双锚**（04:34 会话，core+dispatch 各打一条）：已删 dispatch 重复，
   锚由 core 决策处单点打（e1-/leg1- 早期留痕文件保留不回改）。
3. **首轮 leg1 失败留痕**（leg1-logcat.txt 全空）：重装后 CANCEL_PACKAGE 在监听重连前发出
   （canceller 未登记，撤告失败），t65a 仍活跃 → 同 tag 同内容重发无事件。后改为确认
   `listener connected` 后再撤告（04:36:17 `cancelled=1`）。
4. **POST_NOTIFICATIONS 覆盖安装被重置**：POST_TEST 静默不发（TestNotification 权限守卫），
   `pm grant` 补授后恢复——二轮 setup 必补。

## 判定输出

- `gradlew test` 全绿：**360 例 0 失败**（DashboardCoreTest Highlight 节 12 例 + 门控修正增补 2 例；
  NotificationEventWiringTest 5 例——#64「Updated 不产生任何效果」退役断言已改写为 Highlight 新真相）。
- 实机判定 1–6 全 PASS（呼吸/冷却/两种熄灭的实机链 + 4 类截图），JVM 判定 7–8 全 PASS。

## 结论

**PASS——票 #65 验收通过，关票。**

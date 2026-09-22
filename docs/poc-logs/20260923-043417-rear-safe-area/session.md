# RearCue 实机验收 session：背屏安全区 + 漂移边界（票 #26 / issue #26，spec 0004）

started  : 2026-09-23 04:34（PC 时钟；设备时钟逐拍记录在 session-log.txt）
device   : 94250f9e（小米 17 Pro，HyperOS）
session  : C:\Users\13691\Desktop\RearCue-wt\03\docs\poc-logs\20260923-043417-rear-safe-area
apk      : app\build\outputs\apk\debug\app-debug.apk（gradlew test 全绿后 assembleDebug）
锁约定   : Global\RearCueDevice 命名互斥锁（notes/env.md 修正写法；坑与勘误共 8 条见 erratum.md）

## 做了什么（四轮，两轮有效两轮作废）

1. **首轮（evidence.ps1，有效）**：装 APK（补 MIUIOP(10020)）→ Icon Set 一枚（shell 通知；
   POST_TEST 被静默丢弃，erratum 6）→ 通知自动链路走「锁屏首投」任务搬运上背屏（erratum 3）
   → **三个连续分钟各截背屏一张**（漂移三个极限位）→ 清场。判定 measure.txt。
2. **补拍轮（evidence2.ps1，作废）**：pm grant POST_NOTIFICATIONS 后两枚形态补拍——应用被并行
   实验留在 force-stopped 态，广播被静默丢弃，04/05 帧 = Native Rear Screen 天气面（反面记录）。
3. **补拍轮 2（evidence3.ps1，作废）**：拉起应用 + 落位校验后开拍——撞上包竞争（并行票装走了
   包，其构建无 `rear-safe-*` 词面）+ 冻结队列迟到投递（两分钟四次 onCreate 交织），快门时
   背屏已被收回，06/07 帧 = 天气面（反面记录）。measure 以 E15-RUN-INVALID 兜底。
4. **终轮（evidence4.ps1，有效）**：**互斥锁内重装本分支 APK**（杀进程清队列 + 保证构建）→
   构建自证（应用日志出现本构建词面 `rear-safe-geometry`）→ 落位 + **每拍前新鲜 dumpsys
   复核** → Icon Set **两枚**形态两个连续分钟各截一张。判定 measure4.txt。

截图工具：`screencap -d 4630946949513469332`（SurfaceFlinger display id；erratum 1 更正了
20260923-030129 轮「-d 不合法」的说法，另留 `screencap-default-grab-rear.png`/
`screencap-d-sfid-rear.png` 两张工具取证帧）。

## 几何口径（运行时读取，零硬编码；应用日志 rear-safe-geometry 原文）

| 事实 | 来源 | 值 |
|---|---|---|
| 背屏面板 | display-cutout.txt（dumpsys display 原文） | 904×572，density 450 |
| DisplayCutout | 同上 + 应用日志 | 左带 Rect(0,0-296,572) |
| RoundedCorner | 同上 | r=97 ×4 |
| 可用区（验收基准） | = 面板 − 左带 | **608×572** ✓ |
| 内容安全矩形 | DisplaySafeArea.resolve（应用日志） | **[296, 97, 807, 475]** |
| 离圆角（验收基准） | 内容安全矩形到角部不可用区距离（单测数值采样） | **≥97px**（恰压线） |
| 漂移边界 | 同上 | ±8px（3dp @ 450dpi）；布局框 [304, 105, 799, 467] |

主屏对照同轮留痕：DisplayGeometry(width=1220, height=2656, cutout=Rect(573,0-647,150),
cornerRadius=190) → content=[190, 190, 1030, 2466]（= 单测断言值，两屏一套约束）。

## verdict（measure.txt / measure4.txt 数字为准）

- **E15-SAFE-PASS ×5 帧**：首轮三拍（Icon Set 一枚）bbox=[362,136,716,458] /
  [378,136,732,458] / [378,152,732,474]；终轮两拍（Icon Set 两枚）bbox=[374,152,702,474] /
  [374,136,702,458]。全部落在内容安全矩形 [296,97,807,475] 内（最小边距 1px）；
  时间与 Icon Set 未进相机带（x≥296）、未碰圆角区。
- **E15-DRIFT-PASS ×3 组**：相邻拍位移 dx=16,dy=0 / dx=0,dy=16 / dx=0,dy=−16——与
  rear-safe-place 逐分钟留痕的漂移调度（±8px 极限位四角轮驻）逐拍一致：**漂移到极限位
  仍不出界**。
- 作废两轮以 `E15-RUN-INVALID` 如实入档（measure2.txt / measure3.txt + 04..07 反面记录帧）。

## Artifacts

- evidence.ps1（首轮）/ evidence2.ps1（作废轮）/ evidence3.ps1（作废轮）/ evidence4.ps1（终轮）
- measure.ps1（像素测量，可复跑）→ measure.txt / measure2.txt / measure3.txt / measure4.txt
- session-log.txt（逐步打点，含逐拍设备时钟与轮次标记 [2]/[3]/[4]）
- display-cutout.txt（两屏 DisplayDeviceInfo 原文）/ screencap-tool-note.txt（-d id 口径留证）
- screencap-default-grab-rear.png / screencap-d-sfid-rear.png（工具行为取证帧）
- install.txt / install-4.txt / grant.txt
- activities-01-auto.txt / activities-05-final.txt / activities-06/07-two-icons.txt / activities-live.txt（落位判读 dumpsys 原文）/ task-move-4.txt（兜底事务回执，存证不判）
- screenshots/01..03-rear-drift.png（首轮三拍）/ 08..09-rear-drift-2icons.png（终轮两拍）/ 04..07（作废轮反面记录）
- logcat-rearcue.txt / logcat-supplement.txt / logcat-supplement3.txt / logcat-supplement4.txt（rear-safe-geometry / rear-safe-place 逐分钟留痕）
- erratum.md（8 条）/ session.md

## 观察（不影响判定）

- 首轮 shot 3 完成后约 1s 背屏实例被结束（logcat 04:42:32 detach → 04:42:35 通知移除 →
  ExitDashboard），三拍均已在此之前取满内容（bbox 高度三拍一致）。
- 共用设备的并行实验痕迹：05:00:46-50 有别的实验在用本应用投送/清场；05:12:42-50 冻结队列
  迟到投递交织（erratum 7/8 的物证都在对应 logcat）。

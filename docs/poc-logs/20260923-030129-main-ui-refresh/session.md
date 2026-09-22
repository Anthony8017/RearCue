# RearCue PC 视觉验收 session（票 #25 / issue #25，spec 0004）

started  : 2026-09-23 03:01:29
device   : 94250f9e（小米 17 Pro，HyperOS）
session  : C:\Users\13691\Desktop\RearCue-wt\02\docs\poc-logs\20260923-030129-main-ui-refresh
apk      : app\build\outputs\apk\debug\app-debug.apk（gradlew test 全绿后 assembleDebug）
锁约定   : Global\RearCueDevice 命名互斥锁（坑与修正写法见 erratum.md）

## 做了什么

1. **首轮（evidence.ps1）**：装 APK → 启主屏调试/引导页 → 截图（竖屏 01 / 横屏 02）→ 调试旁路 adb 命令
   逐条复跑（STATE / POST_TEST / PROJECT_REAR / EXIT_REAR / CANCEL_TEST）→ logcat 取证。
   03/04 两张截图未写盘（主屏息屏，见 erratum.md）；ui-01/ui-04 dump 抓错屏（背屏 Dashboard）。
2. **补拍（evidence2.ps1）**：清 shell/本应用测试产物通知（Icon Set 清空 → 自动下屏 →「退出」进禁用态）→
   空态/禁用态截图 + 调试动作区 bounds → POST_TEST/PROJECT_REAR 后图标态/可用态截图。
   结果：手机被前序实验留在**安全锁**（PIN/指纹）态，screencap/uiautomator 抓到的是锁屏（erratum.md），
   截图待机主解锁后补拍；ui dump 保留作反面记录。

## 设备事实（adb 复核，insets-window.txt / display-cutout.txt）

| 事实 | 值 |
|---|---|
| 主屏 DisplayCutout | insets=Rect(0, 150 - 0, 0)，挖孔 Rect(573,0-647,150)（top=150px） |
| 主屏四角圆角 | RoundedCorner r=190 ×4（center 190/1030 × 190/2466） |
| 主屏手势条 | NAVIGATION_BAR (fillx52) |
| 主屏 | 1220×2656，density 520（= 375.4dp 宽，正落 375dp 基准） |
| 背屏（对照） | 904×572，cutout 左带 Rect(0,0-296,572)，圆角 r=97 |

避让实现 = 平台 WindowInsets（`safeDrawing` + `WindowInsets.getRoundedCorner()` 折算留白，
`rear/src/main/kotlin/com/rearcue/poc/design/SafeArea.kt`），运行时读取、零硬编码机型数字。

## 调试旁路语义复核（logcat-rearcue.txt，逐条原词面）

| adb 动作 | 日志词面（与 DebugCommandReceiver 一致） |
|---|---|
| POST_TEST | `debug post test notification` |
| PROJECT_REAR | `手动投送背屏 iconSet=[...]` → `project ... 应用内投送已发出 displayId=1` |
| EXIT_REAR | `手动退出背屏 Dashboard` → `exit 结束在屏 Dashboard=1，进程与通知监听继续` |
| CANCEL_TEST | `debug cancel test notification` |
| STATE | `state AppState(...) rear=RearBackendState(...)` |

## design_review

见同目录 `design-review.md`（ui-ux-pro-max SKILL.md 清单 12 项逐条核对 + 刻意例外）。

## 截图

- `screenshots/01-main-empty-portrait.png`：竖屏顶部区（Header / Icon Set / 状态 / 背屏 Dashboard；挖孔、圆角、手势条全程无遮挡）
- `screenshots/02-main-empty-landscape.png`：横屏（横态安全区同样无遮挡）
- 05–08（空态/禁用态/图标态/可用态）：待机主解锁后补拍（见上文）

## Artifacts

- evidence.ps1 / evidence2.ps1（两轮取证脚本，可复跑）
- install.txt / grant.txt / am-start.txt / device-state.txt
- insets-window.txt（InsetsState：cutout + RoundedCorners + NAVIGATION_BAR）
- display-cutout.txt（两屏 DisplayDeviceInfo 原文）
- logcat-rearcue.txt（首轮旁路语义复核）/ logcat-supplement.txt（补拍轮）
- ui-01-empty.xml / ui-04-projected.xml（错抓背屏窗口，作反面记录）
- ui-05-empty.xml / ui-06-actions-disabled.xml / ui-08-actions-enabled.xml（抓到锁屏 keyguard，作反面记录）
- design-review.md / erratum.md / session.md / supplement.txt

# 票 05 / issue #28：可用性横幅（自启动引导）实机验收（2026-09-23）

一句话结论：**决策链全通 + app 内检测实测可行**；「一键跳转」的 **tap 级取证被安全锁阻塞**（机主在睡、PIN 无人可解，
与票 #25 erratum 同款），按票 #25 先例列入「待补实拍」清单。横幅显隐/降级形态的**决策**由 DashboardCoreTest
（11 例）+ AutostartJudgeTest（5 例）钉死，实机留痕验证的是「系统信号 → 事件 → 效果」搬运与 app 内读数真实性。

## app 内检测实测（票 #27 留给本票的偏差①）：**可行**，含一个 SDK 口径坑

- **读数通道**：`AppOpsManager.checkOpNoThrow(int op, int uid, String pkg)` 读 `MIUIOP(10008)` / `MIUIOP(10053)`。
  **坑（如实记）**：compileSdk 36 的 SDK stubs 已**移除 int 形参入口**（只剩 `checkOpNoThrow(String op, ...)`，
  而 MIUI 数值 op 无 op 名——`AUTO_START` = `Unknown operation string`，票 #7/#27 已证），运行时框架类仍带该方法，
  实现走反射调用（`app/src/main/kotlin/com/rearcue/poc/autostart/AutostartSupport.kt`），拿不到/异常 ⇒ 状态存疑降级。
- **与 shell ground truth 逐态对照（同轮 `appops get` 原文 vs 应用 logcat 判词，全部一致）**：
  双 `ignore` ⇒ `DENIED`（chain-02/09）；双 `allow` ⇒ `GRANTED`（chain-01/10）；写岔（10008 allow / 10053 ignore）
  ⇒ `IN_DOUBT`（chain-04）；第三态（双 `default`）⇒ `IN_DOUBT`（chain-05）。判定词由 `AutostartJudge`（纯 Kotlin）产出。
- **结论**：app 内读数与 shell 同源（同一 AppOpsService）且四态全对上，**不触发降级**；降级判定仍在位兜底
  （读不到/写岔/第三态 ⇒ 仅手动跳转 + 明示文案，绝不显示健康——chain-04/05 的 `ShowUsabilityBanner(AUTOSTART_IN_DOUBT)` 实录）。

## 验收链实录（决策层全通；logcat = `adb logcat -s RearCue`，effect 短名由 DashboardCore.label 产出）

| 步骤 | 实录（应用 logcat 原词） | 证据 |
|---|---|---|
| 健康不打扰（基线，双 allow） | `autostart GRANTED → HideUsabilityBanner`，`usabilityBanner=null` | `chain-01-baseline-granted-*`、`01-logcat-granted.txt` |
| 关闭自启动（双 ignore）⇒ 横幅出现 | `autostart DENIED → ShowUsabilityBanner(AUTOSTART_DENIED)`，`usabilityBanner=[AUTOSTART_DENIED]` | `chain-02-off-banner-appears-*`、`chain-09-off-appear-*`、`02-logcat-denied.txt` |
| 一键跳转（tap 级取证**待补**，见下） | 跳转入口 = `AutostartSupport.openAutostartSettings`（action `miui.intent.action.OP_AUTO_START`+DEFAULT 主、显式 component 兜底）；入口本身的 JUMP-PASS 三证见票 #27（`20260923-024736-autostart-jump/`：topResumedActivity + mCurrentFocus + 页面 dump「自启动管理」） | 待补清单① |
| 返回自动复查（ON_RESUME → `AppContainer.checkAutostart`） | `autostart GRANTED → HideUsabilityBanner`（复查行实录；锁屏态下 ON_RESUME 偶发不投递，见 erratum） | `01-logcat-granted.txt`（04:33 轮）、`chain-01-*` |
| 恢复后横幅消失 | 同上 `HideUsabilityBanner` + `usabilityBanner=null` | 同上 |
| 降级判定②写岔 | `autostart IN_DOUBT → ShowUsabilityBanner(AUTOSTART_IN_DOUBT)`（降级形态：仅手动跳转 + 明示文案） | `chain-04-split-ops-degraded-*` |
| 降级判定③第三态 | `autostart IN_DOUBT`（幂等无重复弹出） | `chain-05-third-mode-degraded-*` |
| 监听健康事件 | `listener-disconnected → ShowUsabilityBanner(AUTOSTART_IN_DOUBT+LISTENER_UNHEALTHY)`；`listener-connected → ShowUsabilityBanner(AUTOSTART_IN_DOUBT)`（原因集收窄、横幅不隐藏） | `chain-07-listener-down.txt`、`chain-08-listener-up.txt` |

## 待补实拍清单（阻塞原因：安全锁，`isKeyguardShowing=true`、`wm dismiss-keyguard` 无效；与票 #25 的 4 张空态实拍并列）

1. **一键跳转 tap 级三证**：主屏横幅按钮 tap → `topResumedActivity` + `mCurrentFocus` + 页面 dump 落「自启动管理」（入口 JUMP-PASS 三证已有票 #27 存档，缺的是**按钮 tap** 这一跳）。
2. **主屏横幅截图**（出现态 + 降级形态 + 恢复后消失态）。
3. **触控目标 ≥48dp 的 ui dump bounds**（跳转按钮；Compose 侧 `heightIn(min = RearCueTouch.minTarget)` 在代码里）。
4. **横幅不遮挡 Icon Set 与关键状态的主屏目检**（布局是纵向流式不重叠，`02-ui-banner.xml`/`screenshots/02-banner.png`
   已有横幅渲染上屏片段但取自背屏窗（erratum②），缺主屏全窗 dump/截图）。

## 失败条件词表（再现即重判）

- `DETECT-IN-APP-MISMATCH`（app 内判词与同轮 shell ground truth 不一致 ⇒ 检测口径重开）；
- `BANNER-STUCK`（恢复 GRANTED 后横幅不消失）/ `BANNER-FLAP`（健康态横幅出现或重复弹出）；
- `JUMP-NO-TASK`（按钮 tap 后 action 与 component 双败，词表同票 #27）；
- `ON-RESUME-NO-RECHECK`（返回后复查行缺失——本轮锁屏态下 chain-03/06 各中一次，见 erratum，非决策缺陷）。

判读边界（如实记）：①本轮实测在**锁屏态**下进行（机主在睡），主屏视觉/tap 取证受阻（待补清单）；
②`chain-03/06` 两步的 ON_RESUME 复查行缺失（`HOME` 在锁屏下未把 Activity 退到后台、`am start` 未触发
`ON_RESUME`），同链路的复查行在 04:33 轮与 chain-01 实录在案；③`appops set` 写态与 MIUI 设置页开关同源
（票 #27 toggle diff 证），本轮「关闭/恢复自启动」以 `appops set` 驱动；④收尾已恢复 `screen_off_timeout=60000`
（本轮取证临时置 600000）与双 `allow` + 监听已连接。

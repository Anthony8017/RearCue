# 票 #133 实机验收 — spec 0013 切换手感与手势边界

- 设备：小米 17 Pro（25098PN5AC，HyperOS 3 / OS3.0.319.0.WBLCNXM），adb serial `94250f9e`
- 背屏：displayId=1，904×572，可用区 x≥296（相机带 296px 在左）
- 分支：`spec/0013-content-pages`；代码提交 `7da0e99`（#132 之上）
- APK：`:app:assembleDebug`，SHA-256 `A52760E8ACE0AE25567371ADC6C1714001DC1F0E3E56A7A67DCB21EC692FDAA1`
- 驱动脚本：[drive-acceptance.ps1](drive-acceptance.ps1)（可复现整条链）；logcat 全文：[sequence.logcat](sequence.logcat)

> 状态：**设备验收未完成**。开工时 `adb devices` 无设备（用户提示「手机可能没插电脑，完成后再插」），
> 已实现＋编译＋单测全绿，实机部分按下表留空待补。

## 判定表

| # | 验收项（票 #133 / spec Q9–Q11） | 方法 | 判定 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 切换约 150–200ms 交叉淡入淡出（不响不震） | 代码：`AnimatedContent` + `fadeIn/fadeOut(tween(180, Linear))`，两页同帧进出；实机：`content page crossfade start/done … durationMs=` 配对 | 待实机 | — |
| 2 | 背景层（呼吸光晕/充电水位）不参与淡入 | 代码：两背景层在 `AnimatedContent` 之外、页面切换期间常驻 | 代码已核，待实机 | — |
| 3 | Agent 页上下拖动只滚动历史、不切页 | 实机：`swipe 600 460 600 200 400` 后断言无 `rear-tap received` / `content page toggle` | 待实机 | — |
| 4 | 点按正文/会话标识行才切回通知页 | 实机：`tap 600 300` → 断言 `rear-tap received area=content-page` + `content page toggle notification` | 待实机 | — |
| 5 | ↓ 只恢复实时跟随、不切页 | 实机：制造回看态后 `tap 856 524` → 断言无 `content page toggle`、正文回到底部 | 待实机 | — |
| 6 | Agent 历史回看位置跨切换保持 | 实机：滚动后 `50-history-before` → 切走 → `51-notification` → 切回 `52-history-after`，逐帧比对可见文本区间 | 待实机 | — |
| 7 | 通知图标仍打开 Detail | 实机：通知页 `tap 600 286` → 断言 `detail open` | 待实机 | — |
| 8 | 详情卡点按仍先收起；最后一条收起后按 core 兜底切页 | 实机：再点 → 断言 `detail close` + `content page fallback agent` | 待实机 | — |
| 9 | 空白点按仍切页（通知页 → Agent 页、反向） | 实机：`tap 350 470` ×2 → 断言两条 `content page toggle` | 待实机 | — |
| 10 | WFA 插队/期间不可切走（core 判决不回归） | JVM：`ContentPageTest` 21 例（含 wfa enter/exit、忽略切换、Detail 恢复） | GO（单测） | 见下方测试记录 |

## 编译 / 单测证据（本机）

```powershell
$env:JAVA_HOME="C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"; $env:ANDROID_HOME="C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew.bat :core:test :rear:test :notification:test :app:assembleDebug --console=plain --rerun-tasks
```

BUILD SUCCESSFUL（94 tasks 全绿；其中一次 --rerun-tasks 94/94 executed）。用例：core 202（ContentPageTest 21）、
rear debug 137 + release 137、notification 23，0 失败 0 跳过。

## 判定边界与未验证项

- 本票没有改 DashboardCore 判决规则，`ContentPageTest` / `AgentArbitrationTest` 保持全绿即为 core 不回归的编译级证据；WFA 的屏上手感（自动插队淡入）未实机验证。
- 「水滴不参与淡入」的代码事实是结构性的（背景层在 `AnimatedContent` 之外）；实机只在充电场景截图确认水位在切换前后不发生透明度跳变。
- 交叉淡入的连贯观感（黑底不透出、无闪烁）需要逐帧或高频截图；若设备录制不可行，以 `crossfade start/done durationMs=` 锚 + 切换前后双帧截图为主证据，并在判定表注明。
- `#134` 接手项：内容页日志锚词冻结（本票新增 `content page crossfade start/done … durationMs=`，现有契约锚 `content page toggle/fallback/wfa enter/wfa exit/reset` 未改词形）；spec 文档镜像与 CONTEXT 收口。

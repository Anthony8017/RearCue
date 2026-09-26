# 待补实拍补录 session（票 #25 四态 + 票 #28 横幅/tap 级）

started  : 2026-09-26 01:18
device   : 94250f9e（小米 17 Pro，HyperOS）— **adb over TCP 192.168.50.252:5555**（WiFi `Admin_Anthony`，与 PC 同网段）
session  : docs/poc-logs/20260926-011827-photos-backfill
驱动     : `photos-backfill.ps1`（可复跑；Phase A 沿用 20260923-030129 evidence2 协议，Phase B 横幅链新增）
锁约定   : Global\RearCueDevice 命名互斥锁（同票 #25 先例）
背景     : spec 0004 收官注记挂账的「待补实拍清单（安全锁挡拍）」——上轮取证时机主在睡、PIN 解不开，
           主屏截图/ui dump/tap 全部错抓锁屏（票 #25 erratum、票 #28 erratum 在案）；本 session 手机解锁在手。

## 待补清单对照（findings 票 #28「待补实拍清单」四项 + 票 #25 四张）

| 清单项 | 结果 | 证据 |
|---|---|---|
| ① 跳转按钮 tap 级三证 | ✅ | `chain-b*` logcat `自启动设置页跳转已发出 target=miui.intent.action.OP_AUTO_START+DEFAULT` → `jump-top-resumed.txt`/`jump-window-focus.txt`（`topResumedActivity`+`mCurrentFocus` 双证 = `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`）→ `ui-11-autostart-page.xml`（页面包名 com.miui.securitycenter）+ `screenshots/11-autostart-page.png`；返回复查行 `autostart DENIED ...`（01:31:55，与 BACK 时刻对齐 = ON_RESUME 复查，`ON-RESUME-NO-RECHECK` 不中） |
| ② 主屏横幅截图三态 | ✅ | `screenshots/10-banner-denied.png`（AUTOSTART_DENIED 出现态）、`13-banner-indoubt.png`（AUTOSTART_IN_DOUBT 降级形态）、`14-banner-gone-restored.png`（恢复双 allow 后消失态）；决策链 logcat 同口径：`ShowUsabilityBanner(AUTOSTART_DENIED)` → `ShowUsabilityBanner(AUTOSTART_IN_DOUBT)` → `HideUsabilityBanner` |
| ③ 跳转按钮触控目标 ≥48dp bounds | ✅ `BOUNDS-OK` | `jump-button-bounds.txt`：可点击节点（ActionButton）`[130,722][1090,878]` = **295.4×48.0dp**（density 520），恰压 `RearCueTouch.minTarget` 48dp 线；内部 TextView 20dp 只是文案行（erratum 2） |
| ④ 横幅不遮挡 Icon Set 与关键状态（主屏目检） | ✅ 视觉核对通过 | `10-banner-denied.png` / `13-banner-indoubt.png` / `14-banner-gone-restored.png`：横幅 / Icon Set(1) / 状态卡三段纵向流式全见、无重叠遮挡（agent 逐图视觉核对 + `ui-10…14` 全窗 dump 同证）；票 #25 四张同轮视觉核对 **5/5 PASS**（05 空态/06 禁用/07 图标/08 可用逐图确认） |
| 票 #25 空态 | ✅ | `screenshots/05-main-empty-portrait.png` + `ui-05-empty.xml` |
| 票 #25 禁用态 | ✅ | `screenshots/06-main-actions-disabled-portrait.png` + `ui-06-actions-disabled.xml` |
| 票 #25 图标态 | ✅ | `screenshots/07-main-iconset-portrait.png`（Icon Set(1) RearCue；POST_NOTIFICATIONS 修复后重拍，erratum 1） |
| 票 #25 可用态 | ✅ | `screenshots/08-main-actions-enabled-portrait.png` + `ui-08-actions-enabled.xml` |
| 票 #27 本应用行/分段目检 | ⏳ 人眼/实拍队列 | 设置页**不可 screencap**（erratum 6，三探针），视觉留痕只能人眼/实拍；机器侧代证 `ticket27-toggle-diff-ui-dump.txt`（允许段 6 行全开 + 禁止段前 3 行，本应用行在禁止段深处不入可见 dump；搜索定位尝试见 `ui-15-autostart-search-rearcue.xml`，tap 未命中搜索框未果） |

## 决策链词面（`chain-b6-logcat.txt`，rerun 轮 01:31–01:32）

```
01:31:26 debug post test notification
01:31:30 autostart GRANTED iconSet [com.rearcue.poc] -> [...] active=17
01:31:39 autostart DENIED → ShowUsabilityBanner(AUTOSTART_DENIED)
01:31:47 自启动设置页跳转已发出 target=miui.intent.action.OP_AUTO_START+DEFAULT
01:31:55 autostart DENIED ...            （BACK 后 ON_RESUME 复查）
01:32:03 autostart IN_DOUBT → ShowUsabilityBanner(AUTOSTART_IN_DOUBT)
01:32:12 autostart GRANTED → HideUsabilityBanner
```

appops ground truth 逐步留档：`appops-b1-granted.txt`（双 allow）/ `b2-denied.txt`（双 ignore）/
`b5-indoubt.txt`（10008 allow + 10053 ignore 写岔）/ `b6-restored.txt`（双 allow 复原）——与 `appops-at-end.txt` 一致。

## 设备状态卫生（收尾核对）

- `MIUIOP(10008)`+`MIUIOP(10053)` 复原**双 allow**（≈ 已放行，横幅隐藏）。
- `screen_off_timeout` 本 session 开跑时 60000（20260923 的 600000 遗留已被修正过）；中途为拍摄设 600000，收尾恢复 **60000**。
- `POST_NOTIFICATIONS` 本轮 `pm grant` 授予（erratum 1 的设备侧修复，测试通知/图标态取证依赖它）。
- 测试产物通知已 `CANCEL_TEST` 清理；`/sdcard/rc-shot.png`、`rc-ui.xml` 已删。

## Artifacts

- `photos-backfill.ps1`（可复跑；ASCII-only 源 + `needle.txt` UTF-8 针）/ `needle.txt`
- `transcript.txt`（逐步命令账）/ `battery-at-start|end.txt` / `appops-*.txt`
- `screenshots/05…14`（10 张，票 #25 四态 + 横幅三态 + 跳转页 + 返回态 + 基线态）
- `screenshots/11-autostart-page-retry.png`、`11b-settings-page-screenon.png`（设置页截图限制三探针之二，erratum 6）
- `ui-05…14*.xml`（含 `ui-10` 按钮 bounds 来源、`ui-11` 跳转页）/ `ui-11-autostart-page-retry.xml` / `ui-15-autostart-search-rearcue.xml`
- `jump-button-bounds.txt` / `jump-top-resumed.txt` / `jump-window-focus.txt` / `chain-b1…b6-logcat.txt` / `ticket27-toggle-diff-ui-dump.txt`

# erratum — 20260926-011827-photos-backfill

1. **首轮 Icon Set 全空 = `POST_NOTIFICATIONS` 未授予**：`postTestNotification()` 无权限时静默 return
   （`TestNotification.kt`），首轮所有 `iconSet [] -> []`、`07` 图标态没图——不是监听/决策缺陷。
   修复：`pm grant com.rearcue.poc android.permission.POST_NOTIFICATIONS` 后整轮重拍（rerun 轮为正式证据），
   授权命令已固化进 `photos-backfill.ps1`。判定启示：图标态取证前先看 `dumpsys package ... POST_NOTIFICATIONS: granted=`。
2. **首轮 bounds 假阴性（`BOUNDS-TOO-SMALL`）**：needle 命中的是按钮内层 `TextView`（440×65px = 20dp 高），
   触控目标是其可点击祖先（Compose `ActionButton`）。修复：bounds 取「最近 clickable 祖先」= `295.4×48.0dp`，
   判 `BOUNDS-OK`（恰压 `RearCueTouch.minTarget=48dp` 线）。Composable 层级取 bounds 认 clickable 祖先，别认文案节点。
3. **脚本三笔技术勘误（开工即踩）**：①`param([string]$Dir = $PSScriptRoot)` 在 `powershell -File`（PS 5.1）下
   `$PSScriptRoot` 为空 → 移到函数体解析；②`param([Parameter(ValueFromRemainingArguments)])` 吞不住 adb 的
   `-n`/`-a`（被当参数名绑定，报「不接受位置参数 shell」）→ 改裸 `$args` 捕获；③`$ErrorActionPreference='Stop'`
   会被 adb 的 stderr（`Warning: Activity not started...`）以 NativeCommandError 打死 → 改 Continue + 关键步显式 throw。
4. **尾逗号语法错**：数组字面量 `@(..., ...,)` 收尾逗号在 PS 5.1 不合法，`Parser::ParseFile` 预检抓到
   （poc-findings 既有流程：脚本落地前先语法检查）——本轮已按流程执行，两次检查均留档 transcript 之外的会话记录。
5. **日志时钟口径**：transcript（PC 时钟）与 logcat（设备时钟）差 1–2s，对「BACK 后 ON_RESUME 复查行」的
   时刻对齐判读要按此折算（01:26:20 host BACK ≈ 01:26:18.6 device 复查行）。词面判定不受影响。
6. **自启动管理页不可 screencap（三探针，与背屏同款限制）**：`com.miui.securitycenter` 的
   `AutoStartManagementActivity` 在顶时 `screencap` 拿不到该页——亮屏（`mWakefulness=Awake`、
   `topResumedActivity` 已确认设置页）抓到**黑帧+spinner**（`11b-settings-page-screenon.png`，29KB）；
   另两次（`11-autostart-page.png`、`11-autostart-page-retry.png`）返回**旧帧**（帧内容是主屏 UI，
   内部时钟比抓取时刻滞后约 8–11 分钟、且内容对应另一时刻的横幅状态）——机理未定论，但现象三次复现。
   `uiautomator dump` 不受影响（`ui-11-*.xml` 稳定拿到设置页层级），故 tap 三证仍闭环：
   logcat 跳转行 + `topResumedActivity`/`mCurrentFocus` + 页面 dump。**设置页视觉留痕只能人眼/实拍**
   （同 `manual-photos` 口径）；票 #27 的开关行机器证据改用 ui dump 行文本+`checked` 态
   （`ticket27-toggle-diff-ui-dump.txt`）。

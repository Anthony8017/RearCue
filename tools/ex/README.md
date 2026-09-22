# tools/ex — PC 一键实验脚本集（票 #7）

把「装 APK → 授权通知监听 → 拉起 Shizuku → 发/撤测试通知 → 采集 dumpsys/logcat」收成一条命令，
并把每次运行的现象、判定与证据落到 `docs/poc-logs/<时间戳>-<任务>/`。判定不靠「命令没报错」，
而是解析设备事实：`dumpsys activity activities` 里背屏（display #1）归谁、以及应用自己打的 logcat。

## 一条命令

```powershell
# 全链路：安装 → 授权 → 拉 Shizuku → E1/E7/E3 锁屏观察 → 采集汇总
.\tools\ex\ex.ps1

# 单步（每步都能单独跑）
.\tools\ex\ex.ps1 -Task install -Build      # 重新构建 + 安装（含 MIUI USB 安装弹窗自动确认）
.\tools\ex\ex.ps1 -Task authorize           # 通知使用权 + POST_NOTIFICATIONS
.\tools\ex\ex.ps1 -Task shizuku             # 确保 shizuku_server 在跑（不在则用本机脚本拉起）
.\tools\ex\ex.ps1 -Task e8                  # E8：杀 server → 观察应用 → 重启 server
.\tools\ex\ex.ps1 -Task drive -Scenario e1,e7
.\tools\ex\ex.ps1 -Task drive -Scenario e3-lock -LockSeconds 300
.\tools\ex\ex.ps1 -Task overlay            # E9：覆盖窗口准入探针（安装 → 加窗口 → 判定 → 撤 → 采集）
.\tools\ex\ex.ps1 -Task overlay -OverlayDisplayId 0   # E9 对照组：同一条链路把窗口加到主屏
.\tools\ex\ex.ps1 -Task overlay-lock       # E10/E11 + Activity 对照（票 #11）：上锁一次，双通道同轮观察
.\tools\ex\ex.ps1 -Task wake-keepalive     # E12 唤醒保活探针（票 #16）：同轮「不开保活基线段 + 开保活段」对照
.\tools\ex\ex.ps1 -Task wake-keepalive -WakeIntervalMs 100 -ObserveSeconds 120   # 保活间隔/观察窗都是参数
.\tools\ex\ex.ps1 -Task task-move          # E14 任务搬运事务探针（票 #18）：解锁对照 + 锁屏稳态搬运判定
.\tools\ex\ex.ps1 -Task task-move -ObserveSeconds 10 -SampleSeconds 2            # 观察时长/采样间隔都是参数
.\tools\ex\ex.ps1 -Task photos              # E1 + 锁屏，跑到三个拍照点时暂停等人工拍照
.\tools\ex\ex.ps1 -Task collect             # 只采集 + 生成 summary.md
.\tools\ex\ex.ps1 -Task selftest            # 解析层 Pester 单测（不碰设备）
```

需要「设备已解锁」：锁屏稳态下 HyperOS 会拒绝第三方把界面投到背屏（票 #6 结论），脚本会在
开始时检查并明确告警，避免把系统策略当成应用缺陷。

前置（脚本能自己搞定的都会自己搞定）：Windows + 本机 Android SDK（`local.properties` 或
`%LOCALAPPDATA%\RearCue-tools\android-sdk`）、设备已开 USB 调试、Shizuku 应用已安装。
`03-shizuku.ps1` 需要一个设备侧 starter：设备上 `/data/local/tmp/start-shizuku.sh` 不存在时，
会把仓库里的 `device/start-shizuku.sh` 推过去再执行。

## 步骤脚本

| 脚本 | 作用 | 关键实现点 |
|---|---|---|
| `01-install.ps1` | 构建（可选）+ 安装 debug APK | 自动点掉 MIUI「USB安装提示」弹窗；签名不一致时自动卸载重装；装完补 `POST_NOTIFICATIONS` 与 `MIUIOP 10020`（通知使用权由 02 负责，重装会清掉） |
| `02-authorize.ps1` | 通知使用权 + POST_NOTIFICATIONS | `cmd notification allow_listener`；记录 MIUI 自启动无法用 adb 授权的已知缺口 |
| `03-shizuku.ps1` | 拉起/重启 Shizuku server（E8） | `pidof shizuku_server`；设备上没有 starter 时把仓库里的 `device/start-shizuku.sh` 推上去；`-Restart` 走 kill + 重启；报告 `server/granted/userService` 与崩溃检查 |
| `04-drive.ps1` | E1/E7/E3+E4+E6 场景驱动 | `cmd notification post` + debug 广播（`POST_TEST`/`CANCEL_TEST`/`CANCEL_PACKAGE`）驱动通知；判定读 dumpsys；锁屏观察逐点采样 |
| `05-collect.ps1` | 采集与汇总 | logcat（应用 + 系统背屏/BAL/断电/冻结行）、dumpsys display/activities/window、截图尝试、应用侧 Shizuku transcript（有才拉），写成 `summary.md` |
| `06-overlay.ps1` | E9 覆盖窗口准入探针（票 #10） | 一条命令 加窗口 → 判定 → 撤窗口 → 归档；判定只认设备事实：`dumpsys window windows` 里探针窗口在不在背屏（displayId 运行时识别）+ 应用日志的失败原因 + 系统侧 WindowManager 行（`Not allow non-system app ... add system_window on rear display`）。失败分类：未授权 / 被系统拒绝 / 被背屏策略挡 |
| `07-lock-compare.ps1` | E10/E11 + Activity 对照（票 #11） | 上锁一次、双通道同轮观察：锁前把 Dashboard 送上背屏（Activity 通道）+ 尝试加覆盖窗口（E9 门槛），`log -t RearCue pc-lock-issued` 在设备时钟上打点后逐点采样（窗口在不在 / 背屏 state / 归属 / 应用最后一条日志）；「多久被收走」= 打点 → 第一条 `Dashboard detach` 的设备时钟差，采样只作旁证。判定分类：E10 PASS/CLEARED/BLOCKED-BY-E9、E11 PASS/LOST/BLOCKED-BY-E9、Activity REMOVED-IN/SURVIVED/NO-BASELINE |
| `08-wake-keepalive.ps1` | E12 唤醒保活探针（票 #16） | 同一 session 两段同协议对照：先「不开保活基线段」（上锁 + 逐点采样背屏/主屏 state），再「开保活段」（设备侧 sh 循环注入 `input -d 1 keyevent KEYCODE_WAKEUP`，间隔 = 参数 `-WakeIntervalMs`，连续跨过锁屏时刻）；判定只认设备事实：`dumpsys display` 逐点 state、注入循环的设备侧 tick 日志（每针含 `input` 退出码）+ `ps` 快照、系统侧 `PowerGroup` 行（锁真的断掉背屏 group + 唤醒键真的到达电源层）。判定分类：E12-PASS / E12-LOST（细分为 E12-IGNORED / E12-DELAYED-ONLY）/ E12-INJECT-FAILED / E12-NO-BASELINE。**不需要装应用、不需要 Shizuku**（循环以 shell uid 2000 跑，与 Shizuku shell 同身份） |
| `09-task-move.ps1` | E14 任务搬运事务探针（票 #18） | **需要解锁态的段全部放在上锁之前**（安全锁屏 adb 解不开、KEYCODE_POWER 之后手机留锁）：①E1 链路准备 Dashboard 任务 → ②对照组：解锁态跑同一事务（`service call activity_task <code> i32 <rootTaskId> i32 <displayId>`，搬离背屏再搬回 = 事务号/用法核实）→ ③任务摆到非背屏位置 → ④KEYCODE_POWER 上锁（设备时钟打点）→ ⑤锁屏稳态事务搬运 + 逐点采样（任务归属/背屏归属/背屏 state），含同锁屏态反向对照（搬去 display 0）与「背屏先唤醒」变体。判定只认 `dumpsys activity activities` 的任务归属 + 系统侧拒绝/收走行 + 应用日志，`service call` 返回值只归档不判定。判定分类：E14-PASS / E14-REJECTED / E14-TXN-BROKEN / E14-NO-TASK / E14-TASK-GONE / E14-NO-EFFECT / E14-RUN-INVALID（外部污染）。事务号默认 `-TxnCode 51`（本机 Android 16 实测号；MRSS 记录的 50 是静默 no-op），号不对自动用一次性任务扫候选区间 |
| `ExCommon.psm1` | 公共层 | 纯解析函数（display/activities/logcat → 结构化事实）+ adb 设备助手；Pester 单测覆盖解析层 |
| `device/start-shizuku.sh` | 设备侧 starter | 以 shell uid 拉起 shizuku_server（`/data/local/tmp/start-shizuku.sh`）；仓库自带一份，设备缺了就推过去 |
| `device/wake-keepalive.sh` | 设备侧保活注入循环（票 #16） | 每隔 `<sleep_s>` 秒向目标屏注入 `KEYCODE_WAKEUP` 并把 `input` 退出码追加进 tick 日志；PC 用 `nohup ... &` 起、用 stop 文件停；`ps -A -o PID,NAME,args` 里可见 |

## 为什么脚本里没有中文

Windows PowerShell 5.1 会把**无 BOM 的 .ps1 当 ANSI/GBK 解码**，源码里的中文会变成乱码或直接解析失败
（与票 #4 记录的 AIDL/manifest 同一类坑）。所以脚本源码只用 ASCII：需要匹配设备上的中文界面文字时，
用 `[regex]::Unescape('\u7EE7\u7EED\u5B89\u88C5')` 在运行时还原。设备上抓回来的中文是数据，不受影响。

## 产物（每次运行一个目录）

```
docs/poc-logs/<时间戳>-<任务>/
├── session.md              设备与运行元信息
├── 01-install.txt          安装结果、版本、MIUI 权限状态
├── 02-authorize.txt        监听授权与已知缺口
├── 03-shizuku.txt          E8 的 verdicts 与 server 生命周期
├── e1-drive.txt            E1 判定 + 当时 logcat + display #1 任务栈
├── e7-drive.txt            E7 判定 + 同上
├── e3-lock.txt             E3 逐点采样（owner / 背屏 state / 最后一条应用日志）
├── e9-overlay.txt          E9 判定 + 探针窗口事实 + 系统窗口日志（票 #10）
├── e10-e11-lock.txt        E10/E11/Activity 对照判定 + 采样 + 系统侧行（票 #11）
├── e10-samples.txt         锁屏逐点采样原始行（窗口/背屏 state/归属/应用最后一条日志）
├── e12-wake-keepalive.txt  E12 判定 + 两段采样 + 注入 tick + PowerGroup 证据（票 #16）
├── e12-samples-baseline.txt / e12-samples-keepalive.txt   两段逐点采样原始行（背屏+主屏 state/归属）
├── e12-wake-ticks.txt      注入循环的设备侧 tick 日志（每针 `input` 退出码）；e12-inject-errors.txt 为 stderr
├── e12-inject-ps.txt       注入循环运行中/停止后的 ps 快照
├── e12-power-group.txt     系统侧 PowerGroup 转换行（锁屏断电 / 唤醒键痕迹 / 外部唤醒）
├── e14-task-move.txt       E14 判定 + 对照组 + 各尝试记录 + 采样 + service call 原文（票 #18）
├── e14-samples.txt         锁屏逐点采样原始行（task=t<id>@d<display>|absent / 归属 / 背屏 state）
├── e14-service-calls.txt   每条 `service call` 原文（含设备时钟与调用前后归属；只作证据不作判定）
├── scenario-notes.md       场景设计取舍（如 E12 的循环起停方式），summary.md 会原样收录
├── logcat-rearcue.txt      采集时的完整应用日志
├── logcat-system-rear.txt  系统侧：ActivityStarterImpl / BAL / GreezeManager / subscreencenter
├── dumpsys-display.txt / dumpsys-activities.txt / dumpsys-window.txt
├── state.txt               pid、监听授权、活跃通知数、应用状态行
├── screenshots/            主屏截图（背屏截图会失败，失败原因也记录在案）
├── app-logs/poc-logs/      应用自己写的 Shizuku 命令 transcript（有才拉；目录可能不存在）
├── photo-checkpoints.md    三个拍照点的时刻、观察与目标文件名
└── summary.md              解析后的事实 + 各步 verdicts + 产物清单
```

## 解析层单测

```powershell
.\tools\ex\ex.ps1 -Task selftest
# 等价于：Invoke-Pester -Path tools\ex\tests -PassThru
```

`tools/ex/tests/fixtures/` 是设备真机的 dumpsys / logcat / uiautomator 片段（含锁屏、背屏占用、
MIUI USB 安装弹窗），单测断言解析结果，不依赖设备。

## 已知环境约束（脚本已处理或已记录）

- **MIUI USB 安装确认**：`adb install` 会弹「USB安装提示」，8 秒不点就按拒绝处理并报
  `INSTALL_FAILED_USER_RESTRICTED`；`01-install.ps1` 用 `uiautomator dump` 找到「继续安装」并点掉。
- **重装会清掉 MIUI 逐应用授权**：通知监听、`MIUIOP 10020`（锁屏显示）、`SYSTEM_ALERT_WINDOW`
  （覆盖窗口，票 #10）都要在装完后重新授予；`01-install.ps1` 装完自动 `appops set ... allow`
  并用 `appops get` 复核后写进产物——未授权会明确报 ERROR，不是静默失败。
- **MIUI 自启动没有 adb 接口**：只能用 `cmd notification disallow_listener/allow_listener` 强制重绑兜底
  （`Start-ExApp` 已内置）。
- **安全锁屏无法用 adb 解开**：锁屏态下投送会被 `ActivityStarterImpl: rearDisplay check locked -> deny`
  或后台启动限制（BAL）拦下，脚本会在运行前告警；`08-wake-keepalive.ps1` 的两段对照因此把
  「段间解锁复位」降级为「唤醒 + settle + 要求背屏 ON 起步」（两段同协议，对照仍成立），差异记在
  该 session 的 `scenario-notes.md`。
- **距离传感器被遮挡时 MIUI 会否决 `KEYCODE_WAKEUP`**（手机面朝下放置即触发，
  `BaseMiuiPhoneWindowManager: Going to sleep due to KEYCODE_WAKEUP ... proximity sensor too close`）：
  `08-wake-keepalive.ps1` 的唤醒兜底改用 KEYCODE_POWER 拨动，并给每次按电源键打 `pc-e12-power-*`
  设备时钟标记，把「自己按的」和「人按的」分开。
- **背屏截图不可用**：`screencap -d 1` 报 `Display Id '1' is not valid`，视觉验证靠人工拍照
  （见 `docs/poc-logs/manual-photos/`）。
- **调试旁路的边界**：`POST_TEST`/`CANCEL_TEST`/`CANCEL_PACKAGE`/`OVERLAY_ADD`/`OVERLAY_REMOVE`
  这些动作只在 debug 构建的 `DebugCommandReceiver` 里注册（`android.permission.DUMP` 保护，
  只有 `adb shell` 能发）；`OVERLAY_*` 的探针实现（`OverlayProbe`）也只在 debug source set 里，
  release 不带这条旁路；
  但「撤销某包通知」的能力本身只能挂在监听服务上（系统只给监听服务这个权限），因此
  `AppContainer`/`RearNotificationListener` 里常驻了一小段登记/注销代码——release 构建里没有任何调用方，
  这是为了让 PC 脚本能撤掉 `cmd notification post` 发的 shell 通知而接受的取舍。

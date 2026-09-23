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
.\tools\ex\ex.ps1 -Task shuid-overlay       # SH-UID 覆盖窗口探针（票 #17）：shell uid 加窗 vs 背屏窗口策略
.\tools\ex\ex.ps1 -Task shuid-overlay -WindowPackage system -HoldSeconds 8   # 窗口归属/持窗时长都是参数
.\tools\ex\ex.ps1 -Task autostart-probe      # 自启动探针（票 #27）：检测口径 + 设置页跳转入口 + 开关 diff（自动复原）
.\tools\ex\ex.ps1 -Task lock-survive        # E13 锁屏存活探针（票 #19）：保活跨锁 + Dashboard 存活判定
.\tools\ex\ex.ps1 -Task lock-survive -ObserveSeconds 90 -SampleSeconds 2    # 观察窗/采样间隔都是参数
.\tools\ex\ex.ps1 -Task photos              # E1 + 锁屏，跑到三个拍照点时暂停等人工拍照
.\tools\ex\ex.ps1 -Task collect             # 只采集 + 生成 summary.md
.\tools\ex\ex.ps1 -Task selftest            # 解析层 Pester 单测（不碰设备）
.\tools\ex\ex.ps1 -Task screen-off-chain -Serial 94250f9e  # #37: real-device OFF/ON red-green chain
.\tools\ex\ex.ps1 -Task screen-off-chain -OffOnly -Serial 94250f9e  # #37: idle rear firstcast OFF leg only
```

`screen-off-chain` 是 #37 的 5 秒实机判定：空态锁屏首投、已有 Dashboard 时新增另一应用图标、
锁屏且 Main Display ON 的监听对照。`verdict.md` 与不含通知正文的 `timeline.txt` 归档在
`docs/poc-logs/<时间戳>-issue37-screen-off-chain/`。`GREEN` 要求系统收录、监听回调、
正确 Icon Set、背屏 Dashboard 归属在期限内全部成立，且熄屏腿的 Main Display 始终 OFF。
系统收录但无回调为 `RED-NO-CALLBACK`，已有 Dashboard 留屏不算通过；前置、观察或清理
不可信为 `INVALID`。除全绿外命令都返回非零。5 秒从通知发出前的设备时钟标记开始，
包含一次 adb 往返；这是实验预算，不是产品时延保证。
系统收录以唯一 tag 出现在 `cmd notification list` 的实际 key 为准，`post` 命令回显仅作诊断。

起跑时必须已锁屏、背屏由 Native Rear Screen 持有、没有现存 Allowlist App 通知，且
RearCue 进程和监听器已运行。否则归档 `BLOCKED`，不改设备，避免破坏安全锁屏或真实通知。
完整模式的背屏可在原生界面下处于 ON，或处于锁屏闲置的 DOZE_SUSPEND。
`-OffOnly` 另允许原生背屏 OFF 起点，只测空态首投 OFF 腿；已有 Dashboard 的 update
基线需要主屏 ON，故在此模式标为 SKIPPED，锁屏主屏 ON 对照也 SKIPPED。
结果为 `GREEN-OFF` / `RED-OFF` / `INVALID`，不能当成完整三腿 GREEN。
实机发现唤亮 Main Display 做锁屏对照后，定向睡眠键不能可靠恢复原生背屏的 OFF 状态。
从 DOZE_SUSPEND/OFF 起跑时，清理后先恢复主屏 OFF，再最多等待 60 秒让原生背屏自然回落到
闲置态；若仍亮 ON，整轮 INVALID，不强行注入背屏睡眠键。熄屏判定同时核对 Main Display 的 DisplayInfo 和
`Display Power Controller` 的 `mScreenState=OFF`；主屏点亮或电源状态读不到均不判绿。
清理合成通知若遇 `KEYCODE_WAKEUP` 没有把主屏实际点亮，会在 3 秒确认仍 OFF 后只尝试一次
定向主屏 `KEYCODE_POWER`，并在末尾恢复主屏；通知或显示状态未恢复时整轮 INVALID。
撤销 shell 通知前会确认系统中只有本轮唯一 tag 的 shell key；主屏 ON 仅是清理前置，
不是广播已交付的证明。清理时先临时 unfreeze RearCue，发前台 debug 广播，并按 receiver
日志与实际 key 记录结果；若仍未交付，恢复临时通知权限后有界等待进程重连，再仅重试一次。
最终还会核对 `POST_NOTIFICATIONS` 与起始值一致；真实通知始终不撤销。
脚本按需临时授权 `POST_NOTIFICATIONS`，结束后恢复；唯一 shell tag 与 debug receiver
只用于合成通知及其清理，不发送或撤销飞书通知。运行期间请勿触碰设备。解析 fixture
及前置判定测试使用 `.\tools\ex\ex.ps1 -Task selftest`。

以下已解锁前置要求适用于其它旧实验任务：锁屏稳态下 HyperOS 会拒绝第三方把界面投到背屏（票 #6 结论），脚本会在
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
| `10-shuid-overlay.ps1` | SH-UID 覆盖窗口探针（票 #17） | 一条命令：javac + d8 构建设备侧探针 dex（失败即告警停跑）→ 推到 `/data/local/tmp/` → 对照组（同一份代码加窗到 display 0）→ 一次性加/撤 → 背屏加窗（问题本体）→ register 检查段（最后跑：app attach 握手会杀掉未注册进程）→ 设备事实判定。探针经 `app_process` 以 **shell uid 2000** 跑（与 Shizuku UserService 同 uid 身份，binder 路径未走）；窗口胶水按 窗口上下文 → display 上下文 → 无定向 三策略依次尝试，每段尝试都逐条进产物。判定只认 `dumpsys window windows`（探针窗口在不在目标屏、mDisplayId/ty=/归属 uid）+ 系统侧 WindowManager 行 + 探针自身输出。判定分类：SH-UID-WINDOW-PASS / SH-UID-WINDOW-BLOCKED（引系统侧原文）/ SH-UID-WINDOW-INCONCLUSIVE（对照组也失败）/ SH-UID-RUN-INVALID（外部污染/探针自身故障）。**不装 APK、不按 KEYCODE_POWER**（手机保持解锁；`stay_on_while_plugged_in` 跑前设置、跑后恢复并记录） |
| `11-lock-survive.ps1` | E13 锁屏存活探针（票 #19） | 一条命令：Activity 基线上屏（shell 通知 + debug `POST_TEST` → Dashboard，与 `04-drive` 同法）→ E12 的注入循环**先起、跨过锁屏**（`device/wake-keepalive.sh`，间隔 = `-WakeIntervalMs`）→ `pc-e13-lock-issued` 打点 + KEYCODE_POWER 上锁（每次注入——电源键与唤醒键 alike——都有 `date` 设备时钟戳）→ 逐点采样（背屏/主屏 state + 背屏归属）→ 停循环 + 判定。判定只认设备事实：应用 logcat 的设备时钟链（打点 → `Dashboard detach` → `Dashboard attach`，`Get-ExSurviveFacts` 解析）为主、`dumpsys` 采样为佐证；注入循环的 tick 日志 + `ps` 证明保活真的在跑；`PowerGroup` 行证明锁真的断掉背屏 group；外部污染（指纹唤醒/人按电源，`Select-ExExternalPollution` 按本脚本注入时刻 ±3s 归因，另有唤醒键翻转豁免：`power_button` 熄屏落在 `WAKE_REASON_WAKE_KEY` 事件后 200ms 内 = 唤醒键自身副作用）该轮作废并单列 `erratum.md`。判定分类：E13-PASS / E13-CLEARED（含被收走时刻；`e13-class` = E13-RETURNED（含回归毫秒差）/ E13-NO-RETURN）/ E13-NO-BASELINE / E13-NO-LOCK（锁没真到背屏 group——PASS 永远不可达）/ E13-INJECT-FAILED / E13-RUN-INVALID。前提：debug APK 已装（安装走 `-Task install`/`-Task drive`，本任务不重装）、手机起跑时解锁（上滑解锁，`wm dismiss-keyguard` 在本机无效，票 #7）。跑前把 main + system log buffer 扩到 32M（防注入洪泛冲掉打点行）；缩回放在 `ex.ps1` 的 05-collect **之后**（`logcat -G` 缩缓冲会截断刚采集的证据，20260922-224734 实测）。`-AppKeepAlive`（票 #21 回归模式）：保活换成**应用自己的 Wake Keep-alive**（`WakeKeepAlive.kt` 经 Shizuku 注入），判定链不变，tick 证据换成应用的 `wake-keep-alive start|ok|stop|fail` 日志行；退出（空集）后另有 `e13-exit-residual.txt` 证明无残留循环 |
| `12-wake-cost.ps1` | 票 #21 代价实测 | **单次上锁盖两条腿**（本机是安全锁，中程解不开；两腿同键guard/同充电器也更公平）：keep 腿先（指示在屏 + 应用保活），撤通知 → idle 腿（空集、原生界面）。各 `-DutySeconds`（默认 300s），前后读 `dumpsys battery` + `batterystats` 功耗段。发热 = 电池温度差（**实测**，keep−idle 差值就是保活的边际代价）；耗电 = `Charge counter` 差（**断电才实测**；插着电时改报 Android 模型**估算值**并全程标注）。`cost-recommendation` 行 = ADR 0003 默认间隔的定案材料。判定分类：COST-MEASURED / COST-ESTIMATED-DRAIN / COST-INVALID（腿状态或 keyguard 没守住） |
| `14-kill-recover.ps1` | 票 #21 进程重建恢复 | 基线上屏 + 应用保活在跑 → `am force-stop` 真杀进程 → **真实世界复活信号**：再发一条白名单通知（系统要投递就得重绑死掉的监听服务 = 拉活进程 → Icon Set 按活跃通知重同步 → 重投 → 保活重启）。判定只认新进程的 `wake-keep-alive` 标记（按 kill 戳分前后）+ `pidof` 每采样点 + 背屏归属。判定分类：REBUILD-RECOVERED / REBUILD-NO-LISTENER / REBUILD-NO-REPROJECT / REBUILD-NO-KEEPALIVE / REBUILD-NO-BASELINE / REBUILD-RUN-INVALID |
| `15-lock-firstcast.ps1` | 票 #22 锁屏首投 | 干净起点（撤光通知、背屏无 Dashboard）→ 上锁（`pc-firstcast-lock-issued` 打点 + 设备时钟戳 + PowerGroup group-1 断屏门）→ **一条白名单通知**（E1 驱动法）→ 逐点采样（背屏归属 + `task=t<id>@d<display>`）→ 撤通知看退出交还。判定只认设备事实：owner 采样 + 应用 `task-move word=...` 标记（`Get-ExTaskMoveAppFacts`）+ 系统拒绝行；`service call` 返回只归档不判定（E14 口径）。判定分类：FIRSTCAST-PASS（附 route：事务 / 应用内窗口）/ FIRSTCAST-NO-TASK / FIRSTCAST-REJECTED（E14 语义：有系统拒绝行）/ FIRSTCAST-TXN-BROKEN（服务端回执文本，不看退出码）/ FIRSTCAST-NO-EFFECT（无拒绝行仍未上屏）/ FIRSTCAST-NO-EVIDENCE（链没跑起来，未测）/ FIRSTCAST-NO-LOCK / FIRSTCAST-RUN-INVALID；另有 `firstcast-exit : native-returned`（清通知后交还原生 + `task-move hand-back` 把搬走的 root task 归还主屏） |
| `16-autostart-probe.ps1` | 自启动/监听健康探针（票 #27） | 一条命令：①检测面原文（`appops get` 全量 + 单 op 10008/10053 + named op `AUTO_START` + LBE provider `content query`）→ ②跳转候选（action `miui.intent.action.OP_AUTO_START` / 显式 component / 负对照不存在类，判定读 `topResumedActivity` + `auto_start_list` 页面事实，`am start` 回执不判）→ ③应用行开关 toggle diff（MIUIOP 模式随开关翻转 = 检测口径成立）→ ④**开关复原校验**（UI `checked` 态 + op 模式都回原样才 `RESTORED`）。判定分类：DETECT-TRACKS-SWITCH / DETECT-NO-TRACK / DETECT-SWITCH-UNFLIPPED / DETECT-SWITCH-UNREACHABLE；JUMP-PASS / JUMP-NO-TASK / JUMP-WRONG-PAGE / JUMP-NO-EFFECT；RESTORED / RESTORE-FAILED。找行 = 标题文本（`-Label`，默认应用 label）+ `id/sliding_button` 的 `checked`；行会随开关在「允许/禁止」两段间重排，扫描固定从表头走起。**需要手机解锁**（设置页 UI 自动化）；`-NoToggle` 为只读模式 |
| `ExCommon.psm1` | 公共层 | 纯解析函数（display/activities/logcat → 结构化事实）+ adb 设备助手；Pester 单测覆盖解析层 |
| `device/start-shizuku.sh` | 设备侧 starter | 以 shell uid 拉起 shizuku_server（`/data/local/tmp/start-shizuku.sh`）；仓库自带一份，设备缺了就推过去 |
| `device/wake-keepalive.sh` | 设备侧保活注入循环（票 #16） | 每隔 `<sleep_s>` 秒向目标屏注入 `KEYCODE_WAKEUP` 并把 `input` 退出码追加进 tick 日志；PC 用 `nohup ... &` 起、用 stop 文件停；`ps -A -o PID,NAME,args` 里可见 |
| `device/shuid-overlay/ShuidOverlayProbe.java` | 设备侧 shell uid 加窗探针（票 #17） | 以 `app_process` 跑（uid 2000），向指定 displayId 加最小 `TYPE_APPLICATION_OVERLAY` 窗口并逐条打印 `probe: ...` 事实行；`10-shuid-overlay.ps1` 每次运行用 javac + d8 现场编译推送 |

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
├── shuid-overlay.txt       SH-UID 判定 + 探针输出 + dumpsys 窗口段 + 系统侧行（票 #17）
├── shuid-probe-*.txt       各阶段探针进程自身输出（control / remove / rear / registercheck）
├── dumpsys-window-*-held.txt / -after.txt   各阶段持窗中/撤窗后的 `dumpsys window windows` 原文
├── logcat-shuid-*-system.txt / -all.txt     各阶段系统侧行（过滤版 / 全量原文）
├── shuid-build.txt         javac + d8 构建输出与工具路径
├── shuid-screen-settings.txt   跑前/跑后的 stay_on_while_plugged_in 与 wakefulness 记录
├── e13-lock-survive.txt    E13 判定 + 存活事件链 + 采样 + tick + PowerGroup 证据（票 #19）
├── e13-samples.txt         锁屏逐点采样原始行（背屏/主屏 state + 背屏归属；E12 同款 wire）
├── e13-wake-ticks.txt / e13-inject-errors.txt / e13-inject-ps.txt   同 E12 的注入循环证据
├── e13-exit-residual.txt   票 #21 生命周期取证：空集退出（ExitDashboard→WakeKeepAlive.stop）后无残留（停止行/心跳/唤醒痕迹计数）
├── wake-cost.txt           票 #21 代价判定 + 两腿发热/耗电 + batterystats 估算段（估算值全程标注）
├── wake-cost-battery.txt   两腿前后 `dumpsys battery` 原文（实测口径的来源）
├── wake-cost-samples.txt   两腿逐点采样原始行（腿状态纪律：keep 腿必须全程 rear=ON owner=dashboard）
├── rebuild-recover.txt     票 #21 进程重建恢复判定 + kill 前后保活标记分割 + pid 链
├── rebuild-samples.txt     恢复观察窗逐点采样原始行（rear/归属/pid，pid 变化即重建证据）
├── firstcast.txt           票 #22 锁屏首投判定 + task-move 应用标记 + 系统拒绝行计数 + 退出交还
├── firstcast-samples.txt   首投观察窗逐点采样原始行（rear/归属 + task=t<id>@d<display>|absent）
├── autostart-probe.txt     票 #27 判定（detect/restore）+ toggle diff + 各跳转候选 verdict
├── autostart-surfaces.txt  检测面原文（appops 全量/单 op/named op + content query 结果）
├── autostart-jump.txt      跳转候选逐个：am 原文 + resumed component + 页面事实 + verdict
├── ui-toggle-*.xml         开关前/后/复原后的 uiautomator dump 原文
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

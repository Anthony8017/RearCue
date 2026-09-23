# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。

## Language

**Main Display（主屏）**:
手机正面的主屏幕（displayId 0），与 Rear Display 相对。本项目的主屏 UI 是调试/引导页。
_Avoid_: 前屏、正面屏、大屏

**Rear Display（背屏）**:
手机背部的物理副屏，系统侧 displayId 为 1。本项目一切"投送到背屏"均指此屏。
_Avoid_: 副屏、后屏、second screen

**Native Rear Screen（原生背屏）**:
`com.xiaomi.subscreencenter` 运管的原生背屏界面（SubScreenLauncher、AOD 等）。
_Avoid_: 小米背屏、subscreen center 混称

**Takeover（抢回）**:
原生背屏（常因 AOD/锁屏，reason="aod"）重新占据背屏显示、顶掉 Dashboard 的行为。
_Avoid_: 覆盖、抢占

**Dashboard**:
本项目投送到背屏的自定义界面：纯黑背景 + 时间 + 图标集。
_Avoid_: 背屏 UI、AOD

**Active Notification（活动通知）**:
已发出且尚未被移除的状态栏通知，以 NotificationListenerService 视角为准；不代表 App 内部未读数。
_Avoid_: 未读消息、unread count

**Allowlist App（白名单应用）**:
允许触发背屏图标的应用。POC 期：微信、QQ、飞书（com.ss.android.lark）、本应用、com.android.shell（自动化发通知用）。
增删的粒度是**应用**：没有「追踪中的通知」这种对象——单条通知是 Active Notification（系统事实），不可手动增删。
_Avoid_: 追踪中的通知、追踪列表

**Icon Set（图标集）**:
Dashboard 上显示的图标集合——每个存在 Active Notification 的 Allowlist App 恰好一枚图标，不带数字角标。
_Avoid_: 未读角标、通知计数

**Degrade（降级）**:
投送通道不可用时的状态：通知监听与图标集照常维护，仅停止投送 Dashboard；通道恢复后自动重投。
_Avoid_: 离线模式

**Projection Channel（投送通道）**:
把 Dashboard 送进背屏的能力，POC 期以「运行时识别到背屏」为可用判据——应用内投送不需要
Shizuku（票 #4 的 E1 实测），Shizuku 只是兜底通道，掉线不改变能否投送。
_Avoid_: Shizuku 连接、投屏权限

**Overlay Window（覆盖窗口）**:
`TYPE_APPLICATION_OVERLAY` 的系统窗口，spec 0002 设想的第二条投送通道的载体（行文简称「覆盖窗口通道」，
即以此窗口为载体的候选投送通道，已否决，不作独立术语）。
E9 实测（票 #10）：HyperOS 的背屏窗口策略只放行系统应用，非系统应用的覆盖窗口上不了背屏
（主屏不受影响）；该通道在本机不成立，主路径仍是 Activity 投送。
E10/E11（票 #11）：因前置失败，锁屏可见/保活在本机不可测（E10/E11-BLOCKED-BY-E9）——
窗口在未锁屏时就加不上背屏，与锁屏无关；同轮 Activity 通道锁屏后 1.3–1.4s 被系统收走。
票 #12 go/no-go 定为 **no-go**：不落第二条通道，主路径维持 Activity 投送；重开条件见
`docs/poc-findings.md` 的「票 #12 验收」。
_Avoid_: 悬浮窗（MIUI 语境的「悬浮窗」开关不是这道背屏门的钥匙）、用「覆盖」单字指 Takeover

**Wake Keep-alive（唤醒保活）**:
让背屏在指示在屏期间保持点亮（不离开 ON、不进 DOZE）的手段总称。spec 0001 story 20 只认可非轮询手段；
周期注入唤醒键（MRSS 式）原被 spec 0002 列为 out of scope，覆盖窗口通道 no-go 后重新进入候选，
E12/E13 实测通过后经 ADR 0003 定案采用（story 20 的非轮询原则就此废止）。
E12 实测语义（票 #16）：以 shell uid 周期注入**定向背屏**的唤醒键（`input -d 1 keyevent KEYCODE_WAKEUP`），
注入期间锁屏后背屏可保持 ON——实测 500ms/5000ms 间隔在 60s 窗内全程 ON，30000ms 间隔守不住
（离开 ON 时刻与不注入一致，其后被针拉回 ON）；注入是显示定向的，三轮干净轮的保活段主屏全程 OFF
（采集轮的 KEYCODE_POWER 复位段曾点亮主屏，非干净观察；「绝对不点亮主屏」未定论）。指手段本身时用本词，
「保活住了」要带上注入间隔与观察窗的实测边界。
_Avoid_: 与「保活轮询」「KEEP_SCREEN_ON」混称

**Lock-screen First Cast（锁屏首投）**:
锁屏稳态（无 Active Notification、背屏无 Dashboard）下来一条白名单通知时，把 Dashboard 送上背屏的那次投送。
它不是「重投」：`am start --display` 路径在锁屏下被 ActivityStarter 的 `rearDisplay check locked -> deny` 硬拒
（票 #6/E3、票 #18/E14 实测每次如此），走的是 E14 验证过的**任务搬运事务**（`service call activity_task 51`
= moveRootTaskToDisplay；MRSS 记的 50 在本构建是静默 no-op），把**带 Dashboard 的 root task** 搬上背屏；
通知清空后退出要把搬走的 root task 归还主屏（`task-move hand-back`），背屏交还原生界面。
失败词与 E14 词表同口径：NO-TASK（没有带 Dashboard 的任务，**不搬**空任务）/ REJECTED（有系统拒绝行）/
TXN-BROKEN（服务端回执文本说通道坏了，**不看进程退出码**）/ NO-EFFECT（无拒绝行仍未上屏）。
_Avoid_: 「锁屏投送」泛指（那是锁窗重投，另一件事）、把 `am start -n` 建任务步说成 `am start --display`

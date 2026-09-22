# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。

## Language

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
允许触发背屏图标的应用。POC 期：微信、QQ、本应用、com.android.shell（自动化发通知用）。

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

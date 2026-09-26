# 合并后实机冒烟（main = PR #49 Allowlist 产品化 + a4210ed 投送会话重构）

- 设备：小米 17 Pro（94250f9e，USB）；构建：main `5a565ed`（merge 后，含 ProjectionSession/ConfirmPolicy/DashboardPresence 与 Allowlist 增删 UI；merge 后 JVM 全量 166 例 0 失败）
- 时间：2026-09-26 14:39–14:58（UTC+8）；口径：`adb logcat -s RearCue` + `dumpsys activity activities`（任务栈回读，CONTEXT.md 判读口径）+ 调试广播 + uiautomator dump
- 缘起：下午 97f902d 冒烟验的是 PR #49 分支单独版，a4210ed 的 rear 大改（RearDisplayBackend ±293 行）与 Allowlist 产品化从未同机共跑。

## 判定：核心链路全过；1 项名单持久化异常（未能定责，待复验）

| 冒烟项 | 判定 | 证据 |
|---|---|---|
| 升级安装 → 种子在册 | ✅ | `install -r` 同签名升级；冷启 `allowlist store-load size=5`（14:39:20） |
| 主页/设置页结构（#47） | ✅ | 横幅/Icon Set 卡/状态三行/齿轮/开发者选项；设置页在册枚举+移除+添加 |
| 通知→上屏（链 A） | ✅ | 对账 `posted shell → LaunchDashboard(1)` → `project 应用内投送已发出` → `投送确认：RearDashboardActivity 已创建 displayId=1`（14:42:26，新 Presence 确认链路） |
| 撤→摘除（链 B 前半） | ✅ | CANCEL_PACKAGE 广播撤 shell → `UpdateIconSet(1) [shell,lark]->[lark]`（14:43:29） |
| 移除→下屏→背屏还原生 | ✅ | 三次 `allowlist remove → ExitDashboard`；Display #1 回 `SubScreenLauncher`（14:44:56 / 14:52:54 / 14:58:12） |
| 重加→在册通知自动重投 | ✅ | App Picker 加回飞书 `add size=5 → LaunchDashboard(1)` → 投送确认（14:45:45） |
| 熄屏不冻结（ADR 0004） | ✅（恢复设置后） | `MILLET_NO_RESTRICT_APP` 恢复含 rearcue 后，SCREEN_OFF 后 8s 内 `posted com.android.shell` 回调达（14:52:30），无 FZ |
| **锁屏首投（a4210ed 主验目标）** | ✅ | 真稳态（iconSet 空 + keyguard）发通知 → `LaunchDashboard` → 应用内投送未获确认 → **锁屏稳态路由任务搬送** `sh [service call activity_task 51 i32 13467 i32 1] exit=0` → `RearDashboardActivity onCreate display=1` → 任务栈回读 `Display #1 Task#13467 visible=true topResumed=RearDashboardActivity` → **`task-move word=OK onDisplay=true`**（14:53:13） |
| 判读纪律（「已发出≠已上屏」） | ✅ | 搬送前回读诚实显示 `topResumed=SubScreenLauncher`（未谎报在屏），搬送后回读才 OK |
| 防烧屏漂移 | ✅ | rear-safe-place drift (8,8)/(−8,8)/(8,−8) 轮换正常 |
| 重启名单一致（链 D） | ⚠️ **异常** | 操作序列（remove 飞书→add 飞书→remove 飞书，日志逐条确认 size 4→5→4）+ 进程两度被系统杀死重启后，`store-load size=3`（14:53:52）——**微信（com.tencent.mm）缺失**；`run-as cat` DataStore 文件实锤仅 QQ/RearCue/Shell。全无 `remove com.tencent.mm` 日志证据（logcat 缓冲滚动 + 多轮 `-c` 清屏），不能定责为代码缺陷；代码走查 `AllowlistStore.edit` 原子、调用点 `applyAllowlist` 同步+`launch save`，未发现产出「无微信」集合的路径；亦不能排除 14:49–14:51 解锁窗口机主手动操作。**待复验**：解锁后 add 微信 → force-stop → 冷启读回应稳定 |

## 事故与变通（如实记）

1. **force-stop 后监听拒绑**（已知 MIUI 行为，autostart DENIED 下 requestRebind 无回调）：两次以授权 toggle 恢复（`settings put secure enabled_notification_listeners` 去掉再 `cmd notification allow_listener` 加回），`listener connected` 即达。
2. **MILLET_NO_RESTRICT_APP 缺 com.rearcue.poc**：下午异机签名卸载重装清掉了该值（ADR 0004 预警在案），导致首轮锁屏通知回调不达（14:47 轮 FIRSTCAST 症状）。按 `issue39-millet-original.txt` 存档值恢复后即正常。**此设置是设备态，不是代码，装机/重装后要复查**。
3. **Shizuku granted=false**：锁屏首投任务搬送的先决（`shell.available`）。经 Shizuku app「已授权的应用」开关 UI 授权（`UserConsentManager: onPermissionsChanged: 10350`）→ `refresh server=true granted=true`；UserService 由 `run()` 惰性 bind，首投轮直接可用。
4. **主屏两度锁屏**（机主使用中）：`wm dismiss-keyguard` 前两次有效；14:55 后无法以 adb 解除，链 D 补验（add 微信后重启复验）未做成，留待下轮。
5. `screencap -d 1` 失败（Display Id '1' is not valid，锁屏下背屏虚拟显示不允许截屏）：判定按 CONTEXT.md 口径本就只认任务栈回读，不影响结论。
6. 机主同期用机（美团/微信切换）：每步 UI 操作前核对 `topResumedActivity`，本轮点击无误落。

## 设备收尾态

- 名单 **3 枚**（QQ/RearCue/Shell）：飞书为本轮链路验证主动移除；**微信缺失原因未定**（见异常行）。canonical 恢复（加回微信+飞书）待机主解锁后补。
- shell 测试通知已全撤；背屏还原生（SubScreenLauncher）；监听已授权（进程重启后需 toggle/自愈重连）。
- 本轮修复的设备态：MILLET_NO_RESTRICT_APP 含 rearcue；Shizuku 对 RearCue 已授权。输入法未动。

## 下一步

1. 机主解锁后：设置页加回微信+飞书 → force-stop → 冷启复验名单稳定（链 D 补验，兼恢复 canonical 5 枚）。
2. 若复验再丢：开票查 AllowlistStore 持久化（怀疑方向：进程死亡时 `scope.launch save` 丢失/竞态，或 DataStore 写入与进程回收的窗口）。

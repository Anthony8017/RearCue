# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。GPL-3.0 开源（见 [LICENSE](LICENSE)）。

- 术语表：[CONTEXT.md](CONTEXT.md)
- 决策记录：[docs/adr/](docs/adr/)
- POC 规格：[docs/specs/0001-rear-display-notification-poc.md](docs/specs/0001-rear-display-notification-poc.md)
- 设备实验记录：[docs/poc-findings.md](docs/poc-findings.md)

## 模块布局（对齐 spec 0001 的模块边界）

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | `DashboardCore` 纯 Kotlin 状态机——事件→效果，并对外暴露 `iconSet` 只读视图 | ✅ 票 #2/#3 |
| `:notification` | `NotificationRepository` 纯 Kotlin Active Notification 汇聚（按 notification key 去重、连接时全量对账） | ✅ 票 #3 |
| `:rear` | `RearDisplayBackend` 接口 + HyperOS 实现（背屏识别、投送、更新、退出）、`RearDashboardActivity`（纯黑 + 时间 + Icon Set）、`RearDashboardHost`（进程内上/下屏句柄）、Shizuku UserService | ✅ 票 #4/#5 |
| `:app`（Android 壳 `com.rearcue.poc`） | `RearNotificationListener`（监听胶水）、`AppContainer`（进程接线 + 效果→动作搬运）、主屏调试页（Icon Set 可视 + 授权入口 + 测试通知 + 投送/退出旁路） | ✅ 票 #3/#4/#5 |
| `rear` 韧性（锁屏/AOD/保活/Degrade） | WakeController、Takeover 监听 | 预留（票 #6） |

JVM 单测 seam 三个：`DashboardCore`（事件→效果，含 Icon Set 决策）、`NotificationRepository`（监听回调→变更事件）、`:rear` 的纯 Kotlin 部分（背屏 flag 判定、投送命令、上屏校验）。都不含 Android 框架依赖；Android 层只做「系统信号 → 事件 → 效果/状态」的搬运，不做决策。

## 构建

minSdk = targetSdk = 36（Android 16），Kotlin + Jetpack Compose，Gradle Kotlin DSL + version catalog（`gradle/libs.versions.toml`）。

```powershell
$env:JAVA_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew test          # :core DashboardCore + :notification NotificationRepository JVM 单测
.\gradlew :app:assembleDebug
```

工具链位于 `C:\Users\13691\AppData\Local\RearCue-tools\`（JDK 17 / Android SDK / Gradle 8.14.3，不入库）。

## 手工验收（票 #3 链路：通知 → Icon Set）

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell cmd notification allow_listener com.rearcue.poc/.notify.RearNotificationListener
adb shell pm grant com.rearcue.poc android.permission.POST_NOTIFICATIONS
adb logcat -s RearCue            # 观测 icon-set 变化
```

主屏调试页实时显示 Icon Set（Allowlist App 的应用图标），并提供「打开通知使用权设置」「发测试通知」「清除测试通知」「投送到背屏」「退出背屏 Dashboard」入口（后两个是绕过自动流转的调试旁路）。PC 侧可用 `adb shell cmd notification post -t RearCue <tag> '<text>'` 模拟 `com.android.shell` 通知（注意：同 tag 再发一次是**更新**既有通知，不产生新的 Post 事件）。逐条验收步骤见 [docs/poc-findings.md](docs/poc-findings.md) 的「票 #3 / #4 / #5 验收」。

## 背屏投送（票 #4）

投送主路径是**应用内** `ActivityOptions.setLaunchDisplayId(<背屏 displayId>)`：不需要 shell，背屏准入由 manifest 的 `miui.rear.policy=1` 决定（E2 实测：缺它必被系统 aborted）。Shizuku（shell uid）保留为兜底通道。

背屏 displayId 不硬编码：按「非默认 + `FLAG_PRESENTATION` + `FLAG_OWN_DISPLAY_GROUP`」在运行时识别（本机实测掩码 16515 / 16779 ⇒ displayId=1）。

```powershell
adb shell dumpsys activity activities | Select-String 'Display #1'   # 复核是否上屏
```

Shizuku 通道只要 server 在线即可用（客户端 provider 的权限必须是 shell uid 持有的 `android.permission.INTERACT_ACROSS_USERS_FULL`，写成 `moe.shizuku.manager.permission.API_V23` 会让 server 回调被拒、`pingBinder` 恒 false——票 #8 实测）；首次使用需在 Shizuku 授权框里放行本应用，未授权时投送自动走应用内路径，不阻塞。

## 自动上/下屏（票 #5）

通知事件驱动，不需要点按钮：首条 Allowlist 通知 → `DashboardCore` 出 `LaunchDashboard` → 上屏；Icon Set 变化 → `UpdateIconSet`（只广播给背屏界面，不重新投送）；末条通知消失 → `ExitDashboard` → `RearDashboardHost` 结束背屏界面，原生背屏恢复。

- **下屏不用 `am force-stop`**：监听服务与背屏 Dashboard 同进程，force-stop 会把监听一起杀掉，之后就没人能自动上屏了。
- **投送通道**判据是「运行时识别到背屏」而不是 Shizuku 连接（应用内投送不需要 Shizuku）；术语见 [CONTEXT.md](CONTEXT.md)。
- 已知真实使用阻断（归票 #6）：HyperOS 会把后台应用冻结（`GreezeManager`，冻结期间通知事件延迟到解冻）；进程被杀后 MIUI 需要「自启动」白名单才肯重绑通知监听。验收细节见 [docs/poc-findings.md](docs/poc-findings.md)「票 #5 验收」。

# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。GPL-3.0 开源（见 [LICENSE](LICENSE)）。

- 术语表：[CONTEXT.md](CONTEXT.md)
- 决策记录：[docs/adr/](docs/adr/)
- POC 规格：[docs/specs/0001-rear-display-notification-poc.md](docs/specs/0001-rear-display-notification-poc.md)
- 设备实验记录：[docs/poc-findings.md](docs/poc-findings.md)

## 模块布局（对齐 spec 0001 的模块边界）

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | `DashboardCore` 纯 Kotlin 状态机——事件→效果 | ✅ 票 #2 |
| `:notification` | `NotificationRepository` 纯 Kotlin Active Notification 汇聚（按 notification key 去重、连接时全量对账） | ✅ 票 #3 |
| `:app`（Android 壳 `com.rearcue.poc`） | `RearNotificationListener`（监听胶水）、`AppContainer`（进程接线）、主屏调试页（Icon Set 可视 + 授权入口 + 测试通知） | ✅ 票 #3 |
| `rear` | RearDisplayBackend 接口 + HyperOS/Shizuku 实现（投送、TaskController、WakeController、Takeover 监听）；Shizuku-API 依赖随本模块引入 | 预留（票 #4） |
| 背屏 Dashboard（`:app` 内 `com.rearcue.poc.ui`） | RearDashboardActivity（纯黑 + 时间 + Icon Set） | 预留（票 #4/#5） |

JVM 单测 seam 两个：`DashboardCore`（事件→效果）与 `NotificationRepository`（监听回调→变更事件），都不含 Android 框架依赖。Android 层只做胶水。

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

主屏调试页实时显示 Icon Set（Allowlist App 的应用图标），并提供「打开通知使用权设置」「发测试通知」「清除测试通知」三个入口。PC 侧可用 `adb shell cmd notification post -t RearCue -n <tag> <text>` 模拟 `com.android.shell` 通知。逐条验收步骤见 [docs/poc-findings.md](docs/poc-findings.md) 的「票 #3 验收」。

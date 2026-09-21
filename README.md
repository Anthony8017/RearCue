# RearCue

在小米 17 Pro（HyperOS 3）上，不 Root、经 Shizuku 把通知指示显示到背屏的 Android 应用。GPL-3.0 开源（见 [LICENSE](LICENSE)）。

- 术语表：[CONTEXT.md](CONTEXT.md)
- 决策记录：[docs/adr/](docs/adr/)
- POC 规格：[docs/specs/0001-rear-display-notification-poc.md](docs/specs/0001-rear-display-notification-poc.md)
- 设备实验记录：[docs/poc-findings.md](docs/poc-findings.md)

## 模块布局（对齐 spec 0001 的模块边界）

| 模块 | 职责 | 状态 |
|---|---|---|
| `:core` | `DashboardCore` 纯 Kotlin 状态机——唯一 JVM 测试 seam（事件→效果） | ✅ 本仓库骨架 |
| `:app` | Android 壳（`com.rearcue.poc`），主屏空页；Compose | ✅ 本仓库骨架 |
| `notification` | NotificationListenerService → NotificationRepository，产出 Active Notification 事件流 | 预留（票 #3） |
| `rear` | RearDisplayBackend 接口 + HyperOS/Shizuku 实现（投送、TaskController、WakeController、Takeover 监听）；Shizuku-API 依赖随本模块引入 | 预留（票 #4） |
| `ui`（`:app` 内包 `com.rearcue.poc.ui`） | RearDashboardActivity + 主屏调试页 | 预留（票 #4/#5） |

`DashboardCore` 无任何 Android 框架依赖（独立 `:core` Kotlin JVM 模块），Android 层只做事件→效果的胶水。

## 构建

minSdk = targetSdk = 36（Android 16），Kotlin + Jetpack Compose，Gradle Kotlin DSL + version catalog（`gradle/libs.versions.toml`）。

```powershell
$env:JAVA_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\android-sdk"
.\gradlew test          # :core DashboardCore JVM 单测
.\gradlew :app:assembleDebug
```

工具链位于 `C:\Users\13691\AppData\Local\RearCue-tools\`（JDK 17 / Android SDK / Gradle 8.14.3，不入库）。

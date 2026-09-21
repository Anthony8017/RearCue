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
| `:rear` | `RearDisplayBackend` 接口 + HyperOS 实现（背屏识别、投送、退出）、`RearDashboardActivity`（纯黑 + 时间 + Icon Set）、Shizuku UserService | ✅ 票 #4 |
| `:app`（Android 壳 `com.rearcue.poc`） | `RearNotificationListener`（监听胶水）、`AppContainer`（进程接线）、主屏调试页（Icon Set 可视 + 授权入口 + 测试通知 + 投送按钮） | ✅ 票 #3/#4 |
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

主屏调试页实时显示 Icon Set（Allowlist App 的应用图标），并提供「打开通知使用权设置」「发测试通知」「清除测试通知」「投送到背屏」「退出背屏 Dashboard」入口。PC 侧可用 `adb shell cmd notification post -t RearCue -n <tag> <text>` 模拟 `com.android.shell` 通知。逐条验收步骤见 [docs/poc-findings.md](docs/poc-findings.md) 的「票 #3 验收」与「票 #4 验收」。

## 背屏投送（票 #4）

投送主路径是**应用内** `ActivityOptions.setLaunchDisplayId(<背屏 displayId>)`：不需要 shell，背屏准入由 manifest 的 `miui.rear.policy=1` 决定（E2 实测：缺它必被系统 aborted）。Shizuku（shell uid）保留为兜底通道。

背屏 displayId 不硬编码：按「非默认 + `FLAG_PRESENTATION` + `FLAG_OWN_DISPLAY_GROUP`」在运行时识别（本机实测掩码 16515 / 16779 ⇒ displayId=1）。

```powershell
adb shell dumpsys activity activities | Select-String 'Display #1'   # 复核是否上屏
```

Shizuku 通道需要**从 Shizuku app 内正常启动**的 server（手工以裸 shell uid 拉起的 server 没有 `moe.shizuku.manager.permission.API_V23`，无法回调应用 provider，`pingBinder` 恒 false）；未授权时投送自动走应用内路径，不阻塞。

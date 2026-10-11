# 主屏底部贴合手势条安全边界：部署记录

日期：2026-10-12（Asia/Shanghai）。

- PR [#348](https://github.com/Anthony8017/RearCue/pull/348) 已合并，主分支提交 `ea86dfeb4d734eb2e1a09e182ff71c7aa2bbc960`。
- 合并结果运行 `gradlew test :app:assembleDebug` 通过，133 个 Gradle 任务成功；从合并提交重建 `:app:assembleDebug` 成功。
- 小米 17 Pro，无线 ADB `192.168.50.2:34711`。`adb install -r -t` 成功，`versionName=0.1.0`，`lastUpdateTime=2026-10-12 00:03`。
- 本地与设备的 `classes3.dex` SHA-256 均为 `30963A404675FAA962379AF495BF48492F9A500224DD0655F41FA127CA93FC54`，并包含 `contentBottomFlush`。整包大小/哈希因 Android 安装期 APK 变换不同，不作整包等同判据。
- 补跑通知授权后监听组件已启用，`POST_NOTIFICATIONS: granted=true`；`MIUIOP(10008/10020/10021)`、`MIUIOP(10053)` 与 `SYSTEM_ALERT_WINDOW` 均为 `allow`。
- `RearCueBridge` 计划任务保持 `Running`，本次部署未停止或重启生产桥。
- 安装后复拍时设备处于安全锁屏，未尝试绕过 PIN；底部边界按 `safeDrawing` 手势条下沿执行，额外 `24dp` 留白已移除。

原始安装/授权回执见 [`20261012-000234-install`](../20261012-000234-install/) 与 [`20261012-000307-authorize`](../20261012-000307-authorize/)。

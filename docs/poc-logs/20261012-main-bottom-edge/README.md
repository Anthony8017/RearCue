# 主屏底部直达屏幕下沿：部署记录

日期：2026-10-12（Asia/Shanghai）。

- PR [#351](https://github.com/Anthony8017/RearCue/pull/351) 已合并，主分支提交 `a52058398eade39c44c166e362695ee76606640f`。
- 合并结果运行 `gradlew test :app:assembleDebug` 通过，133 个 Gradle 任务成功；从合并提交重建 `:app:assembleDebug` 成功。
- 小米 17 Pro，无线 ADB `192.168.50.2:34711`。覆盖安装成功，`versionName=0.1.0`，`lastUpdateTime=2026-10-12 00:21:44`。
- 本地与设备 APK SHA-256 均为 `FA240299706D747ECD3C72B4EB0281EE4FF882E67A28E6DA76E43B82EDC7A3E2`；`classes3.dex` SHA-256 均为 `9C70B008A22F9864A61E20FB9DB1655E85A549D6B8C855A9D1C70FF173B06084`，并包含 `contentBottomFlush`。
- 补跑通知授权后监听组件已启用，`POST_NOTIFICATIONS: granted=true`。`RearCueBridge` 计划任务保持 `Running`，本次部署未停止或重启生产桥。
- `contentBottomFlush=true` 现在会过滤底部 `safeDrawing` 并将底部 padding 设为 0，内容框底边为 `y=2656`。安装后复拍时设备处于安全锁屏，未尝试绕过 PIN。

原始安装/授权回执见 [`20261012-002137-install`](../20261012-002137-install/) 与 [`20261012-002202-authorize`](../20261012-002202-authorize/)。

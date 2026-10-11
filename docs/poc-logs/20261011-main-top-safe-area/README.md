# 主屏顶部贴合状态栏安全边界：部署记录

日期：2026-10-11（Asia/Shanghai）。

- PR [#341](https://github.com/Anthony8017/RearCue/pull/341) 已合并，主分支提交 `6d570e181df84497ae0fc45fe8b3a0c99e6149cb`。
- 合并结果运行 `gradlew test :app:assembleDebug` 通过，133 个 Gradle 任务成功；随后从合并提交重建 `:app:assembleDebug` 成功。
- APK SHA-256：`435247C75A74E30C96B8EA5D55D1905DF2D7078CB8E72B1F00671903A13C7608`。设备 `base.apk` 哈希一致，`versionName=0.1.0`，`lastUpdateTime=2026-10-11 23:37:52`。
- 小米 17 Pro，无线 ADB `192.168.50.2:40745`。覆盖安装成功；补跑通知授权后监听组件已启用，`POST_NOTIFICATIONS: granted=true`。
- `MIUIOP(10008/10021)`、`MIUIOP(10053)` 与 `SYSTEM_ALERT_WINDOW` 为 `allow`；`MIUIOP(10020)` 回执仍为 `ignore`，按设备原状记录，不改写为 `allow`。
- `RearCueBridge` 计划任务保持 `Running`，本次部署未停止或重启生产桥。
- 部署前同一代码内容实测主屏 UI 框上沿 `y=150`、截图首个 APP 像素 `y=172`；安装后复拍时设备已回安全锁屏，未尝试绕过 PIN。

原始安装/授权回执见 [`20261011-233745-install`](../20261011-233745-install/) 与 [`20261011-233818-authorize`](../20261011-233818-authorize/)。

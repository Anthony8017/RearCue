# Shizuku 状态与锁屏恢复提速部署

日期：2026-10-11（Asia/Shanghai）。

- PR [#340](https://github.com/Anthony8017/RearCue/pull/340) 已合并，主分支提交 `5553a427e423c31dabab9c450a9aae683ccd5b04`。
- Android 全量测试与 `:app:assembleDebug` 通过；桥侧 Node 测试 275 项通过，0 失败。
- APK SHA-256：`F1502C5E51D7BE086302C743621F993BAA5ADD1D32BF71C6202B2D9CFCCF82CC`。
- 小米 17 Pro，无线 ADB `192.168.50.2:40745`。覆盖安装成功，设备回读 `versionName=0.1.0`。
- 安装后 `POST_NOTIFICATIONS`、Shizuku `API_V23`、通知监听均已恢复；MIUI `10008/10020/10021` 与 `SYSTEM_ALERT_WINDOW` 均为 `allow`。
- Shizuku 核对：`server=true granted=true`，`fallback-usable=True`，应用进程存活且监听保持工作。
- 当前 Display #1 的顶层界面为 `RearDashboardActivity`，原生 `SubScreenLauncher` 位于其后。
- `RearCueBridge` 计划任务全程保持运行，本次未停止、禁用或重启生产桥。

安装、授权和 Shizuku 原始回执分别见：
- [`20261011-233443-install`](../20261011-233443-install/)
- [`20261011-233549-authorize`](../20261011-233549-authorize/)
- [`20261011-233602-shizuku`](../20261011-233602-shizuku/)

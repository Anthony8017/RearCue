# Shizuku 状态与锁屏恢复提速部署

日期：2026-10-11（Asia/Shanghai）。

- PR [#340](https://github.com/Anthony8017/RearCue/pull/340) 已合并；随后并入 PR [#341](https://github.com/Anthony8017/RearCue/pull/341)，最终部署主分支提交 `05de2b6fd8eabec4159b43a0bbca28af0320420d`。
- Android 全量测试与 `:app:assembleDebug` 通过；最终增量 `:app:testDebugUnitTest`、`:rear:testDebugUnitTest`、`:app:assembleDebug` 通过；桥侧 Node 测试 275 项通过，0 失败。
- APK SHA-256：`F49783B2809AEB5401D7224237C857AF6BEA0D6C1250E13325A606657D97532D`。
- 小米 17 Pro，无线 ADB `192.168.50.2:40745`。覆盖安装成功，设备回读 `versionName=0.1.0`。
- 安装后 `POST_NOTIFICATIONS`、Shizuku `API_V23`、通知监听均已恢复；MIUI `10008/10020/10021` 与 `SYSTEM_ALERT_WINDOW` 均为 `allow`。
- Shizuku 核对：`server=true granted=true`，`fallback-usable=True`，应用进程存活且监听保持工作。
- 当前 Display #1 的顶层界面为 `RearDashboardActivity`，原生 `SubScreenLauncher` 位于其后。
- `RearCueBridge` 计划任务全程保持运行，本次未停止、禁用或重启生产桥。

最终安装、授权和 Shizuku 原始回执分别见：
- [`20261011-234220-install`](../20261011-234220-install/)
- [`20261011-234306-authorize`](../20261011-234306-authorize/)
- [`20261011-234326-shizuku`](../20261011-234326-shizuku/)

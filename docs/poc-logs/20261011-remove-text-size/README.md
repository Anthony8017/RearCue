# 删除字号选项并固定小档：部署记录

日期：2026-10-11（Asia/Shanghai）。

- PR [#338](https://github.com/Anthony8017/RearCue/pull/338) 已合并，主分支提交 `911773b275d4595bf7b2eafee63cf2474b348f22`。
- 从最新主分支运行 `gradlew test :app:assembleDebug` 通过，133 个 Gradle 任务成功。
- APK SHA-256：`2F2F0F2E1C36FB8CEB65CFEBC329B8796EF508ED64C6D9D7E55634C562048FEC`。
- 小米 17 Pro，无线 ADB `192.168.50.2:40745`。`adb install -r -t` 成功，设备 `base.apk` SHA-256 与构建产物一致，`lastUpdateTime=2026-10-11 23:03:02`。
- 覆盖安装后补跑 `02-authorize.ps1`：通知监听已启用，`POST_NOTIFICATIONS: granted=true`，Shizuku `API_V23: granted=true`。
- `MIUIOP(10008/10020/10021)` 与 `SYSTEM_ALERT_WINDOW` 最终均为 `allow`。
- `RearCueBridge` 计划任务保持 `Running`，本次部署未停止或重启生产桥。

原始安装/授权回执见 [`20261011-230250-install`](../20261011-230250-install/) 与 [`20261011-230343-authorize`](../20261011-230343-authorize/)。

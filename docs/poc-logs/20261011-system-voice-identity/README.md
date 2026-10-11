# 小爱同名音色区分：合并与装机

日期：2026-10-11（Asia/Shanghai）。

- PR [#344](https://github.com/Anthony8017/RearCue/pull/344) 已合并，部署代码为最新主分支 `fd5000afe4d9420481b55fc18d3b82856859c8d6`。
- 集成后运行 `gradlew test :app:assembleDebug`：全量 JVM 测试与 APK 构建通过，133 个 Gradle 任务成功。
- APK SHA-256：`60BA9E9075DFE3CC3E134A0C2D2129F1A8A663B959278F6D78BFB61DBE50993F`。
- 小米 17 Pro，无线 ADB `192.168.50.2:40745`。`adb install -r -t` 返回 Success，设备 `base.apk` SHA-256 与构建产物一致，`lastUpdateTime=2026-10-11 23:50:05`。
- 覆盖安装后 `POST_NOTIFICATIONS: granted=true`，通知监听已启用；`MIUIOP(10008/10020/10021)` 与 `SYSTEM_ALERT_WINDOW` 均为 `allow`。
- Shizuku `app-sees-server=true`、`fallback-usable=true`，应用无崩溃且监听持续工作。检查时手机已锁屏，因此未执行锁屏恢复实验。
- `RearCueBridge` 计划任务保持 `Running`，本次部署未停止或重启生产桥。

安装、授权和 Shizuku 原始回执见 [`20261011-235001-install`](../20261011-235001-install/)、[`20261011-235027-authorize`](../20261011-235027-authorize/) 与 [`20261011-235110-shizuku`](../20261011-235110-shizuku/)。

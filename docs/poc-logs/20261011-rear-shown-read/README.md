# 背屏露出当前对话即已阅：合并与装机

日期：2026-10-11（Asia/Shanghai）。

- PR #332 已合并，主分支提交 `dc762e553e7b500d79664d3528da3a70a1a225c1`。
- `gradlew test :app:assembleDebug` 通过。APK SHA-256 `F83EBE4024C8CEE947D3F259885AE8AA4A594EA72753E4A275DCD247BA4DB877`。
- 设备：小米 17 Pro，无线 ADB。`adb install -r -t` 返回 Success。设备回读 APK 与构建哈希一致。`lastUpdateTime=2026-10-11 18:12:44`。
- 覆盖安装后 `POST_NOTIFICATIONS` 曾变为 denied，已重新授权。核对结果：`granted=true`，`POST_NOTIFICATION: allow`。通知监听仍在，Shizuku API 仍为 granted。
- `RearCueBridge` 计划任务保持 Running。本次只改手机已阅规则，没有停桥或重启桥。
- 背屏蓝点的实机目检尚未做。

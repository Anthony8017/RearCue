# 会话页标题放大：合并与装机

日期：2026-10-11（Asia/Shanghai）。

- PR #334 已合并，主分支提交 `e27a140e5f4f803aaa4f44f2241d4a8204fee19d`。
- `gradlew test :app:assembleDebug` 通过。APK SHA-256 `A208F948354D0A546E8149E85DA69445CA349E317E19DE9C3C568204AFEB7B78`。
- 设备：小米 17 Pro，无线 ADB `192.168.50.2:45953`。`adb install -r -t` 返回 Success。设备 `base.apk` 与构建哈希一致。`lastUpdateTime=2026-10-11 22:18:41`。
- 覆盖安装后 `POST_NOTIFICATIONS` 曾变为 denied，已重新授权。核对结果：`granted=true`，`POST_NOTIFICATION: allow`。通知监听仍在，Shizuku `API_V23` 仍为 granted。
- 本次只安装手机 APK，没有停桥。
- 背屏标题字号的实机目检尚未做。

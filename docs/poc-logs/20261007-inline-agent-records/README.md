# 正文原文展示：验收与部署

日期：2026-10-07（Asia/Shanghai）。设备：小米 17 Pro，Android 16。

## 合并与验证

- [PR #326](https://github.com/Anthony8017/RearCue/pull/326) 已合并；部署代码为主分支 `386dcca1d7069c8003118151b21786a9ca6468db`。
- `gradlew test :app:assembleDebug :rear:assembleDebugAndroidTest` 成功；JVM 1339 次执行（含 debug/release），0 失败、0 跳过。
- 独立测试 APK 真机 6 项全通过，覆盖原文安全区、1500 行输出滚动、过程开合、完成/失败及入场回看恢复；原始输出见 [ui-tests.txt](ui-tests.txt)。
- 初期安装受手机锁屏限制；独立测试应用补齐锁屏显示及后台启动权限后执行。测试标题与提问同名导致的匹配歧义已修正，最终为 `OK (6 tests)`。
- 合并后从最新主分支执行 `gradlew :app:assembleDebug` 成功，安装返回 `Success`。

## 装机核验

- 包名：`com.rearcue.poc`；版本：`0.1.0` / versionCode `1`。
- 设备 `lastUpdateTime`：`2026-10-07 01:07:10`。
- 从设备 `pm path` 返回的位置拉回安装后的 `base.apk`，与构建 APK 的 SHA256 完全一致：

```text
E45E593412FD904A24630210BA95655798DD6EAF21BA8B6D4B02F981145B2D6A
```

- 新进程记录 `listener connected active=21`，并持续接收桥事件。
- 主屏 MainActivity 位于 display 0；RearDashboardActivity 已在 display 1 创建并确认投送成功。
- 重装后恢复原已允许的锁屏显示（10020）及后台启动（10021）；通知、自启动保持允许，悬浮窗保持原来的 ignore。
- PC 桥计划任务仍为 Running；此次合并未修改桥代码。
- 独立测试 APK `com.rearcue.poc.rear.test` 已卸载。

以上 UI 自动验证使用独立测试数据；装机后的核验为包文件一致性及运行日志，不将其写成人工目检。

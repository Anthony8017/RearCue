# Latest-build deployment: picker tap fix + APK install (issue #308)

started  : 2026-10-04 17:00 Asia/Shanghai
commit   : 3bae339
scope    : issue #308（并入 #306/#307 的既有部署）

## Completed

- 并行会话留在工作区未提交的背屏修复（通知页空白处点不动 / 退场后全透明列表层吃掉点按）
  已由本会话收尾：`:rear:test` 绿 → 提交 `6cbbd3a` → `--no-ff` 合并 `3bae339` → push。
- `docs` 无新代码之外的改动；`main` 与 `origin/main` 同步。
- 最新 debug APK 从 `main`（工作区无未提交改动）构建：`17:00:39`，含 #307＋#308。
- DSH 由机主重启（#307 的插件侧过滤生效）；重启后 `/snapshot` 里 DSH 会话仍带真标题与目录
  （`bridge.identity.json` 身份表生效），`capabilities.dsh = waiting+approve` 插件存活。
- 手机重新打开无线调试后 ADB 可达（mDNS：`192.168.50.252:41789`）。
  `tools/ex/01-install.ps1` 的 `Invoke-ExInstallApk` 在本机报
  `HasExited` 属性缺失（helper 自身的 Start-Process 包装问题，与本票改动无关），
  因此按脚本同一步骤手工执行：`wm dismiss-keyguard` → `adb install -r -t` → `Success`。
- 装机后授权（与 01/02 脚本口径一致）：`POST_NOTIFICATIONS` grant、
  `appops 10020/10008/10021 allow`、`SYSTEM_ALERT_WINDOW allow`、
  `cmd notification allow_listener com.rearcue.poc/com.rearcue.poc.notify.RearNotificationListener`。
  校验：`lastUpdateTime=2026-10-04 18:21:15`、`POST_NOTIFICATIONS: granted=true`、
  监听组件已在 `enabled_notification_listeners` 列表内。
- 手机链路：装机后 app 报一次 `bridge 请求失败 SocketException` → 退避重连，随后托盘状态翻
  `phone=true`（手机在新隧道 URL 上恢复轮询）；app 进程存活并正常镜像通知。

## Remaining verification

- 背屏截图不可得：`screencap -d 1` 报 "Display Id '1' is not valid"（副屏 Presentation 的
  display id 与 screencap 目标不同），所以「点通知页空白处能回 Agent 页」与「DSH 行显示真名」
  留给机主目检。
- `01-install.ps1` 的 `HasExited` 报错可另开一票修（本机 PowerShell 5.1 下 Start-Process
  -PassThru 的返回对象异常）；本次用等价手工命令绕开。

# erratum -- E13-NO-BASELINE (environment: listener not rebound after reinstall)

本轮不进结论、不作验收证据：锁屏后背屏 7s 离开 ON 的采样属**无 Dashboard、无保活**的裸锁屏
行为（与 E5/票 #11 同类事实），不是「默认 5000ms 没守住」——保活循环从未启动
（`inject-running : False`、应用日志 `debug wake-interval ms=5000 running=false`），无从谈起。

## 事实链

- 03:06:48 `01-install` 重装成功（同一 debug keystore，无签名变更）；重装**重置逐应用 appops**，
  MIUI 自启动（MIUIOP 10008）没被恢复 ⇒ `AutoStartManagerService` 拒绝通知监听重绑。
- 应用日志（`logcat-rearcue.txt`）：`debug cancel pkg=com.android.shell cancelled=-1（监听服务未连接）`、
  `iconSet [] -> [] tracked=0` 全程——POST_TEST 的测试通知没有进监听，核心不触发投送 ⇒
  `E13-NO-BASELINE`（词表如实）。
- `Start-ExApp` 的「监听已连接」判定踩了 `@()`+`,$arr` 陷阱（findings 已两次记载）：
  `@(Wait-ExLog ...)` 把空结果读成 `.Count=1` ⇒ 静默跳过 disallow/allow 重绑兜底，
  没有任何告警地把基线放过去了。**本轮已修**（裸捕获 + 注释）。
- 处置（233947 erratum 同方）：`appops set com.rearcue.poc 10008 allow` + 监听 disallow/allow 重授 +
  重启应用；`settings put global enable_screen_on_proximity_sensor 0`（关掉会吞上滑解锁的
  「亮屏距离传感器防误触」引导窗）。`01-install` 补上 10008 重授（与 10020 同段）。

判定链其余部分工作正常（`lock-press-lag` 0.53s、PowerGroup group-1 power-off 齐、
`pollution : none detected`），纯环境问题。干净重跑见同日后续 `-lock-survive` / `-wake-cost` session。

# erratum（票 #25 实机取证轮，2026-09-23）

1. **首轮 03/04 截图未写盘**（`evidence.ps1`）：主屏自动息屏后 `screencap` 不出文件，`adb pull` 也就无对象可拉
   （日志里只剩 pull 行缺失）。补拍轮（`evidence2.ps1`）改为：每拍前 `input keyevent KEYCODE_WAKEUP` +
   `wm dismiss-keyguard`、`screencap -d 0 -p` 显式指定主屏、取证期间临时 `screen_off_timeout=600000`（结束按原值恢复）。

2. **首轮 ui-01-empty.xml / ui-04-projected.xml 抓错了屏**：`uiautomator dump` 取当前焦点窗口，当时焦点在背屏
   Dashboard（bounds 904×572、节点里是 Dashboard 时间文本），不是主屏调试页。触控目标 bounds 以补拍轮
   `ui-05/06/08-*.xml`（背屏已退出、焦点回主屏，bounds 1220×2656）为准；这两份错抓文件保留作反面记录。

3. **互斥锁收尾写法坑**（已回传、notes/env.md 已修正）：旧写法
   `finally { if ($m.WaitOne(0)) { $m.ReleaseMutex() }; $m.Dispose() }` 在已持锁线程上把计数再 +1、只放一层就
   Dispose → mutex 以 abandoned 释放，下一位 `WaitOne()` 抛 `AbandonedMutexException`（捕获后锁其实已授予，
   继续即可）。首轮 `evidence.ps1` 用的正是旧写法；补拍轮第一次启动即被 `AbandonedMutexException` 打断、
   零产出（证据里保留这条 stderr），改用 notes/env.md 的修正写法（捕获 AbandonedMutexException + `finally` 里
   直接 `ReleaseMutex()`）后重跑成功。

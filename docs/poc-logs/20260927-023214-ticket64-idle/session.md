# 票 #64 验收：Dashboard 常态视觉重构与横幅退役（spec 0008，2026-09-27 02:32）

- 包：app-debug.apk（spec/0008-rear-visual @ 本次工作树），`adb install -r` Success（install.txt）。
- 投送：POST_TEST 通知 + PROJECT_REAR 调试旁路；`RearDashboardActivity onCreate display=1`
  （logcat-full.txt：manual-cast → LaunchDashboard → posted com.rearcue.poc → UpdateIconSet(1)）。

## 判定
1. **常态仅 Icon Set、无时间、无横幅** — PASS。背屏截图（E15 口径 `screencap -d 4630946949513469332`，
   `dumpsys SurfaceFlinger --display-id` 两屏中 HWC 5 = 背屏）：
   screenshots/01、02 = 纯黑底 + 单枚应用图标居中放大（natural 270×270 / 屏宽 904 ≈ 30%，对照
   docs/mockups/0008-dashboard-visual/chatgpt/01-idle-icons.png），无时间文本、无横幅层。
2. **安全区 + 防烧屏漂移不回退** — PASS。rear-safe-geometry 读数同 E15（content=[296,97,807,475]，
   drift ±8）；rear-safe-place 两帧 drift=(8,8)→(-8,8)，placed=[425,159,695,429]→[409,159,679,429]，
   全程在内容安全矩形内（safe-place.txt）。
3. **设置页无 Privacy Mode / Auto-dismiss 项** — PASS（静态证据）：APK resources.arsc 无
   Privacy Mode / Auto-dismiss / 无上限 字串（apk-strings-check.txt，0 命中）；源码 BannerSection /
   AutoDismissSetting / FeedSettingsStore / AutoDismissPolicy 整体删除，设置页渲染面完全由
   AppState 驱动、相关字段已不存在（见 commit diff）。
   实机 UI 取证受限（如实记）：设备实为解锁态、主页可见（04-main-display-state.png），但
   uiautomator dump 恒为空树（settings-ui.xml / ui.xml，6764 字节 0 文本节点，HyperOS 限制），
    两种坐标框架（横屏逻辑系 2349,312＝齿轮实测质心 / 竖屏系映射）均不触发导航
   （05 已删，无效证据不入档）——与 E 系列锁态取证限制同类，静态证据收口。
4. **退出还原** — PASS。EXIT_REAR → ExitDashboard、Dashboard detach 实例数=0、wake-keep-alive stop
   （exit-restore.txt）；退出后背屏截图回原生界面（screenshots/03，非纯黑即原生 AOD/表盘内容）。

## 判定输出
- `gradlew test` 全绿：338 例 0 失败（app 36 / core 117 / notification 23 / rear 162，debug+release 两变体）。
- 退役断言：app 模块 NotificationEventWiringTest「Updated 不产生任何效果（横幅退役断言，spec 0008 反转）」；
  DashboardCoreTest 横幅节删除留痕指向 spec 0008。
- DataStore 残键处置：**废弃容忍**（`feed_settings` 无读者、无害，POC 期不写清理/迁移代码；卸载即清）。

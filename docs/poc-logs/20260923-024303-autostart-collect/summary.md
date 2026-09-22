# 采集轮补记（票 #27 自启动探针，收口时补）

本轮 = 检测面 sweep（只读）。关键原始产物：
- `raw-appops-rearcue.txt` / `-wechat` / `-shell` / `-subscreencenter.txt`：四包 `appops get` 全量（MIUIOP 模式对照的来源）
- `raw-settings-all.txt`：global/secure/system 全量（无 per-app autostart 键）
- `raw-dumpsys-l.txt`：服务清单
- `raw-dumpsys-secpkg-autostart.txt`：发现 `com.lbe.security.miui/...AutoStartManagerProvider`（authority `com.lbe.security.miui.autostartmgr`）

勘误（不影响事实）：其中 4 个 `sh -c "... | grep ..."` 采集因引号被 adb shell 剥掉而失效
（`raw-dumpsys-secpkg-activities.txt` / `raw-pkg-rearcue-uid.txt` 等是 grep 报错与 dumpsys 噪声）——
同类内容改由 024439 轮整段 dumpsys 后 PC 侧过滤补齐。

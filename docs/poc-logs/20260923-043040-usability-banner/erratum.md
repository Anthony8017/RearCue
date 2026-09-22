# erratum（票 #28 可用性横幅实机取证轮，2026-09-23）

1. **安全锁阻塞 tap/视觉取证**：`isKeyguardShowing=true`，`input keyevent 82`、上滑、`wm dismiss-keyguard`
   均无效（PIN 无人可解，机主在睡）。主屏截图会拍到锁屏、`uiautomator dump` 抓的是 `NotificationShade`
   （焦点窗在锁屏）、按钮 tap 无法送达 MainActivity ⇒ 「一键跳转 tap 三证 / 主屏横幅截图 / 触控 bounds /
   不遮挡目检」四项列入 findings「待补实拍」。与票 #25 的「空态四张待机主解锁补拍」同款处置。

2. **ui dump / screencap 取错窗（第二次踩同一坑，票 #25 erratum②）**：自动投送的 E14 任务搬运把**整个 root task**
   （MainActivity + RearDashboardActivity）搬到背屏 display 1，`uiautomator dump` 抓到 904×572 的背屏窗
   （`01/02-ui-*.xml` 保留作反面记录，root bounds 904×572）；`screencap -p` 未指定 display 时也默认拍了
   display 1（`screenshots/01/02-*.png` 实为 904×572 背屏窗——可作**横幅渲染样式**的视觉记录，不能作
   「主屏无遮挡」验收）。清掉触发通知（`CANCEL_PACKAGE com.android.shell`）后 `exit()` 交还主屏
   （`fix-ui-check.xml` root 1220×2656）。教训：dump/screencap 前先验 root bounds + package，screencap 显式
   指定 display id。

3. **pwsh 管道落盘编码坑（新）**：`adb ... | Set-Content` 在本机把中文按 GBK 落盘，`read`/UTF-8 工具打不开
   （内容本身正确，如 `A1FA`=GBK「→」）。修复：全部 `.txt` 按 GBK→UTF-8 转码归档；后续取证统一
   `[Console]::OutputEncoding = UTF8` + `Set-Content -Encoding utf8`（chain-09/10 起）。

4. **锁屏态 ON_RESUME 偶发不投递**：`input keyevent KEYCODE_HOME` 在锁屏下不把 MainActivity 退到后台，
   随后的 `am start` 不触发 `ON_RESUME` ⇒ 复查行缺失（chain-03/06）。非决策缺陷：同链路复查行在 04:33 轮与
   chain-01 实录在案（`autostart GRANTED → HideUsabilityBanner`）；产品语义「从 MIUI 设置页返回触发复查」
   依赖真实前后台切换，锁屏脚本轮无法稳定模拟。

5. **互斥锁长占用**：并行代理实验占用 `Global\RearCueDevice` 超过 2×30s（本轮 `MUTEX-BUSY` 实录），
   按纪律不绕锁跑实验，改做文档/回填后再抢锁补录（chain-09/10）。

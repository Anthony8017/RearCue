# 97f902d 实机冒烟（code-review 修复提交：排序 lambda 去重等，纯展示层）

- 设备：小米 17 Pro（94250f9e，USB）；构建：97f902d `:app:assembleDebug`（14:05，本机 JDK17/SDK 工具链，147 例单测已绿的前置由提交方声明，本轮未重跑 JVM 测试）
- 时间：2026-09-26 14:05–14:22（UTC+8）；口径：`adb logcat -s RearCue AndroidRuntime:E` + `uiautomator dump` + `cmd notification` + 调试广播
- 缘起：该提交标注「实机冒烟因设备断连未跑」，本轮补上。

## 判定：全过，零崩溃（logcat 无 FATAL/AndroidRuntime）

| 冒烟项 | 判定 | 证据 |
|---|---|---|
| 安装/冷启 | ✅ | 全新安装（旧包为异机签名，INSTALL_FAILED_UPDATE_INCOMPATIBLE → uninstall 重装）；冷启 `allowlist store-load size=5` 种子五枚（14:10:16） |
| 主页结构（spec 0005 #47） | ✅ | 横幅（自启动存疑+跳转）/ Icon Set 卡 / 三行摘要（监听·通道·通知数）/ 齿轮入口 / 开发者选项折叠区（展开含状态明细+背屏卡+5 调试按钮，第 6 个「跳转自启动」在横幅） |
| 设置页名单 + 排序（本提交改动点） | ✅ | 在册 5 枚；屏显序「飞书、微信、QQ、RearCue、Shell」= CLDR zh 序（汉字按拼音整体前置于拉丁；笔记/哔哩哔哩 同拼音并列时按包名 `com.miui.notes < tv.danmaku.bili` 次键稳定排序——sortAppEntries 两处调用点行为一致） |
| App Picker | ✅ | BottomSheet 候选+搜索；禁 IME 直注 "bili" 过滤出唯一 `tv.danmaku.bili`（包名子串匹配）；点选即加即关（在册 6 枚、按拼音插入首位）；移除无二次确认即时回 5 枚（14:17:18 add size=6 / 14:17:47 remove size=5） |
| 核心回归 A：通知→上屏 | ✅ | `listener connected active=20`（重连全量对账）→ `posted com.android.shell → LaunchDashboard(1)` → `posted com.ss.android.lark → UpdateIconSet(2)`（14:18:38）；`dumpsys` Display #1 = RearDashboardActivity |
| 核心回归 B：撤/删→摘除/下屏 | ✅ | CANCEL_PACKAGE shell → `removed → UpdateIconSet(1) [shell,lark]->[lark]`（14:20:01）；UI 移除飞书 → `allowlist remove size=4 → ExitDashboard iconSet -> []`（14:20:51）；重加飞书 → `allowlist add size=5 → LaunchDashboard(1)`（14:21:29，飞书真实通知在册自动重投） |
| 防烧屏漂移 | ✅ | rear-safe-place drift x=8 → x=-8（14:18:38/14:19:00） |

## 事故与变通（如实记）

1. **异机签名升级失败**：设备上凌晨验收版（PR #49 构建）与本机 debug keystore 不符，`install -r` 报 UPDATE_INCOMPATIBLE → 卸载重装（DataStore 重置，恰好复验链 E 种子）。
2. **MIUI USB 安装弹窗单点失手**：`tools/ex/01-install.ps1` 的单次点击（351,2414，点位正确、倒计时剩 8s）未生效即被拒；改用循环盯屏（0.6s 间隔、可重点）第 2 次点击（倒计时剩 5s）通过——疑首次点击落在弹窗入场动画/未就绪窗口。**建议 01-install 改为点击后复核弹窗是否仍在、可重试**（本轮未改工具代码）。
3. **搜索框注入被 IME 吞**（已知，验收事故 4 复现）：wetype 在前时 `input text` 无效；禁用 IME 直注成功。**副作用：系统默认输入法被切到搜狗，已 `ime set` 恢复 wetype。**
4. **机主同时在用机**：两次 UI 点击落到了前台的美团（冒烟中段前台被切走），非应用缺陷；后续每步先核对 `mCurrentFocus`。
5. 首次 `cmd notification post` 时监听尚未重绑完成，事件在对账阶段补投（`listener connected active=20` 后全链生效），符合「连接时全量对账」设计。

## 设备收尾态

- 应用：97f902d 构建（14:09:49 安装）；DataStore=种子五枚（canonical）；监听已授权已连接；POST_NOTIFICATIONS + MIUIOP 10008/10020 + SYSTEM_ALERT_WINDOW 均已授；输入法已恢复 wetype。
- 通知：shell 测试通知已撤（CANCEL_PACKAGE）；飞书为机主真实通知，保留。
- 背屏：**Dashboard 在投**（飞书真实通知在册 + canonical 白名单 ⇒ 状态机诚实输出；与 02:36 收尾态「背屏退出」的差异仅在彼时无 Allowlist 通知在册）。
- 桌面已按 HOME 归还前台。

## 工作区

- 冒烟在 detached HEAD 97f902d 下构建；结束切回 main（本目录为未跟踪新文件，是否入库由机主定）。

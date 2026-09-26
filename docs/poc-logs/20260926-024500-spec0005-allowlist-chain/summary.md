# spec 0005 Allowlist 管理：五链路实机验收（票 #48）

- 设备：小米 17 Pro（94250f9e，USB）；构建：`codex/issue-44-allowlist-management`（PR #49）
- 时间：2026-09-26 02:24–02:36（UTC+8）；口径：`adb logcat -s RearCue` + `uiautomator dump` + `cmd notification`

## 判定

| 链路 | 判定 | 证据（logcat 行，全文见 logcat-rearcue-full.txt） |
|---|---|---|
| A 增→上屏 | ✅（合成） | #45：`posted com.android.shell → LaunchDashboard(1)`（02:09:56）；#46：`allowlist add com.baidu.tieba size=5`（02:20:50）；本轮 E 链再证 `posted com.android.shell → LaunchDashboard(1)`（02:35:53，种子上下文） |
| B 删→摘除 | ✅ | #45：`allowlist remove com.android.shell size=4 → ExitDashboard`（02:11:16）；本轮：`removed com.android.shell → ExitDashboard`（02:36:07） |
| C 清空→不投 | ✅ | 清空（UI 5×移除，空态「名单为空」在屏）后 `posted com.android.shell iconSet [] -> []`（02:31:28，无投送效果） |
| D 重启→名单仍在 | ✅（修复后） | 清空→force-stop 冷启：`allowlist store-load size=0` + `posted com.android.shell iconSet [] -> []`（02:35:34/38，不投） |
| E 清数据→种子 | ✅ | `pm clear` → 冷启：`allowlist store-load size=5`（02:35:51）→ `posted com.android.shell → LaunchDashboard(1)`（02:35:53）→ `removed → ExitDashboard`（02:36:07）全环 |
| 未安装态（#45） | ✅ | `pm uninstall -k --user 0 com.baidu.tieba` 后设置页行显示 label 退化「tieba」+「未安装」标签 + 包名（uiautomator 实拍） |

单测基线：`gradlew test` 全绿，debug 变体 **147 例 0 失败**（与 spec 0004 收官基线一致，无回退）。

## 本轮发现并修复

**空集重置 bug（AllowlistStore.load）**：原判据 `stored.isNullOrEmpty() → 种子`，把机主合法清空（存的就是空集）误判成首装、重启后重置回五枚种子——链 D 首跑即踩中（02:31:31 `store-load size=5`，当时已清空）。修复：首读判据改为 **键是否存在**（`apps == null` 才种子；空集是合法持久值）。修复后链 D 复跑通过（02:35:34 `size=0`）。

## 判读边界与事故（如实记）

1. **百度贴吧被真卸载**：为验「未安装」态用 `pm uninstall -k --user 0 com.baidu.tieba`（应保留数据、可 `install-existing` 恢复），但本机 MIUI 把包记录整个移除、`install-existing`/`pm path` 均报不存在（MIUI 行为与 AOSP 语义不符）。数据目录应仍在（-k），**遗留人工步：机主从应用商店重装「百度贴吧」（登录态应可恢复）**。尝试过 market:// 与 mimarket:// deep link，均只落商店推荐页，无法自动化。
2. **MIUI 把本应用自身通知 importance 置 NONE**（dumpsys：`AppSettings: com.rearcue.poc importance=NONE userSet=false`，`set_app_importance` 命令本构建不存在）：`发测试通知`（POST_TEST 广播）发出的通知被系统丢弃、永不产生 listener 事件——这就是 POC allowlist 里放 com.android.shell「自动化发通知用」的原因（历史 E 系列全部走 shell 通知）。链路验收全部改用 shell 通知。
3. **锁屏态 Dashboard 落主屏**：02:32 一轮锁屏态重启时，snapshot→LaunchDashboard 的投送把 RearDashboardActivity 建到了主屏（focus 在 Display 0）——这是上游 #43/#36 的已知失败模式（本分支基线含 6d96644 的 turnScreenOn 修复，但锁屏兜底建主屏本身仍在该票域内），与本 spec 无关，撤 shell 通知即恢复（`removed → ExitDashboard`）。
4. **中文 IME 吞 adb 注入文本**：App Picker 搜索框验证时，拼音 IME 把注入字母吃进组合态（前几字母丢失、上屏为「——」）。验证以「禁用全部 IME 后直注」完成（验后已恢复原 IME）。交互手测不受影响。
5. **force-stop 后监听不重绑**：本机自启动 appops 曾为 DENIED（验收中途经 01-install 同款命令重授 10008 allow），期间每次 force-stop 冷启后需 `cmd notification allow_listener` 重拉监听——已知事实（README/票 #28），链路步骤已含此步。
6. **remember 缓存掩盖卸载**：卸载贴吧后设置页行数据沿用 `remember(pkg)` 缓存（图标/名称/installed 判定），退出重进设置页后才显示「未安装」态。可接受（Compose 惯例），如实记。

## 设备收尾态

- 应用：spec 0005 构建，`pm clear` 后 canonical：DataStore=种子五枚、监听已授权已连接、POST_NOTIFICATIONS+appops 10008/10020 已授、通知已清（`CANCEL_PACKAGE com.android.shell`）、背屏已退出回原生。
- 遗留：贴吧待机主重装（见事故 1）。

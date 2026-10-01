# Spec 0022 实机验收（票 #226）

日期：2026-10-02（CST）
设备：Xiaomi 17 Pro / 25098PN5AC，adb serial `94250f9e`
构建：`0.1.0`，修复状态行重复拼接后的 debug APK，SHA-256 `0FB2FFF00BDBE28C2E6804BCA16A72A76276E1559D8B71F94D493E1C9DF8683C`
桥：`origin/main` @ `eff0fd6`，验收后已恢复生产 quick tunnel。

## 判定

| 条目 | 结论 | 证据 |
| --- | --- | --- |
| 真标题优先；同标题撞名附尾号 | PASS | `shots/s01_true_title_collision_a.png`、`shots/s02_true_title_collision_b.png`、`picker-ui3.xml` |
| 无真标题目录名兜底；同目录撞名附尾号 | PASS | `shots/s07_picker_two_line.png`（AntNest · C300 / D400） |
| 无目录退 sessionId 尾 4 位 | PASS | `shots/s05_tail4_no_dir.png`（E500 / DSH） |
| 超长标题尾部省略 | PASS | `shots/s06_long_ellipsis.png`、`shots/s07_picker_two_line.png` |
| 三档字号联动 | PASS | `shots/s12_text_small.png`、`s12_text_medium.png`、`s12_text_large.png`；logcat `debug mirror text size=SMALL/MEDIUM/LARGE` |
| 选择器/主屏列表两行式，来源进副行 | PASS | `shots/s07_picker_two_line.png`、`shots/s17_empty_state_try.png` 中列表区 |
| 状态行内联「标题 · 来源 · 目录」 | PASS | `shots/s16_statusfix_main.png`；修复锁定档重复拼接后重装复测 |
| 通知正文只取主行 | PASS | `notification-record.txt`：`android.title=任务干完`，`android.text=真标题验收 · A100` |
| 锁定断线空窗缓存显示名 | PASS | `shots/s14_locked_cache_disconnected.png` |
| #211 平铺口径同屏复验 | PASS | `shots/s07_picker_two_line.png`：平铺、无关闭带、末行可截半 |
| 全串 sessionId 退出界面 | PASS | 全部截图/UI XML 未出现 sessionId 全串 |
| 空态无副行 | PASS（结构/JVM） | `AgentSessionDisplay`/`AgentStateLogic` 判例；现场系统通知占位，未单独实拍空态背屏 |

## 测试

- `.\gradlew.bat :agent:test :app:testDebugUnitTest :rear:testDebugUnitTest` — PASS
- `node --test tools/bridge/bridge.test.mjs tools/bridge/make-icons.test.mjs tools/bridge/tray.test.mjs tools/bridge/adapters/*.test.mjs tools/bridge/adapters/dsh/*.test.mjs` — PASS（135 项）
- `node tools/bridge/repo-check.mjs` — PASS

## 收尾

- `SESSION_LOCK` 已回 `auto`
- 桥地址已恢复生产 quick tunnel
- 验收用 `sp022*` 合成会话已从桥名册清除
- `POST_NOTIFICATIONS` 已授予应用，用于验证 Agent 提醒通知

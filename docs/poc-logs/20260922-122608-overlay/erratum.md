# Erratum（事后标注，2026-09-22 review 收口时补写；非本轮原始输出）

本轮是 E9 首轮观察（背屏拒绝，事实成立），但两处产物出自**修复前的脚本**：

1. `e9-overlay.txt` 的 verdict 是 `E9-SYSTEM-REJECTED`——当时解析器没有「Not allow」排除规则，
   把系统策略行当成了 add 命中；收口后的分类为 `E9-REAR-POLICY-BLOCKED`（同一条系统行）。
   正式结论以 `20260922-123134-overlay/`（修复后脚本）为准。
2. `system-window-log : 1 hit(s)` 同因：那 1 hit 就是
   `WindowManager: Not allow non-system app com.rearcue.poc add system_window on rear display`
   这条 deny 行本身。

系统行、应用日志、dumpsys 均为本轮原始输出，未改动。

# erratum — 探针协议在本 session 之后定稿（票 #17）

本 session（20260922-185749-shuid-collect）是 SH-UID 探针的**首轮采集轮**，当时的探针
（`tools/ex/device/shuid-overlay/ShuidOverlayProbe.java`）只有单一窗口胶水
（`createWindowContext`），产物里 `probe: window-context ok type=2038` 是旧协议的一行。

其后探针协议定稿（原始产物不回改，格式差异只在此标注）：

- 改为胶水策略链（window-context → display-context → plain），逐次尝试以
  `probe: attempt strategy=<s> ...` 记录；`probe: window-context ok ...` 那行不再出现。
- 新增 `probe: register ...` 一族与 `<tryRegister>` 参数（app attach 握手检查，最后一步跑）。
- `probe: add ok ... strategy=<s>` 带上成功策略字段。
- main 增加 `System.exit(0)`（本 session 的探针进程打印 `probe: done` 后没有退出，
  以 `pidof app_process` 可见，已在后续 session 前清理）。

Pester fixture 取自协议定稿后的采集轮（20260922-192356-shuid-collect）；本轮产物只作
过程记录。本 session 结论素材（`add failed reason=java.lang.IllegalStateException: Unknown
pid=<pid> uid=2000`）与后续轮一致。

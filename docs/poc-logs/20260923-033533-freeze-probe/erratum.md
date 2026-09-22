# erratum — 20260923-033533-freeze-probe（探针节奏缺陷轮，判定作废，样本不进结论）

3 次控制探针 0 交付 → 脚本按设计止损（EVT-NO-LISTENER）。**post 本身成功**（`freeze-post-outputs.txt`
原文 `posting: Notification(channel=shell_cmd ...)`）但 5s 内无 `posted` 行——不是发帖失败，是**通知监听
处于重绑窗口**：`disallow_listener/allow_listener` 重绑要 9~13s（票 #3 实测），期间到达的通知经初始
snapshot 进入跟踪集、**不产生** `posted`/`removed` 事件行；而本轮脚本在 3 次探针之间连做两次重绑舞步
（间隔仅 ~5s），每次都把重绑时钟拨回零点 ⇒ 永远等不到事件行。20260923-030402 的 c1 之谜与
20260923-032045 的 c1 之谜同因（前者：进程刚重建、监听还在首绑；后者：舞步刚发出）。

修复（下一轮生效）：舞步后**必须等到 `listener connected active=` 行**再探，探针不再压着重绑窗口跑。
归档不回改；修复后判定轮以无 erratum 的 `*-freeze-probe` session 为准。

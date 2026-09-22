# erratum — 同 191525：register 探针在前，探针进程被杀（票 #17）

本 session（20260922-191803-shuid-collect）与 20260922-191525-shuid-collect 同因：app attach
握手（`ActivityThread.attach(false)` → `attachApplication`）放在窗口尝试之前，三段探针全部
死在 `probe: context ok ...` 之后（无 `probe: attempt` / `probe: done` 行）。

机制事实与处置见 `20260922-191525-shuid-collect/erratum.md`（握手即被杀、握手挪到最后一段、
`System.exit(0)`、stray 进程清理）。Pester fixture 取自协议定稿后的采集轮
（20260922-192356-shuid-collect）。

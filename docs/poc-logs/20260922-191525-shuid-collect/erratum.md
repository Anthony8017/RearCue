# erratum — app attach 握手探针致探针进程被杀，无窗口事实（票 #17）

本 session（20260922-191525-shuid-collect）把 app attach 握手（`ActivityThread.attach(false)`
→ `attachApplication`）放在窗口尝试**之前**跑：三段探针全部死在
`probe: context ok ...` 之后（产物里没有任何 `probe: attempt` / `probe: done` 行），
三段均报 `probe output ... has no \`probe: done\` line within Ns`。

死因（下一轮前台复跑抓到 shell 侧原文 `Killed`，即 SIGKILL）：本构建对 ATMS 未注册的
shell uid 进程调用 `attachApplication` 直接杀进程。因此：

- 本 session **不含窗口事实**（窗口尝试根本没跑到），只记录「握手即被杀」这一机制事实；
- 探针随后把该握手挪到最后一步、以 `<tryRegister> on` 显式开启（registercheck 段），
  保证窗口事实先于可能被杀的步骤产出；
- 同批修复：`System.exit(0)`、开跑前按 cmdline 清 stray 探针进程。

Pester fixture 取自协议定稿后的采集轮（20260922-192356-shuid-collect）。

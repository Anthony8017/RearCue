# erratum — 探针协议在本 session 之后定稿（票 #17）

本 session（20260922-190505-shuid-collect）是 SH-UID 探针的采集轮之一：三策略胶水已就位
（`probe: attempt strategy=...` 逐条记录，全部被 `Unknown pid=<pid> uid=2000` 拒绝），
但协议尚缺两处（原始产物不回改，差异在此标注）：

- 尚无 `probe: register ...` 一族与 `<tryRegister>` 参数（app attach 握手检查后补，
  固定放在最后一步：该握手会杀掉未注册进程）。
- main 尚无 `System.exit(0)`：本 session 的探针进程打印 `probe: done` 后未退出
  （`pidof app_process` 可见），后续 session 开跑前按 cmdline 清理 stray 进程并入脚本。

Pester fixture 取自协议定稿后的采集轮（20260922-192356-shuid-collect）；本轮产物只作
过程记录，结论素材与后续轮一致。

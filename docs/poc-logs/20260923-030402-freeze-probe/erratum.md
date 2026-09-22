# erratum — 20260923-030402-freeze-probe（工具缺陷轮，判定作废，样本不进结论）

本轮是 16-freeze-probe.ps1 的首轮 bring-up，产物**归档不回改**，判定与数字**一律不进结论**；修复后的判定轮以 `poc-logs/2026xxxx-*-freeze-probe/`（erratum 的轮次）为准。三个工具缺陷 + 一个时序竞态：

1. **`$pid` 是 PowerShell 只读自动变量**：`Get-ExCgroupFreeze` 的局部变量 `$pid` 赋值全部抛
   `VariableNotWritable`，wire 里 `pid=` 字段实际写入的是 PowerShell 进程号（4132）——cgroup 采样全轮失真，
   判定因此误报 `FZ-NOT-REPRODUCED`。**设备事实恰恰相反**（`logcat-greeze.txt` 原文）：
   `09-23 03:04:17.178 GreezeManager: FZ uid = 10336 reason =tobg success !`（HOME 后 ~6.5s 冻结）。
   修复：改名 `$uidVal`/`$pidVal`。
2. **管道过滤器里的 `$_ -match` 会覆写 `$Matches`**：`Get-ExGreezeEvents` 的 THAW 分支在
   `Where-Object { $_ -match '^\d+$' }` 之后读 `$Matches[3]` → `$null.Trim()` 抛错，`Reason` 丢了
   （本轮 verdict 行 `thaw events ... reason=` 空就是这个）。修复：先把 `$Matches` 各组拷进局部变量再做过滤。
3. **`Write-ExNote` 不收空串**：verdict 文本里的空行逐行打印时报 `ParameterArgumentValidationError`。
   修复：打印时跳过空行。
4. **监听绑定竞态（时序，非缺陷也记）**：控制腿的 `cmd notification post`（03:04:07）落在通知监听
   尚未绑定完成的窗口内——事件进了初始 snapshot 而**没有** `posted` 日志行（`logcat-rearcue.txt` 里
   ctl-c1 之后直接是 cancel 行），配对因此错位（ctl-c1 抢走了 c2 的交付行，本轮 `control pairs : 1`
   是假配对）。修复：时间线开跑前强制 `disallow_listener/allow_listener` 重绑并要求
   `listener connected active=` 行出现。

本轮**仍然有效**的设备事实（可作旁证、不作判定）：

- `FZ uid = 10336 reason =tobg success !`（03:04:17.178）→ `THAW uid = 10336 pid = [ 4470 ] reason : Activity Start caller : 1`（03:04:29.106）：tobg 冻结被 Activity Start 解冻，链路与票 #5 同口径。
- 背屏可见豁免三行：`setSubScreenUid uid=10336`（03:04:53.409 / 03:05:05.034）、`Uid 10336 was show on screen, skip it`（03:04:58.691）。
- on-rear 腿的真实交付在案：`pc-freeze-onrear-c2`（03:05:04.735）→ `posted com.android.shell`（03:05:05.032），+0.297s（本轮误配给 ctl-c1）。
- 本机当前构建已带票 #21 的应用侧 Wake Keep-alive（`wake-keep-alive start displayId=1 intervalMs=500` 常驻 tick），投屏期间日志有注入噪声但与时间线判定无关。

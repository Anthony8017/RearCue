# erratum — 20260923-032045-freeze-probe（监听门失效 + tag 复用叠加轮，判定作废，样本不进结论）

判定误报 `FZ-NOT-REPRODUCED`（该轮实际也确实未冻——cgroup wire 修复后自证 `uid=0 pid=0` 全程，
greezer history 无 FZ 行；「未复现」本身是有效的**条件事实**，进 findings 的不稳定面记录，但判定行
因下述缺陷不作口径）。两个缺陷：

1. **`@(Wait-ExLog)` 空数组包装使监听门恒真**：`Wait-ExLog` 按 `,$hit` 约定返回，外面再包 `@()`
   把空结果变成「含空数组的单元素数组」，`.Count` 读成 1 ⇒ 门（要求 `listener connected active=`）
   从未真正等待——控制腿 c1 又落在重绑窗口里、无 `posted` 行、并抢走 c2 的交付（配对错位，
   `control pairs : 1` 是假配对，`onrear pairs : 0` 同因）。
2. **通知 tag 跨轮复用**：c1 的 tag `rearcue-fz-c1` 与 20260923-030402 轮同名 ⇒ 同 key 更新语义
   （票 #3）⇒ 不产生 Post 事件（当时误判为根因，034104 轮才挖出更深的 adb 拼参拆词根因）。

本轮**仍然有效**的设备事实（作旁证）：真实交付在案（`pc-freeze-onrear-c2` 03:21:52.256 →
`posted com.android.shell` 03:21:52.561，+0.3s）；15s 退后台未冻结（条件不稳定面样本）；
oom_score_adj 遥测自此轮进入 wire。归档不回改。

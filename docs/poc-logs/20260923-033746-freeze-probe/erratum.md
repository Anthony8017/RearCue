# erratum — 20260923-033746-freeze-probe（通知 tag 复用缺陷轮，判定作废，样本不进结论）

3 次控制探针 0 交付，但本轮的 `listener connected active=24`（03:37:58 / 03:38:06 各一次）证明监听
**是健康的**——真相是票 #3 已记过的坑：**`cmd notification post` 复用已有 tag = 同 key 更新 = 不产生
Post 事件**。本轮探针 tag（rearcue-fz-ctl1/2/3）与 20260923-033533 轮完全同名，全部命中「更新」语义；
20260923-032045 轮 c1 之谜的另一半同理（tag rearcue-fz-c1 复用 20260923-030402 轮）。

修复（下一轮生效）：所有实验通知 tag 带**运行级唯一件**（`rearcue-fz-<HHmmss>-c<n|n<n|r1>`），
事件窗口内绝不复用既有 key。归档不回改；修复后判定轮以无 erratum 的 `*-freeze-probe` session 为准。

至此四轮工具坑全部收口且互相印证：
- 030402：`$pid` 自动变量 + `$Matches` 覆写 + 监听首绑竞态（9~13s，票 #3 口径）
- 032045：`@(Wait-ExLog)` 空数组包装使门失效 + tag 复用（c1）+ tobg 冻结未复现（adj 线索已入 wire）
- 033533：重绑舞步压着 9~13s 重绑窗口跑，永远等不到事件行
- 033746：tag 复用 = 同 key 更新 = 无 Post 事件

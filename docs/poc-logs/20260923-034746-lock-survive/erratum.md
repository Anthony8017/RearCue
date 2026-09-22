# erratum -- `wake interval : 500ms` 行是假的（命令参数≠实际间隔）

本轮判定与采样全部真实（E13-INJECT-FAILED、Greeze FZ/THAW、rear +7s 离开 ON），但
`e13-lock-survive.txt` 的 `wake interval : 500ms (command parameter -WakeIntervalMs)` **不代表
应用实际跑的间隔**：`WAKE_INTERVAL` 调试动作是静默 no-op（tools/ex 发 `--ei`（int），接收端
`getLongExtra` 对 int extra 类型不匹配、返回默认值——本对比轮实测发现），应用实际跑的是
**默认 5000ms**（应用日志 `wake-keep-alive start displayId=1 intervalMs=5000`、tick 间隔 5.1s）。

因此本轮**不能当 500ms 对照用**（当时就是拿它当对照跑的，特此勘误）；真正的 500ms 对照是
票 #21 归档的 `20260923-001402-lock-survive`（intervalMs=500、126 条 tick、跨锁不停）与
`20260923-005525-wake-cost`（intervalMs=500、300s keep 腿全程 ON）。glue 已修
（`DebugCommandReceiver` int/long 都收，`20260923-035523-install` 重装后实测 `ms=4000` 真的
调得动），此后 `-WakeIntervalMs` 是真旋钮。

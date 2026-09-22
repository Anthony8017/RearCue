# erratum — 20260923-034104-freeze-probe（adb 拼参拆词缺陷轮，判定作废，样本不进结论）

3 次控制探针 0 交付、`listener connected active=23` 健康、`cmd notification post` 回执正常——本轮把
根因钉死了：**`adb shell` 会把 argv 用空格拼接成远端命令行**，`post -t 'RearCue ex' <tag> <text>` 到
设备上成了 `post -t RearCue ex <tag> <text>` ⇒ `-t` 只吃掉 `RearCue`、**TAG=`ex`**、TEXT=`<tag> <text>`。
`state.txt` 的通知键为证：`0|com.android.shell|2020|ex|2000`——历轮所有实验通知（含票 #21 的
`-t 'RearCue ex' 'rearcue-ex' ...` 同款写法）全部塌缩到同一个 key，彼此互为**更新**，而同 key 更新
不产生 Post 事件（票 #3）⇒ 探针永远静默。`active=23` 恒定（探针没建出新 key）是旁证。

修复（下一轮生效）：post 参数全部单 token——去掉 `-t` 标题旗（`post TAG TEXT`），tag 带运行级唯一件，
text 用下划线；止损 return 前补撤通知（防遗留 key 成为下一轮的更新陷阱）。归档不回改。

**工具坑五连的最终归因链**（互相咬合，缺一轮都到不了根因）：
1. 030402：`$pid` 自动变量 + `$Matches` 覆写（wire/解析失真）＋ 监听首绑竞态（9~13s）
2. 032045：`@(Wait-ExLog)` 包装陷阱使门失效 ＋ tag 跨轮复用 ＋ tobg 冻结未复现（adj 入 wire）
3. 033533：重绑舞步压着 9~13s 窗口跑
4. 033746：tag 跨轮复用 = 更新语义（当时误判为根因）
5. 034104：adb 空格拼参 ⇒ tag 恒为 `ex`（真根因；4 的结论降级为叠加因素）

# toggle diff 因果轮（票 #27 Q1，收口时补记）

判定：**DETECT-TRACKS-SWITCH**——ChatGPT 行开关 OFF 一次，UI 与 appops 同步翻转：
- UI：允许6→**允许5** / 禁止120→**禁止121**（`ui-after-toggle.xml` 的分段标题）
- appops diff（`appops-chatgpt-before.txt` vs `-after.txt`，全量对照）：**恰好** `MIUIOP(10008) allow→ignore`、
  `MIUIOP(10053) allow→ignore`，其余 op 一个没动（10021 等嫌疑排除）。

同轮 provider 只读探测：`content query --uri content://com.lbe.security.miui.autostartmgr[/autostart]` 均 `No result found.`。

状态恢复：本轮把 ChatGPT 置 OFF 后 UI 行移出可视区、当场没翻回去；**最终在 032215 轮以 `appops set com.openai.chatgpt
10008|10053 allow` 复原到 toggle 前的 allow/allow**（`appops-chatgpt-post-restore.txt`）。UI 分段的目视复核待手机解锁。
（教训：开关一翻行就重排，复原动作必须在同一步内完成，已固化进 16-autostart-probe.ps1 的 RESTORED 校验。）

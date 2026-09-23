# 跳转实测轮（票 #27 Q2，收口时补记）

判定：**JUMP-PASS（action 变体）**——`am start -a miui.intent.action.OP_AUTO_START -c android.intent.category.DEFAULT`
落 `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`，三证：
`jumpA-top.txt` 的 `topResumedActivity=...AutoStartManagementActivity`、`jumpA-focus.txt` 的 `mCurrentFocus=...AutoStartManagementActivity`、
`jumpA-ui.xml` 的页面 dump（标题「自启动管理」、`auto_start_list`、允许6/禁止120 分段）。

同轮页面 dump 还给了 ChatGPT 行 `checked=true`（当时在「允许」段）——后续 toggle diff（025010 轮）的起点。

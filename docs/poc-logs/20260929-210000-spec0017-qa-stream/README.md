# Agent 页问答流实机验收（spec 0017 / 票 #169）

- 日期：2026-09-29 21:24–21:33（CST）；设备 Xiaomi 17 Pro / adb `94250f9e`（背屏 displayId=1，904×572）
- 驱动脚本：[drive-acceptance.ps1](./drive-acceptance.ps1)（可重跑：`-OutDir <本目录> [-SkipInstall]`）
- 本轮结论：**代码路径全部通过（日志实证）；外观目检被环境挡住，需拔线复跑**（见文末「阻塞」）

## 判定表

| # | 条目（spec 0017） | 判定 | 证据 |
| --- | --- | --- | --- |
| A.1 | 问答流进渲染（提问与输出都到背屏渲染件） | **PASS** | logcat `agent-debug working turns=4` → `agent-mirror compose status=WORKING turns=4`（两次 compose 均带 4 条） |
| A.2 | 版式外观：提问泡右锚深灰、agent 左对齐无框、代码块等宽 | **BLOCKED** | 该步截图拍到的是**系统「正在通过 USB 充电」界面**（原生背屏抢回），非本应用画面——见「阻塞」 |
| B.1 | 正文档位小档生效 | **PASS（日志+截图）** | `debug mirror text size=SMALL`；`B1-size-small.png` |
| B.2 | 正文档位大档生效且**即时生效**（不重新投送） | **PASS（日志+截图）** | `debug mirror text size=LARGE`；`B2-size-large.png`；logcat 无投送重发 |
| C.1 | 上滑进入回看、屏上停住 | **PASS** | 「回看中注入新内容」前后截图哈希一致（见 C.2），说明停在回看态未跟随 |
| C.2 | **回看中屏上静止**（新内容不进屏） | **PASS** | `C2-paused.png` 与 `C3-paused-frozen.png` 逐像素一致（脚本比对 SHA256） |
| C.3 | 点 ↓ 接上最新 | **PASS** | `C4-resumed-latest.png`（与 C2/C3 不同，恢复了跟随） |
| D.1 | 通知页零变化 | **PASS** | `D1-notification-page.png`：图标 + 角标 3，与既有形态一致 |
| D.2 | **Detail View 零变化**（每行居中、标题正文整体居中、右距 8px） | **PASS** | `D2-detail-view.png`；结构证据：`DetailText.kt` 本次零改动、`readingViewport` 默认值仍是 8px（`DisplaySafeAreaTest` 判例钉死 `PxRect(304,8,896,564)`） |
| E.1 | 点会话标识行开**会话列表**（不是切内容页） | **BLOCKED** | 该步 Dashboard 不在屏（同 A.2），点按落在外层内容页表面上（`area=content-page`）；代码路径本身有锚 `area=agent-session-line` 可判 |
| E.2 | 标识行与链路状态点同排、位置未被下推 | **BLOCKED** | 同 A.2（截图非本应用画面） |

日志侧另有两项强证据（不受截图影响）：

- `content page wfa enter agent` → `content page crossfade done show=agent`：等确认插队照常切到 Agent 页（既有行为不回归）。
- `content page wfa exit notification` / `crossfade done show=notification`：等确认结束后回原页，内容页机制未被我改动影响。

## 阻塞：插着 USB 时原生背屏抢回

- 现象：`am broadcast` 全部成功、`投送确认：Dashboard 已在屏 displayId=1` 也打了，但 `screencap -d 1` 拍到的是系统的「正在通过 USB 充电 / 点按即可查看更多选项」界面。
- 这是项目里**已知的 Takeover 路径**（CONTEXT.md「Native Rear Screen」「Takeover」）：本机插 USB 时 HyperOS 的原生充电界面会占据背屏、把 Dashboard 顶掉；验收期间 adb 必须插线，于是「投上去 → 被顶掉」循环。
- 试过的缓解：`svc power stayon true`（防灭屏）无效；**该设置已在收尾恢复为原值**。
- 复跑方式（拔线后）：打开无线调试（`adb tcpip 5555` + `adb connect <手机IP>:5555`），拔线，再跑
  `powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 -OutDir <本目录> -SkipInstall`。
  脚本会自动重拍 A/B/C/E 各图；D 的两项与日志项不受影响。

## 现场踩到的坑（都写进脚本注释，供下次复用）

1. **HyperOS GreezerManager 冻进程**：缓存进程被冻时广播一律被丢（logcat 写
   `Greezer Denial ... need cached broadcast`），表现是「注入了但屏上没反应、日志也一条没有」。
   脚本每步开头先 `am start` 主界面把进程叫醒（`Wake-App`）。
2. **`adb shell` 的引号**：`--es turns a|b c` 会被拆 token、`|` 被当管道；手工加引号又被 `sh -c`
   重解析（`\`` 原样送进去触发命令替换）。最终改成 **base64 传参**（应用侧解码）。
3. **注入 working/idle 不会自动切内容页**（spec 0013 定案：只有等确认插队 / 内容消失兜底 / 链路恢复三种例外）。
   脚本改成「拍一张 → 点一下空白 → 再拍一张」按像素判页。
4. **快滑被当点按**：`input swipe` 用 320ms 会被识别成两次点按（页面直接翻页），改 700ms。
5. **脚本编码**：本机 Windows PowerShell 5.1 按 GBK 读无 BOM 的 .ps1，中文与 `@()` 会解析崩；
   脚本存成 **UTF-8 with BOM**。

## 本轮的代码改动（随本目录一起提交）

- `RearCueApp.parseDebugTurns`：Debug 问答流注入（base64 或明文 `u|提问;a|回答`）。
- `DebugCommandReceiver`：新增 `AGENT_STATE --es turns <base64>` 与 `MIRROR_TEXT_SIZE --es size <档>`
  两条调试入口（spec 0010 story 28 的 Debug Bypass 惯例）。
- **真机抓到的两个真 bug（已修）**：
  1. 会话标识行的点按热区没接住点按（另铺同高热区那版），改为「点按挂在可见的标识行本体上」；
  2. `AgentReadingText` 外层 `fillMaxSize` 的 Box 当了命中目标却不消费事件——点按穿过它落到外层
     内容页切换上，连正文留白的点按语义都被改写。改为在该层自己认领点按（正文留白=切内容页，
     文字本体仍由句内 clickable 先接）。

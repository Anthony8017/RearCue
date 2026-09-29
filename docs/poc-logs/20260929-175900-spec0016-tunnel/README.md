# Spec 0016 真隧道复测：cloudflared（票 #157 第二轮）

- 日期：2026-09-29 17:55–18:00（CST），机主在场
- 设备与链路：同一台 Xiaomi 17 Pro（adb `94250f9e`）；PC 桥 `node tools/bridge/bridge.mjs`（**默认 cloudflared quick tunnel**，`tools/bridge/bin/cloudflared.exe` 2026.9.3）+ 手机经蜂窝/Wi-Fi 走公网 HTTPS
- 构建：main（PR [#158](https://github.com/Anthony8017/RearCue/pull/158) 合并后，merge commit `226f0cd`）的 debug APK（手机端即上一轮验收装的同一个包）
- 隧道 URL 两次都由桥**自动 adb 推给手机**（免手工重配）：首轮 `https://nirvana-paperback-spatial-misc.trycloudflare.com`，拔桥后重启换 `https://fares-hawaii-tablet-plaintiff.trycloudflare.com`
- 证据：`shots/*.png`（背屏截屏）、`anchors.logcat`（logcat 关键锚摘录，TAG=RearCue）

## 判定表

| # | 条目 | 判定 | 证据 |
| --- | --- | --- | --- |
| 1 | 桥自动起隧道并把 URL 推给手机 | **PASS** | 桥日志 `隧道 URL: https://…trycloudflare.com` + `已自动推送隧道 URL 到手机（adb）`；手机 `bridge url set has=true` → `bridge start url=https://…` |
| 2 | 真隧道下事件流与在册快照到达 | **PASS** | `bridge event source=codex session=bridge:01a0ec84-… status=idle` / `source=claude … status=working`；`bridge snapshot in-roster=2` → `bridge snapshot reconcile in-roster=2 dropped=4 cleared=false` |
| 3 | 真隧道下背屏入口与列表 | **PASS** | 会话标识行用尾 4 位兜底显示（该会话无 workspace）：`shots/01-agent-page-tail4-entry.png`；点它 `rear-tap received area=agent-session-line` + `agent picker open`，列表含「自动」+ 三条真实会话（`941f` / `Ant_Nest · 203f` Codex / `Ant_Nest · 4d4d` Claude），选中态在 `941f`：`shots/02-picker-open-over-tunnel.png` |
| 4 | 真隧道下选中即锁 + 关闭 | **PASS** | `agent picker select bridge:01a0ec84-…203f` + `agent picker close select`；`shots/03-picked-over-tunnel.png` |
| 5 | 拔桥（隧道断）→ 锁在 | **PASS** | kill 桥后手机 `bridge down，退避重连`；状态转储仍 `sessionLock=Locked(bridge:01a0ec84-…203f)` |
| 6 | 重启桥（换新隧道 URL）→ 续看、不误清锁 | **PASS** | 新 URL 自动推送 → `bridge start url=https://fares-hawaii-tablet-plaintiff.trycloudflare.com` → `bridge snapshot in-roster=2 dropped=1 cleared=false`；状态转储 `sessionLock=Locked(…203f)` 不变 |
| 7 | 恢复后内容页归位方式 | **见下（既有约定，非本 spec 偏离）** | 断线期 Agent 理由消失 → 内容页按 spec 0013 兜底回通知页；重连后**不自动切回** Agent 页（spec 0013「恢复不自动切回」判例），点一下空白即回 Agent 页且仍是锁定会话。`shots/04-after-reconnect-fallback-notification.png` |
| 8 | 第二条 Codex CLI 会话 | **未跑（NOT RUN）** | 未新起 `codex` CLI 会话（会真跑一次模型调用、动到机主额度）；「选中另一条真实会话并切回」已由 Claude ↔ Codex 两条真实会话覆盖（见上一轮回环验收判定 13） |

## 结论

真隧道（cloudflared quick tunnel）下 spec 0016 的三条主链——**列表出现 → 选中即锁 → 拔桥保锁 / 重启续看**——与回环冒烟结果一致，未发现隧道特有的新问题。唯一需注意的行为是判定 7：断线兜底回通知页后不自动切回 Agent 页，这是 spec 0013 定下的内容页恢复口径，不是本 spec 的偏离。

## 环境清理

- 本轮桥进程（含 cloudflared 子进程）已停止；手机端 `BRIDGE_URL` 停在上次自动推送的隧道 URL（下次跑桥会自动覆盖）。
- `tools/bridge/bin/cloudflared.exe`（55MB，`.gitignore` 已忽略）为跑隧道重新下载。

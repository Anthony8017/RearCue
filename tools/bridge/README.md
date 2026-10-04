# PC 桥（ADR 0006 / ADR 0014 / 票 #116）

ZCode、Codex、Claude Desktop 与 DeepSeek Harness 共用的常驻采集进程：把会话事件归一为统一模型，
经 tunwg HTTPS 隧道送手机（Agent Mirror 唯一接入通道，ZCode 直连已退役）。

## 统一会话事件（唯一契约）

```json
{ "sessionId": "…", "source": "zcode|codex|claude|dsh",
  "status": "working|waiting|idle|error",
  "title": "…", "workspace": "…", "currentAction": "…", "latestReply": "…", "updatedAt": 1758000000000,
  "turns": [
    { "role": "user",      "text": "机主提问原文", "ts": 1758000000000 },
    { "role": "assistant", "text": "agent 输出原文", "ts": 1758000000001, "open": true }
  ] }
```

`source` 由 ZCode / Codex / Claude / DSH 适配器与 hooks 填充；`title` 可选，承载来源当前显示名（ZCode 标题、Codex 自动命名/人工改名）；`/inject` 与旧事件可缺省（手机侧解码为 null）。
`id` 与游标由桥分配；手机侧解码在 `:agent` 的 `BridgeEventCodec`（判例：`BridgeEventCodecTest`）。

### 来源在册 / 归档事实（spec 0023 / 票 #236）

Codex 的 rollout 没有原生归档事件，但本机 Codex 有真实文件生命周期：活跃
`~/.codex/sessions/**/rollout-*.jsonl` 移到 `~/.codex/archived_sessions/rollout-*.jsonl`
就是归档，反向移动就是取消归档。Claude Desktop 也有真实本地生命周期：
`local-agent-mode-sessions/<accountId>/<orgId>/<sessionId>.json` 中的 `sessionId` 与
`isArchived` 是权威记录，`isArchived:false/true/false` 分别产生 ACTIVE/ARCHIVED/UNARCHIVE；
坏 JSON、坏形状和文件缺失只产生 UNKNOWN。桥仍保留刻意最小的显式生命周期事实：

```json
{ "type": "membership", "session_id": "…", "membership": "ACTIVE|ARCHIVED|ABSENT",
  "generation": 3, "revision": 3 }
```

hook 输入严格只收 `ACTIVE|ARCHIVED|ABSENT` 表达来源生命周期，不接受 `PRESENT|UNKNOWN`；桥统一后为
`membership: PRESENT|ABSENT` ＋ `archiveState: ACTIVE|ARCHIVED|UNKNOWN`。`UNKNOWN` 只属于容错内部扫描器（坏 JSON、坏形状、记录缺失、DSH 只证明移除），不进入显式 hook 词表。
`ACTIVE` 是唯一回册正事实；`ARCHIVED` / `ABSENT` 是出册墓碑。`generation` 必填，
同代可用 `revision` 排序；旧代或旧序号不能覆盖新事实，出册后的活动也不能复活会话。
`task_complete`、Claude `Stop`、文件缺失、超时或无活动都不是归档事实。

ZCode 的 `session/list` 是持久化会话历史，实测会返回任务列表已归档的条目且 `archivedAt` 可整体缺省；因此归档真值取 `~/.zcode/v2/tasks-index.sqlite` 的任务表（任一未归档未删除行才在册），由 `zcode-task-index.mjs` 只读聚合、`zcode.mjs` 转成 `ARCHIVED` / `unarchive`。Codex 文件移动由 `adapters/codex.mjs` 按 800ms 轮询（2s 预算内）转成 `ARCHIVED` /
`unarchive` 事实，取消归档恢复适配器缓存的最后状态。Claude 的 `claude-membership.mjs`
按 1s 轮询扫描权威 `isArchived` JSON 记录；显式 membership hook 仍是兼容入口。

DSH 用官方生命周期映射到同一契约：`session/created` / `agent/created` → `ACTIVE`，
`session/disposed` → `ABSENT` + `source-removed`。移除事实会立即进入 `/events`，
不等重连快照；`/snapshot.memberships` 用于断线后的代数对账。

### 问答流 `turns`（spec 0017 / 票 #169）

`turns` 是**机主提问与 agent 输出按时间顺序**的问答流，由桥按会话持有（`adapters/turn-log.mjs`，
窗口 **20 条 / 16000 字**，超限从最旧丢），每次事件**整份发出**——手机端重渲染整段，
漏一帧下一帧就追上（自愈）。`open: true` 表示该条仍在增长（Claude `MessageDisplay` 的中间批、
ZCode 的流式增量），手机端据此做追加语义。

喂给桥的三种**增量补丁**（内部字段，不上线）：

| 补丁 | 语义 | 谁用 |
| --- | --- | --- |
| `userText` | 机主提问一条 | Codex rollout 的 user 行、Claude transcript 的纯文本 user 行 |
| `assistantText` | agent 一条**完整**输出 | 两个适配器的助手块、hooks 的 `last_assistant_message` |
| `assistantDelta` | agent 输出的**增量**（追加到末尾开放条） | Claude `MessageDisplay` 钩子、ZCode 行增量 |
| `noticeText` / `noticeDetail` | **通知行**一条（`kind: notice`，不是提问也不是回答） | DSH `session-notice`（子任务/后台任务/子 agent 消息，issue #307） |

`noticeText` 是背屏那一行（低强调灰字，如「后台任务状态更新 · pwsh-5 [status: completed]」），
`noticeDetail` 是原文，背屏展开可见；通知行**不进** `latestReply`、不推进「空闲·没阅」。

`latestReply` 保留为**派生字段**（取末尾一条助手输出）：未升级的手机端读它照旧能用；
只有提问、还没有回答时它为 `null`（不留上一轮的回答）。

## 接口

| 接口 | 语义 |
| --- | --- |
| `GET /events?since=<cursor>[&wait=ms]` | 长轮询（无新事件持有 ~25s；`wait=0` 立即返回） |
| `GET /snapshot` | 只读在册快照：`{"sessions":[{sessionId,source,title,workspace,status,updatedAt}]}`；空表返回空数组 |
| `POST /inject` | 灌一条会话事件（适配器/示例源/调试） |
| `POST /hooks/claude` · `POST /hooks/codex` | hooks 转发（部分补丁，缺字段由会话最新态回填） |
| `GET /codex/options` | Remote Codex Conversation 的可用项目与当前 provider 模型 |
| `POST /codex/conversations` | 新建 Codex 会话并发送第一条 prompt |
| `POST /codex/conversations/<id>/messages` · `/stop` · `/delete` | 追问、停止；只删失败且无正常回复的远程空会话 |
| `GET /health` | 存活探测 |

主动写面（`/action` 与 `/codex/*`）必须带 `Authorization: Bearer`。
Codex 控制面按回合持有单写入方：回合完成、停止或失败后立即关闭控制进程并释放写入权，
让 Codex 桌面端可继续；手机下一次追问时再重新接管。
凭据藏在 Bridge URL fragment 的 `token=...`，fragment 不会发给隧道服务端；界面与日志只显示 base URL。

### 桥地址飞书通知（票 #303）

桥第一次拿到可用地址、以及之后每次地址真正变化时，会自动打开飞书并通过「命命」bot 给机主发一条私聊。
消息正文**只有完整 Bridge URL**（含连接所需 fragment），方便直接复制；同址重复出现、访问 token 轮换都不重发。

飞书未安装时直接跳过；安装了但没启动则先启动再发送。找不到 `lark-cli`、飞书启动失败或发送失败都不阻塞桥，
不排队补发，下一次地址真正变化时再尝试。去重状态默认写 `bridge.feishu-url`（只存脱敏 URL）；
隔离实测用 `BRIDGE_FEISHU_NOTIFY=0` 关闭真实通知，`BRIDGE_FEISHU_STATE_FILE` 可改状态文件。

## 在册快照
`GET /snapshot` 从进程当前 `latestBySession` 投影每个会话的键与最小字段；不含正文、不改游标，
空表返回 `{"sessions":[]}`。它供链路重连后的在册对账使用，不替代 `/events` 的增量事件流。

## 使用

```powershell
# 本机跑桥（无隧道，LAN/adb reverse 调试）
powershell -File tools/bridge/start.ps1 -NoTunnel

# 桥 + 隧道（默认 cloudflared quick tunnel）；-Demo 加示例事件源
powershell -File tools/bridge/start.ps1 -Demo

# 常驻自启（计划任务 RearCueBridge：登录拉起 + 启动器 30s 重来 3 次 + 落 bridge.log）
powershell -File tools/bridge/enable-autostart.ps1   # 注销：disable-autostart.ps1
Start-ScheduledTask -TaskName RearCueBridge          # 立即拉起（不等下次登录）

# 体检（桥在不在 / 隧道通不通 / 有没有会话在册；在线 exit 0，否则 exit 1）
powershell -File tools/bridge/status.ps1

# 托盘与看门狗实测（隔离实例：假隧道 + 自动断言七项）
node tools/bridge/tray-check.mjs
```

## 托盘图标（票 #171）

桥没有窗口，任务栏托盘图标是它唯一的人机界面：

- **图标在＝桥在**：桥一停图标就消失（托盘自己盯着父进程），不留孤儿图标。
- **点一下**＝把桥地址复制到剪贴板 + 弹一句气泡；**右键**＝复制桥地址 / 打开 bridge.log / 退出桥。
- **两态换色**：隧道就绪＝薄荷绿、隧道未就绪＝琥珀黄；浅色任务栏自动改用深色版。
- 状态与气泡靠两个文件交接（`tray-state.json` / `tray-event.jsonl`，默认落临时目录
  `%TEMP%\rearcue-bridge\`，可用 `RCU_TRAY_STATE` / `RCU_TRAY_EVENT` 覆盖）——
  桥写、托盘每秒读；托盘进程崩了不影响桥，桥死了托盘自退。
- 图标资产由 `node tools/bridge/make-icons.mjs` 画好写进同一临时目录（仓里不放资产文件）：
  拱桥极简标记，两种状态 + 浅色版，各含 16/20/24/32/48 五个尺寸。
  `--android` 另写手机端启动图标（自适应图标 + 五档密度回退位图）。

## 隧道看门狗（票 #171）

隧道进程退出（cloudflared 崩了、网络抖动）→ 退避 `BRIDGE_TUNNEL_RETRY_MS`（默认 5s）后
**自动重拉一条**，拿到新地址照旧推手机 + 托盘弹气泡（**地址真的变了才弹**，同址重启不吵）。
桥每 `BRIDGE_PROBE_MS`（默认 15s）探一次隧道 `/health`，结果写进托盘状态（图标两态的判据）。
`BRIDGE_NO_WATCHDOG=1` 可关掉重拉（只标未就绪、不重启隧道）。

## 手机侧配置

两条路（ADR 0006 补记）：

1. **电脑自动推送（主路径）**：桥拿到隧道 URL 后自动
   `adb shell am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.BRIDGE_URL --es url <URL>`
   ——无线调试连着就自动推；隧道换域名后自动覆盖（**手填也一样被覆盖**，手填是兜底不是接管）。
   **同时插着 USB 又连着无线调试时必须设 `ADB_SERIAL=<序列号>`**（`adb devices` 里那一列）：
   不给的话 adb 会 "more than one device" 直接失败，广播发不出去（2026-09-29 实测踩过）。
2. **手机手填（兜底）**：设置页 Agent 镜像区「PC 桥地址」输入框 →「保存并测试」先探一次 `/health`
   再落库；探不通照样存，只是如实说明是格式错、隧道没应（HTTP 码）还是连不上。
   下方那行同时说明地址来源：电脑推来的带推送时刻（人不在电脑边时，这是"地址是不是被换过"的判据）。

不带 `--es url` 的广播 = 清除配置。桥随「Agent Mirror」总开关一起起停。
adb 调试快捷通道：`adb reverse tcp:18787 tcp:18787` + URL 填 `http://127.0.0.1:18787`
（回环 cleartext 已在 network_security_config 放行；局域网直连需把该 IP 加进同一配置）。

> 前提：**cloudflared 得先备好**，否则桥起得来但没有隧道（托盘图标停在琥珀黄）。
> 下载 <https://github.com/cloudflare/cloudflared/releases> 放到 `tools/bridge/bin/cloudflared.exe`
> 或加进 PATH；tunwg 同理（`tools/bridge/bin/tunwg.exe`）。

## 隧道排障（2026-09-28 实测）

| 现象 | 处置 |
| --- | --- |
| 手机侧 `bridge http 530`（隧道不存在） | 先跑 `status.ps1`：本机通 = 只是隧道掉了——**等 5s**，看门狗会自动重拉一条并把新地址推给手机；本机也不通 = 桥进程没了，`Start-ScheduledTask -TaskName RearCueBridge` |
| 想手动重启桥（换一条隧道） | 见下方「正确重启」，别只用 `Stop-ScheduledTask` |
| 手机侧 Agent 页空、点空白没反应 | 桥离线时的正常表现：Agent 页「有内容」才可切，内容 = 桥在线且有会话在册 |
| `l.tunwg.com/add` connection refused | 默认公共 API 在本网络不可达（疑 DNS 污染/封锁） |
| `TUNWG_API=relay.hapi.run` → `403 Forbidden` | hapi 中继拒绝注册 peer（需 TUNWG_AUTH 或已关闭公开注册） |
| `TUNWG_RELAY=true` | 仅解决 UDP 被拦，不解决 API 端点不可达 |
| 备选 | ADR 0005 回退位：自建 tunwg 服务端（¥15/月 VPS）或改用其它 HTTPS 隧道；手机侧 URL 只是配置串，通道可换 |

### 正确重启

```powershell
Stop-ScheduledTask -TaskName RearCueBridge
# 光停任务不够：它只收掉启动器，node 还在跑、占着端口，下一次拉起会撞 EADDRINUSE 静默失败
Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
    Where-Object { $_.CommandLine -like "*bridge.mjs*" } | ForEach-Object { taskkill /F /PID $_.ProcessId }
Start-ScheduledTask -TaskName RearCueBridge
```

`disable-autostart.ps1` 里已经把这三步做全了（它就是为跑这条路径写的）。

## 测试

```bash
node --test tools/bridge/bridge.test.mjs tools/bridge/feishu-notify.test.mjs tools/bridge/make-icons.test.mjs tools/bridge/tray.test.mjs tools/bridge/adapters/adapters.test.mjs tools/bridge/adapters/claude-membership.test.mjs tools/bridge/adapters/source-membership.test.mjs tools/bridge/adapters/dsh/*.test.mjs
node tools/bridge/repo-check.mjs        # 脚本编码守卫：改过 .ps1 / .cmd 一定要跑
```

`repo-check.mjs` 钉的是两个"在 PowerShell 7 上完全看不出来"的坑，都是实测踩过的：

- 本目录 `.ps1` **必须带 UTF-8 BOM**：Windows PowerShell 5.1 对无 BOM 文件按 ANSI(GBK) 解码，
  含中文的脚本会被解成乱码、引号配对错位、整脚本语法失败（tray.ps1 / status.ps1 各踩一次，
  症状都是"体检恒报不通"）。它也顺带用 PowerShell 自己的解析器验一遍语法。
- `.cmd` **必须纯 ASCII**：cmd.exe 用 OEM 代码页解码批处理，UTF-8 中文注释会被拆成乱码命令，
  启动器直接失败（start-bridge.cmd 踩过）。

托盘与看门狗是**进程级**行为，单测覆盖不到，另有隔离实例实测：`node tools/bridge/tray-check.mjs`
（假隧道 + 独立端口 + 独立临时目录，断言七项：托盘在、就绪态带地址、杀隧道后自愈换新地址、
杀托盘后桥补拉图标回来且服务不断、杀桥后图标消失、注入补拉失败后桥先广播清除地址再自关
（退出码 75）、重试启动器 3 次重来耗尽后彻底停）。
隔离靠这几个注入缝（生产全都不设，默认行为分毫不差）：`BRIDGE_LOG` / `BRIDGE_SEQ_FILE` /
`BRIDGE_URL_FILE`（日志、序号、地址各写各的文件）、`BRIDGE_ADB_PUSH=0`（关自动推送，
假地址绝不推给真手机）、`BRIDGE_PROBE_BASE`（隧道探活改打指定地址——假隧道域名探不了活，
harness 自己起一个回 200 的口）、`RCU_TRAY_GUARD_MAX` / `RCU_TRAY_GUARD_INTERVAL_MS` /
`RCU_TRAY_BIN`（托盘监护的次数/间隔/程序，实测注入用）。进程查找按隔离实例的完整路径匹配，
不按裸脚本名，免得把生产托盘/桥一起算进去。

托盘只会有**一份**：新托盘起来时先把别的 `tray.ps1` 进程收掉（接管即清场），
且连续 3 拍问不通桥的端口就自退——桥被 `taskkill /F` 单独收掉时也不会留下一个显示失效地址的图标。
托盘没了桥会**自动补拉**（spec 0019-2）：3 次 × 10s（`RCU_TRAY_GUARD_MAX` /
`RCU_TRAY_GUARD_INTERVAL_MS` 可调），补回即清零失败计数——整夜里偶发退出每次都能自愈；
连续补不回（进程起不来或秒退）→ 先尽力广播「清除桥地址」（临终通知，复用不带 `--es url`
的既有广播语义，发不出不重试）再优雅自关（`reason=self-shutdown`）；手机侧靠 #187
超时判停兜底。

桥的生命周期节奏（spec 0019 / #189）：自关走**可重来退出码 75** → `start-bridge.cmd`
的重试引擎等 30s 重拉、最多 3 次（崩溃天然非零，同样重来）→ 耗尽启动器自己退出、彻底停；
干净停（退出码 0，Ctrl+C 信号／托盘右键）不重来。右键「退出桥」＝真的停：托盘先
`Stop-ScheduledTask`（收掉任务持有的启动器＝重来引擎）再兜底杀 node，手动退出不会被
30s 后复活。30s 在启动器层做而不是计划任务：Task Scheduler 拒绝 30s 粒度的重启间隔
（注册报 `Interval:PT30S` 越界，最小 PT1M，2026-09-30 实测），任务级失败重启已随之关闭
（`RCU_BRIDGE_RETRY_MS` 可覆盖重来间隔，测试加速用）。

## 卸载

```powershell
powershell -File tools/bridge/disable-autostart.ps1
```

它删计划任务、**并收掉正在跑的那一份**（node + 托盘 + cloudflared 整棵进程树）。
为什么必须自己收：计划任务的动作是 `cmd.exe /c start-bridge.cmd`，启动器里又套一层
`powershell -WindowStyle Hidden` 才起 node——`Stop-ScheduledTask` 只收得掉任务直接持有的启动器，
node 会变成孤儿：端口仍被占，下一次拉起撞 EADDRINUSE 静默失败，而体检还显示"在线"
（活着的是那个没人管的孤儿，2026-09-30 实测）。

## 适配器（#240 ZCode / #118 Codex / #119 Claude Desktop）

桥启动时自动挂载（`--no-zcode` / `--no-codex` / `--no-claude` 关闭）；坏行跳过、
解析失败不崩桥（非稳定接口的容错契约，见 ADR 0006）；冷启动把所有活跃会话文件补进当前在册集，
近 30 分钟修改的文件从头补读恢复当前态，较旧文件只补 idle 当前态、不重放历史正文，之后只跟增量
（每会话 400ms 尾随去抖）。

| 适配器 | 数据源 | 状态映射 |
| --- | --- | --- |
| `adapters/zcode.mjs` + `zcode-task-index.mjs` | `zcode app-server` 的 `session/list` + 只读任务索引 `~/.zcode/v2/tasks-index.sqlite` + `~/.zcode/cli/rollout/model-io-*.jsonl` | running→working；waiting/error 原样；`turn.steerQueued` 不算等待；完整历史由 `GET /history` 按需重建；任务表归档 → ARCHIVED、取消归档 → ACTIVE、缺行/删除 → source-removed |
| `adapters/codex.mjs` | tail 活跃 `~/.codex/sessions`，观察 `~/.codex/archived_sessions` 与只读 `~/.codex/.codex-global-state.json` | task_started / assistant 输出 / tool 调用 → working；task_complete → idle（+last_agent_message）；**user 行 → 提问**；目录移动 → ARCHIVED / unarchive；`electron-thread-read-state-v1` 的 thread id 移出未读集合 → 已阅 |
| `adapters/claude.mjs` | tail `~/.claude/projects/*/*.jsonl` + scan Claude Desktop `local-agent-mode-sessions/*/*/*.json` | 有增量 → working；idle/waiting 由 hooks 注入；**纯文本 user 行 → 提问**；`isArchived` → ACTIVE/ARCHIVED/UNARCHIVE，坏/缺记录 → UNKNOWN |
| `adapters/claude-hook.mjs` | Claude hooks stdin → `POST /hooks/claude`（恒 exit 0，桥不在不影响会话） | Stop → idle；Notification(permission\|needs_input) → waiting；**MessageDisplay → 逐批增量** |
| `/hooks/codex` | `~/.codex/scripts/notify-dispatch.ps1` 旁路转发（已写入，原文件 `.bak-20260928-bridge`） | agent-turn-complete → idle；approval\*/waiting\* → waiting |

- Claude hooks 注册（幂等、写前备份）：`node adapters/register-claude-hooks.mjs`
  （卸载 `--unregister`）——**Stop + Notification + MessageDisplay 三个钩子**，与既有
  claude-notify.ps1 并列。注册时按「同名 `claude-hook.mjs`」认领并**改写成本仓库的当前路径**
  （曾出现 settings.json 指向旧工作树、钩子跑过期副本的情况）。
- `MessageDisplay` 是 Claude 的**回合内**显示钩子（官方语义 display-only，不改 Claude 的存储
  与模型输入），按「新完成的整行」分批给 `delta` → 桥当增量写进 `turns`——Claude 侧因此是
  「一句一句冒」，而不是等整条写完。
- hooks 事件是**部分补丁**：缺的 workspace/reply 由桥的会话最新态回填，不丢正文。
- `bridge.seq` 持久化事件游标：桥重启续号，手机 `since` 游标不倒退（否则重启即失明）。
- Codex「等待批准」无 rollout 信号，靠 notify/hooks 端点；Claude Chat 标签无落盘，不镜像
  （ADR 0006）。
- **流式能力差异**（2026-09-29 实测）：ZCode 经桥为 model-io 只读增量/完整历史重建；Claude 走 `MessageDisplay` 逐批；
  Codex **消息级**——rollout 在回合中持续追加工具调用/思考摘要/token 计数，但助手正文只有
  整条落盘，`item/agentMessage/delta` 只存在于 app-server 协议（Windows 上那个进程是桌面程序
  的 stdio 子进程，外部观察者不可达）。

## DSH 只读插件（ADR 0010 / spec 0018-1；批准应答通道 spec 0018-6；**宿主侧重写票 #181**）

DeepSeek Harness（DSH）是**第四来源**，数据面是推送：DSH 内的只读插件在**宿主侧**订阅官方
会话事件（`session/created`、`session/disposed`、`agent/created`、`agent/status`、
`agent/error`、`session/event`、`agent/assistant-stream`、`approval/request`、
`user-questions/request`），POST 到本机桥 `POST /hooks/dsh`，桥按
`adapters/dsh/dsh-events.mjs` 的纯映射归一进统一会话事件（`source=dsh`）。
**只订阅官方事件＋只对 waterfall 作批准类应答**（见下），其余写面一概不碰——判例
`adapters/dsh/dsh-plugin.test.mjs` 锁死：`ctx` 只出现 `on`、无其余接口面、唯一外发是本机
回环两口（`/hooks/dsh` 转发＋`/action/pending` 取决定）。

> **为什么是宿主侧**（真机联调 2026-09-30 / #181）：客户端转发面 `ctx.remote.$on` 只转发
> `api-session/*` 等会话级事件，**对话正文不在其中**，参数还是 `(sessionId, running)` 这类
> 位置参数；宿主侧 `ctx.on` 才拿得到正文与真实形状，且不依赖窗口是否打开。

安装（把插件挂进 DSH；**版本门槛 DSH >= 0.2.0-rc.2**；装到**实际在跑的那个 profile**——
桌面应用是 `desktop`）：

```bash
dsh plugin --profile desktop add <repo>\tools\bridge\adapters\dsh
```

- 装载契约：包 export `apply(ctx, config)`（命名导出，cordis 插件），`dsh.bundle.patch`
  指向 `cordis.patch.yml`（插一行 `{id, name}`）；装载时机是 **DSH 启动时**——装完必须重启 DSH。
- 版本不够 / DSH 未开：来源不出现（会话列表里没有 DSH），其余功能照常。
- `--no-dsh` 关闭桥侧入口（不用 DSH 时不留这条面）。
- 状态词表归一：`agent/status` 的 `running`→working、`idle`→idle；`approval/request` 与
  `user-questions/request` 归 `waiting`（插队语义沿既有仲裁）。
- 正文面：`session/event` 的 `user/message` / `assistant/message` 取 `data.message.content`
  的**文本块**（`type === "text"`，推理/工具块不上屏，spec 0017 口径）；流式走
  `agent/assistant-stream` 的 `text-delta` 帧，本地按 250ms 合并后再转发；`session/created`
  时用 `session.deriveMessages()` 回放历史（条数与单条长度双封顶）。
- **`user/message` ≠ 机主提问**（issue #307，真机核对 2026-10-04）：DSH 的注入上下文与通知
  同样是 `role=user` 的消息，判据是 `message.source`——`kind:'user'`（本地输入与远端
  `user-rpc` 同档）与 `user-question-reply`（机主对提问的点选答复）→ `user-message`；
  `form:'notice'|'relay'`（`agent-message` / `subagent-settled` / `tool-jobs` /
  `model-selection`）→ `session-notice`（背屏一行低强调：收到任务消息 / 子任务状态更新 /
  后台任务状态更新 / 模型已切换，原文进 detail）；`form:'snapshot'|'catalog'|'instructions'`
  等系统注入（`runtime-context` / `skill-catalog` / `agent-instructions` / `skill-invocation` /
  `user-approval`…）→ **不上背屏**。`source` 整块缺失（老引擎形状）时按真实提问保留，
  仍过票 #248 的文本形状判据。历史回放与实时事件走**同一函数**，两路一致。
- `session-removed` 把会话摘出 `/snapshot`（手机重连对账据此清锁——「电脑端消失自动清锁」
  的桥侧前提）；`session-error` 归一为 `error` 状态（spec 0018-4 票 #174：出错提醒的来源信号）；
  `session/title` → `session-summary`（只更新摘要、不动状态）。
- **批准应答通道（spec 0018-6 票 #176 / #181）**：两条官方 waterfall 在等待机主期间，插件经
  `GET /action/pending?plugin=dsh` 轮询手机批准的一次性决定，取到即回**真机规范应答**——
  批准回 `'allowed-once'`/`'rejected'`（**不是** `{decision}` 对象），提问回
  `{answers:[{id, selected:[label]}]}`（选项没有 id，`selected` 装 label）；**取不到决定一律
  委派 `next()`**，绝不猜。等待窗 `DSH_APPROVE_WAIT_MS`（默认 30 分钟、0＝只试一次）。
  能力表 `dsh=approve` **随插件活性**：插件失联（心跳/转发停 90s）即收回，动作回 `unsupported`
  不悬挂。提问选项随事件进 `pendingOptions`（手机按选项点选作答）。插件挂载时打一次活性心跳，
  验收可只读 `/snapshot` 的 `capabilities.dsh` 确认装载成功。
- 判例：`adapters/dsh/*.test.mjs`（映射、应答、插件接线与只读红线、宿主装载预演）；
  真机联调证据见 `docs/poc-logs/20260930-*` 与票 #181。
- **一键禁用/启用**（DSH 起不来或不想挂插件时用）：双击
  `tools\bridge\dsh-plugin-disable.cmd` 从 desktop profile 摘除（`dsh-plugin-enable.cmd` 装回），
  或命令行 `powershell -File tools\bridge\dsh-plugin-toggle.ps1 -Action disable|enable|status`。
  脚本改的是 profile manifest（`%USERPROFILE%\.dsh\profiles\<profile>\package.json`，先备份
  `.rearcue-bak`，写出不带 BOM——带 BOM 会让 `dsh plugin` 报 SyntaxError）。
  等价的原生命令：`dsh plugin --profile desktop remove dsh-bridge-readonly`。


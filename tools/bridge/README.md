# PC 桥（ADR 0006 / 票 #116）

Codex 与 Claude Desktop 共用的常驻采集进程：把会话事件归一为统一模型，
经 tunwg HTTPS 隧道送手机（RearCue 的第二条接入通道，与 ZCode 直连并存）。

## 统一会话事件（唯一契约）

```json
{ "sessionId": "…", "source": "codex|claude",
  "status": "working|waiting|idle",
  "workspace": "…", "currentAction": "…", "latestReply": "…", "updatedAt": 1758000000000,
  "turns": [
    { "role": "user",      "text": "机主提问原文", "ts": 1758000000000 },
    { "role": "assistant", "text": "agent 输出原文", "ts": 1758000000001, "open": true }
  ] }
```

`source` 由 Codex / Claude 适配器与 hooks 填充；`/inject` 与旧事件可缺省（手机侧解码为 null）。
`id` 与游标由桥分配；手机侧解码在 `:agent` 的 `BridgeEventCodec`（判例：`BridgeEventCodecTest`）。

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

`latestReply` 保留为**派生字段**（取末尾一条助手输出）：未升级的手机端读它照旧能用；
只有提问、还没有回答时它为 `null`（不留上一轮的回答）。

## 接口

| 接口 | 语义 |
| --- | --- |
| `GET /events?since=<cursor>[&wait=ms]` | 长轮询（无新事件持有 ~25s；`wait=0` 立即返回） |
| `GET /snapshot` | 只读在册快照：`{"sessions":[{sessionId,source,workspace,status,updatedAt}]}`；空表返回空数组 |
| `POST /inject` | 灌一条会话事件（适配器/示例源/调试） |
| `POST /hooks/claude` · `POST /hooks/codex` | hooks 转发（部分补丁，缺字段由会话最新态回填） |
| `GET /health` | 存活探测 |

## 在册快照
`GET /snapshot` 从进程当前 `latestBySession` 投影每个会话的键与最小字段；不含正文、不改游标，
空表返回 `{"sessions":[]}`。它供链路重连后的在册对账使用，不替代 `/events` 的增量事件流。

## 使用

```powershell
# 本机跑桥（无隧道，LAN/adb reverse 调试）
powershell -File tools/bridge/start.ps1 -NoTunnel

# 桥 + 隧道（默认 cloudflared quick tunnel）；-Demo 加示例事件源
powershell -File tools/bridge/start.ps1 -Demo

# 常驻自启（计划任务 RearCueBridge：登录拉起 + 失败自动重启 + 落 bridge.log）
powershell -File tools/bridge/enable-autostart.ps1   # 注销：disable-autostart.ps1
Start-ScheduledTask -TaskName RearCueBridge          # 立即拉起（不等下次登录）

# 体检（桥在不在 / 隧道通不通 / 有没有会话在册；在线 exit 0，否则 exit 1）
powershell -File tools/bridge/status.ps1
```

- **隧道默认 cloudflared**（2026-09-28 实测大陆可达：PC、手机 Wi-Fi、手机蜂窝三路全通）：
  拿到 `https://<随机>.trycloudflare.com` 后落 `bridge.url` 并**自动 adb 推给手机**——
  quick tunnel 重启换 URL 也免手工重配（不在 adb 旁时需手动配一次）。
  `BRIDGE_TUNNEL=tunwg` 切回 tunwg（URL 稳定但公共实例当日实测被墙/403）。
- cloudflared / tunwg 二进制放 `bin/`（本目录已下载 cloudflared.exe 则自动用）：
  <https://github.com/cloudflare/cloudflared/releases> · <https://github.com/ntnj/tunwg/releases>
- 手机配置（debug 构建，通常由自动推送完成）：
  `adb shell am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.BRIDGE_URL --es url <URL>`
  （不带 `--es url` = 清除）。桥随「Agent Mirror」总开关一起起停。
- adb 调试快捷通道：`adb reverse tcp:18787 tcp:18787` + URL 填 `http://127.0.0.1:18787`
  （回环 cleartext 已在 network_security_config 放行；局域网直连需把该 IP 加进同一配置）。

## 隧道排障（2026-09-28 实测）

| 现象 | 处置 |
| --- | --- |
| 手机侧 `bridge http 530`（隧道不存在） | 先跑 `status.ps1`：本机通 = 只是隧道掉了，`Stop-ScheduledTask`+`Start-ScheduledTask` 重启桥（换新 URL 并自动推给手机）；本机也不通 = 桥进程没了，`Start-ScheduledTask` |
| 手机侧 Agent 页空、点空白没反应 | 桥离线时的正常表现：Agent 页「有内容」才可切，内容 = 桥在线且有会话在册 |
| `l.tunwg.com/add` connection refused | 默认公共 API 在本网络不可达（疑 DNS 污染/封锁） |
| `TUNWG_API=relay.hapi.run` → `403 Forbidden` | hapi 中继拒绝注册 peer（需 TUNWG_AUTH 或已关闭公开注册） |
| `TUNWG_RELAY=true` | 仅解决 UDP 被拦，不解决 API 端点不可达 |
| 备选 | ADR 0005 回退位：自建 tunwg 服务端（¥15/月 VPS）或改用其它 HTTPS 隧道；手机侧 URL 只是配置串，通道可换 |

## 测试

```bash
node --test tools/bridge/bridge.test.mjs tools/bridge/adapters/adapters.test.mjs
```

## 适配器（#118 Codex / #119 Claude Desktop）

桥启动时按目录存在性**自动挂载**（`--no-codex` / `--no-claude` 关闭）；坏行跳过、
解析失败不崩桥（非稳定接口的容错契约，见 ADR 0006）；近 30 分钟活跃的会话文件
从头补读恢复当前态，之后只跟增量（每会话 400ms 尾随去抖）。

| 适配器 | 数据源 | 状态映射 |
| --- | --- | --- |
| `adapters/codex.mjs` | tail `~/.codex/sessions/**/rollout-*.jsonl` | task_started / assistant 输出 / tool 调用 → working；task_complete → idle（+last_agent_message）；**user 行 → 提问** |
| `adapters/claude.mjs` | tail `~/.claude/projects/*/*.jsonl` | 有增量 → working；idle/waiting 由 hooks 注入；**纯文本 user 行 → 提问** |
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
- **流式能力差异**（2026-09-29 实测）：ZCode 原生逐字；Claude 走 `MessageDisplay` 逐批；
  Codex **消息级**——rollout 在回合中持续追加工具调用/思考摘要/token 计数，但助手正文只有
  整条落盘，`item/agentMessage/delta` 只存在于 app-server 协议（Windows 上那个进程是桌面程序
  的 stdio 子进程，外部观察者不可达）。

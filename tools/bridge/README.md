# PC 桥（ADR 0006 / 票 #116）

Codex 与 Claude Desktop 共用的常驻采集进程：把会话事件归一为统一模型，
经 tunwg HTTPS 隧道送手机（RearCue 的第二条接入通道，与 ZCode 直连并存）。

## 统一会话事件（唯一契约）

```json
{ "sessionId": "…", "status": "working|waiting|idle",
  "workspace": "…", "currentAction": "…", "latestReply": "…", "updatedAt": 1758000000000 }
```

`id` 与游标由桥分配；手机侧解码在 `:agent` 的 `BridgeEventCodec`（判例：`BridgeEventCodecTest`）。

## 接口

| 接口 | 语义 |
| --- | --- |
| `GET /events?since=<cursor>[&wait=ms]` | 长轮询（无新事件持有 ~25s；`wait=0` 立即返回） |
| `POST /inject` | 灌一条会话事件（适配器/示例源/调试） |
| `POST /hooks/claude` · `POST /hooks/codex` | hooks 转发（部分补丁，缺字段由会话最新态回填） |
| `GET /health` | 存活探测 |

## 使用

```powershell
# 本机跑桥（无隧道，LAN/adb reverse 调试）
powershell -File tools/bridge/start.ps1 -NoTunnel

# 桥 + tunwg 隧道（默认）；-Demo 加示例事件源（working→waiting→idle 循环）
powershell -File tools/bridge/start.ps1 -Demo

# 开机自启（HKCU Run，登录时拉起，无示例源）
powershell -File tools/bridge/enable-autostart.ps1   # 注销：disable-autostart.ps1
```

- 隧道 URL 落 `bridge.url`（tunwg 的 URL 由 key 派生、重启不变——手机配一次长期有效）。
- tunwg 二进制：放到 `bin/tunwg.exe`（本目录已下载则自动用）或 PATH；下载
  <https://github.com/ntnj/tunwg/releases>。
- 手机配置（debug 构建）：
  `adb shell am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a com.rearcue.poc.action.BRIDGE_URL --es url <URL>`
  （不带 `--es url` = 清除）。桥随「Agent Mirror」总开关一起起停。
- adb 调试快捷通道：`adb reverse tcp:18787 tcp:18787` + URL 填 `http://127.0.0.1:18787`
  （回环 cleartext 已在 network_security_config 放行；局域网直连需把该 IP 加进同一配置）。

## 隧道排障（2026-09-28 实测）

| 现象 | 处置 |
| --- | --- |
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
| `adapters/codex.mjs` | tail `~/.codex/sessions/**/rollout-*.jsonl` | task_started / assistant 输出 / tool 调用 → working；task_complete → idle（+last_agent_message） |
| `adapters/claude.mjs` | tail `~/.claude/projects/*/*.jsonl` | 有增量 → working；idle/waiting 由 hooks 注入 |
| `adapters/claude-hook.mjs` | Claude hooks stdin → `POST /hooks/claude`（恒 exit 0，桥不在不影响会话） | Stop → idle；Notification(permission\|needs_input) → waiting |
| `/hooks/codex` | `~/.codex/scripts/notify-dispatch.ps1` 旁路转发（已写入，原文件 `.bak-20260928-bridge`） | agent-turn-complete → idle；approval\*/waiting\* → waiting |

- Claude hooks 注册（幂等、写前备份）：`node adapters/register-claude-hooks.mjs`
  （卸载 `--unregister`）——Stop + Notification 两个钩子，与既有 claude-notify.ps1 并列。
- hooks 事件是**部分补丁**：缺的 workspace/reply 由桥的会话最新态回填，不丢正文。
- `bridge.seq` 持久化事件游标：桥重启续号，手机 `since` 游标不倒退（否则重启即失明）。
- Codex「等待批准」无 rollout 信号，靠 notify/hooks 端点；Claude Chat 标签无落盘，不镜像
  （ADR 0006）。

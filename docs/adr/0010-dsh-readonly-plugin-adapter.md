# 10. DSH 接入：桥内自写只读插件订阅官方事件流，不用全权遥控通道

日期：2026-09-30
状态：已接受（grilling 定案；spec 0018）

## 背景

2026-09-30 机主点名接入 DeepSeek Harness（DSH），并给出参考
dsh-remote-web-ui。调研结论（2026-09-30）：

- 该参考包**不是只读查看器**，而是「手机全权遥控 DSH」的远程访问插件——配对设备是
  完全控制凭据（聊天、会话、设置、凭据全可写），与「除批准外只读」（ADR 0009）冲突；
- DSH 会话数据的稳定入口是官方 SDK 事件流（会话增删、运行状态、活动、**审批请求**、
  **用户提问请求**）；运行时等待状态只在事件流里，不在会话落盘文件里。

候选与取舍：

| 候选 | 取舍 |
| --- | --- |
| **自写只读 host 插件订阅官方事件流** | 只订阅、不调写方法＝天然只读；随官方 SDK 版本门槛（≥ 0.2.0-rc.2）演进；**采纳** |
| 回环官方 /api ＋ 流式网关代理（参考包的做法） | 属内部接缝，跨版本易碎，还要仿认证兑换；不采纳 |
| 第三方 REST 插件（dsh-webapi） | 声明版本停在 0.1.x，与 0.2.0 线兼容未知；不采纳 |
| 会话 JSONL 尾随 | 只有历史、没有实时状态（审批/运行中拿不到）；仅作完整历史的可选叠加 |

## 决定

PC 桥（ADR 0006）内新增 DSH 适配器，经**自写只读 DSH 插件**订阅官方会话事件流
（含审批请求、用户提问请求），归一进桥的统一会话事件契约送手机；批准动作经插件的
应答通道回给官方审批/提问流（天然限定批准类应答）。dsh-remote-web-ui **只作协议参考，
不作通道**。DSH 与 Codex/Claude 共用一桥，电脑侧零新增程序安装。

## 后果

- 需要在电脑的 DSH 内装一个只读插件（一行命令），对机主无常驻新进程；
  DSH < 0.2.0-rc.2 时该来源不可用，其余功能不受牵连。
- DSH 官方 SDK 与事件流属非稳定接口：插件/适配器须扛版本变更（解析失败不崩桥、
  降级只读已知段），升级 DSH 后需按惯例做接缝复核。
- ZCode 已改走共享 PC 桥（ADR 0014）；一桥单点的后果沿 ADR 0006/0014。

## 修订 2026-09-30（票 #181：真机联调后订阅面由客户端改宿主侧）

决定不变（仍是「自写只读插件订阅官方事件流」），但**订阅面**按真机证据改了：

- 原实现挂在**客户端转发面**（`ctx.remote.$on`）。真机核对（DSH 0.2.0-rc.2 的
  `API_REMOTE_FORWARDED_EVENTS`）后发现：该集合只含 `api-session/*` 等**会话级**事件，
  **对话正文（`user/message`、`assistant/message`）根本不在其中**，且参数是
  `(sessionId, running:boolean)` 这类位置参数 → 背屏永远拿不到问答流内容。
- 改为**宿主侧** `ctx.on(...)`：事件名与载荷按 `api-catalog` 的签名对齐
  （`session/created`、`session/disposed`、`agent/created`、`agent/status`、`agent/error`、
  `session/event`、`agent/assistant-stream`、`approval/request`、`user-questions/request`），
  正文从 `session/event` 的 `data.message.content` 文本块取；流式增量走
  `agent/assistant-stream` 的 `text-delta` 帧（本地合并后再转发）。
- 应答值按真机形状：批准 waterfall 回 `'allowed-once' | 'rejected'`（**不是**
  `{decision}` 对象），提问 waterfall 回 `{answers:[{id, selected:[label]}]}`
  （选项没有 id，`selected` 装 label）；取不到决定一律委派 `next()`，绝不猜。
- 宿主侧同时去掉了客户端装载面（`exports["./client"]`／`dsh.client`／打包脚本）：
  不再依赖窗口是否打开，也不再在渲染器装载期产生报错。

## 修订 2026-10-04（在册与运行状态对账）

真机发现事件推送不能代表完整列表：桥晚于 DSH 启动时会漏掉早已存在的会话，
归档未必触发 `session/disposed`；`assistant/message` 后若没有收到 `agent/status idle`，
桥会一直显示工作中。桥增加只读本机工作区名册轮询，以 DSH 的工作区登记、归档名单和
会话投影 `turnBoundary` 对账；插件同时识别明确的 `turn/end`。正文和等待确认仍走官方事件流。
本机文件读取失败时保留上次状态，不凭坏文件推断归档。

# Codex 追问由现有桌面持有者接收

2026-10-06，机主确认二级对话页全部采用推荐，并授权 agent 自行处理技术细节。

## Context

Codex Desktop 0.160.0 在回合完成后仍持有会话 writer。隔离实测中，独立 app-server 的
`thread/resume` 即使目标空闲也返回 `already has an active writer`。直接恢复不能实现桌面
会话追问；独立客户端的 `thread/read` 还会把正在运行的回合重建为 interrupted，因此不能用
这个读结果判断电脑是否忙。

内部桌面 MCP pipe 要求执行器上下文，深链接仅填草稿，两者都不作为常驻桥的发送通道。
本机公开 CLI 提供 `codex queue`，app-server 对应 `thread/queue/add/list/delete`。
两个真实 app-server 配合本地 Responses stub 的隔离测试证明：原持有者会在约 7–10 秒内
收到跨进程投递并运行回合；生成的 userMessage.clientId 与 clientUserMessageId 一致。

## Decision

普通追问仍先尝试 `thread/resume`；仅 writer 冲突且镜像证明空闲时，用公开队列向原持有者
投递一次。requestId 作为 clientUserMessageId，看到对应 userMessage 后才回 accepted。
20 秒内未确认接收则撤销未消费项；确认 deleted=true 才报明确失败，否则回 unknown。
桥关闭时尽力撤销待投递项；不自动重新提交，不保留待以后发的手机消息。

忙闲只消费现有实时镜像事实。手机回答中禁发、允许写草稿，不提供用户消息排队功能。
队列不支持 model，因此桌面持有者路径仅接受自动模式，沿用桌面当前会话模型；明确模型
选择会被拒绝并提示改自动，不静默回退。

停止只作用于桥正在持有的回合。隔离实测另一客户端 turn/interrupt 返回 thread not found，
不会影响原持有者；电脑承载的回合提示在电脑端停止，不误报停止成功。

## Consequences

桌面空闲会话可继续追问，无需抢夺 writer 或借用 agent 身份。发送可能等待数秒，二级页
立即显示发送中、禁止连点、独立保存草稿。投递及取消失败仍存在未知态，必须查看后手动重试。
任务继续使用持有该会话的 Codex 原有权限与批准设置，投递不附加任何批准决定。

# 手机与电脑连接同一个 Codex 持有者

2026-10-16，手机新建任务停在等待批准时，Codex Desktop 因独立 app-server 的 writer 锁只能阅读。
另一个隔离复现证明，共用一个桥子进程时，别的会话尚未结束还会连带锁住已完成会话。
本次修复授权下，协同入口让桌面和桥通过本机 WebSocket 连接同一个 Codex 运行进程；
该进程独立于手机桥和桌面窗口的生命周期。这样 PC 能恢复同一轮、收到原始批准请求并作决定，
桥收到 `serverRequest/resolved` 后清除同一请求；不会通过终止任务或自动批准来交接。

这在协同模式下取代 ADR 0024 的独立 writer 顺序释放方案；未迁移的桌面会话仍兼容旧的队列投递。
新建手机任务要求已经配置协同入口，缺配置、坏配置或连接失败均明确报错，不另起独立 writer。
首次迁移需正常退出并从新入口打开 Codex，不热切或中断正在运行的桌面任务。启动入口不修改全局
provider、鉴权、权限或模型配置；`CODEX_APP_SERVER_WS_URL` 仅在它启动的桌面进程内生效。

WebSocket 入口是本机安装版本提供的实验接口，升级后需重跑真实协议检查；
官方说明见 [Codex App Server](https://learn.chatgpt.com/docs/app-server)。前端网关只监听
`127.0.0.1`，校验随机本机能力路径并拒绝浏览器 Origin，后端另用能力 token 验证；
两种能力只保存在本用户的运行配置里，不放进手机隧道、命令行或日志。

`node tools/bridge/adapters/codex-shared-check.mjs` 使用隔离 CODEX_HOME、临时工作区和本地 Responses stub，
验证真实批准中的 PC 恢复/决定、手机解除、并发会话不互锁，以及桥断开后 PC 继续可用；不调用真实模型。
自动检查覆盖协议与控制面，用户桌面窗口及手机操作仍需切换入口后实机验收。

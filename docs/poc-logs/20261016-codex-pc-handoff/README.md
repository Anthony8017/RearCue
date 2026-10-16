# Codex 手机与 PC 控制权修复验证

2026-10-16；集成主线基线 `da4fcd5`，保留 #392 的并发批准修复。

## 自动验证

- 桥全部 Node 测试：289 passed，0 failed；包括同一会话多个待批准请求逐项处理。
- `node tools/bridge/adapters/codex-shared-check.mjs`：隔离 CODEX_HOME 与本地 Responses stub；
  待批准时 PC 恢复同一线程并作决定、手机解除请求、其他等待会话不锁住已完成会话、桥断开保留 PC 内存线程均通过。
  原生通知可能早于 legacy rollout 元数据写入，因此先等待可读记录，再断言批准中 PC 恢复；没有绕开 writer 冲突断言。
- `node tools/bridge/adapters/codex-queue-check.mjs`：旧桌面持有者投递通过，确认准确 clientId，剩余队列项为 0。
- 最后补充共享进程退出后保留配置标记，避免桥退回独立 writer；真实协议检查和 6 项专项检查复跑通过。
- `node tools/bridge/repo-check.mjs`：14 个启动脚本编码与语法检查通过；`git diff --check` 通过。

## 部署与验收边界

生产目录已放入控制面、共享连接、运行进程和启动器文件；桌面 `Codex-RearCue.lnk` 已创建并核对，
指向生产目录的 `start-codex-shared.vbs`。旧控制面备份在 `C:\Users\13691\rearcue-prod\backups\20261016-1125-codex-shared`。

受控 Ctrl+C 重启生产桥的命令被自动审批拒绝，返回原因仅为 `blocked by policy`；
未执行该命令，也未改用其他停止方式。本轮后续只收口代码和合并，不再尝试重启。
核验时生产桥 PID 为 19804，`/health` 为 `ok`，用户配置目录尚无共享运行配置，因此不能称生产修复已激活。

启用仍需通过合法入口正常重启桥，并正常退出 Codex 后从 `Codex-RearCue` 入口打开，
再用手机新建任务、等待批准、PC 打开同一任务实际决定。未修改/安装 APK，未完成这组实机验收。

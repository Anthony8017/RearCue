## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues (`gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Grilling 提问边界

用户是技术小白。grilling/追问时只问产品、业务、使用场景、成本与取舍偏好；
禁止提问技术实现细节（架构、语言/库选型、API、部署、代码方案）。
技术问题由 agent 自行调研并给出带推荐的选项，用户只拍板其可感知的影响。
例外：用户主动发起技术讨论时不适用。

### PC 桥是常驻服务，清理时不得顺手停桥（#184）

`RearCueBridge` 计划任务（`tools/bridge/`）是生产常驻服务，手机靠它连电脑。
任何 agent 会话在做环境清理、进程收拾、重启验证时：
- 禁止 `Stop-ScheduledTask -TaskName RearCueBridge`、`taskkill`/`Stop-Process` 桥的 node 进程；
- 停桥只有两条合法路径：托盘右键「退出桥」，或跑 `tools/bridge/disable-autostart.ps1`；
- 部署新桥代码后的重启属于例外，但硬杀会在 bridge.log 留 `external-signal` 痕迹——
  重启前先托盘退出或写 stopflag，别让部署动作污染「谁在杀桥」的排查数据。

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

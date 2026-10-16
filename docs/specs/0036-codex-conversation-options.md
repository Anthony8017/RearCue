# Spec 0036：手机新建 Codex 对话的思考强度与权限

状态：2026-10-16，Q1–Q10 全部确认后，机主显式调用 implement-spec 授权实现。实施中，尚未完成验收或部署。规格工单：[#387](https://github.com/Anthony8017/RearCue/issues/387)。

术语采用 GLOSSARY.md 的 Remote Codex Conversation、Conversation Settings、Default Model、Manual Model、Remote Approval。

## 问题与当前实现

手机主屏的新建窗口已有项目、模型与任务文字，尚无思考强度和执行权限的选择或展示。PC 桥创建会话及发起后续回合时，固定使用受限权限；没有传递思考强度。电脑端的默认设置不能据此视为手机端已采用的设置。

相关入口为 `CodexCreateDialog.kt`、`CodexRemote.kt`、`bridge.mjs` 的 `/codex/options` 与 `/codex/conversations`，以及 `codex-control.mjs` 的 `startConversation` / `startTurn`。这些是当前源码观察，不代表新功能已通过设备验收。

## 已确认的产品决定

1. **Q1 权限范围**：手机提供电脑允许的全部执行权限档位，包括电脑允许时的“完全访问”。每档简要说明访问范围及是否需要确认。具体可选档位服从电脑的实际限制。
2. **Q2 生效范围**：新建时所选思考强度与权限是这条对话的持续设置，后续追问沿用；手机的此次改选不修改电脑默认值或其他对话。
3. **Q3 展示位置**：手机主屏的新建窗口直接显示模型、思考强度、权限的当前值，每项可点开选择。
4. **Q4 默认值与跨次记忆**：每次打开新建窗口，思考强度和权限读取所选项目在电脑端的新对话默认设置。手机本次改选只用于新建的这条对话，不记作下次新建的默认值。
5. **Q5 不兼容的改选**：换模型或项目后，已手选的思考强度或权限不再可用时，保留任务文字，标明需要重选的项；重选有效值后才允许创建。
6. **Q6 完全访问的交互**：在选项旁说明完全访问可修改电脑文件、执行命令等影响；机主明确选择后随创建生效，不增加额外确认弹窗。
7. **Q7 读取失败**：默认值读取失败时显示重试入口。仍能取得有效可选名单时，允许机主手动选齐缺失项后创建；可选名单也无法取得时，暂时禁止创建，不猜测档位。
8. **Q8 填写时电脑默认值变化**：保持手机当前显示的值，按机主眼前这组设置创建；创建时发现组合已失效则提示重选，不自动改用另一组设置。
9. **Q9 切换项目或模型**：未手选字段更新为对应默认值；已手选且仍有效的值保留，失效的按 Q5 要求重选。
10. **Q10 电脑修改本条对话**：机主后来在电脑明确修改本条对话的设置时，手机后续追问沿用修改后的实际设置，不把创建时的旧设置覆盖回去。

权限决定修订 ADR 0017 和 spec 0024 中“手机远程发起始终逐项批准”的固定约定，原因与边界见 ADR 0029。现有代码尚未完成这一修订。

## 规则归纳

| 时机 | 默认字段 | 手选字段 | 创建或追问 |
| --- | --- | --- | --- |
| 打开新建窗口 | 读取所选项目的电脑端新对话默认设置 | 本次窗口尚无手选 | 显示具体值，等待任务文字与有效设置齐备 |
| 主动切换项目或模型 | 更新为新上下文的默认值 | 仍有效则保留，失效则标明待重选 | 设置全部有效后才允许创建 |
| 电脑在填写期间修改默认值 | 保持手机当前显示值 | 保持手机当前显示值 | 按所见组合创建，失效则提示重选 |
| 默认值读取失败 | 显示读取失败与重试入口 | 可从有效名单手动补齐 | 名单有效且缺失项补齐后可创建；否则禁发 |
| 取消后重新打开新建窗口 | 重新读取电脑默认设置 | 上次改选不作为下次默认 | 任务文字沿用既有草稿规则 |
| 创建后继续追问 | 沿用本条对话的实际设置 | 创建时选择已成为本条对话设置 | 手机、电脑接续及重连不能将其改回固定受限档位 |
| 机主在电脑明确修改本条对话 | 采用该对话修改后的实际设置 | 创建时旧值不再作为覆盖依据 | 后续追问沿用最新实际设置 |

可选思考强度由当前模型支持的档位决定，权限由电脑对所选上下文允许的档位决定。显示某个默认值与机主明确手选是不同状态，沿用 ADR 0025 的区分；创建时实际采用的组合必须与窗口显示一致。

## 沿用的既有约束

本次围绕主屏新建窗口设计。连接、手机解锁、独立草稿、发送结果未知时不自动重发、请求归属与顺序写入交接，沿用 spec 0024。Remote Approval 开关控制批准入口，不等同于 Conversation Settings 中的执行权限。

修改模型、强度或权限不能证明上一次状态未知的请求未送达，也不能消除既有的重复创建提醒。未知状态继续按 spec 0024 处理。

## 验收标准

1. 手机主屏的新建窗口可直接看到模型、思考强度、执行权限的具体当前值，并分别展开选择。强度列表匹配当前模型；权限列表匹配电脑允许的档位，完全访问可选时可明确选中并看到影响说明，创建时无额外权限确认弹窗。
2. 每次打开窗口，以所选项目的电脑端新对话默认设置为起点。手机手选并创建后，再次新建仍读取电脑默认值；此次手机改选不修改电脑默认设置或其他会话。
3. 切换项目或模型时，未手选项更新，仍有效的手选项保留；无效项明确提示重选，任务文字保留且创建按钮不可用，直至组合有效。
4. 填写期间电脑默认值变化，窗口显示及创建提交仍是机主所见的组合。创建前重新验证组合；失效时明确拒绝并保留文字和待重选提示，不静默换档。
5. 默认值读取失败时可重试；仍有有效名单时可手动选齐后创建；无有效名单时禁发。项目或模型切换期间，旧上下文的迟到响应不能更新当前上下文的显示或提交值。
6. 创建结果中的实际模型、思考强度及权限与窗口所见一致；首轮及后续追问均遵守该条对话的设置，允许档位与所需批准行为相符，不能在后续回合固定覆盖回受限档位。
7. 退出手机页面、重开手机应用、桥重连以及手机与电脑按回合接续，不改变本条对话已经生效的设置。机主在电脑明确改动后，手机下一次追问沿用新值，创建时缓存不能覆盖新值。
8. 断线、手机锁定、忙时禁发、草稿保留、未知状态不自动重发、重复创建提醒、回执归属与现有模型显示规则继续通过既有验收。变更设置后再次尝试，不能绕过上次未知请求的重复提醒。

## 实施与验证边界

- 创建前的默认值解析、可选名单、提交校验与实际采用值应一致，需覆盖项目默认值、模型差异、电脑允许的权限范围及填写期间的变化。
- 对话设置的延续以 Codex 当前实际设置为依据。应验证手机追问、桌面接续、重连及电脑明确改动，避免把“界面显示正确”或“创建请求包含字段”当成已经生效。
- 实现阶段补充有意义的桥契约与手机状态测试，覆盖以上默认、失效、读取失败及接续场景。构建通过与静态检查不能代替真实手机创建及电脑接续的验收；未验证项需明确保留。
- 生产 PC 桥的部署或重启遵守 AGENTS.md 的常驻服务规则，使用托盘退出或 stopflag 等受控退出方式。

## 完成边界

产品问题已收齐，已获实施授权。当前文档记录不表示代码、APK、手机或生产 PC 桥已完成验收或更新。

## 实现工单与测试边界

集成分支为 `codex/mobile-codex-options`，审查基点为 `46e8048222bb5560f12969e82d0aba266f32f740`。各实现工单使用独立工作区，按依赖并入集成分支。

| 工单 | 交付 | 前置 |
| --- | --- | --- |
| [#388](https://github.com/Anthony8017/RearCue/issues/388) | 桥端项目默认值、模型支持档位及允许权限名单，固定共享 JSON 契约 | 无 |
| [#389](https://github.com/Anthony8017/RearCue/issues/389) | 手机选项状态、新建窗口和创建请求 | 共享契约已固定，可与 #388 并行 |
| [#390](https://github.com/Anthony8017/RearCue/issues/390) | 创建时采用所见组合、后续回合沿用最新实际设置 | #388 |
| [#391](https://github.com/Anthony8017/RearCue/issues/391) | 联调回归、双轴审查与验收记录 | #389、#390 |

TDD 的测试边界沿用本规格已确认的桥契约与手机状态：桥的 HTTP / app-server 请求与响应验证可见选项及实际提交；手机的选项状态和请求 JSON 验证手选保留、失效禁发、读取失败与迟到响应。前者不证明真实 provider 或手机部署，后者不以布局快照代替真机界面验收。

## 共享协议附录：选项读取 v1（#388）

适用于 #388 / #389 / #390。

固定于 2026-10-16。GET `/codex/options?projectId=...&model=...`，保持原 `projects` / `models`；query 均可省略。项目省略时读取全局上下文，手机选定项目后必须重读。`model` 为实际 model slug（保留 provider 前缀），不能用显示名。

```json
{
  "ok": true,
  "projects": [{"id":"p1","name":"RearCue","roots":[{"path":"C:\\work\\RearCue"}]}],
  "models": [{"id":"model-a","displayName":"Model A","isDefault":true}],
  "context": {"projectId":"p1","workspace":"C:\\work\\RearCue","model":"model-a"},
  "defaults": {"model":"model-a","effort":"high","permission":"full-access"},
  "efforts": [{"id":"low","description":"Fast"},{"id":"high","description":"Thorough"}],
  "permissions": [{"id":"full-access","label":"完全访问","description":"可修改电脑文件和执行命令，无需逐项确认。","settings":{"permissions":":danger-full-access","approvalPolicy":"never","approvalsReviewer":"user"}}],
  "errors": []
}
```

- `context` 回显此次解析的项目、该项目第一根目录和实际选项模型。模型 query 缺省时取 `defaults.model`；默认模型未知时为 null。客户端必须同时按项目、模型和请求代次拒绝迟到响应。
- `defaults.model` 始终是电脑在该项目的新建默认模型；即使 query 手选另一模型也不改它。`defaults.effort` 是 `context.model` 下的新建默认强度（项目保存值仍受支持则采用，否则采用该模型明确声明的默认值）。`defaults.permission` 是 `permissions[].id`。三个值均可为 null，表示未能可靠读出，不能自行补猜。
- `efforts` 是当前模型声明的真实强度，id 原样保留（包括 `none`）。`permissions` 是电脑允许的选择；只含允许项。数组为空即不能选择该项，不注入内置候选。手机只有项目、模型、强度、权限全部有效时才可创建；有名单时可手选补齐 null 默认值。
- 权限 id：`read-only`、`auto`、`guardian-approvals`、`full-access`；命名 profile 用 `profile:<原 id>`；非等价的有效旧配置用 `custom`。只在系统来源确证且允许时出现，不是固定枚举名单。每项 `label` / `description` 用于展示范围和批准行为；`settings` 仅供桥创建控制使用，手机不解释。
- `settings` 使用当前 app-server 的 thread/start 字段：`permissions`、`approvalPolicy`、`approvalsReviewer`；命名 profile 可以只有 `permissions`。旧配置 `custom` 可使用 `sandbox`、上述批准字段和仅含 `sandbox_workspace_write` 的 `config`。创建 agent 按 id 从重新读取的名单取得 settings，不能信任手机传任意 settings。
- #389 创建 JSON 约定：新增 `effort`（efforts 中的 id）和 `permission`（permissions 中的 id），`projectId` / `model` / `prompt` / `requestId` 沿用。#390 创建时重读所选上下文并校验组合，采用手机明确提交的三项值，失效则拒绝并提示重选，不换成新默认值。#388 不修改创建或追问行为。
- `errors` 为 `{ "field": "defaults.model|defaults.effort|defaults.permission|projects|models|efforts|permissions", "code": "..." }[]`。代码为稳定的 `read-failed`、`unavailable` 或 `not-allowed`；不回传配置内容、路径外的原始异常或凭据。部分失败仍 HTTP 200 / `ok:true`，成功的名单继续保留，字段 null 与 errors 供重试提示使用；`ok` 不表示可直接创建。
- 明确传入不存在的项目／模型：HTTP 400，`{"ok":false,"receipt":"bad-request","error":"invalid-project"}` 或 `invalid-model`；无法读取项目名单而不能验证明确项目：HTTP 503，`{"ok":false,"receipt":"unavailable","error":"projects-unavailable"}`。无可用根目录的明确项目同 invalid-project。既有 codex-disabled / 桥鉴权边界沿用。
- 只读来源：按 cwd 的 `config/read`、分页 `model/list`、分页 `permissionProfile/list`、`configRequirements/read`，以及 `.codex-global-state.json` 的 `electron-persisted-atom-state` 中 local host 权限偏好。要求和 allowed 标记过滤名单；Desktop 有效 local 偏好优先于配置默认。任何读取不写电脑默认设置、不启动真实回合。

# Codex 背屏选项作答：只读可行性调查

日期：2026-10-06。范围：Spec 0031。
源码基准：生产 checkout `724896d`；当前安装包 `OpenAI.Codex 26.930.4958.0`。
第一阶段只读源码和通道枚举；第二阶段已只读连接 IPC、注册独立客户端、查询 owner 并取当前回合快照。未发送答复、操控真实会话、修改 Codex 程序或停止 PC 桥。

## 结论

普通 Desktop 的异步问题已有具体可实现候选；RearCue 尚未接入或实测。桥自己发起的同步问题可以补请求答复链，但不能代替普通 Desktop 的题目回写。两条链必须分别验证。

## 普通 Desktop 异步问题

- 本机存在 `\\.\pipe\codex-ipc`，仅通过只读枚举核实。
- 当前 `app.asar!/.vite/build/application-network-startup-BEAX-hka.js:32`：固定 Windows 管道，4 字节 UInt32LE 长度加 UTF-8 JSON 帧。通道存在不代表已验证连接权限。
- `app.asar!/.vite/build/bootstrap-BXPOZU-a.js:1121`：客户端通过 `initialize` 获取身份，`thread-owner-discovery` 查询真正会话拥有者，路由按目标客户端转发并确认 owner 能力。
- 同文件 `:598`（偏移 745088）：`thread-follower-steer-turn` 交给真正 owner 的 `manager.steerTurn`，候选方案不需要另起 app-server 接管 Desktop 的活动会话。
- `app-primary-e38a45a66e69.js:11–13`（偏移 504708/505371）：异步答复通过当前回合的 steering 消息发送结构化 question reply。
- `app-shared-e20a5fe9db04.js:497` 的 `pOn` 使用调用 ID 与题目索引构造题目 ID；`:532` 的 `Cmr` 只从实际用户消息或已接受的 steering 消息归一答案。`app-initial-146770bfc1f4.js:1521` 的 `iPt` 同步电脑端问题 UI 状态。

结构化答复含题目 ID、原题正文和答案，置于 `send_user_message_question_reply` 标记内。普通追加选项文字或批准决定不能冒充该答复。

桥账户连接与注册、owner discovery、只读回合快照已实测通过；真实答题与解除没有执行。此 IPC 属于私有兼容面，安装包升级须重新核对。

### 第二阶段实测与提交护栏

- `initialize` 可使用独立 clientType 注册；`thread-owner-discovery` 返回真正拥有者及 supportsUntrustedAppInput。
- `thread-stream-following-changed` 的只读订阅立即返回 conversationState；已取消订阅并断开。
- 当前历史可能为 canonical turnHistory，不能只读 turns 数组；须按 islands/entries 在 entitiesByKey 中查到尾部 inProgress 回合及其题目。
- 当前 follower steer 未暴露原子 expectedTurnId 校验；manager 的 cue 在 active_turn_mismatch 后提取实际新回合并自动重试。提交前快照检查不能严格阻止跨轮误投。
- 内部的 assertRequestCurrent 是不可经 JSON 传递的 JavaScript 回调；现有 follower handler 不读取它。不能通过多传一个字段假装已建立保护。
- 已核全部固定 follower 方法及 dynamic app-tools 的只读 tools/list。后者虽有 send_message_to_thread，但没有目标 expectedTurnId 或专用 question reply；其普通对话路径还可开新回合，不作为答题替代。
- 同步答复将解除整个 request，不能把 answers map 允许少键误说成逐题生效。可靠工程方案为逐题保存、整组发送，此项改变生效时机，已交给机主确认。

## 桥内同步问题

- `tools/bridge/adapters/codex-control.mjs:248–258`：桥自己启动 `codex app-server --stdio`。
- `:486–490`：当前 `requestUserInput` 仅产生 `waiting/inputRequests`，没有保存可答请求或专用答题 resolver；`:323–330` 的 resolver 只回批准。
- `tools/bridge/bridge.mjs:1434–1439`：Codex 的既有 `select` 动作被明确拒绝。
- 当前 Desktop 同步 follower 入口调用 `replyWithUserInputResponse`；答复按 question ID 映射，每项含答案数组。这与异步文本标记链不同。
- 现有桥缺少 `serverRequest/resolved` 生命周期处理。补链时需保留请求身份、所属回合和解除状态。

## 题型与有效性

| 项目 | 已确认 | 仍需验证 |
| --- | --- | --- |
| 无选项 | Desktop 可以文字回答，桥保留空选项 | 背屏按产品决定回退，不能凭空提供答案 |
| 异步单选 | 专属组件仅提供单个 selectedOptionId | 真实背屏提交与解除 |
| 多选 | 其他共享组件存在多选属性 | 不能宣称当前异步问题支持多选 |
| 多题 | ID 按调用与索引关联，按题归一答案 | 多题的桥回写 |
| 逐题提交 | 源码过滤空答、只更新已答题；当前 UI 通常最后一起发送 | 分次提交同一调用的不同题目 |
| 过期 | UI 核对创建回合与 inProgress | 发出前恰逢结束或换轮的竞态 |
| 电脑跳过 | 现有 rollout 无可靠即时信号 | 不能把 Skip 即时同步写成已支持 |

额外竞态：follower steer 参数没有独立 expectedTurnId，owner 会读取当前活动回合；必须验证旧题的答案不会在换轮时变成新回合普通输入。

## 实现前的最小验证

在隔离、具名测试会话分别验证 Desktop owner 路由与桥内同步请求；不以机主真实会话作测试对象。覆盖单题、两题只答一题、重复点击、电脑先回答、断线、回合结束与换轮。
成功必须有来源端接受及正确题目解除的证据；未发出、状态未知、过期和不支持应明确区分，不自动重发。

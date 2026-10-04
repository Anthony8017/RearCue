# Codex 用户提问等待状态：事实调查

调查日期：2026-10-04。仅做只读调查；未停止桥、启动另一个 Desktop、控制既有会话或发送测试消息。仓库没有既有 `docs/research/`，本文件集中保存调查结果。

## 结论

普通 Codex Desktop 的异步问题已有可读的本地创建记录和逐题回答记录，能够补齐「有问题未答」的提醒。`accepted: true` 只代表问题已提交，不能解除提醒。异步提问后 agent 仍会执行工具，不能把普通工作增量当作问题已答。追加安装包源码审计确认：可操作的异步问题提醒限于创建它的当前运行回合，完成、中断或换回合后解除；电脑端「跳过」主要是本地 UI 状态，rollout 观察无法保证同步。同步提问和普通 Desktop 手机回写尚未实测验证。

以上结论分别来自下述实测 E1–E5、源码 S1–S3；未知边界见末节。

## 本机实测

### E1：版本和样本范围

- Windows 安装包：`OpenAI.Codex 26.930.3930.0`；当前 rollout 的 `session_meta` 记录 `originator: "Codex Desktop"`、`source: "vscode"`、`cli_version: "0.160.0"`。不能仅凭 `source: "vscode"` 将这批样本当作独立 IDE extension 会话。
- 只读解析 `%USERPROFILE%\.codex\sessions` 与 `archived_sessions` 的 178 个 JSONL 文件，找到 6 个直接工具调用，全部为 `request_user_input_async`；没有 `request_user_input` 的实际调用样本。数量是调查时快照，不是全部平台支持范围。

证据：`Get-AppxPackage '*Codex*'` 的包元数据；`%USERPROFILE%\.codex\sessions\2026\10\04\rollout-2026-10-04T22-10-06-01a10740-2182-7d80-a244-7f8e4d6434f4.jsonl:1`；归档旧样本版本包括 `0.155.0-alpha.9.2` 和 `0.155.0-alpha.16`。

### E2：异步创建与立即返回

当前版本真实记录的结构如下；题目及答案内容已隐去：

```json
{"type":"response_item","payload":{"type":"function_call","name":"request_user_input_async","call_id":"<call-id>","arguments":"{\"questions\":[{\"title\":\"<题目>\",\"options\":[\"<选项>\"]}]}"}}
{"type":"event_msg","payload":{"type":"item_completed","thread_id":"<thread-id>","turn_id":"<turn-id>","item":{"type":"AgentMessage","id":"<call-id>","phase":"final_answer","delivery":"async","questions":[{"title":"<题目>","options":["<选项>"]}]}}}
{"type":"response_item","payload":{"type":"function_call_output","call_id":"<call-id>","output":"{\"accepted\":true}"}}
```

`arguments` 和 `questions.options` 的例子只表示字段关系；选项内部结构未在此展开，不能据此假定选项一定是字符串。实测 question 本身只有 `title`、`options`，不能要求它一定带同步工具常见的 `id`、`header`、`question` 字段。`AgentMessage.id` 等于创建工具的 `call_id`；工具返回与实际回答属于不同事件。

证据：上列当前 rollout 的第 227、228、230 行（创建 15:07:40.175 UTC、返回 15:07:41.197 UTC）；同文件第 548、549、551 行再次出现相同结构。两次创建分别只有一个题目；本次父会话的问题创建日志也有一次调用包含两个题目。

### E3：实际回答是新用户消息，能关联到单题

Desktop 实际回答落成 `response_item/message`、`role: "user"`，文本含：

```text
<send_user_message_question_reply>
[{"questionItemId":"[\"request_user_input_async\",\"<call-id>\",0]","question":"<题目>","answer":"<答案>"}]
</send_user_message_question_reply>
```

`questionItemId` 是 JSON 编码的字符串，内部数组依次为工具名、创建调用 ID、题目索引。因此一个问题组里可以按索引关联回答，不能仅凭出现任意 `user` 消息就宣布所有问题已答。

证据：当前 rollout 第 278 行；`%USERPROFILE%\.codex\archived_sessions\rollout-2026-10-04T22-47-52-01a10762-b58e-78c2-bd66-092f87098357.jsonl:68`；更旧的 `rollout-2026-09-19T13-55-51-01a0b831-177e-7cc1-8833-b131cb9f12a9_01a0b83c-3f2e-7bc3-9f56-86f52451faa7.jsonl:76` 也存在同一标记。三处实测均为单题已答，尚没有多题部分回答实测样本。

### E4：异步提问后继续工作

当前 rollout 第 227 行提问、第 230 行返回 `accepted`，第 234 行继续调用 `exec`，第 278 行才收到实际回答；提问至回答约 156 秒。中间存在大量工具和思考增量。故「有问题未答」与「正在工作」可以同时成立，不能从工作增量推断用户已答，也不能把异步问题说成整个 agent 必然已停下。

### E5：取消和回合终止的实测边界

归档 `rollout-2026-09-23T08-47-33-01a0cbbb-6cc6-74b2-aadb-7ab1a87c3f58.jsonl` 第 232–235 行创建异步问题并返回 `accepted`，第 243 行为 `turn_aborted`，没有对应的上述回答标记。本次样本没有提供一个可确认「该问题 UI 已取消/失效」的独立事件。

仅凭这一日志样本不能证明 UI 的后续行为。追加安装包源码审计 B1 已确认回合结束会隐藏当前提问面板及提醒，历史题目记录仍保留；应解除的是「当前可操作问题提醒」，不能伪造一条用户回答或题目记录删除。

## 当前仓库源码

- **S1：普通 Desktop 观察通道。** `tools/bridge/adapters/codex.mjs:40` 起解析 rollout；第 80–104 行把所有工具调用和结果都映射成 `working`，第 107–119 行将回合终止/完成映射成 `idle`；没有问题 pending 集合或逐题回答解析。这是目前漏掉提问等待的直接原因。
- **S2：冷启动限制。** `tools/bridge/adapters/codex.mjs:400` 仅对最近改写的文件从头重放；较旧文件从末尾读取并建立 `idle` 基线（第 411–418 行）。若沿用此策略，桥重启后可能漏掉早已创建但仍未回答的问题，不能声称现有冷启动恢复覆盖它们。
- **S3：手机 remote 控制通道不是 Desktop 观察通道。** `tools/bridge/adapters/codex-control.mjs:205` 使用 `codex app-server --stdio`，第 245 行创建独立子进程；第 300–315 行请求写入该子进程 stdin，第 472–484 行接收该进程的批准请求。现有代码没有普通 Desktop 的 async 问题回复入口；把新的请求处理器加进此进程，不会自动取得另一个 Desktop app-server 的既有 pending 请求。后半句是由进程/stdio 边界得出的推断。

## 官方协议：有清除事件，但须取得正确会话的事件流

[OpenAI App Server 官方文档](https://learn.chatgpt.com/docs/app-server#toolrequestuserinput) 说明：`item/tool/requestUserInput` 由客户端答复后，服务端发 `serverRequest/resolved`，参数为 `{threadId, requestId}`；回合开始、结束或中断清除 pending 请求时也发此通知。`autoResolutionMs` 可以为毫秒数或 `null`。这支持协议请求按明确 ID 解除的做法，但文档没有证明本机 `request_user_input_async` 的 `AgentMessage` 问题与这一 RPC pending 生命周期完全等价。

同页 [Events](https://learn.chatgpt.com/docs/app-server#events) 要求从活动 transport 读取通知。**推断：** 若今后能连接到承载目标请求的同一个 app-server，协议请求的解除可据明确事件处理；本次没有验证普通 Desktop 进程提供给 RearCue 的只读连接或 pending 快照，也没有尝试 resume/抢占/中断既有会话。

## 接入前应写入验收边界

下面是调查约束，不是产品决定：

1. 异步创建可识别；`accepted` 不能算回答；工作增量不能清除问题；实际回答按调用 ID 和题目索引关联。必须区别任务运行状态与未答问题状态。
2. 同步 `request_user_input` 只有工具名称/官方协议线索，没有本机真实样本。不能宣称同步创建、空答、取消、自动超时与同步输出结构已经验证。
3. 按追加源码证据 B1，创建回合的 `task_complete` / `turn_aborted` 或明确切换到新回合，可以结束该回合的可操作问题提醒；不得将其解释为用户回答。电脑端关闭/跳过、普通文本回答是否清题、重复题目、多题部分回答仍有未实测路径；不能用静默超时猜测用户操作。
4. 普通 Desktop 当前没有经本次验证的手机回写公开通道。已有 remote app-server 批准回复只针对其自己接收的请求；普通问题不能套用「同意/拒绝」的批准载荷。
5. 若首期仅做提醒，可以利用已确认的创建/回答/创建回合结束记录；仍须明确电脑端跳过与冷启动恢复的覆盖，不能将「最后一次观察到未答」表述为已经验证的实时 Desktop UI 真相。

## 追加审计：当前安装包的异步 UI 生命周期

只读解析已安装 `C:\Program Files\WindowsApps\OpenAI.Codex_26.930.3930.0_x64__2p2nqsd0c76g0\app\resources\app.asar`，没有解包改写、连接进程、启动会话或制造实际问题。下面是源码事实，区别于前面的真实日志实测；内部压缩名称仅用于复查，不能作为稳定 API。

### B1：完成、中断和新回合确实结束可操作提醒

`app.asar!/webview/assets/app-initial-74dc12f48352.js:4604` 的 `u$s` 监听 `turn/completed`，核对当前问题的 `turnId` 与完成回合一致后，调用 `a$s(..., "turn_completed")`；后者调用 `pPt`，将当前选中的异步提问面板状态置空。这里是明确的回合完成清理路径，初轮仅看日志未能证明的边界得到补充。

同文件第 4605 行 `function $8s` 的异步提问通知有效条件包含：

```js
n.lastSubmission == null && !n.isSkipped &&
n.turnId === creationTurnId && currentTurn.id === creationTurnId &&
currentTurn.status === "inProgress"
```

条件不成立则 `notifications.hide`。因此回合完成/中断（不再 `inProgress`）或当前回合 ID 更换后，旧题的可操作提醒结束；不能无限维持旧回合的绿点。历史题目 item 不必删除，也不能误说用户已答。

日志可关联这些事实：旧取消样本第 204 行 `task_started` 与第 243 行 `turn_aborted` 都有同一个 `turn_id`；10-04 已答归档样本第 2 行 `task_started` 与第 261 行 `task_complete` 也有同一个 `turn_id`。题目创建的 `event_msg/item_completed` 同时带 `turn_id`（E2）。**实施推断：** 按创建回合 ID 清理提醒有源码与日志的共同依据；要避免迟到的旧回合结束事件清掉新回合问题。

### B2：「关闭」与「跳过」主要是本地状态，没有已确认的撤回日志

- 同文件第 1521 行 `pPt` 只清掉当前选中的提问面板。第 4604 行 `a$s` 的关闭原因包括 `timeout`、`turn_completed`、`user`，另发一条分析事件；没有在这条路径调用会话 RPC 或写一条用户答复。因此「关闭面板」不能等价成「已答」。
- 真正跳过：`app.asar!/webview/assets/app-primary-bb5c3be1c98a.js` 的 `onSkip` 回调调用 `Wje` 和 `nge`；导入/导出映射表确认 `nge` 对应 `mPt`。`app-initial-74dc12f48352.js:1521` 的 `mPt` 只把问题 atom 的 `isSkipped` 设为 `true` 并隐藏该问题的 Desktop 通知。`Wje` 是 `i$s`，第 4604 行只记录 `QUESTION_SKIPPED` 分析事件。这两条路径没有观察到向会话发送撤回/回答的 RPC。
- `pPt` 的过期参数是提问面板的 UI deadline；`sPt` 创建时设置约 30 秒 deadline。不能把这个面板倒计时当作问题自动已答或可靠服务器取消。

**实施限制：** 当前查到的 Desktop 本地 atom 不在 rollout 中。不能承诺只读 tail rollout 可即时跟随电脑端「跳过」隐藏绿点；也不能把未找到单独撤回事件当成所有版本绝不存在撤回事件的证明。需要将这一同步差异写入首期边界，或另行获得明确、可验证的 Desktop UI 状态来源。

### B3：回答结构与回合边界互相印证

`app.asar!/webview/assets/app-shared-2d992d47c83d.js` 的 `fOn` 将题目 ID 编码为 `JSON.stringify(["request_user_input_async", item.id, index])`（字符偏移 3511302 附近）；`Smr` 解析上述答复标记并建立 `answersByQuestionItemId`（偏移 4774070 附近）。答复只有已接受的 `steeringUserMessage` 或实际 `userMessage` 才进入答案集合。

`app-primary-bb5c3be1c98a.js` 的发送答复代码（字符偏移 504707 附近）先验证 `turnId` 相同且回合 `status === "inProgress"`，再 `steerTurn` 发送结构化回答；回合更换/结束时不会继续向旧题提交该答复。这里进一步支持按创建回合限定提醒的技术边界；这是 Desktop 内部实现，不构成 RearCue 手机回写的公开授权/API。

### B4：本轮审计的最终可实施范围

可靠观察：创建、按题回答、创建回合完成/中断、新回合取代旧回合。保留限制：电脑端本地关闭/跳过即时同步、所有版本的撤回形态、同步问题的实际输出、冷启动旧未答题恢复。问题结束原因需要保留「已答」和「回合结束/替换」的区别。审计到此收口，未为了填满未知边界而操控生产会话。

### B5：补充成本核查——没有找到可以直接 tail 的可靠跳过信号

定点追踪 B2 的 atom 与 Desktop 通知 hide 链路，得到以下新增源码证据：

- `app-initial-74dc12f48352.js:1521` 将问题状态 `_S` 声明为 `aa(G, () => null, undefined, {owner: vPt})`。该模块的导入映射 `aa` 对应 `app-shared-2d992d47c83d.js` 的 `Wk`；后者及 `Vk`（第 294 行）通过 scope 的 `familyBindings` / `familyKeysByOwner` 等 `Map` 保存 reactive signal。在这条状态创建/设置路径中没有 `.codex-global-state`、Local Storage 或 IndexedDB 持久化动作。
- `app.asar!/.vite/build/main-C_jM0dPl.js:1249` 的通知代理 `show` 会记录 `forward show`，所以 Desktop 程序日志能看到问题通知创建；同类 `hide` 直接调用 `dismissByNotificationId` / `dismissByConversationId`，没有对应 `forward hide` 记录。
- 同 main 文件第 430 行 `dismissByNotificationId` 和 `removeNotification` 只关闭通知、删除内存 `Map`、调用移除回调，没有写隐藏日志。操作系统通知的 `close` / `failed` 事件也进入相同移除函数。因此一般通知消失不能证明用户点击了题目「跳过」，也不能从只有创建日志的日志流还原跳过。

**成本判断（推断）：** 清除 RearCue 自己的绿点很简单；难点在取得电脑端用户跳过的可靠事实。已确认的路径主要是 renderer 内存状态、内部通知服务调用与分析事件，尚未找到可直接读取的持久化字段或可 tail 的独立跳过日志。严格即时同步需额外适配内部事件/renderer 状态或 UI，并承担 Desktop 升级兼容成本。该判断只覆盖已追踪路径，不宣称所有版本和其他路径绝无可用信号，也没有实际操控 Desktop 来测试跳过。

### B6：再次有界调查——分析出口、IPC 和现有调试端点

用户授权再次查找后，仅做定点源码追踪与当前进程元数据读取，没有启动/重启、修改 Codex 包、控制 UI、触发问题或发送会话消息。结论仍是未找到稳定且合理成本的即时 Skip 观察入口，依据新增如下。

1. **分析事件能区别 Skip，但自身缺少题目关联。** `app-shared-2d992d47c83d.js:425` 将 `XJt` 定义为 `protobuf_analytics_events.v1.CodexRequestInputDismissed`；前述 `i$s` 发送 `QUESTION_SKIPPED`，载荷只有 `kind`、`dismissalReason`、`questionCount`、`hasUnsubmittedResponse`。`app-initial-74dc12f48352.js:4601` 的 `oPs/sPs` 只可能附加鉴权方法、窗口类型、build、preload/浏览器归属等 `codexMetadata`；这条路径没有增加 thread、turn、question ID。即使能观察该事件，也不能直接可靠关联多个会话中的具体问题。
2. **找到的产品事件调试出口仍在 renderer 内存。** 同文件第 4601 行 `UNs` 将调试事件存入 `ePs` 数组（保留最近 100 条）和 `k8` Map；`WNs` 通过 `QNs` Set 注册回调，`KNs` 唤醒这些回调。此实现没有独立本地文件日志或对外订阅端点。`Rz`（app-shared 第 425 行）仅向当前 scope 的产品 logger 转发；没有找到 Skip 专用的公开外部 IPC 订阅方法。
3. **当前实例没有现成 TCP 调试端点。** 只读 `Win32_Process` 核对安装包内的 `ChatGPT.exe` 主进程及 10 个子进程，没有 `--remote-debugging-port`、`--remote-debugging-pipe`、`--inspect` / `--inspect-brk` 参数；按这些进程 ID 查询 `Get-NetTCPConnection -State Listen` 无监听记录。这是本次运行快照，只排除已检查的 flags/TCP 路线，不证明所有未公开 named-pipe 或未来版本都不可用。
4. **程序日志只印证创建。** 对当日 `LocalCache\Local\Codex\Logs\2026\10\04` 定点查找 skip/dismiss/hide 关键词，仅找到题目通知 ID 中的 `user_input_async`，未找到已确认的题目跳过/隐藏日志；例如 `codex-desktop-157f064a-68a7-46d1-b87b-5716f7a12fb3-206520-t0-i1-131547-0.log:10008`。这一负面搜索与 B5 的 hide 源码一致，但不能推导为所有出口永远不存在。

**实施建议（工程判断）：** 即时读取 Skip 至少需要额外适配内部 renderer/IPC 或 UI，并解决 thread/turn/question 关联、多个窗口、升级兼容与失败回退；当前没有证据支持只增加一个日志匹配就能可靠实现。按用户授权的回退边界，首期可在电脑端 Skip 后保留已有提醒，等创建回合结束/中断或被新回合取代时解除。该策略不把 UI 关闭、操作系统通知消失或静默超时误判为 Skip，需在规格中明确其可感知延迟。

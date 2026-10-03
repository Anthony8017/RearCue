# Spec 0024：Remote Codex Conversation（issue #285）

状态：已与机主 grilling 收口（2026-10-03，frontier 空，末轮「确认」），待实现。
术语采用 CONTEXT.md 的 Remote Codex Conversation、Agent Mirror、Remote Approval、Agent Alert、Session Lock。
本 spec 将 ADR 0009 的「发 prompt 永不做」部分反转；任意按键、文件管理与任意电脑控制仍禁止。

## Problem Statement

Codex 经 ccswitch 切换 provider 后，外部远程新建会话可能把第三方模型配到 ChatGPT 执行账号上，
首轮即失败；RearCue 只读镜像只能看到失败结果，不能主动选择项目/可用模型、重试或收口失败空会话。

## Product Scope

- 单电脑、手机主屏、Codex 单来源；背屏继续只读与 Remote Approval。
- 会话列表顶部与 Agent 空态提供「新建 Codex 对话」。
- 支持新建、对所有 Codex 会话继续追问、立即停止；正常会话只在电脑端归档。
- 输入仅文字与粘贴；不做语音、图片、文件、成本预测、多电脑选择或离线队列。
- 项目默认最近使用项，只允许选择电脑上的可用项目。
- 模型默认「自动」；手动模型仅当前会话有效，只显示当前可用模型，中途不可用必须询问。
- 发送后进入实时会话；不抢占手动 Session Lock，自动档沿现有仲裁。
- 发起/停止要求手机已解锁；发起不预批准文件修改或命令执行。

## Failure And Lifecycle

- 断线禁止发送，不排队。
- 发送状态未知时绝不自动重试；提供查看与手动重试，重试前提示可能重复。
- 失败空会话保留「发起失败」状态，可换可用模型后重试或二次确认真删除。
- 删除仅适用于失败且无正常回复的会话；正常会话不得从手机删除/归档。
- App 退出不终止电脑任务；完成、失败、等待确认复用 Agent Alert，点通知直达会话。

## Security

- 公共隧道上的发起、追问、停止、删除和远程批准必须携带 PC 桥访问凭据。
- 访问凭据只证明 RearCue 端点可达，不替代手机解锁门槛。
- Codex 的文件/命令批准仍逐项发生；不得使用自动批准或危险旁路完成远程任务。

## Acceptance

1. provider/model 不匹配在创建前被阻止或由当前 provider 的模型列表解决。
2. 新任务创建后可在手机和电脑同一列表看到；失败空会话不再出现「手机有、电脑无」的不可解释状态。
3. 手动锁定、离线禁发、无自动重试、失败重试/删除和通知直达均按 Product Scope 生效。
4. 文件修改与命令执行仍产生批准请求，拒绝后任务继续但不执行该动作。


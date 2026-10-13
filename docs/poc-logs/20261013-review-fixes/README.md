# Spec 0034：两项审查修复

日期：2026-10-13

工作分支 `codex/spec0034-reviewfix` 基于集成分支 `bce4e4b`。主要代码提交 `0e77b7d`；最终检查前通过 `4fd141a` 合并集成分支 `cf51727`。

## Standards P2：首页摘要归纯投影

复用零 Android 依赖的 `AgentSurfaceProjection.mainSummary`，输出待处理会话数量和运行摘要。主页仅搬运事实与渲染结果；不再直接判断会话等待状态、去重会话或拼运行条件。

判例覆盖：等待确认与只读待答题都计入待处理；按会话键去重；Debug Session 参与同一口径；监听、投送通道或可用性提示异常时显示需处理。业务含义沿用机主已确认的首页摘要。

## Spec P2：成功读取后再消费剪贴板时间戳

时间戳消费收口到既有 `ClipboardBridgeImporter.inspect` 的读取入口，继续复用原地址识别、验证、保存和首次安装逻辑。`AppContainer.shouldReadBridgeClipboard` 及其前置写入已删除。

- 读取不可用或 SecurityException 不消费时间戳与首次机会；同时间戳后续可重试。
- 读取期间失焦或连接事实变化也不消费；获得焦点后可检查同时间戳。
- Android 适配层将实际空/非文本内容映射为成功的空内容；文本剪贴板返回 null 或读取到不同时间戳时映射为不可用。
- 真正读取成功才记录时间戳；成功读取后的重复焦点、状态刷新不再次读取和验证。首次验证失败仍保留原配置、不循环。
- 新时间戳会使旧联网验证失效，即使暂时读不到新内容；真实空/非文本内容也使旧候选失效。

测试 seam 沿用已确认的公开导入/读取行为与纯投影边界。读取回调是系统边界输入，观察验证次数、实际保存结果及失效结果；没有源码断言、私有字段检查或布局镜像测试。

## Red → Green

| 片段 | Red | Green |
| --- | --- | --- |
| 首页按待处理会话去重并包含只读待答题 | [1 项失败](01-pending-summary-red.txt) | [通过](01-pending-summary-green.txt) |
| 首页按监听、通道和可用性事实给运行摘要 | [1 项失败](02-health-summary-red.txt) | [通过](02-health-summary-green.txt) |
| 不可用/SecurityException 后仍处理同时间戳有效链接 | [1 项失败](03-read-unavailable-red.txt) | [通过](03-read-unavailable-green.txt) |
| 读取期间失焦后保留同时间戳机会 | [1 项失败](04-focus-read-red.txt) | [通过](04-focus-read-green.txt) |
| 新时间戳暂时读不到时，慢的旧验证不得保存 | [1 项失败](05-changed-read-red.txt) | [通过](05-changed-read-green.txt) |

另外补齐成功空内容使旧候选失效、成功读取后不循环，以及配置未加载/镜像关闭/已连接/无焦点时不读取、首次机会保留的回归。

## 最终验证

- [`:app:testDebugUnitTest :app:assembleDebug`](06-app-final.txt)：205 项 APP 单测，0 失败/错误；APK 构建成功。
- [测试数量](tests-summary.txt)来自最终 JUnit XML；较修复前新增 7 项：2 项摘要投影、5 项公开剪贴板读取行为。
- 完整 APP 判例包含原 13 项导入行为及 `BridgeConnectionVerifyTest`；本轮未修改桥代码。
- `git diff --check`：通过。

APK：`app/build/outputs/apk/debug/app-debug.apk`（未纳入版本控制）

SHA-256：`523DDEA20685A2BF4660C46884FBB93575E07A9B7475D8BD0A5AF569E81B84F6`

本轮未操作设备、生产桥、工单或 PR。原生系统焦点/剪贴板与重装恢复的实机验收仍未完成，单测与构建不代替 #375 的部署和设备证明。

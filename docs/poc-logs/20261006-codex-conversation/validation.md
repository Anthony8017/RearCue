# Codex 二级对话页与桌面追问验证

2026-10-06；机主确认全部采用推荐，后续不再追问产品选择。

## 已确认的原因

- 现有追问不显示发送中，存在连点和切会话后回执清错草稿的风险。
- 会话/模型菜单与按钮平级放入整块 Column，没有按钮局部锚点。
- Codex Desktop 0.160.0 在回合结束后仍持 writer；独立进程 resume 会失败。
- HTTP 500 + receipt=failed 被手机误归 unknown。

## 已执行

- `:agent:test`：160 项通过。
- `:app:testDebugUnitTest`：160 项通过。
- `:rear:testDebugUnitTest --tests '*AgentProcessPresentationTest'`：15 项通过。
- `node --test tools/bridge/adapters/codex-queue.test.mjs tools/bridge/adapters/codex-control.test.mjs tools/bridge/bridge.test.mjs`：65 项通过。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功。
- `node tools/bridge/adapters/codex-queue-check.mjs`：两个真实 app-server、独立 CODEX_HOME、纯本地 Responses stub。原 writer 接收追问；clientId 精确匹配；耗时 9777ms；队列剩余 0；退出码 0。
- 回归覆盖读历史失败仍撤销投递、未消费超时撤销、关闭期间不重新启动控制进程、明确模型不静默忽略、忙时不留队。

## 真机限制与部署状态

### 08:39 起的安装与真机验证

- 已连接正确机主手机：serial `94250f9e`、model `25098PN5AC`。
- 最终普通 APK 已安装，手机实际 base.apk SHA256 与发布包完全一致：`F0F74D3D4517B7FB87D7C2373F21939420A332CA362644615F63082EFBC90BF2`。
- 两项新建弹窗测试与八项二级对话页测试均通过：`OK (10 tests)`。首次运行的两处断言文案与实际既有文案不符，已纠正；没有改产品逻辑掩盖失败。
- 已经合法部署重启桥，使用 stopflag + 部署留痕 + disable/enable-autostart 路径；完整新地址及凭据已推手机，主页显示 PC 桥已连接。
- 实际新建诊断会话成功，完整回复 `REARCUE_E2E_READY` 进入历史。
- 实际桌面持有者收到追问并回复；发现“工作中事实早于只读历史持久化”的时序窗口可能造成未知回执。补充回归先失败，再修复为已消费项继续等待 clientId 对账。
- 修复后的真实桌面持有者验证返回 accepted、managedBy=desktop，耗时 1210ms，完整回复 `REARCUE_DESKTOP_CONFIRM_OK` 到达历史。桥回归增加至 66 项，全通过。
- 18:30 无线调试恢复，确认仍为机主手机。按 stopflag + 部署留痕 + disable/enable-autostart 路径部署最终 `8843e1a` 控制器，生产桥 PID 为 76192；自启已恢复。
- 完整新地址及凭据已推送，手机数据仓库中地址和凭据均匹配；主页显示 PC 桥已连接。再次核对手机实际 base.apk，哈希仍与正式 APK 一致。
- 最终生产桥经公网 HTTP 实测桌面追问：200、accepted、managedBy=desktop，耗时 8692ms；完整回复 `REARCUE_FINAL_HTTP_OK` 已进入历史。未重发任何旧的未知回执请求。
- 补充显式传入 `diagnosticThreadId` 才运行的 `CodexConversationLiveTest`，直接启动真实二级页，检查实际菜单、离页草稿、真实手机传输与桌面回执；普通测试运行会跳过。
- 实际手机检查通过会话/模型菜单选择与离页再进入后的草稿恢复，点击发送后桌面只收到一条诊断消息并回复 `REARCUE_PHONE_7af23341`。手机持久化回执为“已发送”、desktop-managed=true、unknown=false，草稿已清空。
- 真实页测试第一次在冷启动列表同步前超时，没有发送；延长同步等待后完成上述真实发送。最后的界面断言遇到页面焦点切到其他应用，改为核对持久化回执与真实历史。用 `verifyMarker` 只读续验原消息，结果 `OK (1 test)`、3.863s；没有重发原 prompt。
- 已归档本任务诊断会话，恢复已有通知监听与 MIUI appops；生产桥及自启保持运行，手机连接正常。自动跟进在收口后停用，PR 保持未合并。

只读续验命令（须保留真实运行产生的 marker；不会发送消息）：

```text
adb -s <机主当前地址> shell am instrument -w \
  -e class com.rearcue.poc.ui.CodexConversationLiveTest \
  -e diagnosticThreadId <本轮专用诊断会话> \
  -e verifyMarker <本轮收到的 REARCUE_PHONE_xxxxxxxx> \
  com.rearcue.poc.test/androidx.test.runner.AndroidJUnitRunner
```

### 之前的阻塞记录

早期独立测试 APK 被 HyperOS 拒绝安装，随后无线调试掉线。08:39 恢复后已经完成最终 APK 安装与十项真机测试；18:30 恢复后已发布最终桥补丁。旧缓存端口未用于安装，应用没有卸载，生产桥只在有留痕的部署重启中暂时退出。

明确范围：电脑持有的回合仍在电脑停止；手机承载的回合可从本页停止。桌面持有者的
模型选择由桌面管理，手机选择自动时投递，明确手动模型选择会提示改自动。

## 2026-10-07 合并与最终部署

机主授权收尾、合并 PR #325 并部署最新。合并前 Standards / Spec 复核补齐：

- 冷名册同步后重新优先恢复最近会话。
- 新建发送前持久化未知态，同稿重发须确认；离页后回执仍保存，新建目标在重入及晚到回执时恢复。
- 新建与追问的回执检查、草稿版本检查和写入均串行到主线程，防止后台回执误清新稿。
- 新建目标切换统一受恢复权限控制，不覆盖等待期间的手动选择或明确通知目标。

164 项 app 单元测试通过（新增 4 项恢复判断），66 项桥回归再次通过。普通 APK 与 AndroidTest APK 构建成功；新增 6 项 Compose 回归已编译，与原 10 项合计 16 项。设备离线，本轮新增回归尚待真机执行。

最终普通 APK SHA256：`46495E3F5B4EE4846BB17DB4BB0C774A17E3400B817DE5AF2BA91E21BF030091`。没有嵌入 instrumentation；需要覆盖安装本次 APK，之前手机已安装的 `F0F74D…` 包不包含本次收口修复。

部署包保存至本机 `RearCue-tools/deployments/20261007-codex-conversation-final/`，合并提交、生产快进部署与真机安装状态另记 `deployment-status.json`。现有生产桥运行代码已经与本 PR 一致，更新主线 checkout 不需要重启桥或换地址。无线调试恢复后仅连接 serial `94250f9e`，保留数据安装并完成 16 项真机回归。

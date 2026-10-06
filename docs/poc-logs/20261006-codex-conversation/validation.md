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
- 手机调试连接随后再次断开；APK 安装与真机测试已完成，最后的桥回执时序补丁尚待恢复连接后配套发布、推送地址并收口。

### 之前的阻塞记录

中间版普通 APK 安装成功，独立测试 APK 被 HyperOS 拒绝安装。随后无线调试掉线；重新发现的
缓存端口拒绝连接，刷新 adb 服务后没有可用设备。最终版普通 APK 与测试 APK 已构建；
Compose 点击/菜单/键盘测试已编译，尚未在真机执行。

最终版联动部署未完成。生产 RearCueBridge 保持运行，本轮没有重启或停止生产桥。
后续设备恢复后需安装最终 APK、执行 Compose 测试、同步桥代码并按既有合法部署路径重启，
再验证实际桌面会话追问与菜单位置。

明确范围：电脑持有的回合仍在电脑停止；手机承载的回合可从本页停止。桌面持有者的
模型选择由桌面管理，手机选择自动时投递，明确手动模型选择会提示改自动。

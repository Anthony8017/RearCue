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

中间版普通 APK 安装成功，独立测试 APK 被 HyperOS 拒绝安装。随后无线调试掉线；重新发现的
缓存端口拒绝连接，刷新 adb 服务后没有可用设备。最终版普通 APK 与测试 APK 已构建；
Compose 点击/菜单/键盘测试已编译，尚未在真机执行。

最终版联动部署未完成。生产 RearCueBridge 保持运行，本轮没有重启或停止生产桥。
后续设备恢复后需安装最终 APK、执行 Compose 测试、同步桥代码并按既有合法部署路径重启，
再验证实际桌面会话追问与菜单位置。

明确范围：电脑持有的回合仍在电脑停止；手机承载的回合可从本页停止。桌面持有者的
模型选择由桌面管理，手机选择自动时投递，明确手动模型选择会提示改自动。

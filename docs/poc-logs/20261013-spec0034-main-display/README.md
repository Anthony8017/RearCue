# Spec 0034 本地验证

日期：2026-10-13

- `:app:testDebugUnitTest`：191 项，0 失败/错误（含 7 项新判例）。
- `:app:assembleDebug`：成功，最终资源版本已重新构建。
- `node --test tools/bridge/bridge.test.mjs`：55 项，0 失败。
- `git diff --check`：通过。
- 覆盖完整可连接桥地址识别、身份与凭据验证、失败保留配置、去重跨重启、手动重试、关闭/已连接时不导入、配置变更使旧请求失效、新剪贴板优先。
- 桥测试使用独立随机端口和测试凭据；未触碰生产 `RearCueBridge`。
- 未安装手机、未切换生产桥、未做实机页面/焦点/剪贴板验收。

APK：`app/build/outputs/apk/debug/app-debug.apk`（构建输出，不纳入版本控制）
SHA-256：`42a7c3ffa9dcb1184ea4be0a43924a30548a7015a4706c2602ee1ac2b9a8b94a`

日志：

- [APP 单测与构建](app-build-results.txt)
- [最终 APK 构建](app-assembly-results.txt)
- [桥测试](bridge-test-results.txt)

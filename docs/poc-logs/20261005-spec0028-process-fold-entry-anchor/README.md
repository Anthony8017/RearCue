# Spec 0028 验收记录

日期：2026-10-05。设备：小米 17 Pro，USB `94250f9e`，背屏 904×572。

## 自动验证

- `gradlew test :app:assembleDebug`：全套 JVM 与构建通过。
- 最终 UI 修订后 `gradlew :rear:testDebugUnitTest :app:assembleDebug`：通过；新增过程/锚点判例 12 项，覆盖归组、作答、开合优先级、等待/失败、入场资格、短内容留白与固定阅读触底。
- 新增 BridgeEventCodec 判例：回合标识及结束事实解码、旧桥兼容。
- 桥全套 208 项通过；随后回合去重/迟到完成修订的定向 `node --test tools/bridge/adapters/turn-log.test.mjs tools/bridge/bridge.test.mjs` 77 项通过。
- `AgentProcessFoldUiTest`：三个独立 Compose 场景已写入并编译通过，覆盖开合不发正文回执、完成/失败的可见内容及固定阅读恢复。首轮运行无进展，停止测试进程后再次运行被设备拒绝安装测试 APK：`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；0 项执行，不能计作通过，也没有绕过安装限制。
- `git diff --check`：通过。

收尾复验：`gradlew test :app:assembleDebug :rear:compileDebugAndroidTestKotlin` 143 个任务成功；桥全套 215 项全部通过，最终日志已覆盖到本目录的 `build-tests.txt` 与 `bridge-tests.txt`。入场状态同步只在页面进场时执行，点 ↓ 清除锚点不再重新触发状态同步、抢回回看态。

## 实机证据

| 场景 | 证据 |
| --- | --- |
| 完成后过程默认收起，回答继续可见 | [默认折叠](01-default-fold.png) |
| 从空闲没阅列表点入，短机主消息首行位于标题渐隐下方 | [列表](02-picker.png)、[入场](03-entry-anchor.png) |
| 点开过程能看到思考与工具，重新入场保留人工展开 | [人工展开](04-manual-expand.png)、[重入](05-reentry-expand.png) |
| 最终版本在第二轮机主消息处固定阅读，新第三轮内容到达不改变可见画面 | [阅读起点](08-final-reentry.png)、[新输出后](09-final-new-output.png) |
| 点 ↓ 后接上完整第三轮输出，显示末行，↓ 消失 | [恢复跟随](10-final-resume.png) |

定位日志记录 `firstLine=98.0 readableTop=98`；短内容不再回到居中位置。08 与 09 图片 SHA256 相同。截图仅记录上述场景；停止/失败的边界由 JVM 与桥协议测试核验，独立 Compose 测试尚未执行。不把其他会话同时操作设备后的画面当成有效证据。

实机发现并修复：点 ↓ 后去掉旧锚点留白与重排时，像素滚动值可能减少；此前观察器会误判为人工回看。跟随滚动现在屏蔽该观察器，并等新布局测量后对齐到底部。

## 装机与恢复

- 最终功能 APK 构建后安装成功；同机其他任务的新增测试配置与文件保留。
- PC 桥部署先写 `bridge.log.stopflag` 与 `deployment-restart spec=0028 actor=codex` 记录，再恢复 `RearCueBridge`，没有把环境清理当作停生产桥理由。
- 生产隧道有短暂 502/530，后续健康检查恢复 200。隔离测试使用 `18788`、无来源适配器与虚构测试凭据，手机经 USB reverse 接入；不向真实 agent 发送任务或批准。
- 验收完成后停止隔离实例，撤销临时 reverse，移除 `spec0028-phone-qa` / `spec0028-isolated-qa` 测试会话，并恢复生产桥地址及原会话锁定。
- 恢复时生产健康检查正常，`RearCueBridge` 为 Running；生产名册确认原会话仍存在，`spec0028-phone-qa` 已移除。隔离实例退出后 `18788` 不再监听。

没有把同机并行装机期间的额外文件改动归为本次实现，也没有回退这些改动。

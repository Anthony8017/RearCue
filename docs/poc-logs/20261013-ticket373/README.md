# #373 主屏主页归位收口

日期：2026-10-13。工作分支：`codex/spec0034-ticket373`。

基线 `c7470a3` 与集成分支一致；开始修改前已接入 `a38f57f`，保留当前 main 的显示优先级和通知长按消除功能。

## 本轮修正

- 主页待处理数量改按 `attentionStatus` 的等待确认/待答事实计数，并按 sessionId 去重。Remote Approval 的能力与开关继续只控制批准操作，不再造成数量漏计。
- 设置跳转将 BringIntoViewRequester 绑定到地址配置内容，等该内容完成布局再请求显示；不再以包含全部提醒、语音与显示参数的整个长区块为目标。
- 默认字体保留两列四入口；系统大字体（fontScale > 1.3）改为四个纵向入口。实时电脑连接状态独占摘要宽度并固定预留两行，保留原状态文字。运行详情与连接配置的图标入口让出文字宽度。
- 沿用 RearCue 颜色、间距、图标与最小触控尺寸；主页和二级页继续消费平台 safeAreaPadding，没有替换系统字号、挖孔、圆角或手势安全区。
- 设置保留 `DisplayPrioritySection`、通知名单、连接与镜像、亮度、提醒、震动、语音、批准、充电、姿态及默认收起的开发者选项。

## 检查与证据

```powershell
$env:JAVA_HOME='C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1'
$env:ANDROID_HOME='C:\Users\13691\AppData\Local\RearCue-tools\android-sdk'
.\gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

- APP 的 191 项既有 JVM 行为测试通过；APK 构建成功。原始输出见 `gradle-app.txt`，最终代码确认见 `gradle-app-final.txt`。
- `git diff --check` 通过。
- 按 spec Testing Decisions，主页以真实 UI 验收为主，未新增仅镜像源码布局或内部调用的 JVM 测试。既有会话/批准行为检查继续运行。
- 静态导航核对：主页通知/Agent 入口打开 MainContentActivity，Codex 入口打开原 CodexConversationActivity，设置与摘要入口打开 SettingsActivity；二级页顶部返回调用 finish。设置不渲染会话列表或 Codex 入口。
- 静态去重核对：电脑实时状态只由主页的 bridgeStatusText 渲染；设置保留「地址测试通过」的地址验证文案；Agent 会话批准按钮嵌在对应列表行下，同一会话没有另起批准列表。

## 未完成的 AC

没有执行手机操作或生产部署，本轮没有设备截图、UI dump 或可点击性证据。因此以下验收仍未完成：

- 默认字体与大字体、挖孔/圆角/手势安全区下的实际入口可见可点。
- 正常、异常、未配置、镜像关闭和断线时实际一屏及文字可读性。
- 四个入口、二级返回、运行详情与连接配置锚点的设备交互。
- 所有设置操作的设备写回与进程重启后持久化，以及真实 Session Lock/Remote Approval 操作。

上述静态核对和构建结果不能替代 #373 的实机 AC。本票不据此关闭；设备演示及两端部署由 #375 接续记录。

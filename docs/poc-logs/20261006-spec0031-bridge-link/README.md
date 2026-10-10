# Spec 0031 验证记录

日期：2026-10-06

## 自动验证

```powershell
$env:JAVA_HOME = 'C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\Users\13691\AppData\Local\RearCue-tools\android-sdk'
.\gradlew.bat :agent:test :rear:testDebugUnitTest --tests com.rearcue.poc.rear.AgentMirrorParamsTest :app:assembleDebug --console=plain
```

- 构建成功：BUILD SUCCESSFUL，74 项任务执行。
- agent：155 项测试，0 失败。
- rear AgentMirrorParams：29 项测试，0 失败。
- 连续失败跨超时窗仍上报 DISCONNECTED，维持重连，恢复后自动回到 CONNECTED；主动停止才上报 DISABLED。
- 新增超时断线状态同样把背屏会话标识压成灰点，并保留长时间断线后灯带停止的既有行为。
- git diff --check 通过。

## 手机安装与核对

通过 adb install -r 安装本次 app-debug.apk，返回 Success。机主解锁后，使用明确的主屏物理 display ID 截图目检：

- 总览状态卡只显示监听服务、投送通道与通知数，没有重复的 PC 桥状态。
- Agent 镜像卡顶部先显示「PC 桥：已连接」，紧接桥地址、输入框、保存并测试、清除与地址来源说明；总开关位于这些内容下面。
- 桥状态没有混入会话名称或工作状态。

实机未操作保存、清除或总开关，也未人为制造生产连接故障；对应链路断线、超时、停止与恢复行为由隔离 JVM 测试覆盖。未设置与关闭的显示文案已结合配置、总开关及状态事实核对源码，但未逐项实机切换。

未通过操作生产 PC 桥制造断线；断线与恢复的验证使用 JVM 测试的隔离环回 HTTP 服务。

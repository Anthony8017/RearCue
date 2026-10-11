# 伪流式显现：合并与装机记录

日期：2026-10-11（Asia/Shanghai）。

- PR #328 已合并，主分支提交 `75309c4e222b80f40d3a76e83bda8c95540dbdaa`；从该提交执行 `:app:assembleDebug` 成功。合并前在当时最新主分支执行 `gradlew test :app:assembleDebug :rear:assembleDebugAndroidTest :app:assembleDebugAndroidTest` 成功。
- 设备：小米 17 Pro，Android 16，无线 ADB。安装前后 APK 签名不同，直接覆盖报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；先在本机私有备份原 APK、应用数据及权限状态，校验归档，再换签安装、恢复数据与权限。备份不入 Git。
- 已装 APK 从设备 `pm path` 拉回，SHA-256 与合并版构建产物完全相同：`455EC5EF0ABC865214C1A2C151EF6B43EABC43E7DDD0351ACC6E8000BAFBD340`。设备 `lastUpdateTime=2026-10-11 15:58:07`。
- 恢复后通知监听仍在授权列表，通知权限与 Shizuku API 权限为 granted；安装前的 appops 已按原状态复原，包括 MIUIOP 10020/10021 的 ignore。备份文件回读后，应用运行及桥自动推送会更新连接和 Codex 偏好，因此运行中的逐字节比对不是静态数据验收。
- PC 桥启动前计划任务处于 Ready、无桥进程；日志显示其在本次装机前因 SIGHUP 退出。本次手机改动不需重启桥，未对运行中的桥执行停桥操作。启动 `RearCueBridge` 计划任务后为 Running；本机及去掉 `bridge.url` 的 fragment 后的公网 `/health` 均返回 200，手机日志持续出现 Codex 桥事件。原始桥地址含访问令牌，未写入本记录。
- 定向 `AgentProcessFoldUiTest` 首项在设备上长时间未结束，3 项中 0 项完成；为避免继续占用手机而停止隔离测试进程。此项不能算通过。四源真实增量/整条到达对照、伪流式视觉节奏、背屏交互的实机验收尚未完成；不将构建与连接成功写成 UI 验收。

# 2026-09-28 Shade-visible Notification 验收

设备：Xiaomi 17 Pro / OS3.0.319.0.WBLCNXM
分支：`codex/shade-visible-0014`（PR #136；Spec 0014 / #135，切票 #129–#131）

## 已实机验证

- **进程重启收敛**：`am force-stop` 后重启应用，NLS 原始在册 5 条，SystemUI 可见 5 条；
  路由后 `shown=2`，`com.miui.misound` 的 3 条系统服务通知被移除。
- **手动划掉**：用 `cmd notification post` 发 `com.android.shell` 可见测试通知，展开主屏下拉栏后
  用 `input -d 0 swipe` 划掉该行；背屏日志随即 `removed com.android.shell`，
  下一次探测 `systemUiVisible=5 raw=5 shown=2`。
- **可见测试通知**：通知到达时 `com.android.shell` 进入 Icon Set；划掉后离开 Icon Set。
- **监听重连**：观察到 `listener disconnected` 后自动重连，可见集合保持收敛，无崩溃。
- **周期校准**：有可见内容时按约 15s 触发 `periodic` 探测；
  本机单次 SystemUI 查询约 200–300ms（含 Shizuku UserService 往返）。
- **JVM 判例**：`gradlew test` 全绿，覆盖 dump 解析、SummaryFilter 排除、数量不符 fail-open、
  隐藏/恢复/移除/快照路由。

## 设备拔出后未完成

- 手机冷重启后的收敛。
- Shizuku 真实掉线→恢复的设备链路；目前 fail-open 只有 JVM 判例，没有真机掉线证据。
- 静默/折叠通知的真机样本；解析与路由有判例，但本次没有构造出真机静默样本。
- 长时间耗电测量；当前只有周期频率和单次耗时估算。

## 已知边界

- Shizuku 在线时严格按 SystemUI 当前集合；不可用/解析失败时 fail-open，可能短暂多显示隐藏系统服务。
- 首次探测前和每次新到 key 的未知窗口约 200ms；之后由探测校正。
- SystemUI dump 目标与文本形状是 HyperOS 适配边界；格式变化时解析返回未知并 fail-open。

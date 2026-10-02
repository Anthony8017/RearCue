# 8. 锁屏可更新性：设备侧冻结唤醒（锁屏过滤器豁免已退役）

日期：2026-09-29
状态：**部分有效（2026-10-03 修订）**——冻结唤醒仍生效；腿 2「锁屏态过滤器豁免」随 Shade-visible 筛选层删除而退役。

## 背景

机主报：**完全锁屏**（主屏灭屏 + keyguard）时背屏 Dashboard 的通知图标不更新——飞书来了消息，
背屏还是旧图标。用 `tools/ex/23-locked-icon-update.ps1` 把报告变成可重复循环后，实测出**两个
互相独立**的断点（缺任一腿都复现不了修复后的行为）：

1. **GreezeManager 冻结**（平台侧）：灭屏后约 5s 应用进程被冻
   （`FZ uid = 10371 pid = [ ... ] reason : screen off | tobg`）。冻结期间 NotificationListenerService
   回调全部压在队列里：锁屏 62s 窗内 5 发探针**零投递**、背屏像素零变化；解冻那一刻（实测 3ms 内）
   一次性补投。背屏虽亮、Dashboard 也在屏上，但画的是**冻住的那一帧**。
2. **锁屏态过滤器被误判**（本应用侧）：SystemUI 在 keyguard 锁定时给用户通知条目加
   `filter=KeyguardCoordinator`。我们的 Shade-visible 解析把 `filter=` 一律当「下拉栏看不到」，
   于是通知刚上屏约 300ms 就被自己的可见性链删掉（`posted <pkg>` → 330ms → `removed <pkg>`）。
   而 CONTEXT 对 Shade-visible 的定义本就是「**解锁状态下**，系统下拉通知栏实际会列出的通知」。

## 决定

**腿 1：冻结唤醒（Freeze Thaw Nudge）下放到设备侧保活循环。**
应用被冻住时喊不动自己，所以由 shell uid 的 Wake Keep-alive 循环（本就不吃这道冻结，ADR 0003）
每拍多查一步：读该 pid 的 `cgroup.freeze`，为 1 就 `am start` 一个无 UI 空转页
（`com.rearcue.poc/.ThawNudgeActivity`：`Theme.NoDisplay`、`onCreate` 立即 `finish()`、
`taskAffinity=""`、`exported`——adb/shell uid 启动的硬要求）。**进程被冻结时无法启动 Activity，
系统必须先解冻它**，Activity Start 一发生，压住的回调随即补投。

**腿 2：锁屏态过滤器豁免。**
`ShadeVisibilityDump` 把 `filter=KeyguardCoordinator` 视为**状态**过滤器而非内容过滤器：
该条目照常算 Shade-visible。`SummaryFilter`、`MediaCoordinator` 等**内容**过滤器照旧剔除
（豁免清单是显式的、带注释的常量，不是「凡 filter 都放过」）。

## 取舍与边界（如实记）

- 冻结唤醒是**事后叫醒**，延迟上界 ≈ 一个循环间隔（默认 5000ms），不是实时；换来的好处是
  平台侧不改、不加权限、不改 MIUI 设置。要更实时只能进 MIUI 省电白名单（无限制/自启动），
  那属于用户侧一次性设定，本 ADR 不依赖它。
- 不承诺「不再被冻」：MIUI 仍会在灭屏后冻它。实测一次唤醒后进程通常能持续保持未冻状态
  （随后命中 MIUI 自己的 `Uid ... was show on screen, skip it` 豁免），本轮 6 发探针全程未再冻；
  若某天不成立，循环会持续叫醒（代价是更频繁的唤醒）。
- 新增的 exported 空转页是**有意**暴露的最小面：任何应用都能启动它，效果仅是「解冻 + 立即退出」，
  不接收数据、不返回结果、不进最近任务。
- 腿 2 只豁免 keyguard 这一种状态过滤器；将来 SystemUI 出现别的**状态**过滤器（如多用户切换）
  需要同样豁免时，加进同一张显式清单并补一条 fixture 判例。

## 影响

- `:rear`：`WakeKeepAliveScript.wakeLoopStartCommand` 的循环脚本多一段冻结看护（JVM 判例
  `WakeKeepAliveTest` 钉住片段形状、单引号禁令与新词形 `wake-keep-alive thaw nudges=N`）；
  `:app` 新增 `ThawNudgeActivity` 与其 manifest 声明。
- `:notification`：`ShadeVisibilityDump` 语义收紧（锁屏态豁免），新增 2 例真机 fixture 判例。
- 回归工具：`tools/ex/23-locked-icon-update.ps1`（一条命令：对照组 → 锁屏 → 逐条探针 → 判定词
  `CTRL-PASS` / `LOCKED-PASS` / `LOCKED-NO-EVENT` / `LOCKED-NO-ICONSET` / `LOCKED-CORE-ONLY` / `LOCKED-NO-REAR`）。

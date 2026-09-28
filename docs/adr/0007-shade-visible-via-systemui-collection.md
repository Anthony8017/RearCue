# 7. 下拉栏可见性以 SystemUI 当前通知集合为准，Shizuku 只做可选精确源

日期：2026-09-28
状态：已接受（grilling 定案）

## 背景

NotificationListenerService 的 `getActiveNotifications()` 返回系统仍在册的 Active Notification，
其中包含下拉通知栏完全不显示的条目（本机实机观察到 `com.miui.misound`、`com.xiaomi.aicr`、
`com.xiaomi.mi_connect_service` 等系统前台服务/自动分组通知）。公开 API 只暴露通知的 ranking 与属性，
不暴露 SystemUI 当前的可见集合；仅靠 `isOngoing`、importance、channel 或包名无法同时满足：

- 下拉栏可见的 Android 系统通知（USB、充电提示）照常显示；
- 下拉栏实际隐藏的系统服务通知不上背屏；
- 用户从下拉栏划掉但系统仍保留的活动通知不上背屏；
- 静默区/折叠分组中实际可找到的通知仍算可见；
- Shizuku 掉线时不把整个通知链路绑死，宁可多显示也不漏真实消息。

实机确认 HyperOS 3 的 SystemUI 在以下只读 dumpsys 目标中暴露当前 `NotifCollection` 与
`missingNotifications`，可以在 Shizuku shell uid 下读取并由纯 Kotlin 解析：

```
dumpsys activity service com.android.systemui/.SystemUIService NotifCollection
```

## 决定

新增 Shade-visible Notification 过滤层，夹在 NLS 原始 Active Notification 与
Dashboard/Icon Set 之间：

1. 保留 NLS 原始在册集合；
2. Shizuku 可用时，读取并解析 SystemUI 当前 `NotifCollection` 的可见 key 集合；
3. 仅把可见 key 对应通知送入 Dashboard；探测时不在原始集合中的新 key 按“未知即可见”放行，
   等下一次探测校正；
4. Shizuku 不可用或解析失败时退回“全部在册即可见”（fail-open），不阻断通知监听与投送；
5. 刷新触发点包括监听连接/快照/增删、ranking 更新、Shizuku 恢复与低频周期性校准；周期校准仅在当前有可见内容时运行，隐藏-only 场景由事件/ranking 更新触发，避免恒常 shell 查询；
6. 具体解析器与路由层保持纯 Kotlin seam，SystemUI dump 形状变化只影响该适配点。

## 考虑的替代方案

- **按包名/频道黑名单**：针对 `com.miui.misound` 等逐项排除。否决：无法覆盖未来同类系统服务，
  且会把“下拉栏可见性”错误固化成维护名单。
- **按 FGS、ongoing、importance 等属性推断**：实现简单。否决：Android/HyperOS 的可见性还受本地划掉、
  MIUI 过滤、分组和静默区影响，属性与下拉栏可见性不等价。
- **要求 Shizuku 常驻，掉线即停止通知显示**：可达严格一致。否决：违背现有“通知监听与投送不依赖 Shizuku”的边界。
- **AccessibilityService/读屏抓取下拉栏**：可达精确。否决：权限与侵入面过大，且实现更脆弱。

## 后果

- Shizuku 在线时，背屏图标/角标以下拉栏当前实际可见集合为准；掉线时可能重新出现被隐藏的系统服务图标。
- 依赖 HyperOS SystemUI 的 dump 目标名与文本形状；适配点、日志锚和测试 fixture 必须隔离，解析失败不得崩溃。
- 低频周期查询增加少量 shell/CPU 开销；以真实设备耗电和滑动体验为准，必要时调整周期或合并事件。
- Shizuku 从此不只是投送兜底：它是“精确通知可见性”的可选数据源；但监听、投送与 fail-open 路径仍不依赖它。

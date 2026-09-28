# 锁屏图标不更新：复现、两个根因、修复与回归（issue #143）

- 设备：小米 17 Pro（25098PN5AC，HyperOS 3 / OS3.0.319.0.WBLCNXM），adb serial `94250f9e`
- 循环：`tools/ex/23-locked-icon-update.ps1`（对照组 → 锁屏 → 每条探针「清图标 → 发通知 → 12s 后判定」）
- 判定词：`CTRL-PASS` / `LOCKED-PASS` / `LOCKED-NO-EVENT`（回调没到）/ `LOCKED-NO-ICONSET`（到了又被可见性链删）/ `LOCKED-CORE-ONLY`（数据动了背屏没重绘）/ `LOCKED-NO-REAR`（背屏不是我们的）
- 状态：**已修复并实测通过**（ADR 0008；issue #143）

## 机主报的现象

完全锁屏（主屏灭屏 + keyguard）时，背屏 Dashboard 的通知图标不更新——飞书来消息，背屏还是旧图标。

## 三个会话目录（修复链）

| 目录 | 代码状态 | 结果 | 读法 |
| --- | --- | --- | --- |
| [20260929-000126](20260929-000126-locked-icon-update/) | 修复前 | 对照组 2/2 `CTRL-PASS`；锁屏 **5/5 `LOCKED-NO-EVENT`** | 复现「事件根本没到」 |
| [20260929-001455](20260929-001455-locked-icon-update/) | 只修冻结 | 锁屏 **6/6 `LOCKED-NO-ICONSET`** | 事件到了、图标 300ms 后被删 → 暴露第二根因 |
| [20260929-002215](20260929-002215-locked-icon-update/) | 两腿都修 | 锁屏 **6/6 `LOCKED-PASS`**（含 3 条真飞书） | 修复验证 |

## 根因 1：GreezeManager 冻结（平台侧）

锁屏后约 5s 应用进程被冻，冻结期间 NLS 回调全压队列：

```
2026-09-29T00:02:03.756455 - FZ uid = 10371 pid = [ 11245 ]  reason : tobg caller : 1     ← 锁屏后 ~5s
（62s 冻结窗内 5 发探针：posted=0、背屏像素 0 变化、cgroup.freeze=1）
2026-09-29T00:03:05.173376 - THAW uid = 10371 pid = [ 11245 ]  reason : adj caller : 1000 ← 解冻瞬间
09-29 00:03:05.181  posted com.android.shell → UpdateIconSet(2)+HighlightBreath ...        ← 3ms 内补投
09-29 00:03:05.182  posted com.android.shell ...
09-29 00:03:05.183  posted com.android.shell ...
09-29 00:03:05.184  posted com.android.shell ...
```

背屏亮着、Dashboard 也在屏上（`owner=dashboard`），但它是**冻住的那一帧**——所以机主看到的不是黑屏，
而是「停在旧图标」。事件不丢，只是被压到解冻。

**修复**：设备侧 Wake Keep-alive 循环（shell uid，ADR 0003 既有机制）每拍多查一步
pid 级 `cgroup.freeze`，为 1 就 `am start` 新增的无 UI 空转页 `ThawNudgeActivity`；
进程被冻结时无法启动 Activity ⇒ 系统先解冻它 ⇒ 回调随即补投。

## 根因 2：锁屏态过滤器被误判（本应用侧）

事件到了之后，图标仍被自己的可见性链删掉：

```
00:19:17.292  posted com.android.shell → UpdateIconSet(2)+HighlightBreath iconSet [android] -> [com.android.shell, android]
00:19:17.497  posted com.android.shell iconSet [com.android.shell, android] -> [com.android.shell, android]
00:19:17.625  removed com.android.shell iconSet [com.android.shell, android] -> [com.android.shell, android]   ← 330ms 后删掉
00:19:17.626  shade-visible probe reason=posted systemUiVisible=3 raw=14 shown=4
```

SystemUI 锁屏 dump 原文（同一时刻现抓）：

```
NotifCollection unsorted/unfiltered notifications: 8
    [3]  0|com.android.shell|2020|filt1|2000
        pkgName=com.android.shell ... keyguard=F ...
        filter=KeyguardCoordinator          ← 锁屏这层挡一下，不是「下拉栏里没有」
```

`ShadeVisibilityDump` 原本把任何 `filter=` 当「会被后续过滤器剔除」⇒ 判「下拉栏不可见」⇒ 删图标。
但 CONTEXT 对 Shade-visible 的定义是「**解锁状态下**，系统下拉通知栏实际会列出的通知」——
锁屏态过滤器表达的不是用户意图。**修复**：豁免 `KeyguardCoordinator`（显式清单），
内容过滤器（`SummaryFilter`、`MediaCoordinator`…）照旧剔除。

## 修复后实测（20260929-002215）

```
Probe Kind    Locked Wake   Owner     Posted InSet   Diff Verdict
l1    fixture   True Dozing dashboard   True  True 0.1676 LOCKED-PASS
l2    feishu    True Dozing dashboard   True  True 0.1673 LOCKED-PASS
l3    fixture   True Dozing dashboard   True  True 0.1676 LOCKED-PASS
l4    feishu    True Dozing dashboard   True  True 0.1721 LOCKED-PASS
l5    fixture   True Dozing dashboard   True  True 0.1676 LOCKED-PASS
l6    feishu    True Dozing dashboard   True  True 0.1673 LOCKED-PASS
```

冻结看护自己的足迹（greezer 原文）：

```
2026-09-29T00:23:05.549340 - FZ uid = 10371 pid = [ 19506 ]  reason : tobg caller : 1
2026-09-29T00:23:10.323338 - THAW uid = 10371 pid = [ 19506 ] reason : Activity Start caller : 1   ← 看护那一下
```

背屏证据：`l4-before.png`（只有旧的 android/smarthome 图标）→ `l4-after.png`
（飞书图标 + 角标 1、shell 3、微信 1）；对照组 `l2-before/after` 见修复前目录（背屏一动不动）。

## 复跑

```powershell
# 需要手机已连 adb。脚本自己锁屏（KEYCODE_POWER），跑完请用指纹/PIN 解锁。
powershell -ExecutionPolicy Bypass -File tools\ex\23-locked-icon-update.ps1 -LockedProbes 6 -SettleSeconds 12
# 只在有真飞书能发时含飞书腿；不想发就用 -SkipFeishu
```

产物：每探针 `<id>.logcat` / `<id>-state.txt`（freeze、owner、icon set、像素差、看护计数）/ \
`<id>-before.png` `<id>-after.png` / 飞书腿另有 `<id>-feishu.json`；总结 `locked-icon-summary.txt`。

## 遗留 / 边界（如实记）

- 冻结唤醒是**事后叫醒**：延迟上界 ≈ 一个循环间隔（默认 5000ms）。要更实时只能进 MIUI
  省电白名单（无限制/自启动），那是用户侧一次性设定，本修复不依赖它。
- 本轮 `u1` 对照组落在「手机已锁」的状态（无人值守跑，手机一直锁着），所以 `CTRL-NO-REAR`
  是环境所致，不是回归；要跑干净对照组请先解锁并让 Dashboard 上屏。
- 待机耗电：看护只在 `cgroup.freeze==1` 时动作，正常情况下每拍只多一次文件读。

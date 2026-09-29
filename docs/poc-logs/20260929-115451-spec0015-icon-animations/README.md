# spec 0015 实机验收 — 通知图标出现/消失动效（票 #150）

- 设备：小米 17 Pro（25098PN5AC，HyperOS 3 / OS3.0.319.0.WBLCNXM），adb serial `94250f9e`
- 背屏：逻辑 displayId=1，904×572，可用区 x≥296（相机带 296px 在左）；截图/录屏用 SurfaceFlinger id `4630946949513469332`
- 分支：`ticket/150-acceptance`（基于集成分支 `spec/0015-icon-animations`，PR [#151](https://github.com/Anthony8017/RearCue/pull/151) 仍是 draft）
- APK：`:app:assembleDebug`，SHA-256 `55512B549770CEDDEB6E7357D87118F861B9F676518544D63E1877212D635087`
- 驱动脚本：[drive-acceptance.ps1](drive-acceptance.ps1)（一键：前置体检 → 逐场景注入/动作 → logcat 锚断言 + 截图 + 背屏录屏抽帧 → 汇总）
- 状态：**2026-09-29 12:44–12:53 实机跑通**。汇总判定：**FAIL=0，INCONCLUSIVE=3**（5 / 10 / 11，全部是「机主真实通知在册」造成的构造不出前置，见下）；其余 20 项 PASS。
  逐轮原始表见 `acceptance-summary.txt`（含每轮 `failed=/inconclusive=`），痕量见 `sequence.logcat`（按 `########## run` 分段）。

## 1. 判定表（实机列已回填）

| # | 验收项（票 #150 / spec 0015） | 腿 | 操作与判据 | 判定 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 1a | 入场（图标＋角标）触发 `icon enter` | 真实通知腿（`cmd notification post`） | 呼吸探针开窗后发一条 shell 通知；断言 `icon enter com.android.shell` | PASS | `10-enter-real.png`、`frames/01-enter-real/`（27 帧 @15fps，动作窗 1.8s） |
| 1b | 与 3 秒呼吸高亮叠加 | 真实通知腿 | 断言 enter 时刻落在 `highlight breath start..end` 之间（12:50:32.781 → 12:50:35.797；enter 12:50:34.849） | PASS | `sequence.logcat` 1 段 |
| 1c | 角标随图标一起出现 | 真实通知腿 | 入场截图留证（角标「1」与图标同一帧群出现；观感归目检） | PASS（目检） | `10-enter-real.png` |
| 2 | 多枚同时入场 | 注入腿 | 一次广播注入 3 枚 fixture；断言 3 条 `icon enter` 且时间跨度 ≤300ms（实测 span=0ms，同一 `LaunchedEffect` 批量打） | PASS | `20-multi-enter.png`、`frames/02-multi-enter/`（接触表第 4–5 格：三枚同时从 0 缩放出现、其余图标下移） |
| 3 | 补位滑动 | 注入腿 | 先把总数补到 6（容量内）再撤首格一枚；断言 `icon exit <fixture>` + `update iconSet` | PASS | `30-reflow-after.png`、`frames/03-reflow/`（接触表第 4 格：退场图标缩小淡出，其余 5 枚已滑到新位） |
| 4a | 最新位挪动（新通知把该 App 挪到左上） | 真实通知腿 + 1 枚注入项占位 | 注入项占住首位后发一条新 tag 的 shell 通知；断言 AppState 投影 `after[0]=com.android.shell` 且 `before[0]≠shell`（before[0]=占位 fixture） | PASS | `40-move-front.png`、`frames/04-move-front/` |
| 4b | 挪位不触发进退场锚 | 真实通知腿 | 断言该窗口内无 `icon enter/exit com.android.shell`（同 App 增条只挪位） | PASS | `sequence.logcat` 4 段 |
| 5 | 1 行 ↔ 2 行换挡 | — | 行数换挡要跨 3↔4 枚；本机非 fixture 真实通知恒有 4 枚以上（`android / com.openai.chatgpt / com.android.mms / com.ss.android.lark`，跑本轮时 + shell=5），按票 #150 纪律不得清理机主通知 | **INCONCLUSIVE** | 见下方「构造不出的前置」 |
| 6a | 6 枚（容量内）无 +N | 注入腿 | 补到 6 枚后断言 `rear-icon-place ... chip=null` | PASS | `60-tier-6.png` |
| 6b | 第 7 枚入场并出现 +N | 注入腿 | 再投 1 枚；断言该枚 `icon enter` + `rear-icon-place ... chip=PxRect` | PASS | `60-tier-7.png`、`frames/06-tier-67/`（整组重排与「+N」出现） |
| 7a | 充电让位：电量数字占位消失 | 真实腿（`CHARGING_ENABLED=false`，等价设置页开关） | 关充电动画；断言 `rear-icon-place ... numberSlot=null` | PASS | `70-charging-off.png` |
| 7b | 充电让位：电量数字占位回来 | 真实腿（`CHARGING_ENABLED=true`） | 开回来；断言 `numberSlot=PxRect(...)` | PASS | `71-charging-on.png` |
| 7c | 让位过程连续 | 真实腿 | 关→开全程背屏录屏抽帧 | PASS（目检） | `frames/07-charging-slot/`（接触表：图标整组上下平移让出/收回电量数字位） |
| 8 | 相机带避让 | 真实腿（静态几何） | 断言 `block.left ≥ 296` 且不越右缘（实测 left=322 right=870） | PASS | `80-camera-band.png`、`rear-safe-geometry` / `rear-icon-place` 锚 |
| 9 | 退场 | 真实通知腿 | 清掉 shell 通知；断言 `icon exit com.android.shell` | PASS | `90-exit-real.png`、`frames/09-exit-real/`（接触表：该枚收缩淡出、其余补位） |
| 10 | 最后一个图标的退场与交还时机 | 真实通知腿（**构造不出**） | 需要 Icon Set 能清空才能进 `exit grace start/end` | **INCONCLUSIVE** | 见下方「构造不出的前置」；复跑入口：`drive-acceptance.ps1 -GraceOnly` |
| 11 | 宽限窗内新通知取消退屏 | 真实通知腿（**构造不出**） | 同 10（前置是空集 → `exit grace cancel`） | **INCONCLUSIVE** | 同 10 |
| 12 | 只在「+N」里的溢出应用消失不演退场 | 注入腿 | 先投 alpha 再投 6 枚更新的 fixture，让 alpha 落在溢出位（实测序号 6）；撤 alpha 后断言窗口内**无** `icon exit alpha`，只有「+N」数字变化 | PASS | `120-overflow-after.png`、`frames/12-overflow-exit/` |
| 13a | Detail View 打开成功 | 注入腿 | 现算首格中心点按；断言 `rear-tap received app=...` + `detail open <pkg>` | PASS | `sequence.logcat` 13a |
| 13b | Detail 当口被清掉不演退场 | 注入腿 | Detail 打开时撤该 App 通知；断言窗口内**无** `icon exit <pkg>`（静默让位） | PASS（注：该窗内也落过一次可见性探测，两条路径都走 core 的「详情当口静默移除」出口，见「坑位」5） | `130-detail-silent.png`、`frames/13-detail-exit/` |
| 14a | 角标 1→2 不动效 | 真实通知腿 | 同 App 发第二条（新 tag = 新 key）；断言该窗口无 `icon enter/exit com.android.shell`，集合成员不变 | PASS | `sequence.logcat` 14 段 |
| 14b | 角标计数 2 | 真实通知腿 | 截图留观感（数字由 `unreadCounts` 投影给出） | PASS（目检） | `140-badge-2.png` |
| 15 | 帧率与发热基线 | 真实腿（采样） | `dumpsys gfxinfo` + `thermalservice` + `battery` + 背屏空录帧数 | PASS | `gfxinfo.txt` / `thermalservice.txt` / `battery.txt`；见下「基线」 |
| 16a | 主屏总览吃到同一份事实 | 注入腿 | 主屏前台时注入一枚；断言 core 同一条事件链上 `icon enter` | PASS | `161-main-after.png` |
| 16b | 主屏总览同一套观感 | 注入腿 | 主屏 1220×2656 录屏抽帧 | PASS（目检） | `frames/16-main-enter/` |
| 90 | 收尾复位 | — | fixture / 自发 shell+test 通知清空，充电动画与门控档位复位；机主真实通知原样保留 | PASS | `sequence.logcat` 90 段 |

## 2. 目测结论（帧序列，非单帧）

0.25 秒级动效抓不住单帧（`screencap` 单帧 ~200–300ms）；本目录全部动效场景都是**背屏 `screenrecord` 连续录屏 → ffmpeg 抽帧**（`video/*.mp4` + `frames/<step>/fNNN.jpg`，动作窗 1.8s @15fps ⇒ 约 67ms/帧；`frames/<step>/frames-timing.txt` 是原片逐帧时间戳，原件背屏 60–120fps）。逐条目检结论：

- **入场（1）**：新图标从 0 缩放出现、角标同一进度淡入，轻微过冲后收回；与整屏呼吸高亮同时进行、互不打架。
- **多枚同时入场（2）**：三枚在同一次重组里一起出现，不排队、不逐枚错峰。
- **补位滑动（3）**：退场那枚边缩边淡出、仍占格；其余图标同帧开始滑动到新位，没有「先跳一格再抖回来」的双段感。
- **最新位挪动（4）**：被挪的图标从第三格平移到左上首位，其余整体顺次滑动；无入场/退场重演。
- **6 枚 ↔ 7 枚边界（6）**：第 7 枚出现时整组重排、「+N」徽标一起淡入；6 枚态无徽标。
- **充电让位（7）**：电量数字占位出现/消失时，图标整组平移让位，不是硬切。
- **退场（9）**：撤通知后该枚收缩淡出、其余补位，一次连续变化。
- **溢出应用（12）**：只在「+N」里的应用被清掉时，屏上没有任何收缩/淡出，只有徽标数字变化。
- **Detail 当口（13）**：卡片盖着的那枚被清掉时没有退场动画，卡片照常收起。
- **主屏总览（16）**：同一份 Icon Set 投影在主屏 `IconSetCard` 上播同一族动效（注入项同样从 0 缩放入场）。

## 3. 帧率与发热基线（后续调参参照）

采样时刻：2026-09-29 12:44–12:52（验收链跑动期间），口径逐条写清：

- `dumpsys gfxinfo com.rearcue.poc`（进程级、主屏窗口）：`Total frames rendered 34921`、`Janky frames 2 (0.01%)`、50th 18ms / 90th 22ms / 95th 23ms / 99th 28ms、`Number Missed Vsync 2`。
  注意 `Janky frames (legacy) 83.77%` 是旧口径（把 60Hz 基准套在 120Hz 上），与本机刷新率不符，**不作为结论**。
- 背屏录屏实测（`screenrecord --display-id <sfId>`，5 秒空录）：`nb_frames=298 / duration=4.96s` ⇒ **约 60fps**；另一次 4 秒空录为 472 帧 ⇒ 约 120fps。即背屏面板按 120Hz 刷新，编码器随内容/负载在 60–120fps 间浮动；0.25s 级动效在这两档下分别有 ~15 / ~30 帧可用，足够目检。
- 发热：`dumpsys battery temperature=391`（39.1°C，验收尾段）/ 早段 352–364（35.2–36.4°C）；`thermalservice` 的 `Thermal Status: 0`（无降频热状态），CPU 各核 87.6–103.5°C（瞬时读数，非结温）。全程未见 `thermalRefreshRateThrottling`。

## 4. 构造不出的前置（INCONCLUSIVE 的原因，逐条写清）

1. **10 / 11（最后一条 + 退屏宽限）**：`reconcileExit` 的宽限只在「在屏 ∧ 无持有理由 ∧ Icon Set 空」时进入；本机 Icon Set 里常驻 4 个**机主真实通知** App（`android` 系统、`com.openai.chatgpt`、`com.android.mms`、`com.ss.android.lark`；跑验收时还会加上我们自己的 `com.android.shell`）。票 #150 明确「严禁撤销/清理机主真实应用的通知」，因此空集不可构造，`exit grace start/cancel/end` 三锚无法产生。
   复跑路径：机主把这几个应用的通知清掉（或等自然清空）后跑 `powershell -ExecutionPolicy Bypass -File drive-acceptance.ps1 -GraceOnly` —— 该档只跑 0 / 10 / 11 / 90，脚本会自动关充电动画（否则充电持有会挡退屏）、发真实通知 → 撤掉 → 量 `icon exit` 与 `exit grace start|end` 的间隔（上限 1s）与「窗内来新通知 → `exit grace cancel` 不退屏」。
2. **5（1 行 ↔ 2 行换挡）**：行数换挡要跨 3↔4 枚，同上原因本机触不到 3 枚上限（非 fixture 恒 ≥4）。该项的**纯几何**由 `IconGridTest` 判例覆盖；同一族动画（尺寸档不变时的整组等距平移）由第 3 / 7 条腿（补位、充电让位）真机覆盖；尺寸档变化的整组缩放另由第 6 条腿真机覆盖——但「1 行态本身」在本机没跑过，如实记 INCONCLUSIVE。

## 5. 本机坑位（都给后续复跑留了注释）

1. **背屏截图/录屏不认逻辑 display id**：`screencap -d 1` 报 `Display Id '1' is not valid`；要用 `dumpsys display` 里 displayId=1 的 `uniqueId`（SurfaceFlinger id，本机 `4630946949513469332`）。`screenrecord --display-id <sfId>` 可用（spec0013 时以为不可用）。
2. **`cmd notification post` 的 tag 是位置参数，`-t` 是标题**：语法是 `cmd notification post [flags] <tag> <text>`。tag 会进 notification key，而 SystemUI `NotifCollection` dump 的 `[i] <key>` 行被 App 的解析器按 `\S+` 截断——**tag 里带空格**（例如把「RearCue spec0015」当 tag）会让「真实通知」被判成「不在下拉栏里」，发出去 ~300ms 就被可见性链自己删掉（图标闪一下）。本脚本统一 `cmd notification post -t RearCue <无空白tag> '<正文>'`。
3. **注入腿的寿命 = 下一次成功的 Shade-visible 探测**：debug 注入项不在 SystemUI 在册集合里，探测一落地就会被按可见性隐藏（本机周期 15s，真实通知会插队触发 `queued`）。脚本因此：注入前 `remove` 清 `hiddenKeys`、动作前 `Wait-FreshProbeWindow` + **当场复核**状态、6↔7 段整段允许重试（最多 3 次）。
4. **退场动画的容量前提**：`IconSetMotion.presentedOrder` 里退场条目「占格不占容量」，只有本帧剩余条目 **< 6** 时退场条目才留得下格位；总数 8 撤 1（剩 7）时它没有格位可占，`icon exit` 锚不会出现（这一步直接不演）。脚本第 3 条腿因此先把总数补到 6 再撤。
5. **同一 App 增条 = 只挪位不进退场**：`NotificationPosted`（新 key）只走 `activeCounts` 的 remove+重插；集合成员不变时 `reconcile()` 不产出 `UpdateIconSet`——（a）`rear=RearBackendState(... iconSet=[...])` 镜像因而仍显示旧顺序，判「挪到首位」必须读 **AppState 顶层** 的 `iconSet=[...]`（脚本已修）；（b）同 key 同内容的重复注入是 no-op（`RecordOutcome.UNCHANGED`），fixture 要挪位就得换 key 或换正文。
6. **主屏熄屏会冻结应用并撤下背屏 Dashboard**：`mWakefulness=Dozing` 后实测 `cgroup.freeze=1`、`Display #1` 任务空、后续无日志。脚本为此常驻一个后台 job 每 12s 补发 `KEYCODE_WAKEUP`，且在每步开始时体检「本票 APK 在不在 / 进程冻结没 / Dashboard 在不在屏」。
7. **同机并发跑别的票**：本轮跑动期间 `154-merged-roster`（12:38:03、12:42:17）与 `153-bridge-source`（12:11:58）各自覆盖安装了自己的 APK，会把本票 APK 顶掉、把应用重启，导致个别步骤中途失锚。脚本已内置嗅探（`FIXTURE_NOTIF` 动作变成「未知调试动作」即判定被换包）→ 自动重装本票 APK 再继续；`acceptance-summary.txt` 与 `sequence.logcat` 因此按 `run` 分段保留历次痕迹，判定表以最近一轮通过为准。

## 6. 一键复跑

```powershell
$env:JAVA_HOME = "C:\Users\13691\AppData\Local\RearCue-tools\jdk-17.0.20.1+1"
.\gradlew.bat :core:test :rear:test :app:testDebugUnitTest :app:assembleDebug --console=plain

powershell -ExecutionPolicy Bypass -File tools\ex\01-install.ps1      # 安装（含 MIUI USB 弹窗）
powershell -ExecutionPolicy Bypass -File tools\ex\02-authorize.ps1    # 重新授予通知使用权
powershell -ExecutionPolicy Bypass -File docs\poc-logs\20260929-115451-spec0015-icon-animations\drive-acceptance.ps1 -SkipInstall
# 只跑某几步 / 只跑退屏宽限腿：
#   ... -Only 1,4,6
#   ... -GraceOnly
```

脚本只使用既有 debug 旁路（`DebugCommandReceiver` 的 `STATE/PROJECT_REAR/EXIT_REAR/CANCEL_PACKAGE/CANCEL_TEST/POST_TEST/CHARGING_ENABLED/POSTURE_GATE/SESSION_LOCK`）与本票新增的 `FIXTURE_NOTIF`；
不发送 prompt、不批准 agent、不触碰机主真实应用的通知。

## 7. 需要人工/后续

1. **3 项 INCONCLUSIVE 的补跑**（上面第 4 节）：需要机主的 Icon Set 能清空；`-GraceOnly` 已备好一条命令。
2. **观感终审**：帧序列与结论已归档，但「0.25s 手感、回弹幅度、充电让位顺滑度、主屏/背屏一致性」的最终主观判断仍建议机主亲自看一眼 `frames/*/contact-sheet.jpg`（或 `video/*.mp4`）。
3. **验收环境冲突**：本轮与 #153/#154 在同机并发，个别 PASS 项跨轮次取得（判定表按项给出证据；`acceptance-summary.txt` 保留每轮明细）。
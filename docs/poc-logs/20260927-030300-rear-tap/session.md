# E16 Rear Tap 真机验证（票 #63）— session 判定

- 时间：2026-09-27 03:03–03:12（设备时钟）；设备 94250f9e（小米 17 Pro，HyperOS 3，Android 16）
- 分支：spec/0008-rear-visual；APK：debug（含 Rear Tap 探针，`rear-tap received app=<pkg>`）
- 投送：调试旁路 PROJECT_REAR（手动路径）；Icon Set=[com.android.shell]；任务 t13610/t13611（④ 被顶后重投新建）
- 坐标：背屏 904×572，可用区 x≥296；图标中心 (544,278)（`rear-safe-place` placed=[409,143,679,413] + cal.png 截图双证）；空白点 (350,460)

## 判定表（GO 判据：探针锚 5/5 且除④外无原生手势/抢回）

| 场景 | 轮 | 手势 | 探针锚 | 原生手势行 | Dashboard 被顶掉 |
|---|---|---|---|---|---|
| ① 空白区短点按 | 5/5 | `tap 350 460` | 0（预期，无可点目标） | 0 | 无 |
| ② 图标短点按 | 5/5 | `tap 544 278` | **5/5** | 0 | 无 |
| ③ 图标长按 | 5/5 | `swipe 544 278 544 278 800` | **5/5**（clickable 在 UP 触发） | 0 | 无 |
| ④ 上滑（对照，复现票 #7） | 5/5 | `swipe 550 560 550 200 200` | — | **5/5**（`GestureInputHelper onTriggerGestureSuccess` → `startRecentAnimation, topApp: com.rearcue.poc`） | **5/5 被顶掉**，**5/5 应用 ~1.5s 自动重投恢复**（`Dashboard 意外销毁 → LaunchDashboard(1)`） |
| ⑤ 保活进行中图标点按 | 5/5 | `tap 544 278`，注入循环全程 alive（pid 32630，5000ms） | **5/5** | 0 | 无 |

文件：`<场景>-r<N>.logcat`（每轮全量 main buffer，logcat -c 后采集）、`<场景>-r<N>.verdict`（owner/探针/手势行/保活/进程汇总）。

## 结论：GO

1. **短点按可被 Dashboard 独占接收**：Compose clickable 在 displayId=1 的 Activity 上 5/5 收到点按（②⑤），空白区不触发任何系统行为（①）；`MIUIInput` 每轮有 MotionEvent 投递到 `RearDashboardActivity` 窗口的原文。长按同样干净（③，无原生长按行为触发）。
2. **原生手势只在手势区触发**：仅④（底部上滑）触发 `SubScreenCenter_GestureInputHelper → startRecentAnimation` 并顶掉 Dashboard——票 #7 风险实锤仍存在，但**边界清晰**：点按（任意位置）不进手势判定。
3. **与 #7 的差异（新事实）**：④ 被顶掉后应用侧「意外销毁 → LaunchDashboard」自动恢复路径生效，5/5 在 ~1.5s 内重上背屏（#7 时代是「顶掉且无广播、不恢复」；该恢复路径为 #24/#36 期间建成）。
4. **与 Wake Keep-alive 无互扰**（Q3）：注入循环全程 alive、5000ms 节奏（`wake-keep-alive ok ticks=70/80 intervalMs=5000` 直接证据落在轮窗内），点按 5/5 正常接收，无 SUB_SCREEN_ON/OFF 跳变（25 轮 0 次）。

## 判读边界

- ④ 的 owner 采样在 +4s，显示的是自动恢复后的状态；被顶事实以轮内 `Dashboard detach 实例数=0` + `startRecentAnimation` 原文为准（`swipe-up-r*.logcat`）。
- 长按触发的是 Compose clickable 的 onClick（UP 时刻），不是长按语义；#66 的 Detail View 若要长按语义需自行用 combinedClickable，本票只证「长按不触发原生手势」。
- 单图标形态验证；多图标折行时图标区扩大，但点按结论按窗口级输入路由推断不变（点按不进手势判定与图标数无关）。
- 防烧屏漂移 ±8px 使图标中心在 (544±8, 278±8) 轮驻，远小于图标半宽 135px，定坐标点按不受影响。

## Setup erratum（复现须知）

- 覆盖安装（`install -r`）后：通知监听 binder 未自动重绑，事件静默丢失——需 `cmd notification disallow_listener` + `allow_listener` 强制重绑（重绑后积压事件补投）。
- 应用无 Dashboard 在屏且主屏空闲时被 GreezeManager **cgroup 冻结**（`/sys/fs/cgroup/apps/uid_10358/pid_<pid>/cgroup.freeze=1`，`dumpsys` 的 isFrozen=false 不可信）：广播与通知事件排队不投。解法：`am start` MainActivity 解冻后再驱动（Dashboard 在屏时进程可感知，不再冻结）。
- 覆盖安装会重置 MIUIOP 10020（锁屏显示），本轮已重放 `appops set ... 10020 allow`（本票实验全程未锁屏，未受影响）。
- `screencap -d` 的合法 id 是 SurfaceFlinger display id（`dumpsys SurfaceFlinger --display-id`，本机背屏 = 4630946949513469332），E15 口径复用。

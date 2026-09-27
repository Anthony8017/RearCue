# ticket #76 / spec 0009：实机验收 verdict（2026-09-27，设备 94250f9e）

轮次：软件模拟充放电（`dumpsys battery unplug/set level/set ac 1`，结束已 `reset`）；
通知 `cmd notification post`（shell 在 Allowlist）；背屏点按 `input -d 1 tap 544 278`；
截图 `screencap -d <SF display id>`。判定：像素测量（PIL）＋视觉模型复核＋app 词锚。

## verdicts

| 腿 | 判定 | 证据 |
|---|---|---|
| L1 全屏水位含相机带 | **PASS** | L1-charge-68.png：x=45/452/858 三点全绿（44,94,61），左带（0-296px 相机区）染色；0008「不染色」反转生效 |
| L2 水面微波 | **PASS** | W1/W2/W3 三帧 diff：变化带 (0,351)-(904,409) 恰为全宽水位线带，~7k px/2s；带内波面 24 列轮廓 y 393-413 非平直；幅度安静（spec 3-5px 档）。注：L1/L2 初测静止系进程被 Greeze 冻结的假象，主屏前台解冻后复测在动 |
| L3 电量变化 68→30 | **PASS** | L3-charge-30.png：水位线 183→400 区域；词锚 `电量读数 level=30 → 30%` |
| L4 充电中共存＋光晕 | **PASS** | L4-coexist-halo.png：水面＋图标＋通知共存；词锚 `highlight add com.android.shell`+`breath start`；视觉复核光晕弥散无硬边 |
| L5 呼吸＋暖白光晕 | **PASS** | 词锚 `highlight breath start` 17:54:50.268 → `breath end` 17:54:53.294（3.0s）；L5b 径向亮度 232→18→9→0 单调衰减、无环状凸起（无描边感）；L5c 呼吸中帧 |
| L6 Detail 全屏去应用名 | **PASS** | L6-detail-full.png：四角（含相机带中点）皆 detailSurface #232529；首行文字带 y=80 高 43px＝17sp 标题（无 13sp 应用名行/无小图标行）；词锚 `rear-tap received app=com.android.shell`；L6b 滚动 diff bbox (70,147)-(834,504) 全文可滚 |
| L7 拔电退出回归 | **PASS** | 词锚 `power-disconnected → ExitDashboard`（17:54:05）＋背屏 owner 归还 SubScreenLauncher |
| 数字右下＋细体 | **FAIL→修复→PASS** | 初测：数字锚 contentRect 右下（ink 581-750×220-368），单图标居中时与图标右缘相碰（视觉复核「靠在图标上、无间距」）——#72 验收项「不与 Icon Set 重叠」不满足。修复 4b8e658：整屏右下锚（圆角感知内缩 0.35r+md＋漂移保留）。复验 F1-number-68.png：ink 右缘 806、底 440，与图标间距 0.3-0.5 倍字高、不相碰；细体（横游程 5-9px vs Bold≥15px）、Outfit Light 白字绿水可读 |
| 帧率/发热 | PASS（观察档） | gfxinfo reset 后 728 帧 0 jank、p50 25ms（背屏刷新率档位）；电池 40.1°C（边充电边渲染，无异常升温） |

**overall：PASS（9/9，其中数字落位一腿为实机判定后修复复验）**

## 过程发现（非本票缺陷）

1. **Greeze 冻结假象**：app 退后台后被 MIUI 冻结，充电水面动画静止、STATE 广播被拒
   （`Greezer Denial`）——实机验收需保持 app 前台（记忆 erratum 已有，本轮再次实证：
   静止的 L1/L2 三帧 identical 是冻结帧）。
2. **Agent Mirror（spec 0010，并行会话已合入）接管投送**：agent 理由在身时 castSource=AGENT，
   `chargingOnScreen` 投影排除 AGENT 持有（0010 优先级链），充电水面不显示。验收注入
   `AGENT_STATE --ez connected false` 即交还。**spec 0010 会话注意**：agent 默认开＋未配对
   状态下出现过 agentReason 在身（UNPAIRED/agentState=null 时 castSource=AGENT），建议核查
   理由位来源（可能与并行探针注入有关）。
3. 两会话共用一台设备/一个工作树：本轮改在独立 worktree（RearCue-0009）构建修复，
   主工作树未动。

## 截图清单（screenshots/）

L1-charge-68 / L2-ripple-b / L3-charge-30 / L4-coexist-halo / L4b-halo-settled /
L5-breath-mid（原生屏，弃）/ L5b-halo-settled / L5c-breath-mid / L6-detail-full /
L6b-detail-scrolled / W1-W3（微波三帧）/ F1-number-68（修复后数字）/ crop-*（判定裁剪）。

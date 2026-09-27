# 票 #89 实机验收：可读文字避让相机带

- 时间：2026-09-27 20:26–20:45；设备 94250f9e（HyperOS 背屏 904×572、cutout 左带 296px、圆角 97）
- 装机：`app-debug.apk` 覆盖安装（`fix/camera-band-text`，修复前后各装一次对照）
- 判据：`screencap -d 4630946949513469332` 截显存、量文字行首 x 坐标 ≥ 304（布局框左界）。
  注意：本缺陷本身**截屏判不了物理遮挡**（截的是显存），截屏只判落位；遮挡口径以 spec 0004
  「内容一旦越界就被相机模组挡住」为准。

## 轮次

1. **Detail View**（飞书实通知点开）：标题/正文横跨 [304, 799]，行首完整不进带区 → PASS（`detail-fixed.png`）。
   修复前同视图行首 x≈70（见 `docs/poc-logs/20260927-175100-issue76-visual-polish/screenshots/L6-detail-full.png`）。
2. **Agent Mirror**（AGENT_STATE 注入工作态、POSTURE 注入倒扣走自动路径）：状态/工作区/动作/回复
   全部落在带外 → PASS（`agent-mirror-fixed.png`）。

## erratum

1. 手动投送（PROJECT_REAR）的 Dashboard **不被 Agent 抢占**（DashboardCore:825 的 MANUAL 豁免，设计如此）——
   首轮注入 AGENT_STATE 屏幕未切换不是缺陷，换姿态注入走自动路径复验通过。
2. `screencap -d 1` 不合法，须用 SurfaceFlinger display id（票 #26 判读边界⑥在案，此轮再犯一次，记此）。

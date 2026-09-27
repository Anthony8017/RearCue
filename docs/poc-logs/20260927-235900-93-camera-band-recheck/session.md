# 票 #93 实机复验：标题≠应用名 Detail 正案 + Agent Mirror 留白（erratum 3 闭环）

- 时间：2026-09-27 23:17 – 2026-09-28 00:31；设备 94250f9e（小米 25098PN5AC，HyperOS）
  背屏 SF display id `4630946949513469332`（904×572，cutout 左带 296px，文字布局框左界 304、右界 754）
- 分支 `fix/camera-band-text`（worktree `RearCue-fix89`，a78f352），装机 APK 22:41 构建产物；
  23:33:55 与 00:22:00 各覆盖安装一次（本票期间并行会话 #88 两次插装，见 erratum 1）
- 判读（PIL）：逐行扫与背景差 >40 的像素，按连续墨迹行归带，求每带 min/max x；
  卡底在行间空白处多点采样 RGB。全量数值见 `judge.txt`。

## 轮次

1. **Detail「标题≠应用名」正案** —— PASS（`detail-title-shown.png`，23:56:44）
   shell 通知 `tag=rk93a`、`android.title=产品群 · 王工`（≠应用名 shell/RearCue）、
   `android.text` 首词 `RK93-A7F2`。点图标开 Detail 后截屏：
   - 标题行存在且未省略：首带 y79..123 = 「产品群 · 王工」；正文 5 带，首行含 `RK93-A7F2` → 新鲜帧成立；
   - 文字墨迹 min x = **306**（≥304）；最右 max x = **738**（≤754）→ 右留空 = 904−738 = **166px** ≥150；
   - 卡底采样 20 点全为 **(0,0,0)**（bg 占比 0.940）。
2. **Agent Mirror（票面长动作行）** —— PASS（`agent-mirror-long-action.png`，00:22:14）
   `EXIT_REAR` 清手动投送 → `AGENT_STATE`（status=working、ws=rear-cue、动作 27 字、回复 70 字）
   → `POSTURE faceDown=true` 走姿态注入自动路径：
   - 状态/工作区/动作三段 5 条文字带 min x = **306**（状态行 307）、max x = **747**
     → 右留空 = 904−747 = **157px** ≥150；
   - 新鲜帧：动作行原文 `正在 RK93 动作行：…` 可见，与装机前旧帧不同；
   - 卡底采样 (0,0,0)。
3. **Agent Mirror（回复行进首屏）** —— PASS（`agent-mirror-reply-visible.png`，00:25:29）
   同一注入把动作行缩到 9 字，让回复区拿到剩余高度：
   - 状态 307..451、工作区 306..454、动作 306..666、回复首行 307..708、回复次行（底缘裁切）312..668；
     全局 min x = **306**、max x = **708** → 右留空 = **196px** ≥150；
   - 回复首行原文 `RK93 回复原文：这…` → 新鲜帧；卡底 (0,0,0)。

AC 四条判定：Detail 正案实拍 PASS、Agent Mirror 实拍 PASS、像素判读归档 PASS（本目录 `judge.txt` + 3 张 PNG）、
复验未发现本票改动范围内的回归（故无需修码），装机时全模块 `test` 已绿。

## erratum

1. **并行会话插装**：#88 在 23:58:30、00:10:21 两次覆盖装机（主树 APK mtime 与 `docs/poc-logs/` 同步变动），
   落在 23:58:30 之后的 Agent Mirror 截图按规则作废重拍。两次有效装机前各等满 ≥8 分钟静默窗口
   （23:31:50 拿到第 1 窗；第 2 窗 00:09:54 开窗后 43 秒即被对方装机打断，且该次 `adb install`
   因 MSYS 路径转换未落地；00:20:54 拿到第 3 窗后 00:22:00 装机成功）。
2. **`adb shell` 多词命令必须整体加引号**：不加引号时 Git Bash 先拆词、设备端 sh 再拆一次，
   `cmd notification post -t 产品群 · 王工 rk93a …` 被拆成 title=`产品群`、tag=`·`、text=`王工`——
   首拍 Detail 只剩「产品群/王工」两行、正文缺失。整串加引号重发后
   `android.title`/`android.text` 才正确。
3. **Agent Mirror 首屏四元素不同框（非本票引入）**：按票面 27 字动作行，头部块（状态+工作区+动作 3 行）
   吃满高度，回复区 `weight(1f)` 被压到 0、回复不渲染（轮次 2 只有 5 条文字带）。
   两档下列宽 495→450 下动作行都是 3 行、`ACTION_MAX_CHARS=60` 也未触发截断，故与 #89/#93 的
   gutter 收窄无关；按轮次 3 补短动作截图把回复纳入判读。
4. **截图与亮屏旧坑复用**：`screencap -d <id>` 的 `/sdcard/...` 若作独立参数传会被 MSYS 改写成
   Windows 路径（`adb: failed to stat`），需 `MSYS_NO_PATHCONV=1` 或整串引号；
   主屏 Dozing 态 `KEYCODE_WAKEUP` 拉不醒，须 `KEYCODE_POWER`；有效 SF display id 只有
   `…331`/`…332`，`-d 0`/`-d 1` 不合法（#89 erratum 2 口径）。
5. **收尾状态**：`tag=rk93a` 测试通知已清（`CANCEL_PACKAGE com.android.shell` + `CANCEL_TEST`，
   dumpsys 计数 0）、`POSTURE faceDown=false` 已复位、手动投送已 `EXIT_REAR` 交还。

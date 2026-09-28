# 20260928 票 #88 实机验收：Agent Mirror 二期（v4 真数据面全绿）

- 日期：2026-09-28 11:03–13:40 (+08:00)，小米 17 Pro（94250f9e），debug 构建
- 链路：ZCode 桌面远控会话 → 官方中继（wss://zcode.z.ai）→ v4 workspace-bridge → Conversation V4 增量流
- 设备态：`MILLET_NO_RESTRICT_APP` 含 rearcue（重装后按 ADR 0004 恢复，见正文「装机陷阱」）

## 验收判据（issue #88 Acceptance criteria）

| # | 判据 | 判定 | 关键证据 |
| --- | --- | --- | --- |
| 1 | 实机：真实会话**最新回复原文**上背屏 | **PASS** | 修复后 OCR 实拍：`docs/poc-logs/20260928-88-reply-one-line/`（PR #109）；真数据工作中帧 `rear-working-live.png` |
| 2 | 实机：真实 agent 停下等批准 → 背屏插队＋脉冲（**非注入**） | **PASS** | `rear-waiting-approval.png` OCR「等你确认 …」；logcat `agent-v4 waiting_for_approval → AgentPulse` → `agent pulse end`（13:35:55.501→58.502，13:36:30.871→33.874，均精确 3s） |
| 3 | DND Follow／Quick Tile 在 AGENT 源在屏时的实机回归 | **PASS** | 见下方 DND / Quick Tile 分腿记录 |
| 4 | `gradlew test` 0 失败 | **PASS** | `:rear:test` ＋全量 `gradlew test`（修复工装 worktree 内，BUILD SUCCESSFUL 109 tasks） |

心跳根因（同票顺带项）已于 2026-09-27 落档：`docs/poc-logs/20260927-88-heartbeat-kick.md`（fresh-waiting 发 `pair_status_query` → 同帧 `error KICKED`；心跳永久停用）。

## 判据 1：回复原文上屏（真数据）

数据面全程真链路：`agent-mirror compose status=WORKING … reply=1198` → 屏面 OCR 实读出回复正文
`# RearCue: 把 通知…`（`bright>200` 1610→5525）。

**过程中发现并修复一个真 bug**（PR #109）：动作行 60 字符折 3 行把 `weight(1f)` 回复区挤到零高
——compose 有 reply、屏面逐帧全等无一字；注入短动作一直可见故长期未暴露。修复＝动作行
`maxLines=1`（spec story 7「一行当前动作」本来就是这个意思），单测钉住。

## 判据 2：真实 pendingApproval → 插队＋脉冲（非注入）

- ZCode 权限档切「变更前确认」→ 发真实任务（新建 `APPROVAL_TEST.txt`）→ 桌面端停下要批准；
- 手机侧（**真 v4 帧驱动**，无任何注入）：

```
13:35:55.501 agent-v4 waiting_for_approval → AgentPulse
13:35:55.507 agent-mirror compose status=WAITING_FOR_APPROVAL ws=RearCue
13:35:58.502 agent pulse end
13:36:30.871 agent pulse start / agent-task waiting_for_approval → AgentPulse
13:36:33.874 agent pulse end
```

- 背屏 OCR 实拍（`rear-waiting-approval.png`，3 帧一致）：**「等你确认 RearCue 正在 create a file
  APPROVAL_TEST.txt in the repo root with…」**——等确认放大档＋强调色上屏（story 10/11）。
- 批准动作全程未执行（严格只读）；文件未创建，批准弹窗留给机主处置。

## 判据 3a：DND Follow × AGENT 源（debug 注入定源，DND 走生产缝 `cmd notification set_dnd`）

| 步骤 | 结果 |
| --- | --- |
| AGENT 在屏（OCR「工作中 DND回归动作 回复原文」） | ✓ |
| DND 开（`set_dnd priority`） | logcat `DND开启 → 无需重投`，**无 ExitDashboard**，背屏 OCR 不变（`rear-dnd-on-stays.png`） |
| DND 关（`set_dnd all`） | `DND关闭 → 无需重投`，背屏保持 |

判：AGENT 源豁免 DND（spec story 15 / 门控偏离说明），实机成立。

## 判据 3b：Quick Tile（真 SysUI 点击路径：`cmd statusbar add-tile` + `expand-settings` + `uiautomator` bounds + `input tap`）

| 腿 | 日志锚 | 结果 |
| --- | --- | --- |
| CAST（空屏点贴） | `tile 点击 → 投送` → `manual-cast → LaunchDashboard(1)` → `Dashboard attach 实例数=1`（13:29:49） | ✓ |
| HOLD 豁免（manual 在屏时：注入 agent working / DND 关 / 姿态翻正） | 三条均 `→ 无需重投`，**无 compose 接管、无 Exit**，背屏保持 icon set（`manual-hold` 轮） | ✓ |
| EXIT（在屏点贴） | `tile 点击 → 退出` → `manual-exit → ExitDashboard` → `Dashboard detach 实例数=0`（13:30:26，共 4 次成功） | ✓ |

判：Quick Tile 手动投送/退出语义不变、manual 不被自动逻辑抢占或撤下（spec story 16），实机成立。

## 装机陷阱（当日踩坑，写给下一轮）

1. **重装清 `MILLET_NO_RESTRICT_APP`**（ADR 0004 预警在案）：进程被 Greezer 冻结、认证线程停摆，
   症状是 `agent link CONNECTING` 后永无下文。按 `issue39-millet-original.txt` 恢复含 rearcue 即愈。
2. **`01-install.ps1 -NoDialog` 会跳过 MIUI USB 安装确认** → 安装失败且已先卸载 → 应用与凭据尽失；
   用默认带确认的模式，脚本会自动点对话框。
3. **BAL 拦副屏投送**：应用无可见 Activity 时 `应用内投送` 被 `Abort background activity starts` 拦下；
   App 回前台即恢复，shell `am start --display 1` 是等价兜底（等价 Shizuku 路径）。
4. 背屏被 MIUI `SubScreenLauncher` 收回后，重新投送同样受 3 约束——先拉起主屏 Activity 再投。

## 范围外遗留

- ZCode 桌面「变更前确认」档已复原为「完全访问」；pendingApproval 弹窗未处置（等批准的文件未创建）。
- 修复本体（动作行一行封顶）：PR #109；本记录为验收证据归档。

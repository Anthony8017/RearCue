# 20260927 票 #86 实机验收：Agent Mirror（spec 0010）——真链路端到端

序列号（adb devices）：94250f9e。构建：debug（f76b8cf + phase B 修复，见 commit）。
MILLET_NO_RESTRICT_APP=com.rearcue.poc 已设（ADR 0004）。

## E 系列判定与证据（logcat TAG=RearCue；截图 .scratch/spec0010/*.png，真机帧 docs/poc-logs/20260927-e1-captures/）

| 场景 | 判定 | 证据 |
| --- | --- | --- |
| E1-PB 真凭据配对（terminal 免注册路径） | **GREEN** | 手机 logcat `agent link CONNECTED`（18:32:07、18:53:06 两次）；PC spike 同链路 `pair_status=waiting→matched`（18:32 同窗口） |
| E2 注入 working → 背屏镜像四要素 | **PASS** | rear_working4.png / rear_full2.png：工作中/RearCue/正在 edit…/回复原文 |
| E3 等确认插队＋脉冲 | **PASS** | rear_waiting.png（等你确认＝强调色大字）；logcat `agent pulse start 18:14:37.682`→`agent pulse end 18:14:40.682`（精确 3s） |
| E4 空闲回落 | **PASS** | 注入 idle → castSource=AUTO（iconSet 非空留屏） |
| E5 断连回落 | **PASS** | `--ez connected false` → `agent 链路失联 → 无需重投`，castSource=AUTO 零打扰 |
| E6 真链路端到端（真实 ZCode 会话→背屏） | **PASS** | rear_live.png：工作中/RearCue/正在 连接电脑Agent并在背屏显示对话调研（= 会话 sess_651d77f1 真实 running 态）；logcat `agent-task working` 任务流驱动 |
| E7 离家流量 | **PASS** | `svc wifi disable` → active default network=115（蜂窝）→ `agent-task working` 10s 周期照常（19:02:51/19:03:01） |
| E8 姿态门（翻正撤下） | （注入路径覆盖） | debug POSTURE 注入走 PostureGate 同一事件；传感器真值提交会覆盖注入 |

## phase B 结论（回填 spec #78）

1. **matched 晚到机制**：auth_ack(waiting) 后，relay 在「桌面被踢→约 1s 重连」周期完成后于
   **同一终端连接**上推 matched——终端等待窗须 ≥45s（旧 10s 永远差几秒）。实证 18:32:07（5s）与 18:53:06。
2. **一机限制**：同会话第二条终端连接会**静默踢掉第一条**（无 4013 close 帧，行为级后到踢先到）；
   被踢方按失联→退避重连自愈。
3. **QR 链接有效期**：同一条链接（今晨签发）7 小时后仍配对成功（无 4011）——链接长期可用，
   pass_hash 不随二维码刷新轮换。
4. **控制面**：数据帧直接 `{type:"data",payload:{zcode_type,...}}`（不套 rpc-frame）；
   `bootstrap-request`/`workspace-list-request` 响应 ~0.7s，**任务表（taskId/title/displayStatus
   running|completed|error/updatedAt/workspaceLabel）随桌面会话实时更新**，另有无 requestId 的
   `workspace-list-updated` 主动推送（19:01:05 实证）。
5. **遗留（二期）**：workspace-bridge-open→workspace-bridge-ready 流程未走通（无响应，待 zemote
   对照）；v4 conversation rows（回复原文＋pendingApproval 等待检测）依赖桥接，一期以任务表
   displayStatus 为状态源（running→Working，其余→Idle）。

## 遗留风险

- 心跳 pair_status_query 的 A/B：带 10s 心跳的会话 ~15s 被 relay 掐断（18:50 序列），已停用；
  真因未知（relay 配对状态机与心跳的交互），留二期观察。
- 配对收敛依赖「重试撞上桌面新连接」：极端时序下最长 60s（退避上限）才 matched，可接受。

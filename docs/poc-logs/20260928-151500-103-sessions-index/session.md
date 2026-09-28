# session.md — sessions-index 等待视图实机验证（票 #103 P0 / PR #108 修复轮）

- 时间：2026-09-28 15:08–15:17（本地）
- 分支：`spec/session-lock-glow` + 本轮 P0/P1/P2 修复（worktree `agent-a80efc5bcd12b273b`）
- 构建：本 worktree `:app:assembleDebug`（含 `SessionIndexFeed` / V4Bridge 索引订阅 / 在册护栏 / 词形改措辞）
- 设备：小米 17 Pro `94250f9e`；ZCode 桌面 3.14.3 中继在线
- 证据：`logcat-sessions-index.txt`（订阅链）、`logcat-lock-guard.txt`（护栏＋词形）、`logcat-live-buffer.txt`（收尾现场）

## P0 调查结论（决定本验证对象）

| 查证项 | 结论 | 依据 |
|---|---|---|
| ① 任务表（workspace-list）有无等待字段 | **无**：schema `displayStatus ∈ idle\|running\|completed\|error` | 官方客户端 zod schema（`app.asar` 逆向）＋实抓 payload 字段枚举 |
| ② conversation V4 能否多订/覆盖他会话 | 单订阅一会话（topic `conversation/<id>`），锁档把订阅钉在锁会话 ⇒ 他会话等待帧到不了 | `V4Bridge` 实现与 wire 实抓 |
| ③ 有无覆盖全工作区的等待数据源 | **有：`sessions-index/<workspace>` 主题**——一条订阅推送全工作区会话摘要，`pendingInteraction`（permission/userInput）即等待 | 官方消费面 `pendingInteraction ? "waiting"` 判据（`app.asar`） |

路线：解析 sessions-index → 合成会话状态 → 进 core 仲裁（**非降级**；spec 0010 与 issue #103 无需按降级修订）。

## 实机验证表

| 项 | 结果 | 证据 |
|---|---|---|
| 握手后按工作区订阅 sessions-index | ✓ | `logcat-sessions-index.txt`：`v4 -> sessions-index subscribe path=C:\Users\13691\Desktop\RearCue id=7` |
| 桌面端接受方法名与参数（ack 订阅键） | ✓ | 同上：`v4 sessions-index ack … subId=six-muksldz4-ru6jfxhn-6` |
| 帧包裹与解析假设一致（真实 wire） | ✓ | 同上：连续两帧走 Applied 分支 `frame … n=18`；`frame ignored`=0、`gap`=0（同轮全文计数） |
| 等待判读（本刻无等待会话） | ✓（诚实空集） | `waiting=[]`；任务表轮询首项 `archived:true`（在册为空，索引∩在册=∅ ⇒ 无补发属正确行为） |
| 换向跟随在册护栏（P1） | ✓ | `logcat-lock-guard.txt`：锁 `ghost-verify-1` → `v4 lock-follow skip task=… not in task list`，无订阅帧 |
| 清锁词形（P1） | ✓ | 同上：core 契约锚 `session lock cleared ghost-clear-check` 字节不动；接线层新词形 `lock auto-cleared: …`；全角冒号旧措辞计数=0 |
| `gradlew test` | ✓ | 579 项 0 失败（基线 551，只增不减；P0 判例 `SessionLockIndexTest` 不注入事件、由真实形态 wire 解析合成） |

## 待人工（未伪造）

1. **真实「B 进等待 → 插队 → 处理完回锁」同屏镜头**：本刻任务表全部会话已归档（`parseAll` 在册为空），且 ZCode 桌面端不可控——无法造出一个在册且等确认的会话。协议层与解析层已由本轮实机（订阅/帧）＋ JVM 判例（`SessionLockIndexTest`）两端锁死；真会话现场复现步骤沿用 #106 待人工第 1 条（把某在册会话推入等确认即可）。
2. **跨工作区等待覆盖**：索引按工作区订阅（本刻仅活跃工作区 `Desktop\RearCue`）——补订集合取自**在册（非归档）任务**出现过的工作区；若会话在未覆盖工作区等待，等待检出缺席（插队退化为既有单订阅口径）。现场多工作区复现待人工。

## 过程事件（如实记录）

- 15:00:06 另一流水线向同一设备覆盖安装了不含本 PR 的构建（`lastUpdateTime` 跳变、`SESSION_LOCK` 广播回报「未知调试动作」、APK 字节数与本 worktree 构建不一致），首轮验证被冲掉；15:08:02 重装本构建后完成上述验证。
- 链路在重装后经历约 4 分钟 `RECONNECTING` 循环才 `CONNECTED`（与 #106 待人工第 3 条披露的断连重连现象同形，未在本轮处理）。

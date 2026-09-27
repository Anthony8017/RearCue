# pair_status_query 心跳为何掐断等待中的终端（票 #88 顺带调查）

- 日期：2026-09-27 20:39–20:46 (+08:00)
- 执行：agent 无人值守，spike `tools/ex/e1-relay-spike.mjs`（真凭据 terminal 路径，直连无代理）
- 结论（因果已实证）：**pair_status 状态为 waiting 的终端发 `pair_status_query` → relay 当帧回
  `{type:"error",code:"KICKED"}` 并断连**；不发心跳的对照组同条件等待 150s 无任何掐断。

## A/B 实测

| 组 | 条件 | 结果 |
| --- | --- | --- |
| B（对照，多次） | auth_ack(waiting) 后不发任何应用层心跳 | 会话存活至自设 deadline（45–150s），仅 WS 层 10s ping/pong；relay 无 error 帧（run1/4/5/retry×4/settle×5，共 ≥10 次） |
| A（实验，1 次即触发） | auth_ack(waiting) 后 t+10s 发一条 `pair_status_query` | **同秒**收到 `{"type":"error","server_ts":...,"code":"KICKED","message":""}` → 服务端直接断 TCP（无 close 帧） |

证据帧（`20260927-e1-captures/20260927-203901-e1-frames-v4-bridge-hb2.jsonl`）：

```
C auth_init(role=terminal) → S auth_ack(pair_status=waiting, t+0.24s)
C pair_status_query(device_sid, client_ts)            ← t+10.02s
S {"type":"error","code":"KICKED"}                    ← t+10.09s（查询后 ~70ms）
（随后 TCP 直接断开）
```

对照（同日多次，如 `20260927-204047-…-run4.jsonl`）：waiting 后 150s 全程仅 ping/pong，无 error。

## 与官方客户端语义对照（为何官方不踩）

官方手机 web 客户端（`zcode.z.ai/remote/v4/latest/assets/index-*.js`，`applyPairStatus`）：

- **fresh waiting（从未 matched 过）**：`stopHeartbeat()` + `startWaitingTimer()` —— **不发心跳**，
  只等 relay 推 matched；等超时 → `invalid-mobile-connection` 终态失败。
- `matched`，或 **waiting 且 `wasPaired==true`**（曾配对过的短暂 waiting）：`startHeartbeat()`
  （每 10s±jitter 发 `pair_status_query` + 30s 无 ack 看门狗重连）。
- 收到 `KICKED` error → `session-conflict` 终态失败（不重试）。

即官方**从不在 fresh-waiting 状态发查询**；phase B（票 #86）的实现在 waiting 早期就起心跳，
踩的正是这条 relay 规则。18:50 A/B「带 10s 心跳 ~15s 被掐」与此完全吻合（查询 10s + 应答/拆链 ≈ 15s）。

## relay 内部成因

服务端闭源，无法读取其配对状态机；本仓只固化**因果行为**（查询=fresh-waiting 下的致命输入）
与**正确语义**（对齐官方：fresh-waiting 静默等、matched 后可选心跳）。

## 落地决策（票 #88）

1. **fresh-waiting 不发 `pair_status_query`**：`RelayAuthenticator.startHeartbeat()` 保持停用
   （现状正确，KDoc 依据升级为本文档）。
2. **matched 后也不发**：relay 每 ~10s 的 WS 层 ping 已足够保活；桌面失联由 relay 主动关连接
   （4010）触达，应用层心跳非必需（省一个定时器与看门狗）。
3. matched 等待窗维持 45s（phase B 实机修订）；auth 超时 → 走既有退避重连。

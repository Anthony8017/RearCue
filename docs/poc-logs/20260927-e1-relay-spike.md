# E1 真中继连通性 spike（spec 0010 / ticket #79，phase A）

- 日期：2026-09-27 13:39–13:56 (+08:00)
- 执行：agent 无人值守，全部直连（无代理），工具 `tools/ex/e1-relay-spike.mjs`（node 24 内置模块手写 RFC6455，零依赖）
- 凭据：全部为一次性随机值（随机 device_mid / 随机 pass_hash），不含任何真实凭据

## Verdict：**GREEN**

T2–T8 闸门通过。判定依据（票 #79 验收第 2 条）：mobile 角色注册帧发出后，中继返回协议内响应——实测拿到了完整握手链路直至 `auth_ack`，远超判定线。

## 证据 1：端点可达性（大陆直连）

### wss://zcode.z.ai/ws（主端点）— 可达，质量好

| 阶段 | 实测 |
|---|---|
| DNS | `183.232.185.12` / `.22`（移动 ECDN；响应头 `Server: ESA`、`via: ens-cache*.cn6870` 为阿里边缘节点，境内）6–12ms |
| TCP+TLS | 33–39ms，TLSv1.3 / TLS_AES_256_GCM_SHA384 |
| 证书 | `*.z.ai`，Sectigo，至 2026-11-27 |
| HTTP Upgrade | `101 Switching Protocols`，67–76ms（偶发 292ms） |
| 协议帧 RTT | register→ack 57–60ms；challenge→response→ack 约 55–65ms/轮 |
| 保活 | 服务端每 ~10s 发 WS ping（opcode 0x9，空 payload），客户端 pong 即可 |

### wss://zcode.chatglm.site/ws（备用端点）— 本网络不可用

DNS 解析为 `172.25.136.172`（RFC1918 私网地址，疑似 DNS 污染/内网化），TCP/TLS 永不完成（20s 超时，curl 复核同样立即断开）。主端点不受影响；桌面端日志实测也走 `relay=wss://zcode.z.ai/ws`。RearCue 只需硬编码主端点，endpointOrigin 可切换性保留但备用端点在大陆直连无意义。

## 证据 2：协议内响应（假凭据全链路）

运行 `20260927-135526`（完整录制见 `20260927-e1-captures/`）：

```
[  120ms] -> device_register_init {device_mid:<随机uuid>, pass_hash:<随机43字符base64url>, meta, client_ts}
[  180ms] <- {"type":"device_register_ack","server_ts":1790488526,"device_sid":"d_LoZcBCe6C5LVGJcaKcbPCN"}
[  180ms] -> auth_init {role:"device", device_sid, meta, client_ts}
[  237ms] <- {"type":"auth_challenge","server_ts":...,"nonce":"WsTV-6vBI8KSNZDXBgltbcEH"}
[  238ms] -> auth_response {device_sid, proof:HMAC-SHA256(pass_hash,"nonce|device|device_sid").base64url}
[  304ms] <- {"type":"auth_ack","server_ts":...,"device_sid":"d_LoZcBCe6C5LVGJcaKcbPCN","terminal_sid":"","pair_status":"waiting"}
[10305ms] <- WS ping -> pong（每 10s 一次）
[ 30125ms] 主动关闭，服务端回 CLOSE code=1000
```

关键结论：

1. **随机 pass_hash 被中继接受并创建新会话**（`pair_status:"waiting"` 等待配对端）→ pass_hash 即会话密钥，先到者建会话、后到者凭同 hash 加入。
2. **HMAC proof 公式被生产中继判定通过**（nonce 正确时回 auth_ack 而非 AUTH_FAILED），独立于 bundle 测试向量再次验证 `proof = HMAC-SHA256(key=pass_hash, "${nonce}|${role}|${device_sid}").base64url`。
3. **语义化拒绝路径全部实测**：
   - `auth_init` role 不在白名单（mobile/remote/web/viewer/phone 均 WRONG_PARAM；device/terminal 合法）
   - proof 不匹配 → `{"type":"error","code":"AUTH_FAILED"}`
   - 不发 auth_init 等 30s → 服务端 `AUTH_FAILED` 超时关闭
   - 错误后服务端直接断 TCP（无 WS close 帧）
4. **官方手机端行为确认**（拉取 `https://zcode.z.ai/remote/v4/latest/assets/index-*.js` 逆向）：手机端**不走 register**，直接 `auth_init{role:"terminal", device_sid:<QR sid>, meta:{platform:"web",version,name:"mobile-browser"}}`，passHash 取自 QR `hash` 参数。

## 证据 3：语义 close code

本阶段会话均以 error 帧收场，尚未实测到 4004/4009/4011/4013 close 帧（真实凭据配对后的双连/过期场景才会触发，留 phase B）。close code 表已从 zcode.cjs 固化（4004 SessionNotFound / 4009 SessionConflict / 4010 DesktopDisconnected / 4011 SessionExpired / 4012 WorkspaceClosed / 4013 InvalidMobileConnection），见 `.scratch/spec0010/e1-findings.md`。

## 文件索引

| 文件 | 内容 |
|---|---|
| `tools/ex/e1-relay-spike.mjs` | spike 脚本（可重跑；`--host/--role/--skip-register/--sid/--capture-dir`） |
| `20260927-e1-captures/*.jsonl` | 6 次运行逐帧录制（HTTP upgrade + WS 帧 hex/utf8 双格式） |
| `20260927-e1-captures/20260927-135526-…-zai-device-final.jsonl` | **主向量**：完整握手 + 心跳 + 干净关闭（T2 fixtures 母本） |
| `20260927-135412-…-zai-terminal.jsonl` | terminal 路径（官方手机行为）+ AUTH_FAILED 语义拒绝 |
| `20260927-134149-…-zai-waitchallenge.jsonl` | 30s auth 超时证据 |
| `20260927-133959-…-zai.jsonl` | 首次运行：WRONG_PARAM 证据 |

capture 中的 pass_hash/proof/nonce/device_sid 全部为一次性随机值，会话已销毁，无敏感信息。

## 待 phase B（机主提供二维码链接后）

1. 重跑：`node tools/ex/e1-relay-spike.mjs --host zcode.z.ai --skip-register --role terminal --sid <QR的sid> --hash <QR的hash> --dwell-ms 20000 --capture-dir docs/poc-logs/20260927-e1-captures --label zai-paired`
2. 预期 `pair_status:"matched"`；配对成功后录 `data` 帧（rpc-frame envelope 实样：bridgeSessionId 取值来源、workspace-list、Conversation V4 subscribe/frame payload）回填 T2 fixtures。
3. 澄清两待定项回填 spec：链接有效期（QR `t=` 参数与 4011 的关系）；桌面刷新二维码是否作废已配对（桌面端首次注册后持久化 deviceSid+passHash，刷新 QR 不轮换 pass_hash，推测不作废——需实测）。

# E1 字节级录制向量（spec 0010 / ticket #79）

JSONL，每行一帧：`{ts: 自脚本启动毫秒, dir: C|S, kind: http-upgrade|http-upgrade-response|text|close|ping|pong, utf8, hex(前96字节)}`。

| 文件 | 场景 | 结果 |
|---|---|---|
| `20260927-133959-…-zai.jsonl` | register → auth_init(role=mobile) | WRONG_PARAM（role 白名单外） |
| `20260927-134149-…-zai-waitchallenge.jsonl` | register 后不发 auth_init | 30s 服务端 AUTH_FAILED 超时 |
| `20260927-134651-…-zai-device-role.jsonl` | 全链路 role=device（早期运行） | auth_ack pair_status=waiting |
| `20260927-134824-…-zai-final.jsonl` | role=mobile 复核 | WRONG_PARAM |
| `20260927-135412-…-zai-terminal.jsonl` | **terminal 路径**（官方手机行为，无 register） | auth_challenge → 假 proof → AUTH_FAILED |
| `20260927-135526-…-zai-device-final.jsonl` | **主向量**：register→challenge→ack(waiting)+心跳+干净关闭 | GREEN 母本，T2 fixtures 首选 |

注意：

- 所有 `pass_hash`/`proof`/`nonce`/`device_sid` 均为一次性随机值，会话已销毁，无真实凭据。
- 服务端帧为**带尾部 `\n` 的 JSON 文本**，含 `server_ts`（unix 秒）；客户端帧无尾 `\n`。
- `20260927-135600-…-chatglm.jsonl`（TCP 超时，0 帧）已删，结论记录在 `../20260927-e1-relay-spike.md`。

# 采集/复原轮（票 #27，收口时补记）

- `appops-named-op.txt`：`appops get com.rearcue.poc AUTO_START` → **`Error: Unknown operation string: AUTO_START`**（named op 不存在）
- `appops-op-10008.txt` / `appops-op-10053.txt`：单 op 读口径实测可行（检测口径的最小读法）
- `provider-*.txt`：`content://com.lbe.security.miui.autostartmgr` 三种路径均 `No result found.`（provider 无第三方读面）
- `am-start-missing.txt`：**负对照真样本**（`Error type 3` + `Activity class {...} does not exist.`）→ JUMP-NO-TASK 词表的 fixture
- `appops-chatgpt-pre/post-restore.txt`：**ChatGPT 复原**——`appops set com.openai.chatgpt 10008|10053 allow` 后两 op 回到
  toggle 前的 allow/allow（UI 分段目视复核待解锁）。

注意：本轮实测 `MIUIOP(10008)` 已从 allow 变 ignore（并行代理重装清掉了票 #21 的手工残留）——两 op 现与 UI「禁止」一致。

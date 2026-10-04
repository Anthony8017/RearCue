# DSH 上下文注入 / 通知行（issue #307）deployment

started  : 2026-10-04 16:05 Asia/Shanghai
commit   : 2798098（branch `dsh/injected-context-notice`，已 ff 进 `main`，merge commit `e67e4bb` 带上 #306 的两笔）
scope    : issue #307 / spec 0017 口径（电脑端不可见/不发泡的条目不上背屏） / ADR 0010

## 修的是什么

背屏 Agent 页把 DSH 的**上下文注入**画成了机主提问泡（「当前运行时上下文」「技能清单」
「AGENTS.md 提醒」「技能正文」），子任务/后台任务通知也一样是提问泡。
根因：`mapDshHookToPatch` 的订阅面把 `user/message` 一律当提问，没看 DSH 自己给的 `message.source`。

真机核对（39 个 DSH 会话日志，`~/.dsh/sessions/**/session.v4.jsonl.zstd`）后定的判据：

| `source` | 例 | 处理 |
| --- | --- | --- |
| `kind:'user'` / `user-question-reply` | 本地输入、远端 `user-rpc`、提问点选答复 | 提问泡 |
| `form:'notice' \| 'relay'` | `agent-message` / `subagent-settled` / `tool-jobs` / `model-selection` | **通知行**（低强调一行灰字 + 原文可展开） |
| `form:'snapshot' \| 'catalog' \| 'instructions'` 等 | `runtime-context` / `skill-catalog` / `agent-instructions` / `skill-invocation` / `user-approval` | 不上背屏 |
| `source` 整块缺失（老引擎） | — | 按真实提问保留，仍过票 #248 的文本形状判据 |

通知行的词表跟电脑端一致（客户端 locale `message.trigger.*`）：收到任务消息 / 子任务状态更新 /
后台任务状态更新 / 模型已切换。通知行不是提问也不是回答：不进 `latestReply`、不推进「空闲·没阅」、
不进语音播报，Codex 远程面板的「失败且无输出」判定也不算它。

## 判例

- `node --test tools/bridge/bridge.test.mjs ... tools/bridge/adapters/dsh/*.test.mjs
  tools/bridge/adapters/turn-log.test.mjs` → **158 passed**（含新增：注入不上屏（实时＋历史回放）、
  通知取词与截断、老形状兜底、`hooks/dsh` 端到端通知行、通知不算没阅）。
- `.\gradlew test` → BUILD SUCCESSFUL（新增 `AgentTurnKind.NOTICE` 解码判例）。
- 修前红、修后绿的判据：`上下文注入不当机主提问` 这一组在 `main~` 上必然失败（旧代码无分类）。

## 部署状态

- **桥**：已按部署惯例重启（先写 `bridge.log.stopflag`，再 `taskkill` 旧进程，最后
  `Start-ScheduledTask RearCueBridge`）。旧 pid 225128 → 新 pid 158496（16:16:46）；
  新隧道 `https://headquarters-queensland-postage-exports.trycloudflare.com/`，
  桥已按惯例把新地址推手机并发飞书。`/snapshot` 正常，`capabilities.dsh` 仍为 `waiting+approve`。
- **DSH 插件**：代码在 `main`（profile 用 `link:` 指向本仓库 `tools/bridge/adapters/dsh`），
  但**宿主进程是启动时装载的**——正在跑的这个 DSH 仍是旧代码，**要重启 DSH 才生效**
  （不重启则新事件仍按旧口径转发）。
- **手机 APK**：`app-debug.apk` 已从本分支 worktree 构建成功（`:app:assembleDebug`），
  但无线调试端点从 16:02 起不再应答（`adb devices` 空，`adb connect 192.168.50.252:44947`
  超时，与 #306 部署记录里同一现象），**安装待手机侧重新打开无线调试**。
  未装新包时 `notice` 走 `AgentTurnKind.fromWire` 兜底成普通回答段（不会崩，只是不够低强调）。

## 边界 / 未验

- 背屏视觉未截图：要等 DSH 重启（插件生效）＋ 手机侧安装新包。
- 重启桥后它的内存问答流被清空，历史回放要等 DSH 下次 `session/created`；在那之前背屏
  该会话的窗口偏空（预期，不是缺陷）。
- 本修只覆盖 DSH 来源；ZCode/Codex/Claude 的注入过滤仍走票 #248 的既有路径（未改动）。

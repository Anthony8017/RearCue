# Spec 0031：不自动打开切片与答题接入验证

日期：2026-10-06。基线：`a2df9dd`。分支：`codex/rear-question-choice`。

## 已实现并验证

- Codex 新待答题不自动关闭列表、切会话或改变通知页；Dashboard 已退出时，题目更新不触发投送。
- 新题不强制重置正文回看、不将正文替换为只含问题的视图。
- 桥内同步提问保留 question 事实，真实任务状态保持 working，不冒用批准状态插队。
- 其他来源的待答正文与真正批准的既有规则保留。

## 本地验证

| 检查 | 结果 |
| --- | --- |
| 新增 Core 针对性判例 | 3 通过 |
| `:core:test` | 275 通过，0 失败/错误 |
| `:rear:testDebugUnitTest` | 274 通过，0 失败/错误，含 2 个新增正文判例 |
| `node --test tools/bridge/**/*.test.mjs` | 239 通过，0 失败，含同步提问状态回归 |
| `:app:assembleDebug` | 成功 |
| `git diff --check` | 通过 |

APK：`app/build/outputs/apk/debug/app-debug.apk`。该包仅含本节已实现切片，背屏作答尚未实现。
ADB 当前没有连接设备，未装机、未做此切片的手机目测验收；未更新生产桥或硬停任何生产进程。

## 尚未实现：真实背屏作答

只读 IPC 注册、owner 查询、当前回合快照均成功，但现有 Desktop 写路径自动跨轮重试，缺少外部可用的原子回合护栏；不使用该路径发答复。
同步问题按整个请求解除，逐题立即生效没有可靠事实支持。
已将两个新产品选择（Q12 支持范围、Q13 多题生效时机）写入 [Spec 0031](../../specs/0031-rear-question-choice.md)，待机主选择后继续对应实现。
完整证据与边界见 [调查](../../research/codex-rear-question-reply.md)。

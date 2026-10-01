# 会话标题取 agent 软件真标题，目录名降为副行，sessionId 退出显示

Spec 0016 曾定「标题＝workspace 目录名（无则 sessionId 尾 4 位）」。2026-09-30 机主定案推翻：
主行优先显示 agent 软件自己的会话标题——ZCode 的会话索引自带标题，直接采用，手机与 ZCode
电脑端列表所见一致；副行＝「来源 · 目录名」（如「ZCode · RearCue」）。sessionId 全串退出一切
界面，尾 4 位仅在无标题无目录名、或同列撞名时出现；锁定空窗期沿用缓存的显示名。

## Considered Options

- **桥侧自造标题（否决）**：Codex/Claude/DSH 走 PC 桥（ADR 0006），桥契约只有 workspace 路径
  与来源，没有标题。可以改造桥去读会话文件首条消息生成标题，但桥是生产常驻服务，
  改造与维护风险大于收益——这些来源主行沿用目录名兜底即可。
  未来读者看到「ZCode 显示真标题、Codex 显示目录名」的不对称，是本决定的有意结果，不是漏做。
- **DSH summary 当标题（否决）**：DSH 的 `session/title` 事件被桥归一为「一句话摘要」（summary），
  语义是动态摘要不是稳定显示名，不混用。

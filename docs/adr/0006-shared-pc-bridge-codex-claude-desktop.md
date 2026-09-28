# 6. Agent Mirror 多源接入：Codex 与 Claude Desktop 共用一个开机自启 PC 桥，Chat 标签不镜像

日期：2026-09-28
状态：已接受（grilling 定案；spec 待立）

## 背景

2026-09-28 grilling 把 Agent Mirror 的接入面从「一期 ZCode、二期 Codex 串行」
（ADR 0005 的既定计划）扩为 **ZCode + Codex + Claude Desktop 三源并进**，并回答了三个
可感知取舍：

| 决策点 | 定案 | 关键事实（2026-09-28 调研） |
| --- | --- | --- |
| Codex 怎么接 | PC 桥：rollout JSONL tail（实时输出）+ hooks/notify（回合结束/等待批准），归一为统一会话事件模型 | 会话落 `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`（本机已验证）；官方远控不可复用（ADR 0005 已判）；本机已配 notify 链，机制跑通过 |
| Claude Desktop 怎么接 | **共用同一个 PC 桥**；只镜像 **Code/Cowork 标签会话**（= Claude Code 会话） | Code/Cowork transcript jsonl 落 `~/.claude/projects/<slug>/<sessionId>.jsonl` + hooks 完全可用（本机验证在写）；Desktop 与 CLI 同引擎同配置 |
| Claude Chat 标签（claude.ai 聊天） | **不镜像** | 会话存 IndexedDB LevelDB，无 jsonl、无 hooks、无 API；硬解易碎、维护成本不值 |
| 桥的生命周期 | **开机自启常驻**（一个进程） | 桥很轻（读文件+转发）；手动启动易忘，忘开就没镜像，违背「背屏随时看」 |
| 节奏 | Codex 与 Claude Desktop **同做不分期** | 两者天然同构（hooks 事件 + 会话文件 tail），桥的基础设施一次到位 |

## 决定

手机维持**两条通道**：

1. **ZCode 直连**官方远控中继（ADR 0005 第一期不变，PC 端零安装）；
2. **PC 桥**（Codex + Claude Desktop 共用）：电脑侧一个开机自启的常驻进程，
   把两源的会话事件归一为统一模型，经 ADR 0005 已采纳的 tunwg E2E HTTPS 隧道送手机；
   手机端接入后喂入既有会话流缝（与 ZCode 同一种数据模型，渲染/仲裁零特例）。

Claude Desktop 的镜像范围**限定 Code/Cowork 标签**（即 Claude Code 会话）；Chat 标签
明确出局。Codex 与 Claude Desktop 两个适配器同属一桥、并行实现（票 #116 基础设施 →
#118/#119 适配器），**本 ADR 取代 ADR 0005「第二期 Codex 走 PC 桥共用同一通道」的
二期串行计划**（ADR 0005 原文保留留痕）。

## 后果

- 一桥单点：桥挂了 Codex 与 Claude Desktop 一起断；ZCode 独立不受牵连（两条通道的设计
  正是为此）。适配器相互隔离、单源格式变更不牵连另一源。
- 电脑侧从「零安装」变为「常驻一个桥进程」——仅对 Codex/Claude Desktop 源成立；
  ZCode 源保持零安装。
- 会话文件（rollout/transcript）与 hooks 均为**非稳定接口**，桥须扛版本变更（解析失败
  不崩桥、降级为只读已知段）。
- Chat 标签用户会看到「镜像不了」的边界；如未来官方开放输出流观测再重开本决策。
- 配对/凭据与隧道暴露面沿 ADR 0005 的回退位设计（tunwg E2E HTTPS，手机零安装、不占
  Android VPN 槽位）。

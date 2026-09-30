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

## 补记（2026-09-29）：桥的可观测面与手机侧手填地址

原方案里桥是**纯 headless** 进程：隧道地址落 `tools/bridge/bridge.url`，再靠 adb 广播推给
手机（`bridge.mjs` `publishTunnelUrl`）。实测暴露两个缺口——**手机侧唯一入口是 debug 广播**
（release 构建根本不含 `DebugCommandReceiver`，且当前 release 无签名、不可安装），
以及**隧道进程掉了没有任何自愈**（`child.on("exit")` 只写一行日志，桥进程照旧跑，手机干等）。

本轮 grilling 定案五件：

| 决策点 | 定案 |
| --- | --- |
| 桥的人机界面 | 任务栏托盘图标（Windows）。**图标在＝桥在**，桥停即消失；不做"常驻灰图标" |
| 点开看什么 | 面板显示 Bridge URL 全文 + 复制按钮 + 隧道状态一行；**不做二维码** |
| 图标状态 | 两态，同一图形换色：就绪＝薄荷绿、隧道未就绪＝琥珀黄（另备浅色任务栏的深色版） |
| 手机侧手填 | Agent 设置区新增 Bridge URL 输入框（跟在链路状态行下）；保存前当场探一次 `/health`，不通就提示 |
| 谁覆盖谁 | 自动 adb 推送照旧覆盖手填（手填是 adb 不在场时的兜底，不永久接管）；地址真的变了才弹气泡 |
| 隧道自愈 | 桥加轻看门狗：隧道进程退出即自动重拉一条，新地址自动推手机 + 弹气泡 |
| 多设备 | USB 与无线调试同时在线的机器上，推送需 `ADB_SERIAL` 指定目标机（不给就 "more than one device"，广播发不出去，只能手填） |

图标口径：标记为**拱桥极简**（拱 + 桥面，等粗圆头笔画），资产由桥进程直接把字节写到临时目录加载，
不在仓里新增资产文件；应用图标（本仓原本连 `ic_launcher` 都没有）定为**自适应图标**，
底色深蓝黑、标记薄荷绿——即与托盘"就绪"态同一枚标记。

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

## 补记二（2026-09-30）：切页不再看内容，空页画空态

机主报「点背屏通知页空白处无反应」。实测结论：点按**收到了**（`input -d 1 tap` 连续四次
全部打进 `rear-tap received area=content-page`），切页被规则挡下——`toggleContentPage()`
只切到"有内容的另一边"，而当时 Agent 页没有任何在册会话，于是完全静默地 no-op。
注入一个有内容的会话后同一次点按立刻正常切页（`content page toggle agent` +
`crossfade start/done`），证明链路、手势、切页逻辑都没问题。

机主定夺（反转 spec 0013 的「只切到有内容的另一边」）：

| 决策点 | 定案 |
| --- | --- |
| 点按切页 | **不看内容**：另一边为空也切过去 |
| 空 Agent 页 | 画一行空态说明（「电脑上暂无 agent 会话」）；不画状态词、不给按钮、不加动效 |
| 手动选择 | **不受内容兜底推翻**：站到下一次手动点选或退出投送（WFA 插队照旧临时压过） |
| 诊断 | 被拒路径保留 `content page toggle-rejected <page>` 锚（WFA 期间仍会打）——过去这条路径静默，机主与验收链都看不出「点按丢了」还是「规则挡了」 |

顺带修掉的两个坑：`reconcileContentPage()` 的兜底会在**同一次事件内**把刚切过去的空页踢回来
（日志里 `toggle agent` 紧跟 `fallback notification`），故引入「手动选的页」标记；
`DebugCommandReceiver` 早已在读 `--es source` 但容器侧签名没跟上、debug 源集编译不过，
一并把 `source` 接进 `debugInjectAgentState`（认不出的值记一行日志后当作没给）。

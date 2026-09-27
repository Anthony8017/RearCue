# 5. Agent Mirror 离家通道：复用 ZCode 官方远控中继优先，tunwg 自建隧道回退

日期：2026-09-27
状态：已接受（grilling 定案；spec 待立）

## 背景

Agent Mirror（背屏只读镜像 PC 上 AI agent 会话）要求**离家（手机不在局域网）背屏仍可显示**
（Q10=B），且机主明确「优先复用现成链路，可接受自建工具」（Q11）、在家不插线（Q12=A）。
2026-09-27 调研结论：

| 候选 | 结论 | 关键事实 |
| --- | --- | --- |
| **ZCode 官方 Web 远控中继复用**（手机直连 `wss://zcode.z.ai/ws`） | **采纳（第一期）** | 协议已逆向文档化：QR URL 携 sid+passHash，`proof = HMAC-SHA256(passHash, "nonce\|role\|deviceSid")` 已用 bundle 测试向量独立复算验证；zemote（Flutter）/zflow（Android）复刻先例。PC 端零安装，离家天然可用。私有协议（v4 版本化），无任何 SLA |
| **tunwg 自建隧道**（PC 桥 + E2E HTTPS URL） | **采纳（回退位＋Codex 主通道）** | MIT 可嵌入；大陆公共中继（hapi 官方 relay.hapi.run，腾讯云国际）直连实测可用 ~300ms；手机零安装、不占 Android 唯一 VPN 槽位；不够快可 ~¥15/月 自建国内 VPS 中继，客户端不变 |
| Codex 官方远程 | 不可复用 | ChatGPT App Remote 仅覆盖 ChatGPT 桌面 App 发起的会话（官方文档明示不含 CLI/IDE 扩展）；无云端读取 API。Codex 走 PC 桥（rollout tail + hooks，另见调研） |
| Tailscale / headscale | 否决 | 大陆 UDP 打洞常被干扰、DERP 兜底全海外 100–300ms；且手机必须占用 Android 唯一 VPN 槽位，与机主现有代理 VPN 冲突 |
| frp/rathole + 国内 VPS；Cloudflare Tunnel | 否决（备查） | 前者需 ¥8–17/月 VPS 且非 E2E；后者免费 quick tunnel 域名随机、大陆间歇污染 |

## 决定

第一期只做 ZCode：RearCue 手机端**直连 ZCode 官方远控中继**（复刻私有协议，zemote 同款路线），
读会话状态做只读镜像；PC 端零组件。中继协议被官方变更破坏、或配对/实时性不可用时，
回退 tunwg：PC 起桥（`node zcode.cjs app-server` 订阅 `session/subscribe`，官方本地接口）
经 tunwg 暴露 E2E HTTPS。第二期 Codex 走 PC 桥（rollout tail + hooks）共用同一通道。

## 后果

- 依赖无 SLA 的私有协议：ZCode 桌面版升级可能改协议（v4 版本号/close code/rpc-frame 格式），
  断链只影响 Agent Mirror 单一功能，回退路径已定（tunwg），RearCue 其余功能不受牵连。
- 配对凭据即 QR URL 里的 `hash`：泄露等于交出**控制权**（协议含控制通道）；RearCue 只用其读
  会话状态，但凭据本身能控——保存于应用私有存储，界面不回显、日志不打印。
- 中继同一时刻仅允许一台手机：RearCue 挂着会占掉机主自己浏览器远控的槽位，属已知取舍。
- 回退 tunwg 后在家/离家统一走隧道，局域网直连仅作低延迟优化（可选，非必需）。
- 离家背屏常亮的耗电由 Posture Gate 天然限制（扣下才亮）；HyperOS 后台冻结风险沿用
  ADR 0004 的 MILLET_NO_RESTRICT 机制，不新增保活手段。

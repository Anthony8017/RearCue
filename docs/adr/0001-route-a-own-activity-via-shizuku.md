# Route A：自有 Activity 经 Shizuku 投送背屏

无 root 前提下有两条路线把内容送上小米 17 Pro 背屏：A）自有 Dashboard Activity 经 Shizuku（shell uid）`am start --display` 投送，靠 `miui.rear.policy` meta-data 进系统背屏白名单；B）向 `com.xiaomi.subscreencenter` 的 Smart Assistant 卡片管线注入 MAML 卡片（OuterView/Janus 路线）。决定走 A：B 依赖 LSPosed hook（需 root），与"不 Root"硬约束冲突。后果：需持续应对 subscreencenter 的 Takeover（AOD 抢回），且 HyperOS 更新可能破坏白名单/service call 行为（MRSS 停更先例）；该脆弱性由 RearDisplayBackend 单点封装（见 ADR-0002 与 spec）以控制适配成本。

## Considered Options

- Route A：自有 Activity + Shizuku shell 投送（选定）
- Route B：SubScreenCenter MAML 卡片注入（否决：需 LSPosed/root）
- Route C：root + Xposed（否决：违反不 Root 约束）

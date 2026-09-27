# Spec 0005：Allowlist 管理（增删 UI + 持久化）+ 产品化主页/设置页

状态：已与机主 grill 收口（2026-09-26），frontier 空。

## Problem Statement

Allowlist App 至今是 POC 硬编码的五枚（`PocAllowlist`），机主无法增删——想纳入新应用或剔除不想要的，只能改代码重装。主屏界面也还是调试页形态：状态明细与调试按钮平铺，不像日常使用的入口。

## Solution

主屏拆成两层：**主页**（状态总览 + 开发者选项折叠区）与**设置页**（Allowlist 管理唯一主题）。机主在设置页经 App Picker 添加应用、一键移除，增删即时生效并持久化（DataStore）；首启种子沿用 POC 五枚，升级零迁移、既有验收脚本不破。core 状态机零改动——`DashboardEvent.Allowlist` 的事件→效果语义已有 JVM 单测在位。

## User Stories

1. 作为机主，我希望在设置页看到当前 Allowlist App 名单（图标 + 应用名 + 「通知中」活徽标），以便知道哪些应用会触发背屏图标。
2. 作为机主，我希望通过 App Picker（可桌面启动的应用，带搜索框）添加应用，以便把任何常用 App 纳入指示。
3. 作为机主，我希望搜索框同时匹配应用名与包名，以便按包名定位应用也可行。
4. 作为机主，我希望一键移除名单中的应用、无二次确认，以便快速调整（再添加即恢复，操作天然可逆）。
5. 作为机主，我希望增删即时生效——添加后该应用首条 Active Notification 即点亮 Icon Set 图标，移除时其图标立即从 Icon Set 摘除。
6. 作为机主，我希望名单持久化在设备本地，重启应用/重启手机后仍在。
7. 作为机主，我希望首次安装（含从 POC 版升级）时名单种子为现行五枚（微信、QQ、飞书、本应用、com.android.shell），以便既有行为与验收脚本不破。
8. 作为机主，我希望名单可以全部删掉——空名单下 Icon Set 恒空、永不投送 Dashboard，背屏保持原生。
9. 作为机主，我希望被卸载的应用在名单里显示「未安装」灰色态而非自动消失，以便重装后自动恢复生效（名单是机主的明确选择，系统不代删）。
10. 作为机主，我希望名单按应用名系统排序，以便查找稳定可预期。
11. 作为机主，我希望主页一眼看到可用性横幅、Icon Set 实时卡、监听/通道/通知总数三行摘要，以便不做任何操作也知道当前状态。
12. 作为机主，我希望从主页齿轮图标进设置页、路径唯一，以便不迷路。
13. 作为机主，我希望 Debug Bypass（投送到背屏/退出/发测试通知/清除/打开通知使用权/自启动跳转）与完整状态明细原样收进主页「开发者选项」折叠区，以便既有手动与 adb 习惯一点不变。
14. 作为机主，我希望设置页与主页沿用同一套视觉令牌（AMOLED 纯黑 + 单一强调色、触控目标 ≥48dp、矢量图标、空/错误/禁用态齐全），以便与既有两屏观感一致。
15. 作为开发者，我希望 Allowlist 变更仍走 `DashboardEvent.Allowlist` 事件（收窄/清空/扩容三态语义已有单测），以便 core 零改动、回归有安全网。
16. 作为开发者，我希望持久化只落 `:app` 层、首启种子由存储层直写（空→默认），以便单 seam 原则不破、不为种子逻辑立新 seam。
17. 作为开发者，我希望 Android 11+ 包可见性（`<queries>` 声明）覆盖 App Picker 枚举与名单图标/名称解析，以便在 HyperOS 上功能完整可用。
18. 作为开发者，我希望 `gradlew test` 既有 147 例基线一条不回退。
19. 作为开发者，我希望实机验收链留痕 poc-logs（增→上屏、删→摘除、清空→不投、重启→名单仍在、升级→种子五枚），以便结论可复核。

## Implementation Decisions

- **模块改动**：仅 `:app`（主页重构、设置页、AllowlistStore、App Picker BottomSheet、manifest `<queries>`）；`:core`、`:notification`、`:rear` 不动。
- **core 零改动**：`DashboardCore(initialAllowlist)` 构造注入已存在；启动时从存储读名单注入，此后每次增删发 `Allowlist` 事件，reconcile 自动处理即时上屏/摘除/清空不投。
- **持久化**：DataStore Preferences，存包名集合；首读为空 → 写入 POC 五枚种子 → 发 `Allowlist` 事件；每次增删即写盘 + 发事件（无暂存态、无保存按钮）。
- **交互**：名单行 = 图标 + 应用名 + 活徽标 + 移除按钮；「添加应用」= BottomSheet：App Picker 列表（图标+名称）+ 搜索框，点选即加即关；移除无二次确认。
- **App Picker 范围**：有 launcher intent 的应用，不含无桌面入口的系统组件；复用既有 PackageManager 图标/名称解析，解析失败退化为既有错误态（不崩）。
- **卸载条目**：名单不自动剔除，显示「未安装」灰色态；图标/名称解析失败走同一退化路径。
- **设置页范围**：Allowlist 管理唯一主题；Wake Keep-alive 间隔不暴露 UI（5000ms 已定档，防止用户调进守不住的档位）。
- **主页结构**：可用性横幅（沿用）+ Icon Set 实时卡（沿用）+ 状态摘要三行（监听/通道/通知总数）+ 右上齿轮入口 + 底部「开发者选项」折叠区（6 个调试按钮 + 完整状态明细原样收进）。
- **视觉**：复用 `:rear` 设计令牌；文案用项目术语（Allowlist、Icon Set、Dashboard 等，见 CONTEXT.md）。
- **架构约束不变**：投送主路径 Activity 投送（ADR 0001）、保活周期注入（ADR 0003）、GPL-3.0（ADR 0002）。

## Testing Decisions

- **好测试只测外部行为**（事件→效果）；本轮 core 无新决策 → **不新增 JVM 测试义务**，Allowlist 三态语义沿用既有 DashboardCoreTest 判例。
- **Android/Compose/存储层不写 JVM 决策测试**（本无决策）；以实机验收链替代（spec 0004 同口径）：
  1. 添加应用 → 该应用发通知 → 图标上屏；
  2. 移除有 Active Notification 的应用 → 图标立即摘除；
  3. 清空名单 → 发通知 → 永不投送、背屏保持原生；
  4. 增删后重启应用 → 名单与增删结果一致；
  5. 从 POC 版升级安装 → 种子五枚在册。
- **基线**：`gradlew test` 147 例 0 失败不回退。
- **留痕**：验收轮归档 poc-logs，沿用 E 系列惯例。

## Out of Scope

- 背屏 Dashboard 本体（视觉、防烧屏漂移、Wake Keep-alive）——不动。
- 单条通知增删/屏蔽——术语层面不存在「追踪中的通知」这种对象（Active Notification 是系统事实，Allowlist 增删粒度是应用），永不实现。〔注：spec 0007（2026-09-26）未反转本条——Notification Feed 只读显示最新一条，仍不提供单条管理对象；但「背屏无逐条通知视图」的说法自此不再成立〕
- 保活间隔设置 UI、onboarding/首启引导流程、工作资料/应用双开专门适配。
- 防冻结实现。
- Instrumentation / Compose UI 测试框架引入。

## Further Notes

- 术语改写已随本 spec 落 CONTEXT.md：Allowlist App（用户管理 + 持久化 + 首启种子）、App Picker、Debug Bypass、Main Display（主页+设置页两层）。
- 升级路径：既有安装无持久层 → 首读空 → 种子五枚 = 现行为，零迁移成本。
- 包可见性实现提示：`<queries>` 需覆盖 launcher intent 查询（App Picker 枚举）与按包名取 ApplicationInfo（名单图标/名称）；通知监听本身不受此限。
- 空名单 ⇒ Icon Set 恒空 ⇒ 永不投送，是现状态机自动得出的结论，不需新代码路径。

# design_review 核对清单（票 #25，ui-ux-pro-max）

口径说明：本会话只能读到 skill 的 `SKILL.md`（`design_recommend` / `design_review` / `design_search`
三个查询工具未暴露，web 检索亦不可用），故按 SKILL.md 内置「通用规则 + 交付前检查」清单逐条核对，
取向 = AMOLED 纯黑 + 单一强调色、工具型移动 App、信息密度中等。对比度为独立计算（WCAG 相对亮度），
不推断；实机证据见本目录 screenshots/ 与 ui-*.xml。

| # | 检查项 | 结论 | 证据 / 说明 |
|---|---|---|---|
| 1 | 无 emoji 结构图标（矢量图标集、统一线性风格） | ✅ | 结构图标全部 Material Symbols **Outlined**（统一线性、同一 24dp 视口），尺寸走 `RearCueIconSize` 令牌；应用图标是 PackageManager 位图（语义即「应用图标」，非结构图标）。**刻意例外**：SKILL 默认 Phosphor 图标库未随本机 skill 数据内置且离线不可取，改用平台 Material Symbols Outlined，规则（统一线性/填充 + 尺寸令牌）不降级 |
| 2 | 触控目标 ≥48dp（Android） | ✅ | 调试按钮全宽 + `heightIn(min = RearCueTouch.minTarget=48dp)`；证据 ui-01-empty.xml / ui-04-projected.xml 的 bounds（px ÷ 3.25 = dp） |
| 3 | 按压反馈 80–150ms、不改布局边界 | ✅ | `Modifier.pressFeedback`：缩放 tween `RearCueMotion.pressFeedbackMs = 100ms`（graphicsLayer，不动布局）+ Material3 原生 ripple（平台缓动） |
| 4 | 对比度：正文 ≥4.5:1、次要 ≥3:1（深色独立测试） | ✅ | onBackground #E8E8EA **17.2:1**；onBackgroundSecondary #9EA2A8 **8.2:1**（vs surface 7.2:1）；accent #4D9FFF **7.7:1**、onAccent 7.7:1；error #FF6B6B **7.6:1**、onError 7.6:1。非文字件：outline 2.0:1（装饰描边，不承载信息——**刻意例外**，信息性状态点一律 accent/error ≥3:1）；onBackgroundDisabled 4.3:1（禁用件，WCAG 豁免） |
| 5 | 深浅色模式独立验证 | ⚠️ 单主题 | **刻意例外**：产品定义是纯黑 Dashboard + 单一强调色（CONTEXT.md「Dashboard」），不提供浅色主题；深色侧独立计算如上 |
| 6 | 375px 小屏 + 横竖屏、安全区无遮挡 | ✅ | 主屏 1220px / 520dpi = **375.4dp** 宽（正落 375dp 基准）；竖/横屏截图 01/02/03/04 佐证挖孔 150px、四角圆角 r=190px、手势条 52px 全程无遮挡（避让 = 平台 WindowInsets，见 SafeArea.kt） |
| 7 | 无障碍：标签/角色/焦点、动态字体 | ✅（reduced-motion 除外） | 区块标题 `semantics { heading() }`；装饰图标 contentDescription=null、应用图标 = 应用名；按钮带文字标签；阅读顺序即焦点顺序；文字全走 typography（sp），随系统字号缩放。**刻意例外**：reduced-motion 未接——按压反馈是 ≤100ms 必要反馈（非装饰动画），本轮不随系统「移除动画」关闭 |
| 8 | 语义令牌、不逐屏硬编码 hex | ✅ | `RearCueColors / RearCueSpacing / RearCueIconSize / RearCueShape / RearCueTouch / RearCueMotion`（rear/design）；主屏全量走令牌；背屏 3 处 hex（黑/白/0xFF222222）已换令牌（背屏排版体系归票 #26） |
| 9 | 间距 4/8dp 节奏、纵向层级 16/24/32/48 | ✅ | `RearCueSpacing` xs4/sm8/md16/lg24/xl32/xxl48，屏 gutter=24 |
| 10 | 状态完备：空/加载/错误/禁用 | ✅（加载不适用） | 空态（矢量图标 + 文案，01 图）；错误态（监听/通知使用权/投送通道异常 = error 色 + Warning 图标 + 明示文案）；禁用态（未投送时「退出背屏 Dashboard」禁用 + 说明行，01 图 → 04 图恢复可用）。**刻意例外**：加载态不适用（进程内 StateFlow 同步状态面板，无网络/异步加载，无骨架屏场景） |
| 11 | 长列表虚拟化 / 防抖节流 / 瀑布请求 | 不适用 | 无长列表（Icon Set 枚数级）、无网络请求 |
| 12 | 表单内联校验 / 焦点管理 | 不适用 | 本页无表单 |

票面点名的 design_review 四项（对比度、触控目标、按压反馈、状态完备）= 上表 2/3/4/10，全过。

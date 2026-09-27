package com.rearcue.poc.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rearcue.poc.rear.R

/**
 * RearCue 语义化设计令牌（票 #25）：AMOLED 纯黑 + 单一强调色，工具型移动 App、信息密度中等。
 *
 * 全项目唯一取值源：颜色 / 间距 / 图标尺寸（外加形状、触控、按压节奏）不逐屏硬编码；
 * 屏幕只写「语义名」，换色换档只改这里。令牌落在 :rear 是依赖方向使然（app → rear 单向依赖，
 * 背屏 Dashboard 与主屏调试页共用一套），票 03 起背屏外观同样消费本表。
 *
 * 对比度（深色 AMOLED 底 #000000，WCAG 相对亮度实测口径）：
 * onBackground 17.2:1、onBackgroundSecondary 8.2:1、accent 7.7:1、error 7.6:1（正文 ≥4.5:1、
 * 次要 ≥3:1 均达标）；accent/error 上的字用 onAccent/onError（黑字，同倍率）。非文字件：
 * outline 2.0:1（装饰描边，不承载信息），onBackgroundDisabled 4.3:1（禁用件豁免 WCAG 对比度）。
 */
object RearCueColors {

    /** 页面底色：AMOLED 纯黑。 */
    val background = Color(0xFF000000)

    /** 卡片面：纯黑之上的一档抬升，靠 outline 与底色区分。 */
    val surface = Color(0xFF121316)

    /** 面内强调块（图标退化底、按压块）。 */
    val surfaceHighlight = Color(0xFF1B1D22)

    /** 描边/分隔线：装饰件，纯黑上可见（承载信息的状态点一律用 accent/error）。 */
    val outline = Color(0xFF3C4046)

    /** 正文。 */
    val onBackground = Color(0xFFE8E8EA)

    /** 次要文字（标签、说明）。 */
    val onBackgroundSecondary = Color(0xFF9EA2A8)

    /** 禁用文字/图标。 */
    val onBackgroundDisabled = Color(0xFF6E7278)

    /** 单一强调色：健康态、主操作、关键值。 */
    val accent = Color(0xFF4D9FFF)

    /** 强调色上的文字/图标。 */
    val onAccent = Color(0xFF000000)

    /** 错误/异常语义色（非强调色；只给错误态，不做装饰）。 */
    val error = Color(0xFFFF6B6B)

    /** 错误色上的文字/图标。 */
    val onError = Color(0xFF000000)

    /**
     * Notification Highlight 暖白强调色（spec 0008 / 票 #65）：整屏呼吸光晕、边缘微光描边与
     * 高亮图标描边共用，取设计稿 `docs/mockups/0008-dashboard-visual/chatgpt/02-highlight.png`
     * 的 #F2E9D8 系（黑底暖白、光效克制，贴原生背屏设计语言）。
     */
    val highlightWarm = Color(0xFFF2E9D8)

    /**
     * Detail View 卡片面（spec 0008 / 票 #66）：黑底之上的深灰抬升圆角卡片
     * （对照设计稿 `chatgpt/03-tap-fulltext.png`——卡片明显亮于纯黑一档，白字直接可读）。
     */
    val detailSurface = Color(0xFF232529)

    /**
     * Charging Animation 绿色比例填充（spec 0008 / 票 #67）：低饱和翠绿渐变的两档 +
     * 上缘亮边微光，对照设计稿 `chatgpt/04-charging-green.png`——填充自底部按电量比例
     * 渐变（[chargingFillBright] 靠上缘、[chargingFillDeep] 沉底），上缘一道
     * [chargingEdgeGlow] 亮边微光；白色大号数字用纯白（AMOLED 黑底/绿底上对比度最高）。
     */
    val chargingFillBright = Color(0xFF2FBF71)
    val chargingFillDeep = Color(0xFF0D4D30)
    val chargingEdgeGlow = Color(0xFFA6FFD1)
}

/** 间距令牌：4/8dp 节奏，纵向层级 16/24（screenGutter 还承担主屏安全区留白下限）。 */
object RearCueSpacing {

    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp

    /** 屏幕内容留白（≥ 四角圆角安全留白时取本值，见 [RearCueTheme] 的安全区实现）。 */
    val screenGutter = lg
}

/** 图标尺寸令牌：结构图标三档 + Icon Set 应用图标两档（术语见 CONTEXT.md「Icon Set」）。 */
object RearCueIconSize {

    /** 行内状态图标。 */
    val small = 20.dp

    /** 按钮/标题图标。 */
    val medium = 24.dp

    /** 空态/错误态插图。 */
    val large = 32.dp

    /** 主屏 Icon Set 一枚应用图标。 */
    val iconSetMainDisplay = 48.dp

    /**
     * 背屏 Icon Set 一枚应用图标：spec 0008 起背屏常态只有图标（无时间、无横幅），
     * 按设计稿 `docs/mockups/0008-dashboard-visual/chatgpt/01-idle-icons.png` 放大
     * （64dp → 96dp）；超出安全矩形的部分由 fitScale 等比收口（票 #26 机制不动）。
     */
    val iconSetRearDisplay = 96.dp
}

/** 圆角半径令牌（dp）。 */
object RearCueShape {

    val medium = 12.dp
    val large = 16.dp

    /** Detail View 卡片圆角（spec 0008 / 票 #66）：设计稿 03 的大圆角一档。 */
    val detailCard = 24.dp
}

/** 触控目标令牌。 */
object RearCueTouch {

    /** Android 触控目标下限（≥48dp）。 */
    val minTarget = 48.dp
}

/** 动效节奏令牌。 */
object RearCueMotion {

    /** 按压反馈时长（ms）：80–150ms 带内，配平台原生 ripple。 */
    const val pressFeedbackMs = 100
}

/**
 * 充电水面微波令牌（spec 0009 / 票 #75）：复合正弦两列（主列长波 + 涟漪短波）＋慢相位漂移。
 * 安静档——波峰谷合计仅数 px，无气泡、无 3D 重力液体（CONTEXT.md `_Avoid_`）。
 * 实机帧率/发热不达标时只调这里。
 */
object RearCueChargingWave {

    /** 主列振幅（≈4px @450dpi）。 */
    val amplitudeMain = 1.4.dp

    /** 主列空间波长。 */
    val wavelengthMain = 72.dp

    /** 涟漪振幅（≈2px @450dpi）。 */
    val amplitudeRipple = 0.7.dp

    /** 涟漪空间波长。 */
    val wavelengthRipple = 41.dp

    /** 相位回卷周期（ms）：10π 弧度一个无缝循环（50s），漂移速率 ≈0.63 rad/s（10s 走 2π 量级）。 */
    const val phasePeriodMs = 50_000
}

/** 字体令牌（spec 0009 / 票 #72）：只放确实跨屏复用/需集中换档的字体族。 */
@OptIn(ExperimentalTextApi::class)
object RearCueTypography {

    /**
     * 充电大数字字体（spec 0009 / 票 #72）：Outfit Light——几何感现代细体，大字号下
     * 高级耐看；SIL OFL 授权（许可证随仓库 assets/fonts）。变字体单文件，以 wght=300 取
     * Light；实机观感不满意时换 Manrope Light：仅换 `res/font` 文件与本条目，无逻辑耦合。
     * 只用于充电大数字——Detail 正文小字号细体可读性差，保持系统字体。
     */
    val chargingNumber = FontFamily(
        Font(
            resId = R.font.outfit_variable,
            weight = FontWeight.Light,
            variationSettings = FontVariation.Settings(FontVariation.weight(300)),
        ),
    )
}

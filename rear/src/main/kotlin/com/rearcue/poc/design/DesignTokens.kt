package com.rearcue.poc.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rearcue.poc.rear.R
import kotlin.math.ceil
import kotlin.math.roundToInt

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

    /** 描边/分隔线：装饰件，纯黑上可见（承载信息的状态标识一律走语义色令牌）。 */
    val outline = Color(0xFF3C4046)

    /** 正文。 */
    val onBackground = Color(0xFFE8E8EA)

    /** 次要文字（标签、说明）。 */
    val onBackgroundSecondary = Color(0xFF9EA2A8)

    /** 禁用文字/图标。 */
    val onBackgroundDisabled = Color(0xFF6E7278)

    /** 单一强调色：健康态、主操作、关键值。 */
    val accent = Color(0xFF4D9FFF)

    /** 提问泡底衬：工作中蓝调暗两档，保留蓝相并让白字对比达到正文档。 */
    val promptBubbleBackground = Color(0xFF2F6098)

    /** 强调色上的文字/图标。 */
    val onAccent = Color(0xFF000000)

    /** 错误/异常语义色（非强调色；只给错误态，不做装饰）。 */
    val error = Color(0xFFFF6B6B)

    /** 错误色上的文字/图标。 */
    val onError = Color(0xFF000000)

    /**
     * 等待确认语义色（琥珀黄，spec 0021 / 票 #208）：状态光带等待档——呼吸、全场最亮。
     * 蓝让给工作中档后，等待以「琥珀黄＋呼吸」双重区分（ADR 0012）；黑底对比约 12:1，
     * 取值实机验收定稿。
     */
    val waiting = Color(0xFFFFB84D)

    /**
     * 空闲语义色（绿，spec 0021 / 票 #208）：状态光带空闲档——静止低亮（「没事，不用管」）。
     * 与充电绿同屏可辨是验收点：光带是边缘细环、充电水位是整屏渐变（chargingFill*），
     * 几何分工不靠色值区分。取值实机验收定稿。
     */
    val idle = Color(0xFF3ECF8E)

    /**
     * 会话状态标识专用语义色（2026-10-04 机主定夺）：它与屏幕边缘的 Status Glow 分工不同，
     * 因此不再强制同色。工作中为参考图的中性浅灰 Spinner；等待确认绿；空闲没阅蓝；
     * 出错红；断链灰。色值仍集中在此，渲染层不得另取色。
     */
    val sessionWorkingSpinner = Color(0xFFB3B3B3)
    val sessionWaiting = Color(0xFF3ECF8E)
    val sessionIdleUnread = Color(0xFF4D9FFF)
    val sessionError = Color(0xFFFF6B6B)
    val sessionDisconnected = Color(0xFF9EA2A8)

    /**
     * Notification Highlight 暖白强调色（spec 0008 / 票 #65）：整屏呼吸光晕与边缘微光描边共用
     * （高亮图标描边随图标高亮退役删除，2026-09-28 grilling 定案），取设计稿
     * `docs/mockups/0008-dashboard-visual/chatgpt/02-highlight.png` 的 #F2E9D8 系
     * （黑底暖白、光效克制，贴原生背屏设计语言）。
     */
    val highlightWarm = Color(0xFFF2E9D8)

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

    /**
     * 正文阅读面（Detail / Agent Mirror，grill #89 定案）的**左缘**设计留白地板：
     * 左缘通常由相机带几何抬得更高（textHorizontalPadding 的地板语义，本机 304px），
     * 无带几何时才由本值兜底。**右距不走本值**——票 #97 定案右距屏缘 8px 视觉值
     * （`DisplaySafeArea.TEXT_EDGE_GUTTER_PX`，进圆角弧区自动外扩），推翻 0011 的右留空 150px。
     */
    val readingGutter = 53.dp

    /** 屏幕内容留白（≥ 四角圆角安全留白时取本值，见 [RearCueTheme] 的安全区实现）。 */
    val screenGutter = lg
}

/**
 * [RearCueSpacing.readingGutter] 的像素地板：dp→px 向上取整。
 *
 * 设计留白是「地板」，地板不许向下取整：`roundToPx()`（四舍五入）在本机会把 53dp@450dpi
 * 的 149.06 收成 149，落不到 150px 档；向上取整 149.06→150 恰落档
 * （DisplaySafeAreaTest 钉住本转换环）。Detail / Agent Mirror 两处消费点统一走本函数，
 * 且**只作左缘地板**——右距是票 #97 的 8px 视觉值，不经本函数。
 */
fun Density.readingGutterFloorPx(): Int =
    ceil(RearCueSpacing.readingGutter.value * density).roundToInt()

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

    /** 桌面独立图标的等物理尺寸：186px × 400.028 / 460.446 ≈ 162px，本机 450dpi。 */
    val iconSetRearDisplay = 57.6.dp
}

/**
 * Spec 0012：六格容量内的图标间距和最终屏上角标；角标字号不随整组缩放。
 * 2026-09-28 本机桌面取样：186px 图标、65px 角标，按两屏物理 dpi 换算为背屏约
 * 162px / 56.5px；目标尺寸直接参与圆角感知布局，不再用空行/空徽标带缩小整组。
 */
object RearCueNotificationIcons {
    val horizontalGap = 8.dp
    val verticalGap = 8.dp
    /** 桌面角标在图标右侧、上侧各外探 18/186 个图标边长。 */
    const val badgeOverhangRatio = 18f / 186f
    val badgeSize = 20.dp
    val badgeFontSize = 11.sp
    val badgeHorizontalPadding = 4.dp
    // 桌面截图的 Display P3 像素 #E6462F 转为 sRGB #FA311B；与 Compose Color 的色域一致。
    val badgeBackground = Color(0xFFFA311B)
    val badgeForeground = Color.White
}

/** 圆角半径令牌（dp）。 */
object RearCueShape {

    val medium = 12.dp
    val large = 16.dp
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
 * 安静档——峰谷合计 1.7dp（≈4.8px @450dpi，spec 的 3–5px 带内），无气泡、无 3D 重力液体
 * （CONTEXT.md `_Avoid_`）。实机帧率/发热不达标时只调这里。
 */
object RearCueChargingWave {

    /** 主列振幅。 */
    val amplitudeMain = 1.2.dp

    /** 主列空间波长。 */
    val wavelengthMain = 72.dp

    /** 涟漪振幅。 */
    val amplitudeRipple = 0.5.dp

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

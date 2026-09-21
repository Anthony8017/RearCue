package com.rearcue.poc.rear

/**
 * 一块屏幕的判定输入：只保留识别背屏需要的事实，Android 层负责从 `Display` 采集。
 *
 * [flags] 是 `Display.FLAG_*` 位掩码原样传入，避免在纯 Kotlin 里复刻 Android 常量。
 */
data class ScreenInfo(
    val displayId: Int,
    val name: String,
    val flags: Int,
    val isDefault: Boolean,
)

/**
 * Rear Display（背屏）识别：非默认 + INTERNAL + `FLAG_PRESENTATION` + `FLAG_OWN_DISPLAY_GROUP`。
 *
 * 依据见 `docs/poc-findings.md`（本机实测 displayId=1、904×572、左 cutout 296px）：
 * 不硬编码 displayId，HyperOS 换机/更新后仍按 flag 判定。
 */
object RearDisplayLocator {

    // 位次按本机实测锁定（见 RearDisplayLocatorTest 的 16515/16779 断言）：
    // 背屏相对主屏多出的两位就是 PRESENTATION 与 OWN_DISPLAY_GROUP。
    // AOSP 里 FLAG_PRESENTATION 是 @SystemApi，SDK 拿不到，故按值硬编码。
    private const val FLAG_PRESENTATION = 1 shl 3 // 8
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 8 // 256

    private const val REQUIRED_FLAGS = FLAG_PRESENTATION or FLAG_OWN_DISPLAY_GROUP

    /** 返回判定为背屏的屏幕；没有匹配（如主屏单屏设备、接口预留的其它 ROM）返回 null。 */
    fun locate(screens: List<ScreenInfo>): ScreenInfo? =
        screens.firstOrNull { screen ->
            !screen.isDefault && (screen.flags and REQUIRED_FLAGS) == REQUIRED_FLAGS
        }
}

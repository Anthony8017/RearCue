package com.rearcue.poc.rear

/**
 * 在屏事实（CONTEXT.md「Presence」）：Dashboard 当前在不在背屏。
 *
 * 三态分开是因为「投送已发出」不等于「已上屏」：E7 实测 startActivity 到界面 onCreate 有
 * ~40ms 空档，这段空档既不能算在屏、也不能算不在屏（会误判成「被顶掉」而重复投送）。
 */
enum class Presence {
    ABSENT,
    LAUNCH_PENDING,
    ON_SCREEN,
}

/**
 * 在屏事实的唯一读口：`RearDashboardHost` 的实例登记与「上屏在途」竞态标记都藏在它背后，
 * 业务代码不再直读这两个来源——「Dashboard 在屏与否」全项目只认这里。
 *
 * 登记口径沿用 [RearDashboardHost]：按「实例存在」而不是「实例在屏」（onStop 后仍占着背屏）。
 */
object DashboardPresence {

    /** 上屏在途（已 startActivity、界面还没 onStart）：只有投送路径写它。 */
    @Volatile
    var launchPending: Boolean = false

    /** 纯判定：实例存在优先于「在途」；两者皆无 = [Presence.ABSENT]。 */
    fun of(instanceCount: Int, launchPending: Boolean): Presence = when {
        instanceCount > 0 -> Presence.ON_SCREEN
        launchPending -> Presence.LAUNCH_PENDING
        else -> Presence.ABSENT
    }

    /** 运行时读数。 */
    fun read(): Presence = of(RearDashboardHost.instanceCount, launchPending)
}

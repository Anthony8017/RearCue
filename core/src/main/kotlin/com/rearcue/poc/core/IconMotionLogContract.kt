package com.rearcue.poc.core

/**
 * 图标出入场动效日志锚词形契约（spec 0015 / 票 #147 入场、票 #148 退场）。
 *
 * 词形供 tools/ex 实机验收链读取，**byte 不可改**；pkg 是应用包名。
 * 渲染侧只在某枚图标真正进入/退出当前可见 Icon Set、即将播放入场/退场动效时调用
 * [enter] / [exit]——持续退场中的条目跨帧不重复打。
 */
object IconMotionLogContract {
    const val ENTER = "icon enter <pkg>"
    const val EXIT = "icon exit <pkg>"
    const val CONTRACT = ENTER + "; " + EXIT

    fun enter(pkg: String): String = ENTER.replace("<pkg>", pkg)

    fun exit(pkg: String): String = EXIT.replace("<pkg>", pkg)
}

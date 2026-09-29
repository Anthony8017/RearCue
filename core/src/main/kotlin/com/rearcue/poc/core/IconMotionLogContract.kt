package com.rearcue.poc.core

/**
 * 图标入场动效日志锚词形契约（spec 0015 / 票 #147）。
 *
 * 词形供 tools/ex 实机验收链读取，**byte 不可改**；pkg 是应用包名。
 * 渲染侧只在某枚图标真正进入当前可见 Icon Set、即将播放入场动效时调用 [enter]。
 */
object IconMotionLogContract {
    const val ENTER = "icon enter <pkg>"
    const val CONTRACT = ENTER

    fun enter(pkg: String): String = ENTER.replace("<pkg>", pkg)
}

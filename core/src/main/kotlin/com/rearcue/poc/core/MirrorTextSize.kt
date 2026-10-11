package com.rearcue.poc.core

/**
 * Agent 页字号兼容类型（2026-10-11 机主定案：用户选项已删除，统一使用小档）。
 *
 * 为什么住在 [:core]：存量持久化值与多层渲染接口仍共享这个类型。具体 sp 值继续留在
 * [:rear] 的 `AgentMirrorParams.reading`，纯 JVM 模块不掺排版细节。
 */
enum class MirrorTextSize {
    SMALL,
    MEDIUM,
    LARGE,
    ;

    companion object {
        /** 唯一用户可见档；旧中/大值读入时也归一到这里。 */
        val DEFAULT = SMALL

        /** 持久化字面量读取：只承认小档，旧中/大与未知值都归一为默认小档。 */
        fun fromName(name: String?): MirrorTextSize =
            if (entries.firstOrNull { it.name == name } == SMALL) SMALL else DEFAULT
    }
}

package com.rearcue.poc.core

/**
 * Agent 页正文档位（spec 0017 / 票 #169）：主屏首页 Agent 卡片里的三档单选，**默认 [MEDIUM]**。
 *
 * 为什么住在 [:core]：这一档是**用户偏好**——主屏设置写它、背屏渲染读它、仲裁层只搬运不解释，
 * 三方共用同一个类型才不会出现「设置写 1、渲染认 2」。具体字号（14/16/20sp）不是偏好的事，
 * 留在 [:rear] 的 `AgentMirrorParams.reading`——把 sp 值放这里会让纯 JVM 模块被排版细节污染。
 */
enum class MirrorTextSize {
    SMALL,
    MEDIUM,
    LARGE,
    ;

    companion object {
        /** 出厂默认档（与 `AgentMirrorParams.reading` 的映射同源）。 */
        val DEFAULT = MEDIUM

        /** 持久化字面量 ↔ 枚举（DataStore 只存名字；未知/空字符串退到默认档，不抛）。 */
        fun fromName(name: String?): MirrorTextSize =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

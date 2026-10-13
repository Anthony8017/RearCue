package com.rearcue.poc.core

/** 自动切页的显示顺序；不改变投送条件或语音播放。 */
enum class DisplayPriority {
    APPROVAL, QUESTION, NOTIFICATION, VOICE;

    companion object {
        val DEFAULT: List<DisplayPriority> = entries.toList()

        fun normalize(order: List<DisplayPriority>): List<DisplayPriority> =
            order.distinct() + DEFAULT.filter { it !in order }
    }
}

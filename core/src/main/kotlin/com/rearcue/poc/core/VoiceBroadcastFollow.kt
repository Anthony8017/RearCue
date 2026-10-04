package com.rearcue.poc.core

/**
 * Voice Broadcast Follow 的 UI 跟随事实（ADR 0023）。
 * app 层把语音队列的当前句投影到这里，:rear 只按它滚动；不承载音频播放状态。
 */
data class VoiceBroadcastFollow(
    val itemId: Long,
    val sessionId: String,
    val turnEntryId: String?,
    val sourceBody: String?,
    val sentenceText: String,
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val visualAnchor: Boolean,
)

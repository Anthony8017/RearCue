package com.rearcue.poc.core

/** 播报句高亮的可见目标；合成提示不进入任何目标。 */
enum class VoiceBroadcastVisualTarget {
    REPLY,
    QUESTION,
    APPROVAL,
}

/**
 * Voice Broadcast Follow 的 UI 跟随事实（ADR 0023）。
 * app 层把语音队列的当前句投影到这里，:rear 按它滚动并高亮；不承载音频播放状态。
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
    /** 已朗读的正文句，用顺序定位区分原文里重复出现的同一句。 */
    val precedingSentenceTexts: List<String> = emptyList(),
    val visualTarget: VoiceBroadcastVisualTarget = VoiceBroadcastVisualTarget.REPLY,
    val requestId: String? = null,
    /** 音频中的实际句号；[sentenceIndex] 是可见句号，略过句沿用上一处可见句。 */
    val spokenSentenceIndex: Int = sentenceIndex,
)

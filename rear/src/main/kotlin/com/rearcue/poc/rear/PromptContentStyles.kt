package com.rearcue.poc.rear

import androidx.compose.ui.graphics.Color
import com.rearcue.poc.agent.AgentSources
import com.rearcue.poc.design.RearCueColors

/**
 * Prompt Bubble（提问泡）**泡内**的来源配色（票 #248）。
 *
 * 桥的问答流只带原文，不带客户端实时主题；这里按 ZCode / Codex / Claude Desktop /
 * DeepSeek Harness 各给一套固定近似色，匹配用户可见的来源身份。范围严格限于泡内正文、
 * 行内/围栏代码、普通链接与内嵌引用标签；气泡外框和底色仍由 `AgentReadingText` 管。
 *
 * 取不到来源时用 [neutral]。若后续实机验收发现某色在提问泡底色上低于可读阈值，
 * 只允许在本表内做最小亮度修正，不换色系。
 */
data class PromptContentStyle(
    val text: Color,
    val code: Color,
    val link: Color,
    val mention: Color,
    val mentionBackground: Color,
)

object PromptContentStyles {
    val neutral = PromptContentStyle(
        text = RearCueColors.onBackground,
        code = RearCueColors.onBackgroundSecondary,
        link = RearCueColors.accent,
        mention = RearCueColors.accent,
        mentionBackground = RearCueColors.accent.copy(alpha = 0.18f),
    )

    private val codex = PromptContentStyle(
        text = Color(0xFFF2F4F7),
        code = Color(0xFFB8E4D2),
        link = Color(0xFF19C37D),
        mention = Color(0xFF19C37D),
        mentionBackground = Color(0xFF19C37D).copy(alpha = 0.18f),
    )

    private val claude = PromptContentStyle(
        text = Color(0xFFF5F0EB),
        code = Color(0xFFE8C7BA),
        link = Color(0xFFD97757),
        mention = Color(0xFFD97757),
        mentionBackground = Color(0xFFD97757).copy(alpha = 0.18f),
    )

    private val zcode = PromptContentStyle(
        text = Color(0xFFEAF2FF),
        code = Color(0xFFB8C7E8),
        link = Color(0xFF4D9FFF),
        mention = Color(0xFF4D9FFF),
        mentionBackground = Color(0xFF4D9FFF).copy(alpha = 0.18f),
    )

    private val dsh = PromptContentStyle(
        text = Color(0xFFEDF1FF),
        code = Color(0xFFC1C9FF),
        link = Color(0xFF4D6BFE),
        mention = Color(0xFF4D6BFE),
        mentionBackground = Color(0xFF4D6BFE).copy(alpha = 0.18f),
    )

    /** 来源缺省、旧事件或未知来源都不猜品牌，退回全项目中性可读配色。 */
    fun forSource(source: String?): PromptContentStyle = when (source?.trim()?.lowercase()) {
        AgentSources.ZCODE -> zcode
        AgentSources.CODEX -> codex
        AgentSources.CLAUDE -> claude
        AgentSources.DSH -> dsh
        else -> neutral
    }
}

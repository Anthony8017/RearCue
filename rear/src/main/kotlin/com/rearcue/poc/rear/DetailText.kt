package com.rearcue.poc.rear

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rearcue.poc.design.RearCueColors

/** 通知详情沿用既有字号、标题省略规则和从开头进入的滚动策略。 */
@Composable
internal fun DetailText(title: String, body: String, rules: SafeArea, scroll: ScrollState) {
    CenteredReadingText(
        heading = title,
        body = body,
        headingStyle = TextStyle(
            color = RearCueColors.onBackground,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
        ),
        bodyStyle = TextStyle(
            color = RearCueColors.onBackground,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        rules = rules,
        scroll = scroll,
    )
}

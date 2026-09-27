package com.rearcue.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTouch

/**
 * 设置页分区卡片（spec 0007 story 12）：名单区与充电区共用的外壳——同一形状/表面/描边/
 * 间距令牌，分区标题带 [heading] 语义。两个分区各画一份壳是评审抓过的重复（漂移点：
 * 横幅区标题没带 heading 语义就是这么丢的），从此只此一件。
 * （spec 0008：原第三张卡片「横幅区」随横幅退役删除，共享件由名单区与充电区继续共用。）
 */
@Composable
fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(RearCueShape.large)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RearCueColors.surface)
            .border(1.dp, RearCueColors.outline, shape)
            .padding(RearCueSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/**
 * 设置页开关行（全页唯一开关件，充电总开关在用；原 Privacy / 无上限开关随横幅退役删除，
 * spec 0008）：整行 [toggleable]（触控目标 ≥[RearCueTouch.minTarget]、[Role.Switch] 语义）
 * + 自绘轨道滑块。开 = 强调色轨道 + 黑滑块，关 = 面内高亮轨 + 次要色滑块（单色令牌，
 * 不用第二强调色）；触控与语义由整行承担，滑块只做显示（票 #56 判例，评审抓过的双开关
 * 实现到此收口）。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RearCueTouch.minTarget)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = RearCueColors.onBackground,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = RearCueColors.onBackgroundSecondary,
                )
            }
        }
        ToggleTrack(checked = checked)
    }
}

/**
 * 开关滑块（纯显示）：轨道 + 滑块，只画不响应——触控目标与 [Role.Switch] 语义由
 * [SettingsSwitchRow] 的整行承担。
 */
@Composable
private fun ToggleTrack(checked: Boolean) {
    val track = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(track)
            .background(if (checked) RearCueColors.accent else RearCueColors.surfaceHighlight)
            .border(1.dp, if (checked) RearCueColors.accent else RearCueColors.outline, track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(RearCueSpacing.xs)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) RearCueColors.onAccent else RearCueColors.onBackgroundSecondary),
        )
    }
}

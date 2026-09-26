package com.rearcue.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueShape
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTouch

/**
 * 充电区（spec 0007 story 12 / 票 #57）：充电动画总开关——一区一卡、一个开关一个语义。
 *
 * 自成一件：自己取容器状态、自己发写入口，设置列里只占一行，与横幅区（票 #56）互不依赖
 * （两区各自独立增删，合并时只在同一列相邻插行）。
 *
 * 开关即时生效：容器发 `DashboardEvent.ChargingAnimation` 并写盘（spec 0007 story 11），
 * 决策与语义全在 DashboardCore（关 = 插电无反应、关掉 = 按退出合收取口），本页零决策。
 * 触控目标 ≥ [RearCueTouch.minTarget]（Switch 自带 minimumInteractiveComponentSize 下限）。
 */
@Composable
fun ChargingSettingsSection() {
    val container = (LocalContext.current.applicationContext as RearCueApp).container
    val enabled = container.state.collectAsState().value.chargingEnabled
    val shape = RoundedCornerShape(RearCueShape.large)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RearCueColors.surface)
            .border(1.dp, RearCueColors.outline, shape)
            .padding(RearCueSpacing.md),
        verticalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.settings_charging_title),
            style = MaterialTheme.typography.labelMedium,
            color = RearCueColors.onBackgroundSecondary,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RearCueTouch.minTarget),
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RearCueSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.settings_charging_switch),
                    style = MaterialTheme.typography.bodyLarge,
                    color = RearCueColors.onBackground,
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.settings_charging_on else R.string.settings_charging_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = RearCueColors.onBackgroundSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = container::setChargingAnimationEnabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = RearCueColors.accent,
                    checkedThumbColor = RearCueColors.onAccent,
                ),
            )
        }
    }
}

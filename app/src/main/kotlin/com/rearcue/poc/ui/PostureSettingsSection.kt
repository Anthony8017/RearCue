package com.rearcue.poc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rearcue.poc.R
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueSpacing

/**
 * 姿态区（票 #100）：姿态门控用户开关——一区一卡、一个开关一个语义，沿充电区
 * [ChargingSettingsSection] 的同款判例（[SettingsSectionCard] + [SettingsSwitchRow]，
 * 状态与写入口由调用方注入，本件不自取容器）。
 *
 * 开关**默认关**（旁路：正放/倒扣都照常投送）；开 = 恢复 spec 0006 姿态门控（正放拦/撤、
 * 倒扣补投）。开关即时生效：容器发 `DashboardEvent.PostureGateEnabled` 并写盘，决策与语义
 * 全在 DashboardCore，本页零决策。
 *
 * 卡内开关旁保留只读姿态状态行（正放/倒扣，来自接近传感器防抖提交的读数）——它只陈述
 * 姿态事实，是否参与门控由开关决定。
 */
@Composable
fun PostureSettingsSection(
    postureGateEnabled: Boolean,
    postureFaceDown: Boolean,
    onPostureGateChange: (Boolean) -> Unit,
) {
    SettingsSectionCard(title = stringResource(R.string.settings_posture_title)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_posture_switch),
            description = stringResource(
                if (postureGateEnabled) R.string.settings_posture_on else R.string.settings_posture_off,
            ),
            checked = postureGateEnabled,
            onCheckedChange = onPostureGateChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.label_posture),
                style = MaterialTheme.typography.labelMedium,
                color = RearCueColors.onBackgroundSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (postureFaceDown) R.string.posture_face_down else R.string.posture_face_up,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = RearCueColors.onBackground,
            )
        }
    }
}

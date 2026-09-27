package com.rearcue.poc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rearcue.poc.R

/**
 * 充电区（spec 0007 story 12 / 票 #57）：充电动画总开关——一区一卡、一个开关一个语义。
 *
 * 与已退役的横幅区（票 #56，spec 0008 删除）曾共用同一件的两半：卡片壳与开关行都取
 * [SettingsSectionCard] / [SettingsSwitchRow]（触控目标 ≥48dp、纯黑 + 单一强调色），
 * 状态与写入口由调用方注入，本件不自取容器——页面自己知道自己在给哪一屏接线。
 *
 * 开关即时生效：容器发 `DashboardEvent.ChargingAnimation` 并写盘（spec 0007 story 11），
 * 决策与语义全在 DashboardCore（关 = 插电无反应、关掉 = 按退出合收取口），本页零决策。
 */
@Composable
fun ChargingSettingsSection(
    chargingEnabled: Boolean,
    onChargingChange: (Boolean) -> Unit,
) {
    SettingsSectionCard(title = stringResource(R.string.settings_charging_title)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_charging_switch),
            description = stringResource(
                if (chargingEnabled) R.string.settings_charging_on else R.string.settings_charging_off,
            ),
            checked = chargingEnabled,
            onCheckedChange = onChargingChange,
        )
    }
}

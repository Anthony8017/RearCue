package com.rearcue.poc.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.rearcue.poc.AppState
import com.rearcue.poc.R
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.design.RearCueColors
import com.rearcue.poc.design.RearCueIconSize
import com.rearcue.poc.design.RearCueSpacing
import com.rearcue.poc.design.RearCueTheme
import com.rearcue.poc.design.RearCueTouch
import com.rearcue.poc.design.safeAreaPadding

/**
 * 设置页（主页齿轮的唯一去处）：充电动画总开关（spec 0007 / 票 #57）+ 姿态门控开关
 * （票 #100）。
 *
 * 通知白名单是 Icon Set 唯一的应用级筛选入口。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as RearCueApp).container
            SettingsScreen(
                state = container.state.collectAsState().value,
                onChargingChange = container::setChargingAnimationEnabled,
                onPostureGateChange = container::setPostureGateEnabled,
                onBack = ::finish,
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppState,
    onChargingChange: (Boolean) -> Unit,
    onPostureGateChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    RearCueTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = RearCueColors.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeAreaPadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RearCueSpacing.md),
            ) {
                Header(onBack)
                AllowlistEntrySection()
                // 充电区（spec 0007 票 #57）：状态与写入口由本页注入，本件不自取容器。
                ChargingSettingsSection(
                    chargingEnabled = state.chargingEnabled,
                    onChargingChange = onChargingChange,
                )
                // 姿态区（票 #100）：姿态门控开关（默认关）+ 只读姿态状态行，同款注入。
                PostureSettingsSection(
                    postureGateEnabled = state.postureGateEnabled,
                    postureFaceDown = state.postureFaceDown,
                    onPostureGateChange = onPostureGateChange,
                )
            }
        }
    }
}

@Composable
private fun AllowlistEntrySection() {
    val context = LocalContext.current
    SettingsSectionCard(title = stringResource(R.string.settings_allowlist_title)) {
        Text(
            text = stringResource(R.string.settings_allowlist_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = RearCueColors.onBackgroundSecondary,
        )
        Button(onClick = { context.startActivity(Intent(context, AllowlistSettingsActivity::class.java)) }) {
            Text(stringResource(R.string.settings_allowlist_manage))
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(RearCueSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = RearCueTouch.minTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.settings_back_cd),
                tint = RearCueColors.onBackground,
                modifier = Modifier.size(RearCueIconSize.medium),
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = RearCueColors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
    }
}

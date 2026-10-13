package com.rearcue.poc.ui

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.relocation.bringIntoViewRequester
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

/** 设置页：运行与权限、连接配置、显示与提醒、开发者选项。 */
class SettingsActivity : ClipboardBridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as RearCueApp).container
            SettingsScreen(
                state = container.state.collectAsState().value,
                container = container,
                rearState = container.rearBackend.stateFlow.collectAsState().value,
                initialSection = intent.getStringExtra("section"),
                onClipboardRetry = { checkBridgeClipboard(force = true) },
                onBack = ::finish,
            )
        }
    }
    companion object {
        fun intentFor(context: android.content.Context, runtime: Boolean = false, connection: Boolean = false) =
            Intent(context, SettingsActivity::class.java).apply {
                putExtra("section", if (connection) "connection" else if (runtime) "runtime" else "")
            }
    }
}

@Composable
private fun SettingsScreen(
    state: AppState,
    container: com.rearcue.poc.AppContainer,
    rearState: com.rearcue.poc.rear.RearBackendState,
    initialSection: String?,
    onClipboardRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var listenerEnabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.rearcue.poc.notify.isListenerEnabled(context)) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                container.checkAutostart()
                listenerEnabled = container.probeNotificationListener("settings-resume")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val connectionRequester = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(initialSection) {
        if (initialSection == "connection") {
            androidx.compose.runtime.withFrameNanos { }
            connectionRequester.bringIntoView()
        }
    }
    RearCueTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = RearCueColors.background) {
            Column(modifier = Modifier.fillMaxSize().safeAreaPadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RearCueSpacing.md)) {
                Header(onBack)
                RuntimeStatusCard(state, listenerEnabled, rearState.available)
                state.usabilityBanner?.let { UsabilityBannerCard(it) }
                AllowlistEntrySection()
                Column(modifier = Modifier.bringIntoViewRequester(connectionRequester)) {
                    AgentSettingsSection(
                        bridgeConfigured = state.agentBridgeConfigured, bridgeStatus = state.bridgeLinkStatus,
                        enabled = state.agentEnabled,
                        onEnabledChange = container::setAgentMirrorEnabled,
                        glowBrightness = state.glowBrightness, onGlowBrightnessChange = container::setGlowBrightness,
                        bridgeAddress = state.bridgeAddress, bridgeSource = state.bridgeAddressSource,
                        bridgePushedAt = state.bridgePushedAt, bridgeProbe = state.bridgeAddressProbe,
                        onBridgeAddressSave = container::probeAndSaveBridgeAddress, onBridgeAddressClear = container::clearBridgeAddress,
                        clipboardNote = state.bridgeClipboardNote, onClipboardRetry = onClipboardRetry,
                        alertEnabled = state.agentAlertEnabled, alertVibrate = state.agentAlertVibrate,
                        onAlertEnabledChange = container::setAgentAlertEnabled, onAlertVibrateChange = container::setAgentAlertVibrate,
                        voiceSettings = state.voiceBroadcastSettings, voiceRuntime = state.voiceBroadcastRuntime,
                        onVoiceEnabledChange = container::setVoiceBroadcastEnabled, onCueEnabledChange = container::setAgentCueEnabled,
                        onVoiceSpeedChange = container::setVoiceSpeed, onVoicePitchChange = container::setVoicePitch,
                        onVoiceSystemVoiceChange = container::setVoiceSystemVoice,
                        approveEnabled = state.agentApproveEnabled, onApproveEnabledChange = container::setAgentApproveEnabled,
                    )
                }
                ChargingSettingsSection(state.chargingEnabled, container::setChargingAnimationEnabled)
                PostureSettingsSection(state.postureGateEnabled, state.postureFaceDown, container::setPostureGateEnabled)
                DeveloperOptions(rearState, container)
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

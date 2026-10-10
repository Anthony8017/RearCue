package com.rearcue.poc.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rearcue.poc.R

/** 主页只保留二级对话页入口，离线时也能浏览历史与草稿。 */
@Composable
fun CodexRemotePanel() {
    val context = LocalContext.current
    Button(
        onClick = { context.startActivity(CodexConversationActivity.intentFor(context)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.codex_conversation_title)) }
}

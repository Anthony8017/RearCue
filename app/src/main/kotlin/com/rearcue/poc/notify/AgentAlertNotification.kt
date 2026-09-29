package com.rearcue.poc.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.rearcue.poc.R
import com.rearcue.poc.agentmirror.AgentAlertKind

/**
 * Agent Alert 的手机侧呈现（spec 0018-3 / CONTEXT.md「Agent Alert」）：
 * 系统本地通知＋震动（**不响铃**），标题＝事件类型、正文＝会话名＋一句摘要
 * （组装在 [com.rearcue.poc.agentmirror.AgentAlertPolicy.contentLine]，缺失退化为会话名）。
 * 背屏的脉冲/Approval Glow 照旧并行，本面不替代、不改动背屏视觉语言。
 *
 * 震动开关走**双通道**：通道参数创建后即冻结，运行时切换靠发通知时选通道——
 * 「震动」通道开震动、「静默」通道只留通知栏（都不带声音）。
 */
const val AGENT_ALERT_CHANNEL_ID = "rearcue-agent-alert"
const val AGENT_ALERT_SILENT_CHANNEL_ID = "rearcue-agent-alert-silent"

/** 会话名＝通知 tag（同一会话的提醒原位更新，不同会话互不覆盖）＋固定通知 id。 */
const val AGENT_ALERT_NOTIFICATION_ID = 2

private const val CHANNEL_NAME = "Agent 提醒"
private const val SILENT_CHANNEL_NAME = "Agent 提醒（静默）"

/** 在册提醒账（哪条通知发出去过）：开关拨下时能逐条撤干净，不用 cancelAll 误伤测试通知。 */
private val activeAlerts = mutableSetOf<String>()

/** 提醒种类 → 事件类型文案（等你确认 / 任务干完 / 出错，与 CONTEXT.md 词条同词）。 */
private fun AgentAlertKind.labelRes(): Int = when (this) {
    AgentAlertKind.WAITING -> R.string.agent_alert_waiting
    AgentAlertKind.DONE -> R.string.agent_alert_done
    AgentAlertKind.ERROR -> R.string.agent_alert_error
}

/** 建好提醒双通道；app 启动时调一次（通道参数只影响首次创建）。 */
fun ensureAgentAlertChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(AGENT_ALERT_CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(true)
        },
    )
    manager.createNotificationChannel(
        NotificationChannel(AGENT_ALERT_SILENT_CHANNEL_ID, SILENT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        },
    )
}

/**
 * 发一条 Agent 提醒（[sessionId] 作 tag：同会话原位更新）。未授权通知权限时什么都不做。
 * [contentLine] 由 [com.rearcue.poc.agentmirror.AgentAlertPolicy.contentLine] 组装好送进来——
 * 本函数只呈现，不拼内容、不做判定。
 */
fun postAgentAlert(context: Context, sessionId: String, kind: AgentAlertKind, contentLine: String, vibrate: Boolean) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureAgentAlertChannels(context)
    val channelId = if (vibrate) AGENT_ALERT_CHANNEL_ID else AGENT_ALERT_SILENT_CHANNEL_ID
    val notification = Notification.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(context.getString(kind.labelRes()))
        .setContentText(contentLine)
        .setAutoCancel(true)
        .build()
    context.getSystemService(NotificationManager::class.java)
        .notify(sessionId, AGENT_ALERT_NOTIFICATION_ID, notification)
    activeAlerts += sessionId
}

/** 逐条撤掉在册的 Agent 提醒（提醒总开关/镜像总开关拨下时调——「提醒整体不存在」）。 */
fun cancelAgentAlerts(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    activeAlerts.forEach { sessionId -> manager.cancel(sessionId, AGENT_ALERT_NOTIFICATION_ID) }
    activeAlerts.clear()
}

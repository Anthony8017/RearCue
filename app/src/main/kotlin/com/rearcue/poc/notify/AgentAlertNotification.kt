package com.rearcue.poc.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.rearcue.poc.R
import com.rearcue.poc.agent.AgentPendingOption
import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.agentmirror.AgentAlertKind

/**
 * Agent Alert 的手机侧呈现（spec 0018-3 / CONTEXT.md「Agent Alert」）：
 * 系统本地通知＋震动（**不响铃**），标题＝事件类型、正文＝会话名＋一句摘要
 * （组装在 [com.rearcue.poc.agentmirror.AgentAlertPolicy.contentLine]，缺失退化为会话名）。
 * 背屏的脉冲/Approval Glow 照旧并行，本面不替代、不改动背屏视觉语言。
 *
 * 震动开关走**双通道**：通道参数创建后即冻结，运行时切换靠发通知时选通道——
 * 「震动」通道开震动、「静默」通道只留通知栏（都不带声音）。
 *
 * 等确认提醒带**批准动作按钮**（spec 0018-4 / 票 #174）：确认类＝同意/拒绝两个按钮，
 * 提问类＝选项点选按钮（上限 3 个，多了不放——通知栏不是答题卡，主屏列表才是全量入口）；
 * 全部落点是 [AgentActionReceiver]，**没有自由文字入口**（ADR 0009 红线）。
 */
const val AGENT_ALERT_CHANNEL_ID = "rearcue-agent-alert"
const val AGENT_ALERT_SILENT_CHANNEL_ID = "rearcue-agent-alert-silent"

/** 会话名＝通知 tag（同一会话的提醒原位更新，不同会话互不覆盖）＋固定通知 id。 */
const val AGENT_ALERT_NOTIFICATION_ID = 2

private const val CHANNEL_NAME = "Agent 提醒"
private const val SILENT_CHANNEL_NAME = "Agent 提醒（静默）"

/** 通知栏动作按钮上限：同意/拒绝恒两枚；选项点选截前 3 个（全量入口在主屏 Agent 区）。 */
private const val MAX_NOTIFICATION_ACTIONS = 3

/** 在册提醒账（哪条通知发出去过）：开关拨下时能逐条撤干净，不用 cancelAll 误伤测试通知。 */
private val activeAlerts = mutableSetOf<String>()

/** 一枚通知动作按钮（标签＋动作词＋可选选项 id）：发通知方组装，本层只呈现。 */
data class AlertAction(val label: String, val kind: SessionActionKind, val optionId: String? = null)

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
 * 本函数只呈现，不拼内容、不做判定。[actions] 只随等确认提醒出现（批准入口，票 #174）。
 */
fun postAgentAlert(
    context: Context,
    sessionId: String,
    kind: AgentAlertKind,
    contentLine: String,
    vibrate: Boolean,
    actions: List<AlertAction> = emptyList(),
) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureAgentAlertChannels(context)
    val channelId = if (vibrate) AGENT_ALERT_CHANNEL_ID else AGENT_ALERT_SILENT_CHANNEL_ID
    val builder = Notification.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(context.getString(kind.labelRes()))
        .setContentText(contentLine)
        .setAutoCancel(true)
    actions.take(MAX_NOTIFICATION_ACTIONS).forEach { action ->
        builder.addAction(notificationAction(context, sessionId, action))
    }
    context.getSystemService(NotificationManager::class.java)
        .notify(sessionId, AGENT_ALERT_NOTIFICATION_ID, builder.build())
    activeAlerts += sessionId
}

/** 一枚动作按钮 → PendingIntent（广播落点 [AgentActionReceiver]；requestCode 按会话×动作区分）。 */
private fun notificationAction(context: Context, sessionId: String, action: AlertAction): Notification.Action {
    val intent = Intent(context, AgentActionReceiver::class.java).apply {
        putExtra(AgentActionReceiver.EXTRA_SESSION_ID, sessionId)
        putExtra(AgentActionReceiver.EXTRA_ACTION, action.kind.wire())
        action.optionId?.let { putExtra(AgentActionReceiver.EXTRA_OPTION_ID, it) }
    }
    val requestCode = (sessionId to (action.kind.wire() + (action.optionId ?: ""))).hashCode()
    val pending = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Action.Builder(null, action.label, pending).build()
}

/**
 * 等确认提醒的动作按钮组（spec 0018-4）：提问类＝选项点选（截前 3 个），确认类＝同意/拒绝。
 * 组装口径在调用方（[com.rearcue.poc.agentmirror.AgentApprovePolicy] 判定已过），本层照单呈现。
 */
fun questionActions(options: List<AgentPendingOption>): List<AlertAction> =
    options.take(MAX_NOTIFICATION_ACTIONS).map { AlertAction(it.label, SessionActionKind.SELECT, it.id) }

/** 逐条撤掉在册的 Agent 提醒（提醒总开关/镜像总开关拨下时调——「提醒整体不存在」）。 */
fun cancelAgentAlerts(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    activeAlerts.forEach { sessionId -> manager.cancel(sessionId, AGENT_ALERT_NOTIFICATION_ID) }
    activeAlerts.clear()
}

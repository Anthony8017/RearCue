package com.rearcue.poc.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat

/** POC「发测试通知」入口的固定通道与通知 id（同一 id 重复发 = 更新同一枚通知）。 */
const val TEST_CHANNEL_ID = "rearcue-test"
const val TEST_NOTIFICATION_ID = 1

private const val CHANNEL_NAME = "RearCue 测试通知"

/** 通知使用权是否已授权（未授权时监听服务不会被绑定）。 */
fun isListenerEnabled(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.split(':')
        ?.any { it.contains(context.packageName) } == true

/** 跳转系统的「通知使用权」设置页，让用户授权本应用。 */
fun listenerSettingsIntent(context: Context) =
    android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
        putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            "${context.packageName}/${RearNotificationListener::class.java.name}",
        )
    }

/** 发一枚自发自收的测试通知：走本应用包名（在 POC Allowlist 内）。 */
fun postTestNotification(context: Context): Boolean {
    if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    ensureTestChannel(context)
    context.getSystemService(NotificationManager::class.java)
        .notify(TEST_NOTIFICATION_ID, buildTestNotification(context))
    return true
}

/** 清除测试通知。 */
fun cancelTestNotification(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(TEST_NOTIFICATION_ID)
}

/** 建好测试通知通道；app 启动时调一次（通道只影响首次创建时的参数）。 */
fun ensureTestChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(TEST_CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
    )
}

private fun buildTestNotification(context: Context): Notification {
    val time = DateFormat.getTimeFormat(context).format(System.currentTimeMillis())
    return Notification.Builder(context, TEST_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("RearCue 测试通知")
        .setContentText("自发自收，$time")
        .setAutoCancel(true)
        .build()
}

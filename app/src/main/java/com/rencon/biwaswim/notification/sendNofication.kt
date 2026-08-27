package com.rencon.biwaswim.notification

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private var counter = 0
    fun sendNotification(context: Context, channelid: String, title: String, message: String, isOnGoing: Boolean, notifyId: Int = ++counter): Notification? {
        Log.d(
            "sendNotification",
            "isOnGoing=$isOnGoing notifyId=$notifyId"
        )
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val notification = NotificationCompat.Builder(context, channelid)
            .setSmallIcon(com.rencon.biwaswim.R.drawable.warning)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(isOnGoing)
            .setOnlyAlertOnce(isOnGoing)
            .build()

        NotificationManagerCompat.from(context)
            .notify(notifyId, notification)
        return notification
    }

/**
 * フォアグラウンドサービス用の通知を構築して返します（notify は呼びません）。
 * startForeground() に直接渡してください。
 */
fun buildForegroundNotification(
    context: Context,
    channelId: String,
    title: String,
    message: String,
    contentIntent: android.app.PendingIntent? = null
): Notification {
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(com.rencon.biwaswim.R.drawable.baseline_fmd_good_24)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setAutoCancel(false)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .apply {
            if (contentIntent != null) {
                setContentIntent(contentIntent)
            }
        }
        .build()
}

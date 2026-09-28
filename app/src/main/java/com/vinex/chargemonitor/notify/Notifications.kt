package com.vinex.chargemonitor.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vinex.chargemonitor.MainActivity
import com.vinex.chargemonitor.R

/**
 * Notification channels and builders.
 *
 * Status is IMPORTANCE_MIN so the persistent notification sits collapsed at the bottom of the
 * shade with no status-bar icon; the source prompt is HIGH so it shows heads-up.
 */
object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_PROMPT = "source_prompt"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_STATUS = 1
    const val ID_PROMPT = 2
    const val ID_ALERT = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_STATUS,
                    context.getString(R.string.channel_status),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.channel_status_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_PROMPT,
                    context.getString(R.string.channel_prompt),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_prompt_desc) },
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.channel_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_alerts_desc) },
            ),
        )
    }

    /** The persistent foreground notification. [text] carries live W and % while charging. */
    fun status(context: Context, text: String? = null): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(context.getString(R.string.notif_monitoring))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .build()

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

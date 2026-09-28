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
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.source.SourceResolver

/**
 * Notification channels and builders.
 *
 * Status is IMPORTANCE_MIN so the persistent notification sits collapsed at the bottom of the
 * shade with no status-bar icon; the source prompt is HIGH so it shows heads-up.
 *
 * Posting never throws: without POST_NOTIFICATIONS (Android 13+, granted during onboarding) the
 * prompt and alerts are simply dropped and monitoring carries on.
 */
object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_PROMPT = "source_prompt"
    const val CHANNEL_ALERTS = "alerts"

    const val ID_STATUS = 1
    /** Shared by the "Connected to?" prompt and the laptop notice; a session has one or the other. */
    const val ID_PROMPT = 2
    const val ID_ALERT = 3
    const val ID_ALERT_TEMPERATURE = 4
    const val ID_ALERT_STOPPED = 5

    /**
     * Extra on the [MainActivity] intent from the prompt's content tap: the session whose source
     * the user wants to pick from the full list. The UI opens its source picker when present.
     */
    const val EXTRA_PICK_SOURCE_SESSION_ID = "com.vinex.chargemonitor.extra.PICK_SOURCE_SESSION_ID"

    private const val REQUEST_OPEN_APP = 0
    private const val REQUEST_PICKER = 1
    // Action PendingIntents need distinct request codes, or their extras collapse into one.
    private const val REQUEST_ACTION_BASE = 1_000

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
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Opens the app on its source picker for [sessionId]. */
    fun pickerIntent(context: Context, sessionId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_PICKER,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PICK_SOURCE_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun updateStatus(context: Context, text: String?) = post(context, ID_STATUS, status(context, text))

    /**
     * Heads-up "Connected to?" for an AC plug-in. [options] become action buttons (Android shows
     * at most three); [provisional] is what is kept when the prompt times out after
     * [SourceResolver.PROMPT_TIMEOUT_MS], which needs no code: the session already has it.
     */
    fun showSourcePrompt(context: Context, sessionId: Long, options: List<PowerSource>, provisional: PowerSource?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(context.getString(R.string.prompt_title))
            .setContentText(
                provisional?.let { context.getString(R.string.prompt_text, it.name) }
                    ?: context.getString(R.string.prompt_text_default),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(SourceResolver.PROMPT_TIMEOUT_MS)
            .setContentIntent(pickerIntent(context, sessionId))
        options.take(3).forEach { source ->
            builder.addAction(0, source.name, pickAction(context, sessionId, source.id))
        }
        post(context, ID_PROMPT, builder.build())
    }

    /**
     * Quiet notice on a USB (laptop) session with a way out for cheap banks that enumerate as
     * USB: reassign to [bank] directly, or open the picker when no bank has been used yet.
     */
    fun showLaptopNotice(context: Context, sessionId: Long, bank: PowerSource?) {
        val action = if (bank != null) {
            NotificationCompat.Action(
                0,
                context.getString(R.string.action_its_bank_named, bank.name),
                pickAction(context, sessionId, bank.id),
            )
        } else {
            NotificationCompat.Action(0, context.getString(R.string.action_its_a_bank), pickerIntent(context, sessionId))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(context.getString(R.string.laptop_title))
            .setContentText(context.getString(R.string.laptop_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // The channel is heads-up; a laptop session is the expected case and should not peek.
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(pickerIntent(context, sessionId))
            .addAction(action)
            .build()
        post(context, ID_PROMPT, notification)
    }

    fun cancelPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_PROMPT)
    }

    fun showAlert(
        context: Context,
        kind: AlertKind,
        levelTarget: Int,
        tempC: Float,
        tempThresholdC: Float,
    ) {
        val (id, title, text) = when (kind) {
            AlertKind.TARGET -> Triple(
                ID_ALERT,
                context.getString(R.string.alert_target_title, levelTarget),
                context.getString(R.string.alert_target_text),
            )
            AlertKind.TEMPERATURE -> Triple(
                ID_ALERT_TEMPERATURE,
                context.getString(R.string.alert_temp_title, tempC),
                context.getString(R.string.alert_temp_text, tempThresholdC),
            )
            AlertKind.FULL -> Triple(
                ID_ALERT_STOPPED,
                context.getString(R.string.alert_full_title),
                context.getString(R.string.alert_full_text),
            )
            AlertKind.STOPPED -> Triple(
                ID_ALERT_STOPPED,
                context.getString(R.string.alert_stopped_title),
                context.getString(R.string.alert_stopped_text),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        post(context, id, notification)
    }

    private fun pickAction(context: Context, sessionId: Long, sourceId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_ACTION_BASE + sourceId.toInt(),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_PICK_SOURCE)
                .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                .putExtra(NotificationActionReceiver.EXTRA_SOURCE_ID, sourceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(id, notification) }
    }
}

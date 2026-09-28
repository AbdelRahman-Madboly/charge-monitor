package com.vinex.chargemonitor.ui

import android.content.Context
import android.content.Intent
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.notify.NotificationActionReceiver

/** User actions on sessions that more than one screen offers. */
object SessionActions {

    /**
     * Moves a session to [source].
     *
     * The session still recording goes through the same explicit broadcast as the notification
     * buttons, so the running service updates its in-memory session at once (the Live chip would
     * otherwise flip back until the next checkpoint) and any open "Connected to?" prompt is
     * dismissed. Closed sessions are only rows in Room and are updated directly.
     */
    suspend fun reassign(context: Context, sessionId: Long, source: PowerSource) {
        if (LiveBus.state.value.sessionId == sessionId) {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_PICK_SOURCE)
                .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                .putExtra(NotificationActionReceiver.EXTRA_SOURCE_ID, source.id)
            context.sendBroadcast(intent)
            return
        }
        val settings = ServiceLocator.settings
        ServiceLocator.repository.setSessionSource(sessionId, source.id)
        // Same memory rules as the notification path, so both kinds of correction teach the prompt.
        if (source.kind == SourceKind.POWER_BANK) settings.rememberBank(source.id)
        if (source.kind == SourceKind.POWER_BANK || source.kind == SourceKind.CHARGER) {
            settings.lastNonLaptopSourceId.set(source.id)
        }
    }
}

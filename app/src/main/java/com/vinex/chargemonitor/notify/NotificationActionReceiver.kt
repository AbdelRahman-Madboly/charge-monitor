package com.vinex.chargemonitor.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "Connected to?" action buttons (and "It's a power bank" on laptop sessions).
 *
 * Contract: intents carry [ACTION_PICK_SOURCE] with [EXTRA_SESSION_ID] and [EXTRA_SOURCE_ID].
 * The receiver reassigns the session's source and dismisses the prompt. Not exported; the
 * PendingIntents are explicit.
 *
 * Stub: ignores the intent.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Implemented by the notification team.
    }

    companion object {
        const val ACTION_PICK_SOURCE = "com.vinex.chargemonitor.action.PICK_SOURCE"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SOURCE_ID = "source_id"
    }
}

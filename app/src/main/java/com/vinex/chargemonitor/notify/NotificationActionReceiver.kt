package com.vinex.chargemonitor.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vinex.chargemonitor.LiveBus
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Receives the "Connected to?" action buttons (and "It's a power bank" on laptop sessions).
 *
 * Intents carry [ACTION_PICK_SOURCE] with [EXTRA_SESSION_ID] and [EXTRA_SOURCE_ID]. The receiver
 * reassigns the session's source in Room, remembers the choice for the next prompt, dismisses the
 * prompt and tells the running service through [sourceChanges] so its in-memory session agrees.
 * Not exported; the PendingIntents are explicit.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PICK_SOURCE) return
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1L)
        if (sessionId < 0 || sourceId < 0) return

        Notifications.cancelPrompt(context)
        val pending = goAsync()
        scope.launch {
            try {
                assign(sessionId, sourceId)
            } catch (e: Exception) {
                Log.w(TAG, "Could not reassign session $sessionId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun assign(sessionId: Long, sourceId: Long) {
        val repository = ServiceLocator.repository
        val settings = ServiceLocator.settings
        val source = repository.source(sourceId) ?: return
        repository.setSessionSource(sessionId, sourceId)
        if (source.countsTowardBank) settings.rememberBank(sourceId)
        // Only what an AC plug can be: this is the fallback when the next prompt is ignored.
        if (source.kind == SourceKind.POWER_BANK || source.kind == SourceKind.CHARGER) {
            settings.lastNonLaptopSourceId.set(sourceId)
        }
        LiveBus.publish { state -> if (state.sessionId == sessionId) state.copy(source = source) else state }
        changes.tryEmit(SourceChange(sessionId, sourceId))
    }

    data class SourceChange(val sessionId: Long, val sourceId: Long)

    companion object {
        const val ACTION_PICK_SOURCE = "com.vinex.chargemonitor.action.PICK_SOURCE"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SOURCE_ID = "source_id"

        private const val TAG = "NotificationAction"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val changes = MutableSharedFlow<SourceChange>(extraBufferCapacity = 8)

        /** Source reassignments made from notifications; collected by the service. */
        val sourceChanges: SharedFlow<SourceChange> = changes.asSharedFlow()
    }
}

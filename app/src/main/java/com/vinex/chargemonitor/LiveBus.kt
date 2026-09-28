package com.vinex.chargemonitor

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.session.SessionTotals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is happening right now, shared between the service (writer) and the UI (reader).
 * In memory only; the database remains the record.
 */
data class LiveState(
    val snapshot: BatterySnapshot? = null,
    /** Totals of the open session; null while unplugged. */
    val session: SessionTotals? = null,
    val sessionId: Long? = null,
    val source: PowerSource? = null,
    val isCharging: Boolean = false,
    /** Set by the UI while the Live screen is on screen; the service samples at 2 s then. */
    val liveVisible: Boolean = false,
    val serviceRunning: Boolean = false,
    val isMock: Boolean = false,
)

/**
 * Process-wide live state. The service publishes with [publish], which must not touch
 * [LiveState.liveVisible]; the UI flips that one field with [setLiveVisible]. Both go through
 * [MutableStateFlow.update] so concurrent writers cannot lose each other's fields.
 */
object LiveBus {
    private val mutable = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutable.asStateFlow()

    /** Service side: apply [transform]; the visibility flag set by the UI is always preserved. */
    fun publish(transform: (LiveState) -> LiveState) {
        mutable.update { current -> transform(current).copy(liveVisible = current.liveVisible) }
    }

    fun setLiveVisible(visible: Boolean) {
        mutable.update { it.copy(liveVisible = visible) }
    }
}

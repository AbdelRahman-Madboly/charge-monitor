package com.vinex.chargemonitor.session

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus

/** Running results of a session, in the units the UI and the database use. */
data class SessionTotals(
    val startMs: Long,
    val lastMs: Long,
    val mah: Double,
    val wh: Double,
    val peakW: Double,
    val avgW: Double,
    val maxTempC: Float?,
    val screenOnFraction: Float,
    val reachedFull: Boolean,
    val startLevel: Float,
    val endLevel: Float,
    val sampleCount: Int,
) {
    val durationMs: Long get() = lastMs - startMs
    val levelAdded: Float get() = (endLevel - startLevel).coerceAtLeast(0f)
    /** Session counts as "used while charging" above this screen-on share (spec section 2). */
    val usedWhileCharging: Boolean get() = screenOnFraction > USED_WHILE_CHARGING_FRACTION

    companion object {
        const val USED_WHILE_CHARGING_FRACTION = 0.20f
    }
}

/**
 * Immutable fold of [BatterySnapshot]s into session totals. Pure: no clock, no Android, no I/O.
 *
 * Contract for implementers:
 * - [fold] returns a new instance; the receiver is never modified. The first fold sets the start
 *   time and start level.
 * - mAh and Wh use the trapezoidal rule between consecutive snapshots on `currentMa` and
 *   `powerW`; negative current (phone drawing more than the source gives) subtracts.
 * - An interval longer than [MAX_INTEGRATION_GAP_MS] (sleep, a missed tick, a reconnect) is not
 *   integrated, only its end point is taken as the new reference.
 * - Screen-on fraction is time-weighted, attributing each interval to the earlier snapshot.
 * - [reachedFull] latches once any snapshot reports [ChargeStatus.FULL].
 * - [isWithinReconnectGrace] tells the tracker whether a replug at `nowMs` continues this session.
 */
data class SessionAccumulator(
    val startMs: Long = 0L,
    val last: BatterySnapshot? = null,
    val mah: Double = 0.0,
    val wh: Double = 0.0,
    val peakW: Double = 0.0,
    val maxTempC: Float? = null,
    val integratedMs: Long = 0L,
    val screenOnMs: Long = 0L,
    val reachedFull: Boolean = false,
    val startLevel: Float = 0f,
    val endLevel: Float = 0f,
    val sampleCount: Int = 0,
) {
    val isEmpty: Boolean get() = last == null

    /**
     * Stub: records start/end level, count, peaks and the full latch; integration is left for the
     * session team.
     */
    fun fold(snapshot: BatterySnapshot): SessionAccumulator {
        val first = last == null
        return copy(
            startMs = if (first) snapshot.tMs else startMs,
            last = snapshot,
            peakW = maxOf(peakW, snapshot.powerW),
            maxTempC = maxOf(maxTempC ?: snapshot.tempC, snapshot.tempC),
            reachedFull = reachedFull || snapshot.status == ChargeStatus.FULL,
            startLevel = if (first) snapshot.levelPct else startLevel,
            endLevel = snapshot.levelPct,
            sampleCount = sampleCount + 1,
        )
    }

    /** True when a replug at [nowMs] should continue this session rather than start a new one. */
    fun isWithinReconnectGrace(nowMs: Long): Boolean {
        val lastMs = last?.tMs ?: return false
        return nowMs - lastMs in 0..RECONNECT_GRACE_MS
    }

    val totals: SessionTotals
        get() = SessionTotals(
            startMs = startMs,
            lastMs = last?.tMs ?: startMs,
            mah = mah,
            wh = wh,
            peakW = peakW,
            avgW = if (integratedMs > 0) wh * 3_600_000.0 / integratedMs else 0.0,
            maxTempC = maxTempC,
            screenOnFraction = if (integratedMs > 0) screenOnMs.toFloat() / integratedMs else 0f,
            reachedFull = reachedFull,
            startLevel = startLevel,
            endLevel = endLevel,
            sampleCount = sampleCount,
        )

    companion object {
        /** Unplug and replug within this window continues the same session (loose connector). */
        const val RECONNECT_GRACE_MS = 30_000L

        /** Intervals longer than this are treated as a gap, not integrated. */
        const val MAX_INTEGRATION_GAP_MS = 60_000L

        val EMPTY = SessionAccumulator()
    }
}

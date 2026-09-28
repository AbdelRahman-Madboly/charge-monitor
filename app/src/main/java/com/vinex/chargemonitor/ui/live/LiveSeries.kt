package com.vinex.chargemonitor.ui.live

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.data.Sample

/** One chart point of the live session. */
data class LivePoint(val tMs: Long, val watts: Float, val amps: Float)

/** Pure pieces of the Live screen, split out so they can be unit-tested. */
object LiveSeries {
    /** Spec section 3: fast charging means at least 10 W, sustained. */
    const val FAST_CHARGE_W = 10.0
    const val FAST_WINDOW_MS = 30_000L
    const val CURRENT_WINDOW_MS = 120_000L

    /**
     * Persisted samples cover the time before this screen opened (10 s cadence); the in-memory
     * buffer holds every live tick since. Persisted points inside the buffer's span are dropped
     * so the line does not zig-zag between two cadences.
     */
    fun merge(persisted: List<Sample>, buffer: List<LivePoint>): List<LivePoint> {
        val cut = buffer.firstOrNull()?.tMs ?: Long.MAX_VALUE
        val head = persisted.asSequence()
            .filter { it.tMs < cut }
            .map { LivePoint(it.tMs, (it.mV.toDouble() * it.mA / 1_000_000.0).toFloat(), it.mA / 1000f) }
            .toList()
        return head + buffer
    }

    fun point(s: BatterySnapshot) = LivePoint(s.tMs, s.powerW.toFloat(), (s.currentMa / 1000.0).toFloat())

    /** True when the mean power over the last [FAST_WINDOW_MS] is at or above [FAST_CHARGE_W]. */
    fun isFastCharging(points: List<LivePoint>): Boolean {
        val last = points.lastOrNull() ?: return false
        val window = points.takeLastWhile { last.tMs - it.tMs <= FAST_WINDOW_MS }
        // A single reading is not "sustained"; wait for a second one.
        if (window.size < 2) return false
        return window.sumOf { it.watts.toDouble() } / window.size >= FAST_CHARGE_W
    }

    /** Currents (mA) over the recent window, oldest first, as the time-to-level input. */
    fun recentCurrentsMa(points: List<LivePoint>): List<Double> {
        val last = points.lastOrNull() ?: return emptyList()
        return points.takeLastWhile { last.tMs - it.tMs <= CURRENT_WINDOW_MS }.map { it.amps * 1000.0 }
    }
}

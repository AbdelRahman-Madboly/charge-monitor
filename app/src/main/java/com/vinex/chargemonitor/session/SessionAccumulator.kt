package com.vinex.chargemonitor.session

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.stats.EnergyModel

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
    /** Charge-counter delta over the session in mAh, for cross-checking [mah]; null if unsupported. */
    val counterMah: Double? = null,
    /** Estimated energy the phone ran on while plugged in (see [EnergyModel]). */
    val phoneUseWh: Double = 0.0,
    /** Estimated energy at the source's USB port. */
    val portWh: Double = 0.0,
    /** Estimated energy drawn from a power bank's cells to deliver [portWh]. */
    val cellsWh: Double = 0.0,
) {
    /** Battery energy over cell energy; null before the bank has given anything. */
    val efficiency: Double? get() = if (cellsWh > 0.0) wh.coerceAtLeast(0.0) / cellsWh else null

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
 * Rules:
 * - [fold] returns a new instance; the receiver is never modified. The first plugged fold sets
 *   the start time and start level; unplugged snapshots before that are ignored.
 * - mAh and Wh use the trapezoidal rule between consecutive plugged snapshots on `currentMa`
 *   and `powerW`; negative current (phone drawing more than the source gives) subtracts.
 * - Phone use, port and cell energy use the same rule on each snapshot's [EnergyModel.flow], so
 *   every loss is taken at the power it happened at.
 * - An interval longer than [MAX_INTEGRATION_GAP_MS] is not bridged with a straight line: it is
 *   integrated from the charge-counter delta when both ends have one (Wh at the mean of the two
 *   voltages), and skipped otherwise.
 * - An unplugged snapshot records nothing but breaks the chain, so the interval spanning a
 *   loose-connector gap is never integrated. The tracker should fold the unplug reading for
 *   exactly that reason. [lastMs][SessionTotals.lastMs] and the reconnect grace both use the
 *   last *plugged* reading.
 * - Screen-on fraction is time-weighted over the straight-line intervals, attributing each
 *   interval to its earlier snapshot. Counter-bridged gaps are excluded: nobody knows what the
 *   screen did then.
 * - [reachedFull] latches once any plugged snapshot reports [ChargeStatus.FULL].
 * - A start level with no decimal (the level interpolator has no anchor at plug-in) is really
 *   somewhere in [n, n + 1), so taking n would add about half a percent to every session. Once
 *   a later reading has a decimal, the start is worked back from it with the charge counter and
 *   kept within [n, n + 0.9] so it never contradicts the integer the system reported.
 * - Non-positive time steps (clock set backwards, duplicate reading) only move the reference.
 */
data class SessionAccumulator(
    val startMs: Long = 0L,
    val last: BatterySnapshot? = null,
    val mah: Double = 0.0,
    val wh: Double = 0.0,
    val peakW: Double = 0.0,
    val maxTempC: Float? = null,
    /** Time covered by energy integration; the denominator of average power. */
    val integratedMs: Long = 0L,
    val screenOnMs: Long = 0L,
    val reachedFull: Boolean = false,
    val startLevel: Float = 0f,
    val endLevel: Float = 0f,
    val sampleCount: Int = 0,
    /**
     * Time covered by straight-line intervals; the denominator of screen-on fraction. Defaults to
     * [integratedMs] so an accumulator rebuilt from a stored session (which only has the fraction)
     * keeps a consistent ratio.
     */
    val screenTrackedMs: Long = integratedMs,
    /** Last plugged reading; defaults from [last] for the same reason. */
    val lastPluggedMs: Long = last?.takeIf { it.isPlugged }?.tMs ?: 0L,
    val firstCounterUah: Long? = null,
    val lastCounterUah: Long? = null,
    /** False while [startLevel] is a bare integer that a later reading may still refine. */
    val startRefined: Boolean = true,
    val phoneUseWh: Double = 0.0,
    val portWh: Double = 0.0,
    val cellsWh: Double = 0.0,
    val model: EnergyModel.Params = EnergyModel.DEFAULT,
) {
    val isEmpty: Boolean get() = last == null

    fun fold(snapshot: BatterySnapshot): SessionAccumulator {
        val previous = last
        if (!snapshot.isPlugged) {
            return if (previous == null) this else copy(last = snapshot)
        }
        if (previous == null) {
            return copy(
                startMs = snapshot.tMs,
                last = snapshot,
                peakW = snapshot.powerW.coerceAtLeast(0.0),
                maxTempC = snapshot.tempC,
                reachedFull = snapshot.status == ChargeStatus.FULL,
                startLevel = snapshot.levelPct,
                endLevel = snapshot.levelPct,
                sampleCount = 1,
                lastPluggedMs = snapshot.tMs,
                firstCounterUah = snapshot.chargeCounterUah,
                lastCounterUah = snapshot.chargeCounterUah,
                startRefined = !snapshot.levelPct.isWhole() || snapshot.chargeCounterUah == null,
            )
        }

        var next = copy(
            last = snapshot,
            peakW = maxOf(peakW, snapshot.powerW),
            maxTempC = maxOf(maxTempC ?: snapshot.tempC, snapshot.tempC),
            reachedFull = reachedFull || snapshot.status == ChargeStatus.FULL,
            endLevel = snapshot.levelPct,
            sampleCount = sampleCount + 1,
            lastPluggedMs = snapshot.tMs,
            firstCounterUah = firstCounterUah ?: snapshot.chargeCounterUah,
            lastCounterUah = snapshot.chargeCounterUah ?: lastCounterUah,
        )
        if (!startRefined) next = next.refineStart(snapshot)

        val dtMs = snapshot.tMs - previous.tMs
        if (!previous.isPlugged || dtMs <= 0) return next

        if (dtMs <= MAX_INTEGRATION_GAP_MS) {
            val hours = dtMs / MS_PER_HOUR
            val a = EnergyModel.flow(previous.powerW, previous.screenOn, model)
            val b = EnergyModel.flow(snapshot.powerW, snapshot.screenOn, model)
            next = next.copy(
                mah = mah + (previous.currentMa + snapshot.currentMa) / 2.0 * hours,
                wh = wh + (previous.powerW + snapshot.powerW) / 2.0 * hours,
                phoneUseWh = phoneUseWh + (a.phoneUseW + b.phoneUseW) / 2.0 * hours,
                portWh = portWh + (a.portW + b.portW) / 2.0 * hours,
                cellsWh = cellsWh + (a.cellsW + b.cellsW) / 2.0 * hours,
                integratedMs = integratedMs + dtMs,
                screenOnMs = screenOnMs + if (previous.screenOn) dtMs else 0L,
                screenTrackedMs = screenTrackedMs + dtMs,
            )
        } else {
            val from = previous.chargeCounterUah
            val to = snapshot.chargeCounterUah
            if (from != null && to != null) {
                val gapMah = (to - from) / 1000.0
                val meanV = (previous.voltageMv + snapshot.voltageMv) / 2.0 / 1000.0
                val gapWh = gapMah * meanV / 1000.0
                // Nobody saw the screen during the gap; the idle draw is the cautious guess.
                val f = EnergyModel.bridged(gapWh, dtMs / MS_PER_HOUR, screenOn = false, model)
                next = next.copy(
                    mah = mah + gapMah,
                    wh = wh + gapWh,
                    integratedMs = integratedMs + dtMs,
                    phoneUseWh = phoneUseWh + (f?.phoneUseW ?: 0.0),
                    portWh = portWh + (f?.portW ?: 0.0),
                    cellsWh = cellsWh + (f?.cellsW ?: 0.0),
                )
            }
        }
        return next
    }

    private fun refineStart(snapshot: BatterySnapshot): SessionAccumulator {
        val first = firstCounterUah ?: return copy(startRefined = true)
        val counter = snapshot.chargeCounterUah ?: return this
        if (snapshot.levelPct.isWhole() || snapshot.levelPct < MIN_REFINE_LEVEL) return this
        val fullUah = counter * 100.0 / snapshot.levelPct
        val estimate = snapshot.levelPct - (counter - first) * 100.0 / fullUah
        // Far outside the reported integer means the counter jumped (gauge recalibration); the
        // integer is then the safer start.
        val refined = if (estimate in startLevel - 0.5..startLevel + 1.5) {
            estimate.coerceIn(startLevel.toDouble(), startLevel + MAX_START_FRACTION).toFloat()
        } else {
            startLevel
        }
        return copy(startLevel = refined, startRefined = true)
    }

    /** True when a replug at [nowMs] should continue this session rather than start a new one. */
    fun isWithinReconnectGrace(nowMs: Long): Boolean {
        if (last == null) return false
        return nowMs - lastPluggedMs in 0..RECONNECT_GRACE_MS
    }

    val totals: SessionTotals
        get() = SessionTotals(
            startMs = startMs,
            lastMs = if (last == null) startMs else lastPluggedMs,
            mah = mah,
            wh = wh,
            peakW = peakW,
            avgW = if (integratedMs > 0) wh * MS_PER_HOUR / integratedMs else 0.0,
            maxTempC = maxTempC,
            screenOnFraction = if (screenTrackedMs > 0) screenOnMs.toFloat() / screenTrackedMs else 0f,
            reachedFull = reachedFull,
            startLevel = startLevel,
            endLevel = endLevel,
            sampleCount = sampleCount,
            counterMah = firstCounterUah?.let { first -> lastCounterUah?.let { (it - first) / 1000.0 } },
            phoneUseWh = phoneUseWh,
            portWh = portWh,
            cellsWh = cellsWh,
        )

    companion object {
        /** Unplug and replug within this window continues the same session (loose connector). */
        const val RECONNECT_GRACE_MS = 30_000L

        /**
         * Intervals longer than this are bridged with the charge counter, not a straight line.
         * Samples come every 10 s while plugged, so anything this long is a stall, not a tick.
         */
        const val MAX_INTEGRATION_GAP_MS = 5 * 60_000L

        private const val MS_PER_HOUR = 3_600_000.0
        private const val MIN_REFINE_LEVEL = 5f
        private const val MAX_START_FRACTION = 0.9

        private fun Float.isWhole(): Boolean = this == kotlin.math.floor(this)

        val EMPTY = SessionAccumulator()
    }
}

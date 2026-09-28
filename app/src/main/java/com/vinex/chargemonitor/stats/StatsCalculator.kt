package com.vinex.chargemonitor.stats

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource

/**
 * Derived numbers for the dashboard and Live screen. Pure: callers pass the sessions they want
 * counted (already filtered by source); open and mock sessions are skipped here too.
 */
object StatsCalculator {

    /**
     * What the app can say about a power bank's capacity.
     *
     * Adding up every session's energy only gives a running total ("delivered so far"), which
     * grows without limit. A capacity needs one drain from full to empty, and only the user
     * knows when those happened, so they mark them as [BankEvent]s. A **cycle** runs from a
     * [BankEventKind.RECHARGED] mark to the next [BankEventKind.EMPTY] mark; its sessions'
     * energy is one estimate of the bank's usable capacity, and [cycleMah] is the median of the
     * completed ones.
     */
    data class CapacityEstimate(
        /** Median usable capacity over [completedCycles], mAh at the bank's nominal voltage. */
        val cycleMah: Double?,
        val ratedMah: Int?,
        /** cycleMah / ratedMah; null without a completed cycle or a rating. */
        val ratioOfRated: Double?,
        val completedCycles: Int,
        /** Cycles that ended but were left out (open, unconfirmed or used-while-charging sessions). */
        val skippedCycles: Int,
        /** Energy into the phone over every counted session, and the same as mAh at nominal. */
        val deliveredWh: Double,
        val deliveredMah: Double,
        /** Since the latest mark, when that mark is [BankEventKind.RECHARGED]; else null. */
        val sinceRechargeWh: Double?,
        val sinceRechargeMah: Double?,
        val sessionsUsed: Int,
    ) {
        /** deliveredMah as multiples of the label, e.g. 2.4 "bank-fulls". */
        val deliveredBankFulls: Double? get() = ratedMah?.let { deliveredMah / it }

        companion object {
            val NONE = CapacityEstimate(null, null, null, 0, 0, 0.0, 0.0, null, null, 0)
        }
    }

    data class HealthEstimate(
        /** Median of recent qualifying per-session capacities, mAh. */
        val estimatedMah: Double?,
        val designMah: Int?,
        /** estimatedMah / designMah x 100. */
        val healthPct: Double?,
        val qualifyingSessions: Int,
    )

    /** Minimum level gain for a session to feed the health estimate (spec section 3). */
    const val HEALTH_MIN_DELTA_PCT = 30f

    /** How many of the most recent qualifying sessions the health median uses. */
    const val HEALTH_RECENT_SESSIONS = 10

    /** Above this level the charger tapers (constant-voltage phase). */
    const val TAPER_START_PCT = 80f

    /** Current at 100 % as a share of the current at [TAPER_START_PCT]. */
    const val TAPER_END_FRACTION = 0.15

    /** A time-to-level longer than this means the current is too small to predict from. */
    const val MAX_PREDICTION_MS = 48L * 3_600_000L

    private const val DEFAULT_NOMINAL_V = 3.7

    /**
     * Sum of percentage points added / 100 over closed, non-mock sessions. Exact; the headline
     * "full charges" figure. Includes used-while-charging sessions by design. With
     * [requireConfirmed] (a power bank's total) a session whose source was only guessed is left
     * out, so an unanswered prompt on a wall charger cannot inflate a bank.
     */
    fun fullChargeEquivalents(sessions: List<ChargeSession>, requireConfirmed: Boolean = false): Double =
        sessions.filter { isCountable(it) && (!requireConfirmed || it.sourceConfirmed) }
            .sumOf { it.levelAdded.toDouble() } / 100.0

    /**
     * Capacity of [bank] from its closed, non-mock, confirmed sessions and the user's refill
     * marks in [events] (other banks' marks are ignored).
     *
     * mAh for a set of sessions is `Wh_into_battery / lossFactor / bank.nominalV × 1000`. A
     * cycle is only used when every session in it is closed and confirmed, and, unless
     * [includeUsedWhileCharging], none was used while charging: dropping one session from a
     * drain would understate it far more than the phone's own use does. A source that is not a
     * power bank never gets an estimate.
     */
    fun bankCapacityEstimate(
        sessions: List<ChargeSession>,
        bank: PowerSource,
        lossFactor: Double,
        includeUsedWhileCharging: Boolean,
        events: List<BankEvent> = emptyList(),
    ): CapacityEstimate {
        val rated = bank.ratedMah?.takeIf { it > 0 }
        if (!bank.countsTowardBank) return CapacityEstimate.NONE.copy(ratedMah = rated)
        // A loss factor outside (0, 1] is a settings bug, not a physical bank; keep it sane.
        val loss = lossFactor.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(1.0) ?: 1.0
        val volts = bank.nominalV.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_NOMINAL_V
        fun mahOf(wh: Double) = wh / loss / volts * 1000.0

        val own = sessions.filter { it.sourceId == bank.id && !it.mock }
        val counted = own.filter { isCountable(it) && it.sourceConfirmed }
        val deliveredWh = counted.sumOf { it.whIn }

        val marks = events.filter { it.sourceId == bank.id }.sortedBy { it.tMs }
        val cycles = mutableListOf<Double>()
        var skipped = 0
        var cycleStart: Long? = null
        for (mark in marks) {
            when (mark.kind) {
                BankEventKind.RECHARGED -> cycleStart = mark.tMs
                BankEventKind.EMPTY -> {
                    val from = cycleStart ?: continue
                    cycleStart = null
                    val inCycle = own.filter { it.startMs in from until mark.tMs }
                    val usable = inCycle.isNotEmpty() && inCycle.all {
                        isCountable(it) && it.sourceConfirmed && (includeUsedWhileCharging || !it.usedWhileCharging)
                    }
                    if (usable) cycles += mahOf(inCycle.sumOf { it.whIn }) else skipped++
                }
            }
        }

        val sinceWh = marks.lastOrNull()?.takeIf { it.kind == BankEventKind.RECHARGED }?.let { mark ->
            counted.filter { it.startMs >= mark.tMs }.sumOf { it.whIn }
        }
        val cycleMah = cycles.takeIf { it.isNotEmpty() }?.let(::median)
        return CapacityEstimate(
            cycleMah = cycleMah,
            ratedMah = rated,
            ratioOfRated = cycleMah?.let { c -> rated?.let { c / it } },
            completedCycles = cycles.size,
            skippedCycles = skipped,
            deliveredWh = deliveredWh,
            deliveredMah = mahOf(deliveredWh),
            sinceRechargeWh = sinceWh,
            sinceRechargeMah = sinceWh?.let(::mahOf),
            sessionsUsed = counted.size,
        )
    }

    /**
     * Phone battery capacity = mahIn / (levelAdded / 100) per session with levelAdded >=
     * [HEALTH_MIN_DELTA_PCT] and not used while charging; median of the
     * [HEALTH_RECENT_SESSIONS] most recent. Any source qualifies: this is about the phone.
     */
    fun batteryHealthEstimate(sessions: List<ChargeSession>, designMah: Int?): HealthEstimate {
        val design = designMah?.takeIf { it > 0 }
        val capacities = sessions
            .filter {
                // The source does not matter here (this is about the phone), so unconfirmed
                // sessions are fine.
                isCountable(it) && !it.usedWhileCharging &&
                    it.levelAdded >= HEALTH_MIN_DELTA_PCT && it.mahIn > 0.0
            }
            .sortedByDescending { it.startMs }
            .map { it.mahIn / (it.levelAdded / 100.0) }
        if (capacities.isEmpty()) return HealthEstimate(null, design, null, 0)
        val estimate = median(capacities.take(HEALTH_RECENT_SESSIONS))
        return HealthEstimate(
            estimatedMah = estimate,
            designMah = design,
            healthPct = design?.let { estimate / it * 100.0 },
            qualifyingSessions = capacities.size,
        )
    }

    /**
     * Milliseconds until [snapshot] reaches [targetPct]. Null when not charging, already at
     * target, or the current or capacity is unknown.
     *
     * Current is the median of [recentCurrentsMa] (falls back to the snapshot), which shrugs off
     * a spike from the screen turning off. Above [TAPER_START_PCT] the charger's current is
     * modelled as falling linearly to [TAPER_END_FRACTION] of its pre-taper value at 100 %. A
     * current measured inside the taper is first scaled back up to its pre-taper equivalent, so
     * the prediction stays consistent as the phone moves through the taper.
     *
     * Percent is converted to mAh with [batteryCapacityMah] when given, else from
     * `chargeCounterUah / levelPct` on the snapshot.
     */
    fun timeToLevel(
        snapshot: BatterySnapshot,
        targetPct: Float,
        recentCurrentsMa: List<Double>,
        batteryCapacityMah: Int? = null,
    ): Long? {
        if (!snapshot.isPlugged || snapshot.status != ChargeStatus.CHARGING) return null
        val from = snapshot.levelPct.toDouble()
        val to = targetPct.toDouble().coerceAtMost(100.0)
        if (from >= to) return null

        val capacityMah = batteryCapacityMah?.takeIf { it > 0 }?.toDouble()
            ?: snapshot.chargeCounterUah
                ?.takeIf { snapshot.levelPct >= 5f }
                ?.let { it / 1000.0 / (snapshot.levelPct / 100.0) }
            ?: return null

        val smoothed = recentCurrentsMa.filter { it.isFinite() }
            .takeIf { it.isNotEmpty() }?.let(::median) ?: snapshot.currentMa
        if (!smoothed.isFinite() || smoothed <= 0.0) return null
        val preTaperMa = smoothed / taperFactor(from)

        // Numerical integral of dt = (C / 100) dp / I(p); 0.1 % steps are far finer than needed.
        var hours = 0.0
        var p = from
        while (p < to) {
            val step = minOf(STEP_PCT, to - p)
            val mid = p + step / 2
            hours += capacityMah * step / 100.0 / (preTaperMa * taperFactor(mid))
            p += step
        }
        val ms = (hours * 3_600_000.0).toLong()
        return ms.takeIf { it in 0..MAX_PREDICTION_MS }
    }

    /** Share of the pre-taper current the charger delivers at [levelPct]. */
    internal fun taperFactor(levelPct: Double): Double {
        if (levelPct <= TAPER_START_PCT) return 1.0
        val progress = ((levelPct - TAPER_START_PCT) / (100.0 - TAPER_START_PCT)).coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - TAPER_END_FRACTION) * progress
    }

    private const val STEP_PCT = 0.1

    private fun isCountable(session: ChargeSession): Boolean = !session.isOpen && !session.mock

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}

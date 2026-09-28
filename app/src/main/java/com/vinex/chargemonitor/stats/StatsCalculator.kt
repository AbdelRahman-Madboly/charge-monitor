package com.vinex.chargemonitor.stats

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource

/**
 * Derived numbers for the dashboard and Live screen. Pure: callers pass the sessions they want
 * counted (already filtered by source); open and mock sessions must be skipped here too.
 */
object StatsCalculator {

    /**
     * Estimated usable capacity of a bank, expressed at the bank's nominal voltage.
     * [estimatedMah] is null when there is no qualifying session.
     */
    data class CapacityEstimate(
        val estimatedMah: Double?,
        val ratedMah: Int?,
        /** estimatedMah / ratedMah, null when either is unknown. */
        val ratioOfRated: Double?,
        val whIn: Double,
        val sessionsUsed: Int,
    )

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

    /**
     * Sum of percentage points added / 100 over closed, non-mock sessions. Exact; the headline
     * "full charges" figure. Includes used-while-charging sessions by design.
     */
    fun fullChargeEquivalents(sessions: List<ChargeSession>): Double =
        sessions.filter { !it.isOpen && !it.mock }.sumOf { it.levelAdded.toDouble() } / 100.0

    /**
     * `mAh ≈ Wh_into_battery / lossFactor / bank.nominalV × 1000` over the given sessions of
     * [bank]. Used-while-charging sessions are skipped unless [includeUsedWhileCharging]; sessions
     * of any other source are ignored.
     *
     * Stub: returns an empty estimate.
     */
    fun bankCapacityEstimate(
        sessions: List<ChargeSession>,
        bank: PowerSource,
        lossFactor: Double,
        includeUsedWhileCharging: Boolean,
    ): CapacityEstimate = CapacityEstimate(
        estimatedMah = null,
        ratedMah = bank.ratedMah,
        ratioOfRated = null,
        whIn = 0.0,
        sessionsUsed = 0,
    )

    /**
     * Phone battery capacity = mahIn / (levelAdded / 100) per session with levelAdded >=
     * [HEALTH_MIN_DELTA_PCT] and not used while charging; median of the recent ones.
     *
     * Stub: returns an empty estimate.
     */
    fun batteryHealthEstimate(sessions: List<ChargeSession>, designMah: Int?): HealthEstimate =
        HealthEstimate(
            estimatedMah = null,
            designMah = designMah?.takeIf { it > 0 },
            healthPct = null,
            qualifyingSessions = 0,
        )

    /**
     * Milliseconds until [snapshot] reaches [targetPct], from a smoothed [recentCurrentsMa] and a
     * taper model above 80 %. Null when not charging, already at target, or current is unknown.
     * Percent is converted to mAh with [batteryCapacityMah] when given, else from
     * `chargeCounterUah / levelPct` on the snapshot.
     *
     * Stub: always null.
     */
    fun timeToLevel(
        snapshot: BatterySnapshot,
        targetPct: Float,
        recentCurrentsMa: List<Double>,
        batteryCapacityMah: Int? = null,
    ): Long? = null
}

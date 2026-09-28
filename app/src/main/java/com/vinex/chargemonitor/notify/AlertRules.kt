package com.vinex.chargemonitor.notify

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus

enum class AlertKind { TARGET, TEMPERATURE, FULL, STOPPED }

/**
 * Decides which charging alerts a reading triggers. Pure; the service keeps the [State] and
 * resets it to [State.INITIAL] when a new session opens, so each alert fires at most once per
 * session.
 *
 * - Target: fires when the level crosses the target upward, so a session that starts above the
 *   target (or one resumed after a restart) stays quiet.
 * - Temperature: fires the first time the threshold is reached.
 * - Full / stopped share one toggle and one "once" flag. Stopped needs [STOPPED_STREAK]
 *   consecutive plugged readings that are not charging, because status flickers briefly on
 *   some phones when the charger renegotiates.
 */
object AlertRules {

    data class Config(
        val targetLevel: Int,
        val tempThresholdC: Float,
        val targetEnabled: Boolean,
        val tempEnabled: Boolean,
        val stoppedEnabled: Boolean,
    )

    data class State(
        val prevLevel: Float? = null,
        val targetSent: Boolean = false,
        val tempSent: Boolean = false,
        val stoppedSent: Boolean = false,
        val notChargingStreak: Int = 0,
    ) {
        companion object {
            val INITIAL = State()
        }
    }

    const val STOPPED_STREAK = 2

    fun evaluate(state: State, snapshot: BatterySnapshot, config: Config): Pair<State, List<AlertKind>> {
        if (!snapshot.isPlugged) return state.copy(notChargingStreak = 0) to emptyList()

        val alerts = mutableListOf<AlertKind>()
        var next = state.copy(prevLevel = snapshot.levelPct)

        val prev = state.prevLevel
        val target = config.targetLevel.toFloat()
        if (config.targetEnabled && !state.targetSent && prev != null &&
            prev < target && snapshot.levelPct >= target
        ) {
            alerts += AlertKind.TARGET
            next = next.copy(targetSent = true)
        }

        if (config.tempEnabled && !state.tempSent && snapshot.tempC >= config.tempThresholdC) {
            alerts += AlertKind.TEMPERATURE
            next = next.copy(tempSent = true)
        }

        val notCharging = snapshot.status == ChargeStatus.NOT_CHARGING ||
            snapshot.status == ChargeStatus.DISCHARGING
        val streak = if (notCharging) state.notChargingStreak + 1 else 0
        next = next.copy(notChargingStreak = streak)
        if (config.stoppedEnabled && !state.stoppedSent) {
            when {
                snapshot.status == ChargeStatus.FULL -> {
                    alerts += AlertKind.FULL
                    next = next.copy(stoppedSent = true)
                }
                streak >= STOPPED_STREAK -> {
                    alerts += AlertKind.STOPPED
                    next = next.copy(stoppedSent = true)
                }
            }
        }
        return next to alerts
    }
}

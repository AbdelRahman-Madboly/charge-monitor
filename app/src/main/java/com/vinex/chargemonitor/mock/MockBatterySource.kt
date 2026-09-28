package com.vinex.chargemonitor.mock

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.BatterySource
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType

/**
 * Debug source that replays a synthetic 15 -> 80 % AC charge in [chargeMs], then reports
 * unplugged for [restMs], and repeats. Lets the UI and the session pipeline be exercised on a desk
 * with no charger.
 *
 * Deterministic for a given clock, so tests can drive it with a fake time.
 */
class MockBatterySource(
    private val clock: () -> Long = System::currentTimeMillis,
    private val chargeMs: Long = 60_000L,
    private val restMs: Long = 15_000L,
) : BatterySource {

    private val origin = clock()

    override val isMock: Boolean = true

    override fun read(): BatterySnapshot {
        val now = clock()
        val phase = Math.floorMod(now - origin, chargeMs + restMs)
        val plugged = phase < chargeMs
        val f = if (plugged) phase.toFloat() / chargeMs else 1f
        val level = START_LEVEL + (END_LEVEL - START_LEVEL) * f
        // Constant current to 50 %, then a linear taper, roughly how a phone charger behaves.
        val currentMa = when {
            !plugged -> -250.0
            level < 50f -> 2500.0
            else -> 2500.0 - (level - 50f) / (END_LEVEL - 50f) * 1500.0
        }
        val voltageMv = (3800 + 400 * f).toInt()
        return BatterySnapshot(
            tMs = now,
            voltageMv = voltageMv,
            currentUaRaw = (currentMa * 1000).toLong(),
            currentMa = currentMa,
            levelPct = level,
            chargeCounterUah = (level / 100f * 4_500_000f).toLong(),
            tempC = 30f + 8f * f,
            plug = if (plugged) PlugType.AC else PlugType.NONE,
            status = if (plugged) ChargeStatus.CHARGING else ChargeStatus.DISCHARGING,
            screenOn = false,
        )
    }

    private companion object {
        const val START_LEVEL = 15f
        const val END_LEVEL = 80f
    }
}

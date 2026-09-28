package com.vinex.chargemonitor.mock

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.BatterySource
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType

/**
 * Debug source that replays a synthetic AC charge and then reports unplugged, repeating forever.
 * Lets the UI, the session pipeline, the alerts and the widget be exercised on a desk with no
 * charger.
 *
 * One cycle of [chargeMs] + [restMs]:
 * - the first [RAMP_SHARE] of [chargeMs] ramps 15 -> 80 % in constant current (2.5 A) up to 50 %,
 *   then tapers towards 0.6 A, while the cell voltage climbs 3.7 -> 4.2 V and holds there;
 * - the rest of [chargeMs] reports [ChargeStatus.FULL] at 80 % with a trickle current, so the
 *   full/stopped alert and the reachedFull latch are exercised;
 * - [restMs] reports unplugged, slowly draining.
 *
 * The screen is "on" during a window early in the charge, which also lowers the current into the
 * battery the way real phone use does, so used-while-charging accounting has something to see.
 *
 * Deterministic for a given clock, so tests can drive it with a fake time. The plug state comes
 * from the clock, not from broadcasts, which is why the service polls this source while in mock
 * mode.
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
        // Progress through the ramp, 0..1; stays at 1 in the FULL hold and while unplugged.
        val f = if (plugged) (phase.toFloat() / (chargeMs * RAMP_SHARE)).coerceAtMost(1f) else 1f
        val full = plugged && f >= 1f
        val level = START_LEVEL + (END_LEVEL - START_LEVEL) * f
        val screenOn = plugged && f in SCREEN_ON_FROM..SCREEN_ON_TO

        val chargeMa = when {
            level < CC_END_LEVEL -> CC_MA
            else -> CC_MA - (level - CC_END_LEVEL) / (END_LEVEL - CC_END_LEVEL) * (CC_MA - TAPER_END_MA)
        }
        val currentMa = when {
            !plugged -> -DRAIN_MA
            full -> TRICKLE_MA
            screenOn -> chargeMa - SCREEN_DRAW_MA
            else -> chargeMa
        }
        // Voltage rises through the constant-current phase and sits at 4.2 V during the taper.
        val ccProgress = ((level - START_LEVEL) / (CC_END_LEVEL - START_LEVEL)).coerceIn(0f, 1f)
        val voltageMv = if (plugged) (3700 + 500 * ccProgress).toInt() else REST_MV

        return BatterySnapshot(
            tMs = now,
            voltageMv = voltageMv,
            currentUaRaw = (currentMa * 1000).toLong(),
            currentMa = currentMa,
            levelPct = level,
            chargeCounterUah = (level / 100f * CAPACITY_UAH).toLong(),
            tempC = 30f + 8f * f,
            plug = if (plugged) PlugType.AC else PlugType.NONE,
            status = when {
                full -> ChargeStatus.FULL
                plugged -> ChargeStatus.CHARGING
                else -> ChargeStatus.DISCHARGING
            },
            screenOn = screenOn,
        )
    }

    private companion object {
        const val START_LEVEL = 15f
        const val END_LEVEL = 80f
        const val CC_END_LEVEL = 50f
        const val RAMP_SHARE = 0.85f
        const val CC_MA = 2500.0
        const val TAPER_END_MA = 600.0
        const val TRICKLE_MA = 50.0
        const val DRAIN_MA = 250.0
        const val SCREEN_DRAW_MA = 400.0
        const val SCREEN_ON_FROM = 0.20f
        const val SCREEN_ON_TO = 0.35f
        const val REST_MV = 4100
        const val CAPACITY_UAH = 4_500_000f
    }
}

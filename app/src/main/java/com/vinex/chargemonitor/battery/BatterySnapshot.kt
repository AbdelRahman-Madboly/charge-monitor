package com.vinex.chargemonitor.battery

/** Physical connection reported by the platform; maps from `BatteryManager.BATTERY_PLUGGED_*`. */
enum class PlugType { AC, USB, WIRELESS, NONE }

/** Charging state; maps from `BatteryManager.BATTERY_STATUS_*`. */
enum class ChargeStatus { CHARGING, FULL, DISCHARGING, NOT_CHARGING, UNKNOWN }

/**
 * One reading of the fuel gauge.
 *
 * Contract:
 * - [currentUaRaw] is exactly what `BATTERY_PROPERTY_CURRENT_NOW` returned, unit and sign
 *   unknown (µA on most devices, mA or inverted on some Samsung builds). Keep it for diagnosis.
 * - [currentMa] is the normalised value: milliamps, **positive = into the battery**. Everything
 *   downstream (accumulator, stats, UI) uses only this field.
 * - [levelPct] is 0..100 with a decimal part when the charge counter allows it, otherwise the
 *   integer level as a Float.
 * - [chargeCounterUah] is null when the device does not report `BATTERY_PROPERTY_CHARGE_COUNTER`
 *   (or reports 0 / Int.MIN_VALUE).
 * - [tMs] is wall-clock milliseconds (`System.currentTimeMillis()`), not elapsed realtime, so it
 *   can be stored and compared across process restarts.
 */
data class BatterySnapshot(
    val tMs: Long,
    val voltageMv: Int,
    val currentUaRaw: Long,
    val currentMa: Double,
    val levelPct: Float,
    val chargeCounterUah: Long?,
    val tempC: Float,
    val plug: PlugType,
    val status: ChargeStatus,
    val screenOn: Boolean,
) {
    /** Power into the battery in watts; negative while discharging. */
    val powerW: Double get() = voltageMv / 1000.0 * currentMa / 1000.0

    val isPlugged: Boolean get() = plug != PlugType.NONE

    companion object {
        /** Neutral reading used before the first real sample arrives. */
        val EMPTY = BatterySnapshot(
            tMs = 0L,
            voltageMv = 0,
            currentUaRaw = 0L,
            currentMa = 0.0,
            levelPct = 0f,
            chargeCounterUah = null,
            tempC = 0f,
            plug = PlugType.NONE,
            status = ChargeStatus.UNKNOWN,
            screenOn = false,
        )
    }
}

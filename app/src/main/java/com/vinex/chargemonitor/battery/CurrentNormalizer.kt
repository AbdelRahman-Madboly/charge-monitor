package com.vinex.chargemonitor.battery

/** User override from Settings; [AUTO] lets [CurrentNormalizer] decide from observed values. */
enum class CurrentUnit { AUTO, MICRO, MILLI }

/**
 * Turns the raw `CURRENT_NOW` value into milliamps, positive into the battery.
 *
 * Pure: no Android calls. Stateful only through [verdict], which the caller persists (see
 * [Verdict.encode]) so a detection made on one charge survives process death.
 *
 * Contract for implementers:
 * - With [override] MICRO or MILLI the scale is fixed; only the sign is still inferred.
 * - In AUTO, the scale is decided from magnitudes seen while plugged (a phone charging draws
 *   hundreds of mA, so |raw| in the thousands or more means µA).
 * - Sign: while [ChargeStatus.CHARGING] the true current is into the battery, so a consistently
 *   negative raw value means the device reports inverted sign (seen on some Samsung builds).
 * - [normalize] must return a finite number for every input and never throw.
 */
class CurrentNormalizer(
    private val override: CurrentUnit = CurrentUnit.AUTO,
    initial: Verdict? = null,
) {
    enum class Scale { MICRO, MILLI }

    /** What has been learned about this device's reporting convention. */
    data class Verdict(
        val scale: Scale,
        val invertSign: Boolean,
        /** Number of plugged-in observations that support this verdict. */
        val evidence: Int,
    ) {
        fun encode(): String = "${scale.name},$invertSign,$evidence"

        companion object {
            /** Android documents µA with positive = charging; assume that until shown otherwise. */
            val DEFAULT = Verdict(Scale.MICRO, invertSign = false, evidence = 0)

            fun decode(text: String?): Verdict? {
                val parts = text?.split(',') ?: return null
                if (parts.size != 3) return null
                val scale = runCatching { Scale.valueOf(parts[0]) }.getOrNull() ?: return null
                val invert = parts[1].toBooleanStrictOrNull() ?: return null
                val evidence = parts[2].toIntOrNull() ?: return null
                return Verdict(scale, invert, evidence)
            }
        }
    }

    var verdict: Verdict = initial ?: Verdict.DEFAULT
        private set

    /**
     * Records one observation (which may refine [verdict]) and returns the normalised current
     * in mA, positive into the battery.
     *
     * Stub: applies the current verdict and the override without learning.
     */
    fun normalize(raw: Long, status: ChargeStatus, plug: PlugType): Double {
        val scale = when (override) {
            CurrentUnit.MICRO -> Scale.MICRO
            CurrentUnit.MILLI -> Scale.MILLI
            CurrentUnit.AUTO -> verdict.scale
        }
        val ma = if (scale == Scale.MICRO) raw / 1000.0 else raw.toDouble()
        return if (verdict.invertSign) -ma else ma
    }
}

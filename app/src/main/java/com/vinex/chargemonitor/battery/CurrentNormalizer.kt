package com.vinex.chargemonitor.battery

import kotlin.math.abs

/** User override from Settings; [AUTO] lets [CurrentNormalizer] decide from observed values. */
enum class CurrentUnit { AUTO, MICRO, MILLI }

/** Receives the verdict whenever it changes enough to be worth persisting. */
fun interface VerdictSink {
    fun save(verdict: CurrentNormalizer.Verdict)
}

/**
 * Turns the raw `CURRENT_NOW` value into milliamps, positive into the battery.
 *
 * Pure: no Android calls. Stateful only through [verdict], which is handed to [sink] (see
 * [Verdict.encode]) so a detection made on one charge survives process death.
 *
 * How it learns:
 * - Scale, only from plugged + CHARGING readings. A charging phone takes roughly 0.1–6 A, so
 *   |raw| in [MILLI_MIN, MILLI_MAX] can only be mA (in µA it would be under 10 mA) and
 *   |raw| >= [MICRO_MIN] can only be µA (in mA it would be over 30 A). The band in between,
 *   and anything tiny, is ambiguous and ignored.
 * - Sign, from plugged + CHARGING (should be positive) and unplugged + DISCHARGING (should be
 *   negative). Readings below [SIGN_MIN_MA] are too close to zero to trust.
 * - Each property keeps an evidence counter with hysteresis: agreeing readings add one (capped),
 *   disagreeing ones subtract one, and the verdict only flips once the counter is exhausted. A
 *   fresh install flips on the first clear reading; a well-established verdict survives a few
 *   odd readings such as a phone under heavy load while plugged.
 *
 * [override] MICRO or MILLI fixes the scale for output; learning continues underneath so that
 * switching back to AUTO starts from a good verdict. It is a `var` so Settings can change it
 * without rebuilding the normalizer.
 */
class CurrentNormalizer(
    @Volatile var override: CurrentUnit = CurrentUnit.AUTO,
    initial: Verdict? = null,
    private val sink: VerdictSink = VerdictSink { },
) {
    enum class Scale { MICRO, MILLI }

    /** What has been learned about this device's reporting convention. */
    data class Verdict(
        val scale: Scale,
        val invertSign: Boolean,
        /** Net plugged-in observations supporting [scale]. */
        val evidence: Int,
        /** Net observations supporting [invertSign]. */
        val signEvidence: Int = 0,
    ) {
        fun encode(): String = "${scale.name},$invertSign,$evidence,$signEvidence"

        companion object {
            /** Android documents µA with positive = charging; assume that until shown otherwise. */
            val DEFAULT = Verdict(Scale.MICRO, invertSign = false, evidence = 0, signEvidence = 0)

            /** Accepts the current four-field form and the older three-field form. */
            fun decode(text: String?): Verdict? {
                val parts = text?.split(',') ?: return null
                if (parts.size !in 3..4) return null
                val scale = runCatching { Scale.valueOf(parts[0]) }.getOrNull() ?: return null
                val invert = parts[1].toBooleanStrictOrNull() ?: return null
                val evidence = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val signEvidence = if (parts.size == 4) {
                    parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                } else {
                    0
                }
                return Verdict(scale, invert, evidence, signEvidence)
            }
        }
    }

    @Volatile
    var verdict: Verdict = initial ?: Verdict.DEFAULT
        private set

    /**
     * Records one observation (which may refine [verdict]) and returns the normalised current
     * in mA, positive into the battery. Never throws; always finite.
     */
    @Synchronized
    fun normalize(raw: Long, status: ChargeStatus, plug: PlugType): Double {
        learn(raw, status, plug)
        val scale = when (override) {
            CurrentUnit.MICRO -> Scale.MICRO
            CurrentUnit.MILLI -> Scale.MILLI
            CurrentUnit.AUTO -> verdict.scale
        }
        val ma = if (scale == Scale.MICRO) raw / 1000.0 else raw.toDouble()
        return if (verdict.invertSign) -ma else ma
    }

    private fun learn(raw: Long, status: ChargeStatus, plug: PlugType) {
        // Long.MIN_VALUE has no positive counterpart; it is also the platform's "unsupported".
        if (raw == Long.MIN_VALUE || raw == 0L) return
        val magnitude = abs(raw)
        val before = verdict
        var next = before

        val chargingPlugged = plug != PlugType.NONE && status == ChargeStatus.CHARGING
        if (chargingPlugged) {
            val observedScale = when {
                magnitude >= MICRO_MIN -> Scale.MICRO
                magnitude in MILLI_MIN..MILLI_MAX -> Scale.MILLI
                else -> null
            }
            if (observedScale != null) {
                val (scale, evidence) = vote(next.scale, next.evidence, observedScale)
                next = next.copy(scale = scale, evidence = evidence)
            }
        }

        // Sign needs a magnitude that is not noise in the unit just decided (scale runs first, so
        // a Samsung mA reading flips the scale and then counts for the sign on the same tick).
        val signFloor = if (next.scale == Scale.MICRO) SIGN_MIN_MA * 1000 else SIGN_MIN_MA
        if (magnitude >= signFloor) {
            val shouldBePositive = when {
                chargingPlugged -> true
                plug == PlugType.NONE && status == ChargeStatus.DISCHARGING -> false
                else -> null
            }
            if (shouldBePositive != null) {
                val observedInvert = (raw > 0) != shouldBePositive
                val (invert, evidence) = vote(next.invertSign, next.signEvidence, observedInvert)
                next = next.copy(invertSign = invert, signEvidence = evidence)
            }
        }

        if (next != before) {
            verdict = next
            if (worthSaving(before, next)) runCatching { sink.save(next) }
        }
    }

    /** Hysteresis counter: returns the (possibly flipped) value and its new evidence. */
    private fun <T> vote(current: T, evidence: Int, observed: T): Pair<T, Int> = when {
        observed == current -> current to (evidence + 1).coerceAtMost(MAX_EVIDENCE)
        evidence > 0 -> current to evidence - 1
        else -> observed to 1
    }

    // Saving on every tick would write preferences every 10 s for no benefit; a flip or a round
    // number of confirmations is enough to survive process death.
    private fun worthSaving(before: Verdict, after: Verdict): Boolean =
        before.scale != after.scale ||
            before.invertSign != after.invertSign ||
            (after.evidence != before.evidence && after.evidence % SAVE_EVERY == 0) ||
            (after.signEvidence != before.signEvidence && after.signEvidence % SAVE_EVERY == 0)

    companion object {
        /** |raw| at or above this while charging must be µA (30 mA; 30 A if it were mA). */
        const val MICRO_MIN = 30_000L

        /** mA band while charging: 100 mA .. 10 A. In µA the same numbers would be < 10 mA. */
        const val MILLI_MIN = 100L
        const val MILLI_MAX = 10_000L

        /** Below this many mA the sign is noise (idle drift, heavy use nearly cancelling input). */
        const val SIGN_MIN_MA = 20L

        const val MAX_EVIDENCE = 100
        const val SAVE_EVERY = 10
    }
}

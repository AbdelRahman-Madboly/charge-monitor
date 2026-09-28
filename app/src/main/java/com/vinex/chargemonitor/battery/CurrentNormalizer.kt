package com.vinex.chargemonitor.battery

import kotlin.math.abs
import kotlin.math.ln

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
 * How it learns, strongest evidence first:
 * - Charge-counter slope. When the reading carries a charge counter, readings are gathered into
 *   windows of at least [SLOPE_WINDOW_MS] and [SLOPE_MIN_DELTA_UAH]. The counter's slope is the
 *   true net current, so whichever of `raw / 1000` (µA) or `raw` (mA) matches it within a
 *   factor of [SLOPE_MATCH_FACTOR] is the unit, and the direction the counter moved fixes the
 *   sign. Such a vote carries [CORROBORATED_WEIGHT] and can overturn an established verdict.
 *   A slow window (trickle, protect-battery hold, use cancelling the input) never reaches the
 *   minimum delta and so never votes, which is exactly the case the magnitude rule gets wrong.
 * - Magnitude, only while the verdict is still young (evidence below [LOCK_EVIDENCE]), plugged,
 *   CHARGING and at most [MAX_LEVEL_FOR_MAGNITUDE] %. A phone charging properly takes roughly
 *   0.1–6 A, so |raw| in [MILLI_MIN, MILLI_MAX] reads as mA and |raw| >= [MICRO_MIN] as µA. Near
 *   full, or with the net current near zero, that assumption fails (3 000 µA is 3 mA, not 3 A),
 *   which is why this rule gets a fresh install going but cannot flip a settled verdict.
 * - Sign from status: plugged + CHARGING should be positive, but only once the current is at
 *   least [SIGN_MIN_CHARGING_MA] and the verdict is young, because heavy use can hold the net
 *   current negative for a long time while the status still says CHARGING. Unplugged +
 *   DISCHARGING should be negative (from [SIGN_MIN_MA]); that one cannot be fooled.
 * - Each property keeps an evidence counter with hysteresis: agreeing votes add, disagreeing
 *   ones subtract, and the verdict only flips once the counter is exhausted.
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
     *
     * [levelPct], [counterUah] and [tMs] are optional context: without them only the magnitude
     * and status rules apply.
     */
    @Synchronized
    fun normalize(
        raw: Long,
        status: ChargeStatus,
        plug: PlugType,
        levelPct: Float? = null,
        counterUah: Long? = null,
        tMs: Long? = null,
    ): Double {
        learn(raw, status, plug, levelPct, counterUah, tMs)
        val scale = when (override) {
            CurrentUnit.MICRO -> Scale.MICRO
            CurrentUnit.MILLI -> Scale.MILLI
            CurrentUnit.AUTO -> verdict.scale
        }
        val ma = if (scale == Scale.MICRO) raw / 1000.0 else raw.toDouble()
        return if (verdict.invertSign) -ma else ma
    }

    // Slope window: the counter and time at its start, and the raw readings seen since.
    private var windowStartMs: Long? = null
    private var windowStartCounter: Long = 0L
    private var windowLastMs: Long = 0L
    private var windowRawSum: Double = 0.0
    private var windowReadings: Int = 0

    /** Direction of the last conclusive counter window; null until one completes. */
    private var lastSlopePositive: Boolean? = null

    private fun learn(
        raw: Long,
        status: ChargeStatus,
        plug: PlugType,
        levelPct: Float?,
        counterUah: Long?,
        tMs: Long?,
    ) {
        // Long.MIN_VALUE has no positive counterpart; it is also the platform's "unsupported".
        if (raw == Long.MIN_VALUE || raw == 0L) return
        val magnitude = abs(raw)
        val before = verdict
        var next = slopeVote(before, raw, plug, counterUah, tMs)

        val chargingPlugged = plug != PlugType.NONE && status == ChargeStatus.CHARGING
        val nearFull = levelPct != null && levelPct > MAX_LEVEL_FOR_MAGNITUDE
        if (chargingPlugged && !nearFull && next.evidence < LOCK_EVIDENCE) {
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

        // Scale runs first, so a Samsung mA reading flips the scale and then counts for the sign
        // on the same tick, measured in the unit just decided.
        val ma = if (next.scale == Scale.MICRO) magnitude / 1000.0 else magnitude.toDouble()
        val shouldBePositive = when {
            plug == PlugType.NONE && status == ChargeStatus.DISCHARGING && ma >= SIGN_MIN_MA -> false
            chargingPlugged && !nearFull && ma >= SIGN_MIN_CHARGING_MA &&
                next.signEvidence < LOCK_EVIDENCE && lastSlopePositive != false -> true
            else -> null
        }
        if (shouldBePositive != null) {
            val observedInvert = (raw > 0) != shouldBePositive
            val (invert, evidence) = vote(next.invertSign, next.signEvidence, observedInvert)
            next = next.copy(invertSign = invert, signEvidence = evidence)
        }

        if (next != before) {
            verdict = next
            if (worthSaving(before, next)) runCatching { sink.save(next) }
        }
    }

    /**
     * Adds [raw] to the current counter window and, when the window is conclusive, returns
     * [from] updated with one weighted vote for scale and sign. Unplugging, a missing counter or
     * a stalled window starts over, so a window never spans a change of state.
     */
    private fun slopeVote(from: Verdict, raw: Long, plug: PlugType, counterUah: Long?, tMs: Long?): Verdict {
        val start = windowStartMs
        if (plug == PlugType.NONE || counterUah == null || tMs == null ||
            start == null || tMs <= windowLastMs || tMs - start > SLOPE_WINDOW_MAX_MS
        ) {
            if (plug != PlugType.NONE && counterUah != null && tMs != null) {
                resetWindow(tMs, counterUah)
            } else {
                windowStartMs = null
            }
            return from
        }
        windowLastMs = tMs
        windowRawSum += raw
        windowReadings++

        val deltaUah = counterUah - windowStartCounter
        val elapsedMs = tMs - start
        if (elapsedMs < SLOPE_WINDOW_MS || abs(deltaUah) < SLOPE_MIN_DELTA_UAH) return from

        val slopeMa = deltaUah * MS_PER_HOUR / elapsedMs / 1000.0
        val meanRaw = windowRawSum / windowReadings
        resetWindow(tMs, counterUah)
        return judgeWindow(from, slopeMa, meanRaw)
    }

    private fun judgeWindow(from: Verdict, slopeMa: Double, meanRaw: Double): Verdict {
        if (meanRaw == 0.0) return from
        val slope = abs(slopeMa)
        val asMicro = abs(meanRaw) / 1000.0
        val asMilli = abs(meanRaw)
        val tolerance = ln(SLOPE_MATCH_FACTOR)
        val observedScale = when {
            abs(ln(asMicro / slope)) <= tolerance -> Scale.MICRO
            abs(ln(asMilli / slope)) <= tolerance -> Scale.MILLI
            // Neither unit agrees with the counter: the counter or the reading is odd; no vote.
            else -> return from
        }
        lastSlopePositive = slopeMa > 0
        val (scale, evidence) = vote(from.scale, from.evidence, observedScale, CORROBORATED_WEIGHT)
        val observedInvert = (meanRaw > 0) != (slopeMa > 0)
        val (invert, signEvidence) = vote(from.invertSign, from.signEvidence, observedInvert, CORROBORATED_WEIGHT)
        return from.copy(scale = scale, evidence = evidence, invertSign = invert, signEvidence = signEvidence)
    }

    private fun resetWindow(tMs: Long, counterUah: Long) {
        windowStartMs = tMs
        windowStartCounter = counterUah
        windowLastMs = tMs
        windowRawSum = 0.0
        windowReadings = 0
    }

    /**
     * Hysteresis counter: returns the (possibly flipped) value and its new evidence. A
     * disagreeing vote spends up to [weight] of the existing evidence; the value only flips when
     * a disagreeing vote finds the evidence already at zero.
     */
    private fun <T> vote(current: T, evidence: Int, observed: T, weight: Int = 1): Pair<T, Int> = when {
        observed == current -> current to (evidence + weight).coerceAtMost(MAX_EVIDENCE)
        evidence >= weight -> current to evidence - weight
        evidence > 0 -> current to 0
        else -> observed to weight
    }

    // Saving on every tick would write preferences every 10 s for no benefit; a flip or passing a
    // round number of confirmations is enough to survive process death. Weighted votes move in
    // steps larger than one, hence "crossed a multiple" rather than "landed on one".
    private fun worthSaving(before: Verdict, after: Verdict): Boolean =
        before.scale != after.scale ||
            before.invertSign != after.invertSign ||
            after.evidence / SAVE_EVERY != before.evidence / SAVE_EVERY ||
            after.signEvidence / SAVE_EVERY != before.signEvidence / SAVE_EVERY

    companion object {
        /** |raw| at or above this while charging must be µA (30 mA; 30 A if it were mA). */
        const val MICRO_MIN = 30_000L

        /** mA band while charging: 100 mA .. 10 A. In µA the same numbers would be < 10 mA. */
        const val MILLI_MIN = 100L
        const val MILLI_MAX = 10_000L

        /** Below this many mA the discharge sign is noise (idle drift). */
        const val SIGN_MIN_MA = 20L

        /** A CHARGING reading only argues for the sign when it is clearly charging. */
        const val SIGN_MIN_CHARGING_MA = 300L

        /** Above this level the charger tapers or holds, so magnitude says nothing about units. */
        const val MAX_LEVEL_FOR_MAGNITUDE = 95f

        /** From this much evidence on, only counter-corroborated votes can move a verdict. */
        const val LOCK_EVIDENCE = 20

        const val SLOPE_WINDOW_MS = 60_000L
        const val SLOPE_WINDOW_MAX_MS = 15 * 60_000L

        /** 5 mAh: well above the counter's step on the phones seen so far. */
        const val SLOPE_MIN_DELTA_UAH = 5_000L

        /** Units differ by 1000x, so a generous factor still cannot confuse them. */
        const val SLOPE_MATCH_FACTOR = 3.0

        const val CORROBORATED_WEIGHT = 25

        const val MAX_EVIDENCE = 100
        const val SAVE_EVERY = 10

        private const val MS_PER_HOUR = 3_600_000.0
    }
}

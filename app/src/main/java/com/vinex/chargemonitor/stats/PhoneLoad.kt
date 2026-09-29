package com.vinex.chargemonitor.stats

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.Sample

/**
 * What the phone itself drew from the source while it charged.
 *
 * Every figure elsewhere is current into the battery (net). With the screen on, the source also
 * feeds the phone, so a bank used while charging looks weaker than it is. Below 80 % the phone
 * charges at a roughly constant current, so within one session the drop in net current when the
 * screen comes on is the phone's load:
 *
 *     load mA = median(net mA, screen off) - median(net mA, screen on)    (samples below 80 % only)
 *
 * A session without enough of both states borrows the median of the loads measured in other
 * sessions (a phone-wide typical). The load is then applied to the time the screen was on.
 * Screen-off idle drain is ignored, so the estimate is a lower bound.
 */
object PhoneLoad {
    /** Above this level the charge current tapers, which would read as phone load. */
    const val TAPER_LEVEL10 = 800

    /** Samples of each screen state (below 80 %) a session needs for its own measurement. */
    const val MIN_SAMPLES = 6

    const val MAX_LOAD_MA = 3000.0

    /** A longer gap between samples counts as this long, so missing data cannot inflate the figure. */
    const val MAX_GAP_MS = 120_000L

    enum class Method {
        /** The session's own screen-off vs screen-on currents. */
        MEASURED,

        /** Typical load from the phone's other sessions. */
        TYPICAL,

        /** Screen never on: nothing to add. */
        NOT_USED,

        /** Screen on, but no load measurable here or anywhere else. */
        UNKNOWN,
    }

    /** The sample-derived part of a session, cached per closed session since it never changes. */
    data class Profile(
        /** The session's own load; null if not measurable. */
        val measuredLoadMa: Double?,
        /** Time-weighted screen-on time, gaps capped at [MAX_GAP_MS]. */
        val screenOnMs: Long,
        /** Time covered by samples, gaps capped the same way; the denominator for screen-on share. */
        val coveredMs: Long,
    )

    data class SessionUse(
        /** Charge counter delta if the phone has one, else the integrated current; never negative. */
        val intoBatteryMah: Double,
        /** Estimated charge the phone used while the screen was on. */
        val phoneUseMah: Double,
        val method: Method,
        val loadMa: Double?,
    ) {
        val deliveredMah: Double get() = intoBatteryMah + phoneUseMah
    }

    /**
     * Phone load in mA while the screen is on, or null without at least [minSamples] screen-off
     * and screen-on samples below [taperLevel10]. Clamped to 0..[MAX_LOAD_MA].
     */
    fun estimateSessionLoad(
        samples: List<Sample>,
        minSamples: Int = MIN_SAMPLES,
        taperLevel10: Int = TAPER_LEVEL10,
    ): Double? {
        val flat = samples.filter { it.level10 < taperLevel10 }
        val off = flat.filter { !it.screenOn }.map { it.mA.toDouble() }
        val on = flat.filter { it.screenOn }.map { it.mA.toDouble() }
        if (off.size < minSamples || on.size < minSamples) return null
        return (median(off) - median(on)).coerceIn(0.0, MAX_LOAD_MA)
    }

    /** Median of the measured loads; null when none was measured. */
    fun typicalLoad(measuredLoadsMa: List<Double>): Double? =
        measuredLoadsMa.takeIf { it.isNotEmpty() }?.let(::median)

    /**
     * Screen-on time: each sample's screen state holds until the next sample (or the session end),
     * with every interval capped at [maxGapMs].
     */
    fun screenOnMs(session: ChargeSession, samples: List<Sample>, maxGapMs: Long = MAX_GAP_MS): Long =
        intervals(session, samples, maxGapMs).filter { it.first }.sumOf { it.second }

    /** Charge in mAh the phone used at [loadMa] while the screen was on. */
    fun phoneUseMah(
        session: ChargeSession,
        samples: List<Sample>,
        loadMa: Double,
        maxGapMs: Long = MAX_GAP_MS,
    ): Double = loadMa.coerceAtLeast(0.0) * screenOnMs(session, samples, maxGapMs) / MS_PER_HOUR

    fun profile(session: ChargeSession, samples: List<Sample>): Profile {
        val parts = intervals(session, samples, MAX_GAP_MS)
        return Profile(
            measuredLoadMa = estimateSessionLoad(samples),
            screenOnMs = parts.filter { it.first }.sumOf { it.second },
            coveredMs = parts.sumOf { it.second },
        )
    }

    /** One session's split, using its own load when measured, else [typicalLoadMa]. */
    fun sessionUse(session: ChargeSession, profile: Profile, typicalLoadMa: Double?): SessionUse {
        val into = intoBatteryMah(session)
        val (method, load) = when {
            profile.screenOnMs <= 0L -> Method.NOT_USED to null
            profile.measuredLoadMa != null -> Method.MEASURED to profile.measuredLoadMa
            typicalLoadMa != null -> Method.TYPICAL to typicalLoadMa
            else -> Method.UNKNOWN to null
        }
        val use = load?.let { it * profile.screenOnMs / MS_PER_HOUR } ?: 0.0
        return SessionUse(into, use, method, load)
    }

    /** Totals over a group of sessions, for a source card or the summary. */
    data class Delivery(
        val intoBatteryMah: Double,
        val phoneUseMah: Double,
        val measuredSessions: Int,
        val typicalSessions: Int,
        /** Screen on but no load known anywhere: counted as into-battery only. */
        val unknownSessions: Int,
        val screenOnMs: Long,
        val coveredMs: Long,
        /** The phone-wide typical load the fallback used, if any session needed it. */
        val typicalLoadMa: Double?,
    ) {
        val deliveredMah: Double get() = intoBatteryMah + phoneUseMah

        /** Share of the recorded time the screen was on; null without any samples. */
        val screenOnShare: Double? get() = coveredMs.takeIf { it > 0 }?.let { screenOnMs.toDouble() / it }
    }

    /**
     * Sums [sessions]; a session without a profile (samples not loaded yet) counts into-battery
     * only, as if the screen had stayed off.
     */
    fun delivery(sessions: List<ChargeSession>, profiles: Map<Long, Profile>, typicalLoadMa: Double?): Delivery {
        var into = 0.0
        var use = 0.0
        var measured = 0
        var typical = 0
        var unknown = 0
        var on = 0L
        var covered = 0L
        sessions.forEach { s ->
            val p = profiles[s.id]
            if (p == null) {
                into += intoBatteryMah(s)
                return@forEach
            }
            val u = sessionUse(s, p, typicalLoadMa)
            into += u.intoBatteryMah
            use += u.phoneUseMah
            on += p.screenOnMs
            covered += p.coveredMs
            when (u.method) {
                Method.MEASURED -> measured++
                Method.TYPICAL -> typical++
                Method.UNKNOWN -> unknown++
                Method.NOT_USED -> Unit
            }
        }
        return Delivery(into, use, measured, typical, unknown, on, covered, typicalLoadMa.takeIf { typical > 0 })
    }

    fun intoBatteryMah(session: ChargeSession): Double = (session.counterMahIn ?: session.mahIn).coerceAtLeast(0.0)

    private const val MS_PER_HOUR = 3_600_000.0

    /** (screenOn, capped duration) for each sample, holding until the next sample or the session end. */
    private fun intervals(session: ChargeSession, samples: List<Sample>, maxGapMs: Long): List<Pair<Boolean, Long>> {
        val sorted = samples.sortedBy { it.tMs }
        return sorted.mapIndexed { i, s ->
            val next = sorted.getOrNull(i + 1)?.tMs ?: session.endMs ?: s.tMs
            s.screenOn to (next - s.tMs).coerceIn(0L, maxGapMs)
        }
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}

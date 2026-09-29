package com.vinex.chargemonitor.ui.history

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.ui.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Orders the History list offers. Only the two date orders group rows under day headers. */
enum class HistorySort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    MOST_ADDED("Most % added"),
    MOST_ENERGY("Most energy"),
    LONGEST("Longest"),
    BY_SOURCE("By source"),
    ;

    val groupsByDay: Boolean get() = this == NEWEST || this == OLDEST
}

/** Date window, counted in whole calendar days ending today. */
enum class HistoryRange(val label: String, val days: Long?) {
    ALL("All time", null),
    WEEK("Last 7 days", 7),
    MONTH("Last 30 days", 30),
}

/**
 * What the user asked the History list to show. View only: nothing here ever changes or removes
 * a stored session, and CSV export ignores it.
 */
data class HistoryFilter(
    val sort: HistorySort = HistorySort.NEWEST,
    /** Show only this source; null shows every source. */
    val sourceId: Long? = null,
    val range: HistoryRange = HistoryRange.ALL,
    val hideShort: Boolean = true,
) {
    /** True when anything narrows the list, so an empty result can offer to clear it. */
    val narrows: Boolean get() = sourceId != null || range != HistoryRange.ALL || hideShort
}

/** Totals of a set of sessions: what the summary line and each section header show. */
data class HistoryTotals(val count: Int = 0, val wh: Double = 0.0, val levelAdded: Double = 0.0) {
    /** Percentage points added over 100: how many times a full 0 to 100 % charge that was. */
    val phoneCharges: Double get() = levelAdded / 100.0

    companion object {
        fun of(sessions: List<ChargeSession>) = HistoryTotals(
            count = sessions.size,
            // A session that drained counts as nothing added, same as levelAdded.
            wh = sessions.sumOf { it.whIn.coerceAtLeast(0.0) },
            levelAdded = sessions.sumOf { it.levelAdded.toDouble() },
        )
    }
}

/**
 * One block of the list. [day] is set for the date orders, [sourceId] for the source order, and
 * neither for a flat ranked list.
 */
data class HistorySection(
    val day: LocalDate? = null,
    val sourceId: Long? = null,
    val sessions: List<ChargeSession>,
) {
    val totals: HistoryTotals = HistoryTotals.of(sessions)
}

data class HistoryView(
    val sections: List<HistorySection>,
    /** Totals of everything shown, across all sections. */
    val totals: HistoryTotals,
    /** Sessions matching the source and date filters that were left out only for being short. */
    val hiddenShort: Int,
)

object HistoryQuery {
    /** Anything plugged for less than this is a connector wiggle, not a charge. */
    const val SHORT_MS = 60_000L

    /** Below one percentage point the level readout shows no change at all. */
    const val SHORT_LEVEL_PCT = 1f

    /** A long session at a charge limit adds no level but can still move real energy; keep those. */
    const val SHORT_ENERGY_WH = 0.5

    fun durationMs(s: ChargeSession): Long = ((s.endMs ?: s.updatedMs) - s.startMs).coerceAtLeast(0L)

    /**
     * Noise rows such as "50 to 50 %, 6 s, 0 mAh": under a minute, or no level and next to no
     * energy added. The session still recording is never short; it may yet become a real charge.
     */
    fun isShort(s: ChargeSession): Boolean {
        if (s.isOpen) return false
        if (durationMs(s) < SHORT_MS) return true
        return s.levelAdded < SHORT_LEVEL_PCT && s.whIn < SHORT_ENERGY_WH
    }

    /** First instant the [range] admits, or null for all time. */
    fun rangeStartMs(range: HistoryRange, zone: ZoneId, nowMs: Long): Long? {
        val days = range.days ?: return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return today.minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Filters, orders and groups [sessions] for display.
     *
     * [sourceName] resolves a source id for the source order; ties in every order fall back to
     * newest first so the list never shuffles between recompositions.
     */
    fun apply(
        sessions: List<ChargeSession>,
        filter: HistoryFilter,
        zone: ZoneId,
        nowMs: Long,
        sourceName: (Long) -> String = { "" },
    ): HistoryView {
        val from = rangeStartMs(filter.range, zone, nowMs)
        val matching = sessions.filter { s ->
            (filter.sourceId == null || s.sourceId == filter.sourceId) && (from == null || s.startMs >= from)
        }
        val shown = if (filter.hideShort) matching.filterNot(::isShort) else matching
        val hiddenShort = matching.size - shown.size

        val newestFirst = compareByDescending<ChargeSession> { it.startMs }.thenByDescending { it.id }
        val ordered = when (filter.sort) {
            HistorySort.NEWEST -> shown.sortedWith(newestFirst)
            HistorySort.OLDEST -> shown.sortedWith(compareBy<ChargeSession> { it.startMs }.thenBy { it.id })
            HistorySort.MOST_ADDED -> shown.sortedWith(compareByDescending<ChargeSession> { it.levelAdded }.then(newestFirst))
            HistorySort.MOST_ENERGY -> shown.sortedWith(compareByDescending<ChargeSession> { it.whIn }.then(newestFirst))
            HistorySort.LONGEST -> shown.sortedWith(compareByDescending<ChargeSession> { durationMs(it) }.then(newestFirst))
            HistorySort.BY_SOURCE -> shown.sortedWith(
                compareBy<ChargeSession> { sourceName(it.sourceId).lowercase() }.thenBy { it.sourceId }.then(newestFirst),
            )
        }

        val sections = when {
            ordered.isEmpty() -> emptyList()
            filter.sort.groupsByDay ->
                ordered.groupBy { dayOf(it, zone) }.map { (day, list) -> HistorySection(day = day, sessions = list) }
            filter.sort == HistorySort.BY_SOURCE ->
                ordered.groupBy { it.sourceId }.map { (id, list) -> HistorySection(sourceId = id, sessions = list) }
            else -> listOf(HistorySection(sessions = ordered))
        }
        return HistoryView(sections, HistoryTotals.of(ordered), hiddenShort)
    }

    /**
     * Sources to offer as filter chips: every source with at least one session, most used first,
     * plus [selected] so the active chip never disappears from under the user.
     */
    fun chipSourceIds(sessions: List<ChargeSession>, selected: Long?): List<Long> {
        val counts = sessions.groupingBy { it.sourceId }.eachCount()
        val ids = counts.entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key }).map { it.key }
        return if (selected != null && selected !in counts) ids + selected else ids
    }

    /** A session belongs to the day it started on. */
    fun dayOf(s: ChargeSession, zone: ZoneId): LocalDate = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
}

/** Text for the numbers on History rows and headers; kept out of the composables so it is testable. */
object HistoryText {
    private const val MINUS = "−"

    /** Level change with its sign: "+60 %", "+0.4 %", "0 %", "−3.0 %". */
    fun signedLevel(delta: Float): String {
        val d = delta.toDouble()
        if (abs(d) < 0.05) return "0 %"
        val body = Format.f(abs(d), if (abs(d) >= 10) 0 else 1)
        return (if (d > 0) "+" else MINUS) + body + " %"
    }

    /** Energy; a negative figure means the phone drained while plugged in, shown with a real minus. */
    fun signedWh(wh: Double): String = if (wh <= -0.005) "$MINUS${Format.wh(-wh)} Wh" else "${Format.wh(wh.coerceAtLeast(0.0))} Wh"

    fun sessions(n: Int): String = if (n == 1) "1 session" else "$n sessions"

    /** Level added over 100: "1.26 phone charges". */
    fun phoneCharges(t: HistoryTotals): String = "${Format.f(t.phoneCharges, 2)} phone charges"

    /** "3 sessions · 12.3 Wh · 1.26 phone charges". */
    fun summary(t: HistoryTotals): String = "${sessions(t.count)} · ${Format.wh(t.wh)} Wh · ${phoneCharges(t)}"

    /** Shorter form for section headers, which share the line with a date: "3 sessions · 12.3 Wh · 1.26 charges". */
    fun header(t: HistoryTotals): String = "${sessions(t.count)} · ${Format.wh(t.wh)} Wh · ${Format.f(t.phoneCharges, 2)} charges"
}

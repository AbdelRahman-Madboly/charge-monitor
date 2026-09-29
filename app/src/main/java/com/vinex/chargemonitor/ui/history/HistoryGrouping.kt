package com.vinex.chargemonitor.ui.history

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.SourceKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How the History list is grouped. View only, like every other History filter. */
enum class HistoryGroup(val label: String) {
    DATE("Group by day"),
    SOURCE_TYPE("Group by type"),
    RUNS("Charge runs"),
}

/**
 * Sessions from one source that follow each other with short gaps: a laptop charge that unplugs
 * and replugs many times, or a 6 s connector blip right before a real charge. [sessions] are in
 * start order and never empty.
 */
data class ChargeRun(val sessions: List<ChargeSession>) {
    init {
        require(sessions.isNotEmpty())
    }

    private val first: ChargeSession get() = sessions.first()
    private val last: ChargeSession get() = sessions.last()

    /** Stable id for list keys: the first session never changes as a run grows. */
    val id: Long get() = first.id
    val sourceId: Long get() = first.sourceId
    val startMs: Long get() = first.startMs

    /** Latest instant any piece reached; for the open piece that is its last checkpoint. */
    val endMs: Long get() = sessions.maxOf { it.endMs ?: it.updatedMs }
    val isOpen: Boolean get() = sessions.any { it.isOpen }
    val startLevel: Float get() = first.startLevel
    val endLevel: Float get() = last.endLevel ?: last.startLevel
    val pieces: Int get() = sessions.size

    /** Open session in this run, if it is still recording. */
    val openSession: ChargeSession? get() = sessions.lastOrNull { it.isOpen }

    val totals: HistoryTotals = HistoryTotals.of(sessions)
    val levelAdded: Double get() = totals.levelAdded
    val wh: Double get() = totals.wh
    val mah: Double get() = totals.mah

    /** Time actually plugged in, gaps left out. */
    val activeMs: Long = sessions.sumOf { HistoryQuery.durationMs(it) }

    /** First plug-in to last unplug, gaps included. */
    val spanMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** A source and its shown sessions, inside a [KindSection]. */
data class SourceGroup(val sourceId: Long, val sessions: List<ChargeSession>) {
    val totals: HistoryTotals = HistoryTotals.of(sessions)
}

/** Power banks, chargers, laptop or wireless. [kind] is null for sessions whose source is unknown. */
data class KindSection(val kind: SourceKind?, val sources: List<SourceGroup>) {
    val totals: HistoryTotals = HistoryTotals.of(sources.flatMap { it.sessions })
}

/** A block of runs: under a day header for date orders, a source header for the source order, or flat. */
data class RunSection(
    val day: LocalDate? = null,
    val sourceId: Long? = null,
    val runs: List<ChargeRun>,
) {
    val totals: HistoryTotals = HistoryTotals.of(runs.flatMap { it.sessions })
}

object HistoryGrouping {
    /** Longest unplugged gap that still counts as the same charge. */
    const val RUN_GAP_MS = 30 * 60_000L

    /** Section order in source type mode. */
    val KIND_ORDER = listOf(SourceKind.POWER_BANK, SourceKind.CHARGER, SourceKind.LAPTOP, SourceKind.WIRELESS)

    /**
     * Merges [sessions] into runs, in start order. A session joins the run before it when it is
     * from the same source and started at most [gapMs] after that run's latest end. Any session
     * from another source in between ends the run, since the phone was on another charger then.
     */
    fun runs(sessions: List<ChargeSession>, gapMs: Long = RUN_GAP_MS): List<ChargeRun> {
        val ordered = sessions.sortedWith(compareBy<ChargeSession> { it.startMs }.thenBy { it.id })
        val out = ArrayList<ChargeRun>()
        var current = ArrayList<ChargeSession>()
        var currentEnd = Long.MIN_VALUE
        for (s in ordered) {
            val prev = current.lastOrNull()
            val joins = prev != null && prev.sourceId == s.sourceId && !prev.isOpen && s.startMs - currentEnd <= gapMs
            if (!joins && current.isNotEmpty()) {
                out += ChargeRun(current)
                current = ArrayList()
                currentEnd = Long.MIN_VALUE
            }
            current += s
            currentEnd = maxOf(currentEnd, s.endMs ?: s.updatedMs)
        }
        if (current.isNotEmpty()) out += ChargeRun(current)
        return out
    }

    /** Short means the whole run is noise; a blip next to a real charge rides along with it. */
    fun isShort(run: ChargeRun): Boolean {
        if (run.isOpen) return false
        if (run.activeMs < HistoryQuery.SHORT_MS) return true
        return run.levelAdded < HistoryQuery.SHORT_LEVEL_PCT && run.wh < HistoryQuery.SHORT_ENERGY_WH
    }

    /** Orders runs like sessions; ties fall back to newest first so the list never shuffles. */
    fun sortRuns(runs: List<ChargeRun>, sort: HistorySort, sourceName: (Long) -> String = { "" }): List<ChargeRun> {
        val newestFirst = compareByDescending<ChargeRun> { it.startMs }.thenByDescending { it.id }
        return when (sort) {
            HistorySort.NEWEST -> runs.sortedWith(newestFirst)
            HistorySort.OLDEST -> runs.sortedWith(compareBy<ChargeRun> { it.startMs }.thenBy { it.id })
            HistorySort.MOST_ADDED -> runs.sortedWith(compareByDescending<ChargeRun> { it.levelAdded }.then(newestFirst))
            HistorySort.MOST_ENERGY -> runs.sortedWith(compareByDescending<ChargeRun> { it.wh }.then(newestFirst))
            HistorySort.LONGEST -> runs.sortedWith(compareByDescending<ChargeRun> { it.activeMs }.then(newestFirst))
            HistorySort.BY_SOURCE -> runs.sortedWith(
                compareBy<ChargeRun> { sourceName(it.sourceId).lowercase() }.thenBy { it.sourceId }.then(newestFirst),
            )
        }
    }

    /** Sections for runs mode: day headers for date orders, source headers for the source order. */
    fun runSections(ordered: List<ChargeRun>, sort: HistorySort, zone: ZoneId): List<RunSection> = when {
        ordered.isEmpty() -> emptyList()
        sort.groupsByDay -> ordered.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .map { (day, list) -> RunSection(day = day, runs = list) }
        sort == HistorySort.BY_SOURCE -> ordered.groupBy { it.sourceId }.map { (id, list) -> RunSection(sourceId = id, runs = list) }
        else -> listOf(RunSection(runs = ordered))
    }

    /**
     * Groups already ordered sessions by source kind, then by source. Kinds come in the fixed
     * [KIND_ORDER] with unknown sources last; sessions keep their order within a source.
     * Sources within a kind are ranked by the sort's metric total, by name for the source order,
     * and by most recent use for the date orders.
     */
    fun byKind(
        ordered: List<ChargeSession>,
        sort: HistorySort,
        sourceKind: (Long) -> SourceKind?,
        sourceName: (Long) -> String = { "" },
    ): List<KindSection> {
        if (ordered.isEmpty()) return emptyList()
        val groups = ordered.groupBy { it.sourceId }.map { (id, list) -> SourceGroup(id, list) }
        val recent = compareByDescending<SourceGroup> { g -> g.sessions.maxOf { it.startMs } }.thenBy { it.sourceId }
        val sourceOrder = when (sort) {
            HistorySort.NEWEST, HistorySort.OLDEST -> recent
            HistorySort.MOST_ADDED -> compareByDescending<SourceGroup> { it.totals.levelAdded }.then(recent)
            HistorySort.MOST_ENERGY -> compareByDescending<SourceGroup> { it.totals.wh }.then(recent)
            HistorySort.LONGEST -> compareByDescending<SourceGroup> { g -> g.sessions.sumOf { HistoryQuery.durationMs(it) } }.then(recent)
            HistorySort.BY_SOURCE -> compareBy<SourceGroup> { sourceName(it.sourceId).lowercase() }.then(recent)
        }
        val byKind = groups.groupBy { sourceKind(it.sourceId) }
        return (KIND_ORDER + listOf<SourceKind?>(null)).mapNotNull { kind ->
            byKind[kind]?.let { KindSection(kind, it.sortedWith(sourceOrder)) }
        }
    }
}

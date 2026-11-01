package com.vinex.chargemonitor.ui.history

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryGroupingTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDate.of(2026, 9, day).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at(30, 12)
    private val min = 60_000L

    // 1 charger, 2 laptop, 3 wireless, 5 and 6 power banks.
    private val names = mapOf(1L to "Charger", 2L to "Laptop", 3L to "Wireless", 5L to "Anker", 6L to "ADATA")
    private val kinds = mapOf(
        1L to SourceKind.CHARGER,
        2L to SourceKind.LAPTOP,
        3L to SourceKind.WIRELESS,
        5L to SourceKind.POWER_BANK,
        6L to SourceKind.POWER_BANK,
    )

    private fun session(
        id: Long,
        start: Long,
        source: Long = 1,
        minutes: Long = 60,
        from: Float = 20f,
        to: Float? = 80f,
        wh: Double = 10.0,
        mah: Double = 2000.0,
        counter: Double? = null,
        open: Boolean = false,
    ) = ChargeSession(
        id = id,
        sourceId = source,
        plug = "AC",
        startMs = start,
        endMs = if (open) null else start + minutes * min,
        updatedMs = start + minutes * min,
        startLevel = from,
        endLevel = if (open) null else to,
        whIn = wh,
        mahIn = mah,
        counterMahIn = counter,
    )

    private fun view(sessions: List<ChargeSession>, filter: HistoryFilter) =
        HistoryQuery.apply(sessions, filter, utc, now, sourceName = { names[it].orEmpty() }, sourceKind = { kinds[it] })

    private fun runIds(v: HistoryView) = v.runSections.flatMap { s -> s.runs.map { r -> r.sessions.map { it.id } } }

    // --- Merging -------------------------------------------------------------------------------

    @Test
    fun gapOfExactlyThirtyMinutesMerges() {
        val a = session(1, at(29, 8), minutes = 20)
        val b = session(2, a.endMs!! + HistoryGrouping.RUN_GAP_MS, minutes = 20)
        val runs = HistoryGrouping.runs(listOf(b, a))
        assertEquals(1, runs.size)
        assertEquals(listOf(1L, 2L), runs[0].sessions.map { it.id })
        assertEquals(2, runs[0].pieces)
    }

    @Test
    fun gapOfThirtyOneMinutesSplits() {
        val a = session(1, at(29, 8), minutes = 20)
        val b = session(2, a.endMs!! + 31 * min, minutes = 20)
        assertEquals(2, HistoryGrouping.runs(listOf(a, b)).size)
    }

    @Test
    fun differentSourceSplitsEvenWhenClose() {
        val a = session(1, at(29, 8), source = 1, minutes = 20)
        val b = session(2, a.endMs!! + min, source = 2, minutes = 20)
        val c = session(3, b.endMs!! + min, source = 1, minutes = 20)
        // The charger pieces are close, but the laptop in between ends the first run.
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), HistoryGrouping.runs(listOf(a, b, c)).map { r -> r.sessions.map { it.id } })
    }

    @Test
    fun runFiguresSumPiecesAndSpanTheGaps() {
        val a = session(1, at(29, 8), minutes = 30, from = 20f, to = 50f, wh = 5.0, mah = 1000.0, counter = 1100.0)
        val b = session(2, at(29, 8, 40), minutes = 20, from = 50f, to = 50f, wh = 0.0, mah = -40.0)
        val c = session(3, at(29, 9, 30), minutes = 60, from = 49f, to = 90f, wh = 7.5, mah = 1500.0)
        val run = HistoryGrouping.runs(listOf(a, b, c)).single()
        assertEquals(20f, run.startLevel)
        assertEquals(90f, run.endLevel)
        assertEquals(71.0, run.levelAdded, 1e-4)
        assertEquals(12.5, run.wh, 1e-9)
        // Counter preferred over the integral; the negative piece counts as nothing.
        assertEquals(2600.0, run.mah, 1e-9)
        assertEquals(110 * min, run.activeMs)
        assertEquals(150 * min, run.spanMs)
        assertFalse(run.isOpen)
    }

    @Test
    fun negativeMahIsClampedInTotals() {
        val t = HistoryTotals.of(listOf(session(1, at(29, 8), mah = -300.0), session(2, at(29, 10), mah = 500.0, counter = -5.0)))
        assertEquals(0.0, t.mah, 1e-9)
    }

    @Test
    fun openSessionJoinsItsRunAndMarksItCharging() {
        val a = session(1, at(30, 10), minutes = 30, from = 40f, to = 60f)
        val b = session(2, at(30, 10, 45), from = 60f, open = true)
        val run = HistoryGrouping.runs(listOf(a, b)).single()
        assertTrue(run.isOpen)
        assertEquals(2L, run.openSession?.id)
        assertFalse(HistoryGrouping.isShort(run))
    }

    @Test
    fun emptyInputHasNoRuns() {
        assertTrue(HistoryGrouping.runs(emptyList()).isEmpty())
        assertTrue(HistoryGrouping.byKind(emptyList(), HistorySort.NEWEST, { kinds[it] }).isEmpty())
        val v = view(emptyList(), HistoryFilter())
        assertTrue(v.isEmpty)
        assertEquals(0, v.totals.count)
    }

    // --- Short runs ----------------------------------------------------------------------------

    @Test
    fun blipNextToARealChargeRidesAlongInsteadOfBeingHidden() {
        val blip = session(1, at(29, 8), source = 6, minutes = 0, from = 50f, to = 50f, wh = 0.0, mah = 0.0)
            .copy(endMs = at(29, 8) + 6_000)
        val real = session(2, at(29, 8, 5), source = 6, minutes = 90, from = 50f, to = 95f)
        val lone = session(3, at(29, 14), source = 6, minutes = 0, from = 47f, to = 47f, wh = 0.0)
            .copy(endMs = at(29, 14) + 10_000)
        val v = view(listOf(blip, real, lone), HistoryFilter())
        assertEquals(listOf(listOf(1L, 2L)), runIds(v))
        assertEquals(1, v.hiddenShort)
        assertEquals(2, v.totals.count)

        val all = view(listOf(blip, real, lone), HistoryFilter(hideShort = false))
        assertEquals(listOf(listOf(3L), listOf(1L, 2L)), runIds(all))
        assertEquals(0, all.hiddenShort)
    }

    @Test
    fun runOfManyTinyPiecesIsShortOnlyIfTheWholeIsShort() {
        // Four 20 s laptop pieces, each short alone, adding nothing.
        val tiny = (0 until 4).map { i ->
            session(10L + i, at(29, 8, i * 2), source = 2, minutes = 0, from = 60f, to = 60f, wh = 0.0)
                .copy(endMs = at(29, 8, i * 2) + 20_000)
        }
        assertTrue(HistoryGrouping.isShort(HistoryGrouping.runs(tiny).single()))
        // The same pieces adding 3 % between them are a real run.
        val adding = tiny.mapIndexed { i, s -> s.copy(startLevel = 60f + i, endLevel = 61f + i) }
        assertFalse(HistoryGrouping.isShort(HistoryGrouping.runs(adding).single()))
    }

    // --- Sorting runs --------------------------------------------------------------------------

    private val sortable = listOf(
        // Run A, charger, 26th: 20 to 40 %, 30 Wh, 30 min.
        session(1, at(26, 8), source = 1, minutes = 30, from = 20f, to = 40f, wh = 30.0),
        // Run B, Anker, 27th, two pieces: 10 to 90 %, 5 Wh, 90 min active.
        session(2, at(27, 9), source = 5, minutes = 45, from = 10f, to = 50f, wh = 2.0),
        session(3, at(27, 10), source = 5, minutes = 45, from = 50f, to = 90f, wh = 3.0),
        // Run C, laptop, 28th: 50 to 60 %, 12 Wh, 200 min.
        session(4, at(28, 9), source = 2, minutes = 200, from = 50f, to = 60f, wh = 12.0),
    )

    private fun order(sort: HistorySort) = runIds(view(sortable, HistoryFilter(sort = sort))).map { it.first() }

    @Test
    fun runsSortByEveryKey() {
        assertEquals(listOf(4L, 2L, 1L), order(HistorySort.NEWEST))
        assertEquals(listOf(1L, 2L, 4L), order(HistorySort.OLDEST))
        assertEquals(listOf(2L, 1L, 4L), order(HistorySort.MOST_ADDED))
        assertEquals(listOf(1L, 4L, 2L), order(HistorySort.MOST_ENERGY))
        assertEquals(listOf(4L, 2L, 1L), order(HistorySort.LONGEST))
        // Anker, Charger, Laptop.
        assertEquals(listOf(2L, 1L, 4L), order(HistorySort.BY_SOURCE))
    }

    @Test
    fun runsGetDayHeadersOnlyForDateSorts() {
        val newest = view(sortable, HistoryFilter(sort = HistorySort.NEWEST))
        assertEquals(listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 26)), newest.runSections.map { it.day })
        assertTrue(newest.sections.isEmpty())

        val ranked = view(sortable, HistoryFilter(sort = HistorySort.MOST_ENERGY))
        assertEquals(1, ranked.runSections.size)
        assertNull(ranked.runSections[0].day)

        val bySource = view(sortable, HistoryFilter(sort = HistorySort.BY_SOURCE))
        assertEquals(listOf(5L, 1L, 2L), bySource.runSections.map { it.sourceId })
    }

    @Test
    fun chargeRunsIsTheDefaultGrouping() {
        assertEquals(HistoryGroup.RUNS, HistoryFilter().group)
    }

    // --- Source type -----------------------------------------------------------------------------

    private val mixed = listOf(
        session(1, at(26, 8), source = 3, wh = 2.0),
        session(2, at(27, 8), source = 1, wh = 11.0),
        session(3, at(28, 8), source = 5, wh = 4.0),
        session(4, at(29, 8), source = 6, wh = 3.0),
        session(5, at(29, 20), source = 6, wh = 3.0),
        session(6, at(25, 8), source = 2, wh = 6.0),
        session(7, at(29, 22), source = 9, wh = 1.0),
    )

    private fun byType(sort: HistorySort) = view(mixed, HistoryFilter(group = HistoryGroup.SOURCE_TYPE, sort = sort))

    @Test
    fun sourceTypeSectionsComeInFixedOrderWithUnknownLast() {
        val v = byType(HistorySort.NEWEST)
        assertEquals(
            listOf(SourceKind.POWER_BANK, SourceKind.CHARGER, SourceKind.LAPTOP, SourceKind.WIRELESS, null),
            v.kinds.map { it.kind },
        )
        assertTrue(v.sections.isEmpty())
        assertTrue(v.runSections.isEmpty())
        val banks = v.kinds[0]
        assertEquals(3, banks.totals.count)
        assertEquals(10.0, banks.totals.wh, 1e-9)
        assertEquals(7, v.totals.count)
    }

    @Test
    fun sourcesWithinATypeFollowTheSort() {
        // Most recent use: ADATA (29th) before Anker (28th); sessions newest first.
        val recent = byType(HistorySort.NEWEST).kinds[0]
        assertEquals(listOf(6L, 5L), recent.sources.map { it.sourceId })
        assertEquals(listOf(5L, 4L), recent.sources[0].sessions.map { it.id })
        // Most energy: ADATA 6 Wh total beats Anker 4 Wh.
        assertEquals(listOf(6L, 5L), byType(HistorySort.MOST_ENERGY).kinds[0].sources.map { it.sourceId })
        // By source: alphabetical, ADATA then Anker.
        assertEquals(listOf(6L, 5L), byType(HistorySort.BY_SOURCE).kinds[0].sources.map { it.sourceId })
        // Oldest first orders sessions within a source.
        assertEquals(listOf(4L, 5L), byType(HistorySort.OLDEST).kinds[0].sources.first { it.sourceId == 6L }.sessions.map { it.id })
    }

    @Test
    fun sourceTypeHidesShortSessionsOneByOne() {
        val blip = session(8, at(29, 9), source = 6, minutes = 0, from = 50f, to = 50f, wh = 0.0).copy(endMs = at(29, 9) + 6_000)
        val v = view(mixed + blip, HistoryFilter(group = HistoryGroup.SOURCE_TYPE))
        assertEquals(1, v.hiddenShort)
        assertEquals(7, v.totals.count)
    }

    @Test
    fun textForRunsAndHeaders() {
        assertEquals("1 piece", HistoryText.pieces(1))
        assertEquals("3 pieces", HistoryText.pieces(3))
        assertEquals("Power banks", HistoryText.kindTitle(SourceKind.POWER_BANK))
        val run = HistoryGrouping.runs(listOf(session(1, at(29, 8), minutes = 90))).single()
        assertEquals("08:00 → 09:30", HistoryText.runTimes(run, showDate = false, zone = utc))
        assertEquals("29 Sep · 08:00 → 09:30", HistoryText.runTimes(run, showDate = true, zone = utc))
        val overnight = HistoryGrouping.runs(listOf(session(2, at(29, 23), minutes = 90))).single()
        assertEquals("29 Sep · 23:00 → 30 Sep · 00:30", HistoryText.runTimes(overnight, showDate = false, zone = utc))
        assertEquals(
            "2 sessions · 20.0 Wh · 4,000 mAh · 1.20 charges",
            HistoryText.groupHeader(HistoryTotals.of(listOf(session(1, at(29, 8)), session(2, at(29, 10))))),
        )
    }

    @Test
    fun `a short blip from another source does not split a run`() {
        val a = session(1, at(29, 22, 36), source = 2, minutes = 15, from = 44f, to = 50f)
        val blip = session(2, at(29, 22, 51), source = 6, minutes = 0, from = 50f, to = 50f, wh = 0.0, mah = 0.0)
        val b = session(3, at(29, 22, 51) + 10_000, source = 2, minutes = 36, from = 50f, to = 68f)
        val runs = HistoryGrouping.runs(listOf(a, blip, b))
        assertEquals(listOf(listOf(1L, 3L), listOf(2L)), runs.map { r -> r.sessions.map { it.id } })
    }

    @Test
    fun `a real session from another source still splits a run`() {
        val a = session(1, at(29, 10), source = 2, minutes = 15)
        val other = session(2, at(29, 10, 20), source = 6, minutes = 20)
        val b = session(3, at(29, 10, 45), source = 2, minutes = 15)
        val runs = HistoryGrouping.runs(listOf(a, other, b))
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), runs.map { r -> r.sessions.map { it.id } })
    }
}

package com.vinex.chargemonitor.ui.history

import com.vinex.chargemonitor.data.ChargeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryQueryTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 9, day).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at(30, 12)
    private val names = mapOf(1L to "Charger", 2L to "Laptop", 5L to "Anker")

    private fun session(
        id: Long,
        start: Long,
        source: Long = 1,
        minutes: Long = 60,
        from: Float = 20f,
        to: Float? = 80f,
        wh: Double = 10.0,
        open: Boolean = false,
        mah: Double = 0.0,
    ) = ChargeSession(
        id = id,
        sourceId = source,
        plug = "AC",
        startMs = start,
        endMs = if (open) null else start + minutes * 60_000,
        updatedMs = start + minutes * 60_000,
        startLevel = from,
        endLevel = to,
        whIn = wh,
        mahIn = mah,
    )

    private fun run(sessions: List<ChargeSession>, filter: HistoryFilter) =
        HistoryQuery.apply(sessions, filter, utc, now) { names[it].orEmpty() }

    private fun ids(view: HistoryView) = view.sections.flatMap { s -> s.sessions.map { it.id } }

    // Noise rows seen on the phone: "50 → 50 % · 6s · 0 mAh" and a 10 s one.
    private val noise = listOf(
        session(90, at(29, 8), minutes = 0, from = 50f, to = 50f, wh = 0.0).copy(endMs = at(29, 8) + 6_000),
        session(91, at(29, 9), minutes = 0, from = 47f, to = 47f, wh = 0.0).copy(endMs = at(29, 9) + 10_000),
    )

    @Test
    fun shortMeansUnderAMinuteOrNothingAdded() {
        assertTrue(HistoryQuery.isShort(noise[0]))
        assertTrue(HistoryQuery.isShort(session(1, at(29, 8), minutes = 30, from = 50f, to = 50.4f, wh = 0.1)))
        assertFalse(HistoryQuery.isShort(session(2, at(29, 8), minutes = 30, from = 50f, to = 60f)))
        // A long session held at a charge limit still moved real energy.
        assertFalse(HistoryQuery.isShort(session(3, at(29, 8), minutes = 90, from = 80f, to = 80f, wh = 2.0)))
        // Still recording: may yet become a real charge.
        assertFalse(HistoryQuery.isShort(session(4, at(29, 8), minutes = 0, from = 80f, to = null, wh = -0.02, open = true)))
    }

    @Test
    fun hideShortDropsNoiseAndCountsIt() {
        val all = noise + session(1, at(29, 10))
        val hidden = run(all, HistoryFilter(group = HistoryGroup.DATE))
        assertEquals(listOf(1L), ids(hidden))
        assertEquals(2, hidden.hiddenShort)

        val shown = run(all, HistoryFilter(group = HistoryGroup.DATE, hideShort = false))
        assertEquals(3, ids(shown).size)
        assertEquals(0, shown.hiddenShort)
    }

    @Test
    fun newestGroupsByDayNewestFirst() {
        val view = run(
            listOf(session(1, at(26, 8)), session(2, at(27, 9)), session(3, at(27, 21))),
            HistoryFilter(group = HistoryGroup.DATE),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 26)), view.sections.map { it.day })
        assertEquals(listOf(3L, 2L, 1L), ids(view))
    }

    @Test
    fun oldestReversesDaysAndRows() {
        val view = run(
            listOf(session(1, at(26, 8)), session(2, at(27, 9)), session(3, at(27, 21))),
            HistoryFilter(group = HistoryGroup.DATE, sort = HistorySort.OLDEST),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27)), view.sections.map { it.day })
        assertEquals(listOf(1L, 2L, 3L), ids(view))
    }

    @Test
    fun rankedSortsAreOneFlatSection() {
        val list = listOf(
            session(1, at(26, 8), from = 20f, to = 40f, wh = 30.0, minutes = 30),
            session(2, at(27, 9), from = 10f, to = 90f, wh = 5.0, minutes = 90),
            session(3, at(28, 9), from = 50f, to = 60f, wh = 12.0, minutes = 200),
        )
        val added = run(list, HistoryFilter(group = HistoryGroup.DATE, sort = HistorySort.MOST_ADDED))
        assertEquals(1, added.sections.size)
        assertNull(added.sections[0].day)
        assertEquals(listOf(2L, 1L, 3L), ids(added))
        assertEquals(listOf(1L, 3L, 2L), ids(run(list, HistoryFilter(group = HistoryGroup.DATE, sort = HistorySort.MOST_ENERGY))))
        assertEquals(listOf(3L, 2L, 1L), ids(run(list, HistoryFilter(group = HistoryGroup.DATE, sort = HistorySort.LONGEST))))
    }

    @Test
    fun tiesFallBackToNewestFirst() {
        val list = listOf(session(1, at(26, 8)), session(2, at(28, 8)), session(3, at(27, 8)))
        assertEquals(listOf(2L, 3L, 1L), ids(run(list, HistoryFilter(group = HistoryGroup.DATE, sort = HistorySort.MOST_ADDED))))
    }

    @Test
    fun bySourceGroupsAlphabeticallyThenNewest() {
        val list = listOf(
            session(1, at(26, 8), source = 1),
            session(2, at(27, 8), source = 5),
            session(3, at(28, 8), source = 1),
            session(4, at(25, 8), source = 2),
        )
        val view = run(list, HistoryFilter(group = HistoryGroup.DATE, sort = HistorySort.BY_SOURCE))
        assertEquals(listOf(5L, 1L, 2L), view.sections.map { it.sourceId })
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(view))
    }

    @Test
    fun sourceFilterKeepsOnlyThatSource() {
        val list = listOf(session(1, at(26, 8), source = 1), session(2, at(27, 8), source = 5))
        assertEquals(listOf(2L), ids(run(list, HistoryFilter(group = HistoryGroup.DATE, sourceId = 5))))
    }

    @Test
    fun rangeCountsWholeDaysEndingToday() {
        val list = listOf(session(1, at(24, 0)), session(2, at(23, 23)), session(3, at(1, 8)))
        // Seven days ending on the 30th start at midnight on the 24th.
        assertEquals(listOf(1L), ids(run(list, HistoryFilter(group = HistoryGroup.DATE, range = HistoryRange.WEEK))))
        assertEquals(listOf(1L, 2L, 3L), ids(run(list, HistoryFilter(group = HistoryGroup.DATE, range = HistoryRange.MONTH))))
    }

    @Test
    fun totalsSumShownSessionsAndIgnoreDrain() {
        val list = listOf(
            session(1, at(29, 8), from = 20f, to = 80f, wh = 12.0, mah = 3000.0),
            session(2, at(29, 10), from = 30f, to = 96f, wh = 13.5, mah = 3210.0),
            session(3, at(29, 12), from = 60f, to = 55f, wh = -0.5, minutes = 30, mah = -120.0),
        )
        val t = run(list, HistoryFilter(group = HistoryGroup.DATE, hideShort = false)).totals
        assertEquals(3, t.count)
        assertEquals(25.5, t.wh, 1e-9)
        assertEquals(126.0, t.levelAdded, 1e-4)
        assertEquals(1.26, t.phoneCharges, 1e-6)
        assertEquals(6210.0, t.mah, 1e-9)
        assertEquals("3 sessions · 6,210 mAh · 25.5 Wh · 1.26 phone charges", HistoryText.summary(t))
    }

    @Test
    fun emptyResultHasNoSections() {
        val view = run(noise, HistoryFilter(group = HistoryGroup.DATE))
        assertTrue(view.sections.isEmpty())
        assertEquals(0, view.totals.count)
        assertEquals(2, view.hiddenShort)
    }

    @Test
    fun clearingFiltersKeepsTheOrder() {
        val f = HistoryFilter(sort = HistorySort.LONGEST, sourceId = 5, range = HistoryRange.WEEK)
        assertTrue(f.narrows)
        assertFalse(HistoryFilter(sort = HistorySort.LONGEST, hideShort = false).narrows)
    }

    @Test
    fun chipsListUsedSourcesMostUsedFirstAndKeepSelection() {
        val list = listOf(session(1, at(26, 8), source = 5), session(2, at(27, 8), source = 1), session(3, at(28, 8), source = 5))
        assertEquals(listOf(5L, 1L), HistoryQuery.chipSourceIds(list, null))
        assertEquals(listOf(5L, 1L, 9L), HistoryQuery.chipSourceIds(list, 9L))
    }

    @Test
    fun signedTextReadsNaturally() {
        assertEquals("+60 %", HistoryText.signedLevel(60f))
        assertEquals("+0.4 %", HistoryText.signedLevel(0.4f))
        assertEquals("0 %", HistoryText.signedLevel(0f))
        assertEquals("−3.0 %", HistoryText.signedLevel(-3f))
        assertEquals("12.3 Wh", HistoryText.signedWh(12.3))
        assertEquals("−0.02 Wh", HistoryText.signedWh(-0.02))
        assertEquals("0.00 Wh", HistoryText.signedWh(-0.001))
    }
}

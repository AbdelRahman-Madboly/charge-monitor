package com.vinex.chargemonitor.ui.history

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Sample
import com.vinex.chargemonitor.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryLogicTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 9, day).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(id: Long, start: Long) =
        ChargeSession(id = id, sourceId = 1, plug = "AC", startMs = start, endMs = start + 3_600_000, startLevel = 20f, endLevel = 80f)

    @Test
    fun groupsNewestDayFirstAndNewestSessionFirst() {
        val groups = HistoryViewModel.groupByDay(
            listOf(session(1, at(26, 8)), session(2, at(27, 9)), session(3, at(27, 21)), session(4, at(25, 23))),
            utc,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 25)), groups.map { it.day })
        assertEquals(listOf(3L, 2L), groups.first().sessions.map { it.id })
    }

    @Test
    fun csvQuotesOnlyWhenNeeded() {
        assertEquals("a,b", CsvExport.line(listOf("a", "b")))
        assertEquals("\"Anker, 20k\",\"say \"\"hi\"\"\"", CsvExport.line(listOf("Anker, 20k", "say \"hi\"")))
    }

    @Test
    fun sessionRowMatchesHeader() {
        val src = PowerSource(id = 1, name = "Bank", kind = SourceKind.POWER_BANK, colorArgb = 0)
        val row = CsvExport.sessionRow(session(7, at(27, 9)).copy(whIn = 12.3456, mahIn = 3000.0), src)
        assertEquals(CsvExport.SESSION_HEADER.size, row.size)
        assertEquals("7", row[0])
        assertEquals("Bank", row[1])
        assertEquals("2026-09-27T09:00:00Z", row[4])
        assertEquals("3600", row[8])
        assertEquals("60.0", row[11])
        assertEquals("12.346", row[14])
    }

    @Test
    fun sampleRowComputesWatts() {
        val row = CsvExport.sampleRow(Sample(sessionId = 7, tMs = 0, mV = 4200, mA = 2500, level10 = 555, temp10 = 312, screenOn = true), "Bank")
        assertEquals(CsvExport.SAMPLE_HEADER.size, row.size)
        assertEquals("10.500", row[6])
        assertEquals("55.5", row[7])
        assertEquals("31.2", row[8])
    }
}

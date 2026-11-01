package com.vinex.chargemonitor.ui.history

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Sample
import com.vinex.chargemonitor.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportTest {
    private val bank = PowerSource(id = 7, name = "Test bank", kind = SourceKind.POWER_BANK, colorArgb = 0)

    @Test
    fun lineQuotesOnlyFieldsThatNeedIt() {
        assertEquals("a,b,c", CsvExport.line(listOf("a", "b", "c")))
        assertEquals("\"a,b\",\"say \"\"hi\"\"\",\"x\ny\"", CsvExport.line(listOf("a,b", "say \"hi\"", "x\ny")))
    }

    @Test
    fun closedSessionRowMatchesTheHeader() {
        val s = ChargeSession(
            id = 3, sourceId = 7, plug = "USB", startMs = 0, endMs = 3_600_000,
            startLevel = 20f, endLevel = 80.5f, mahIn = 2500.04, counterMahIn = 2480.0, whIn = 9.5,
            peakW = 18.123, avgW = 9.5, maxTempC = 36.2f, reachedFull = false, usedWhileCharging = true,
            screenOnFraction = 0.25f, sampleCount = 360,
        )
        val row = CsvExport.sessionRow(s, bank)
        assertEquals(CsvExport.SESSION_HEADER.size, row.size)
        val byName = CsvExport.SESSION_HEADER.zip(row).toMap()
        assertEquals("Test bank", byName["source"])
        assertEquals("POWER_BANK", byName["source_kind"])
        assertEquals("1970-01-01T00:00:00Z", byName["start_iso"])
        assertEquals("1970-01-01T01:00:00Z", byName["end_iso"])
        assertEquals("3600", byName["duration_s"])
        assertEquals("60.5", byName["level_added"])
        assertEquals("2500.0", byName["mah_in"])
        assertEquals("9.500", byName["wh_in"])
        assertEquals("18.12", byName["peak_w"])
        assertEquals("true", byName["used_while_charging"])
    }

    @Test
    fun openSessionLeavesEndFieldsEmpty() {
        val s = ChargeSession(sourceId = 7, plug = "AC", startMs = 1000, startLevel = 50f)
        val byName = CsvExport.SESSION_HEADER.zip(CsvExport.sessionRow(s, null)).toMap()
        assertEquals("", byName["source"])
        assertEquals("", byName["end_iso"])
        assertEquals("", byName["duration_s"])
        assertEquals("", byName["counter_mah_in"])
        assertEquals("0.0", byName["level_added"])
    }

    @Test
    fun sampleRowComputesWattsAndUnpacksScaledFields() {
        val x = Sample(sessionId = 3, tMs = 0, mV = 4200, mA = 2000, level10 = 655, temp10 = 312, screenOn = false)
        val row = CsvExport.sampleRow(x, "Test bank")
        assertEquals(CsvExport.SAMPLE_HEADER.size, row.size)
        val byName = CsvExport.SAMPLE_HEADER.zip(row).toMap()
        assertEquals("8.400", byName["w"])
        assertEquals("65.5", byName["level"])
        assertEquals("31.2", byName["temp_c"])
    }
}

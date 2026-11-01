package com.vinex.chargemonitor.ui.bank

import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankReportTest {
    private val bank = PowerSource(id = 10, name = "PB-1", kind = SourceKind.POWER_BANK, ratedMah = 34000,
        outputMah = 20000, cellsMah = 33500, cells = "10 × NCR18650B, 1S10P", module = "22.5 W", colorArgb = 0)
    private val session = ChargeSession(sourceId = bank.id, plug = "AC", startMs = 100, endMs = 200,
        startLevel = 10f, endLevel = 80f, whIn = 35.0, portWh = 40.0, cellsWh = 45.0, sourceConfirmed = true)

    @Test
    fun reportContainsProductDrainMediansAndMethod() {
        val marks = listOf(
            BankEvent(sourceId = bank.id, tMs = 0, kind = BankEventKind.RECHARGED, cellV = 3.90),
            BankEvent(sourceId = bank.id, tMs = 300, kind = BankEventKind.EMPTY, cellV = 3.00, meterWh = 42.0),
        )
        val text = BankReport.text(BankReport.build(bank, listOf(session), marks))
        for (line in listOf(
            "Charge Monitor — PB-1", "Product: 34000 mAh", "Rated output: 20000 mAh @5V",
            "10 × NCR18650B, 1S10P", "1 | A | 70.00 | 3.90/3.00 | 42.00",
            "Medians over 1 counted drains", "Output / full bank: 12000.00 mAh @5V",
            "Phone charges / full bank: 1.00", "Phone-side scale: 1.05",
            "Retest progress: 1 of 3 verified drains", "battery measured", "Module loss = cells - port",
            "Grades: A = both voltages + USB meter; B = both voltages; C = assumed endpoint(s).",
        )) assertTrue("Missing: $line", text.contains(line))
        assertFalse(text.contains("NaN"))
    }

    @Test
    fun runningBankStartsAtRestingVoltageFractionAndUsesCalibration() {
        val start = BankEvent(sourceId = bank.id, tMs = 0, kind = BankEventKind.RECHARGED, cellV = 3.90)
        val empty = BankReport.build(bank, emptyList(), listOf(start))
        assertEquals(0.70, empty.chargeLeft!!, 1e-9)
        assertEquals(0.70, empty.chargeHistory.single().second, 1e-9)
        val used = BankReport.build(bank, listOf(session), listOf(start))
        assertEquals(0.70 - 45.0 / bank.usableCellWh!!, used.chargeLeft!!, 1e-9)
        assertEquals(used.chargeLeft!!, used.chargeHistory.last().second, 1e-9)
    }

    @Test
    fun emptyReportHasNoInventedResults() {
        val text = BankReport.text(BankReport.build(bank, emptyList(), emptyList()))
        assertTrue(text.contains("Output / full bank: —"))
        assertTrue(text.contains("Retest progress: 0 of 3"))
        assertFalse(text.contains("NaN"))
    }
}

package com.vinex.chargemonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSourceTest {
    private fun bank(ratedMah: Int? = null, cellsMah: Int? = null, outputMah: Int? = null, nominalV: Double = 3.7) =
        PowerSource(
            name = "b", kind = SourceKind.POWER_BANK, colorArgb = 0,
            ratedMah = ratedMah, cellsMah = cellsMah, outputMah = outputMah, nominalV = nominalV,
        )

    @Test
    fun ratedWhUsesTheLabelVoltage() {
        assertEquals(37.0, bank(ratedMah = 10_000).ratedWh!!, 1e-9)
        assertEquals(36.0, bank(ratedMah = 10_000, nominalV = 3.6).ratedWh!!, 1e-9)
        assertNull(bank().ratedWh)
        assertNull(bank(ratedMah = 0).ratedWh)
        assertNull(bank(ratedMah = 10_000, nominalV = 0.0).ratedWh)
    }

    @Test
    fun usableCellWhPrefersTheDatasheetAndStopsAtCutoff() {
        assertEquals(10_000 * 3.7 / 1000 * PowerSource.USABLE_TO_CUTOFF, bank(ratedMah = 10_000).usableCellWh!!, 1e-9)
        assertEquals(34_000 * 3.7 / 1000 * PowerSource.USABLE_TO_CUTOFF,
            bank(ratedMah = 10_000, cellsMah = 34_000).usableCellWh!!, 1e-9)
        assertNull(bank().usableCellWh)
    }

    @Test
    fun outputWhIsTheLabelAtFiveVolts() {
        assertEquals(30.0, bank(outputMah = 6000).outputWh!!, 1e-9)
        assertNull(bank(outputMah = 0).outputWh)
    }

    @Test
    fun onlyPowerBanksCountTowardBankTotals() {
        assertTrue(bank().countsTowardBank)
        SourceKind.entries.filter { it != SourceKind.POWER_BANK }.forEach {
            assertFalse(PowerSource(name = "x", kind = it, colorArgb = 0).countsTowardBank)
        }
    }
}

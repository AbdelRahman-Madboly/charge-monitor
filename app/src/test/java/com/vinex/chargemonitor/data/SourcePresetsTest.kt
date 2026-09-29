package com.vinex.chargemonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePresetsTest {
    private val palette = listOf(1, 2, 3, 4, 5)
    private val charger = PowerSource(id = 1, name = "Charger", kind = SourceKind.CHARGER, colorArgb = 9, builtIn = true)
    private val adata = PowerSource(id = 4, name = "ADATA 20000", kind = SourceKind.POWER_BANK, ratedMah = 20_000, colorArgb = 1)

    private fun added(existing: List<PowerSource> = listOf(charger, adata)) = SourcePresets.toAdd(existing, palette)

    @Test
    fun addsBothBanksAndTheChargerAlongsideAnExistingBankWithoutTouchingIt() {
        val rows = added()
        assertEquals(
            listOf(SourcePresets.DIY_3S8P.name, SourcePresets.DIY_1S10P.name, SourcePresets.ANKER_ZOLO_30W.name),
            rows.map { it.name },
        )
        assertTrue(rows.all { it.id == 0L && !it.builtIn }) // new rows, never an update of ADATA's
        assertEquals(listOf(2, 3, 4), rows.map { it.colorArgb }) // colours ADATA does not use
    }

    @Test
    fun packRatingsMatchTheirCells() {
        val byName = added().associateBy { it.name }
        // PACK.md: about 167 Wh, rated in the 3.7 V convention.
        val pack3s = byName.getValue(SourcePresets.DIY_3S8P.name)
        assertEquals(45_000, pack3s.ratedMah)
        assertEquals(167.0, pack3s.ratedWh!!, 1.0)
        // 10 x NCR18650B at 3,350 mAh and 3.6 V.
        val pack10p = byName.getValue(SourcePresets.DIY_1S10P.name)
        assertEquals(SourceKind.POWER_BANK, pack10p.kind)
        assertEquals(33_500, pack10p.ratedMah)
        assertEquals(120.6, pack10p.ratedWh!!, 0.01)
    }

    @Test
    fun theChargerIsRatedInWattsNotMah() {
        val anker = added().single { it.kind == SourceKind.CHARGER }
        assertEquals(30.0, anker.ratedW!!, 0.0)
        assertNull(anker.ratedMah)
        assertNull(anker.ratedWh)
    }

    @Test
    fun skipsAPresetWhoseNameIsAlreadyTakenIgnoringCase() {
        val handMade = adata.copy(id = 5, name = SourcePresets.DIY_3S8P.name.uppercase(), archived = true)
        val names = added(listOf(adata, handMade)).map { it.name }
        assertEquals(listOf(SourcePresets.DIY_1S10P.name, SourcePresets.ANKER_ZOLO_30W.name), names)
    }

    @Test
    fun anInstallThatHadTheFirstPresetOnlyGetsTheNewOnes() {
        val v1 = PowerSource(id = 6, name = SourcePresets.DIY_3S8P.name, kind = SourceKind.POWER_BANK, ratedMah = 45_000, colorArgb = 2)
        val rows = added(listOf(charger, adata, v1))
        assertEquals(2, rows.size)
        assertEquals(listOf(3, 4), rows.map { it.colorArgb })
    }

    @Test
    fun fallsBackToTheFirstColourWhenThePaletteIsExhausted() {
        val full = palette.mapIndexed { i, c ->
            PowerSource(id = 10L + i, name = "Bank $i", kind = SourceKind.POWER_BANK, colorArgb = c)
        }
        assertTrue(added(full).all { it.colorArgb == palette.first() })
    }
}

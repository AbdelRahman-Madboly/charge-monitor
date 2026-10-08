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
    fun addsTheBankAndTheChargerAlongsideAnExistingBankWithoutTouchingIt() {
        val rows = added()
        assertEquals(listOf(SourcePresets.PB1_V1_SN0001.name, SourcePresets.ANKER_ZOLO_30W.name), rows.map { it.name })
        assertTrue(rows.all { it.id == 0L && !it.builtIn }) // new rows, never an update of ADATA's
        assertEquals(listOf(2, 3), rows.map { it.colorArgb }) // colours ADATA does not use
    }

    @Test
    fun pb1IsRatedAsItsLabel() {
        // 10 x NCR18650B, labelled 34,000 mAh at 3.6 V.
        val pb1 = added().single { it.name == SourcePresets.PB1_V1_SN0001.name }
        assertEquals(SourceKind.POWER_BANK, pb1.kind)
        assertEquals(34_000, pb1.ratedMah)
        assertEquals(122.4, pb1.ratedWh!!, 0.01)
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
        val handMade = adata.copy(id = 5, name = SourcePresets.PB1_V1_SN0001.name.uppercase(), archived = true)
        val names = added(listOf(adata, handMade)).map { it.name }
        assertEquals(listOf(SourcePresets.ANKER_ZOLO_30W.name), names)
    }

    @Test
    fun fallsBackToTheFirstColourWhenThePaletteIsExhausted() {
        val full = palette.mapIndexed { i, c ->
            PowerSource(id = 10L + i, name = "Bank $i", kind = SourceKind.POWER_BANK, colorArgb = c)
        }
        assertTrue(added(full).all { it.colorArgb == palette.first() })
    }
}

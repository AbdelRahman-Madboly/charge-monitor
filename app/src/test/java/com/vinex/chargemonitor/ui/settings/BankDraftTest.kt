package com.vinex.chargemonitor.ui.settings

import com.vinex.chargemonitor.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankDraftTest {
    private val base = BankDraft(name = "Anker", ratedMah = "20000", nominalV = "3.7", colorArgb = 0)

    @Test
    fun validDraft() {
        assertTrue(base.isValid)
        assertEquals(20000, base.mahValue)
    }

    @Test
    fun ratedCapacityIsOptionalButBounded() {
        assertTrue(base.copy(ratedMah = "").isValid)
        assertFalse(base.copy(ratedMah = "50").isValid)
    }

    @Test
    fun blankNameAndBadVoltageAreRejected() {
        assertFalse(base.copy(name = "  ").isValid)
        assertFalse(base.copy(nominalV = "abc").isValid)
        assertTrue(base.copy(nominalV = "3,85").isValid)
        assertEquals(3.85, base.copy(nominalV = "3,85").voltValue!!, 1e-9)
    }

    @Test
    fun durationFormat() {
        assertEquals("40s", Format.duration(40_000))
        assertEquals("12m", Format.duration(12 * 60_000L))
        assertEquals("1h 05m", Format.duration(65 * 60_000L))
        assertEquals("--", Format.duration(null))
        assertEquals("1:02:03", Format.elapsed(3_723_000))
    }
}

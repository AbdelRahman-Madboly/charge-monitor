package com.vinex.chargemonitor.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class CellOcvTest {
    @Test
    fun tablePoints() {
        val points = listOf(
            4.20 to 1.00, 4.10 to 0.91, 4.00 to 0.81, 3.90 to 0.70, 3.80 to 0.58,
            3.70 to 0.45, 3.60 to 0.31, 3.50 to 0.18, 3.40 to 0.10, 3.30 to 0.05,
            3.20 to 0.025, 3.10 to 0.01, 3.00 to 0.00,
        )
        points.forEach { (v, expected) -> assertEquals(expected, CellOcv.energyFraction(v), 1e-9) }
    }

    @Test
    fun interpolatesMidpoint() {
        assertEquals(0.755, CellOcv.energyFraction(3.95), 1e-9)
    }

    @Test
    fun clampsOutsideUsableRange() {
        assertEquals(0.0, CellOcv.energyFraction(2.0), 0.0)
        assertEquals(1.0, CellOcv.energyFraction(4.5), 0.0)
        assertEquals(0.0, CellOcv.energyFraction(Double.NaN), 0.0)
    }
}

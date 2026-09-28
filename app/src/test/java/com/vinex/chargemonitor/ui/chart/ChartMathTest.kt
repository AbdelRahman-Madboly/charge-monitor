package com.vinex.chargemonitor.ui.chart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {

    @Test
    fun niceStepSnapsToOneTwoFive() {
        assertEquals(1.0, ChartMath.niceStep(0.8), 1e-9)
        assertEquals(2.0, ChartMath.niceStep(1.3), 1e-9)
        assertEquals(2.5, ChartMath.niceStep(2.2), 1e-9)
        assertEquals(5.0, ChartMath.niceStep(4.1), 1e-9)
        assertEquals(10.0, ChartMath.niceStep(7.0), 1e-9)
        assertEquals(0.05, ChartMath.niceStep(0.04), 1e-9)
    }

    @Test
    fun niceScaleCoversDataWithRoundTicks() {
        val s = ChartMath.niceScale(3.2, 17.9, target = 4)
        assertTrue(s.min <= 3.2 && s.max >= 17.9)
        assertEquals(0.0, s.min, 1e-9)
        assertEquals(20.0, s.max, 1e-9)
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0), s.ticks)
    }

    @Test
    fun niceScaleIncludesZeroWhenAsked() {
        val s = ChartMath.niceScale(3.9, 4.3, includeZero = true)
        assertEquals(0.0, s.min, 1e-9)
        assertTrue(s.max >= 4.3)
    }

    @Test
    fun flatSeriesGetsASpan() {
        val s = ChartMath.niceScale(4.0, 4.0)
        assertTrue(s.max > s.min)
        assertTrue(s.min <= 4.0 && s.max >= 4.0)
    }

    @Test
    fun emptyOrInfiniteInputStillProducesAScale() {
        val s = ChartMath.niceScale(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        assertTrue(s.max > s.min)
        assertTrue(s.ticks.isNotEmpty())
    }

    @Test
    fun downsampleKeepsSmallSeriesIntact() {
        val xs = LongArray(10) { it.toLong() }
        val ys = FloatArray(10) { it.toFloat() }
        assertArrayEquals(IntArray(10) { it }, ChartMath.downsample(xs, ys, 100))
    }

    @Test
    fun downsamplePreservesSpikeAndEnds() {
        val n = 10_000
        val xs = LongArray(n) { it * 1000L }
        val ys = FloatArray(n) { if (it == 4321) 99f else 1f }
        val idx = ChartMath.downsample(xs, ys, 100)
        assertTrue(idx.size < n / 10)
        assertTrue(4321 in idx)
        assertEquals(0, idx.first())
        assertEquals(n - 1, idx.last())
        // Ascending, so the path never doubles back.
        assertTrue(idx.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun nearestIndexPicksClosest() {
        val xs = longArrayOf(0, 10, 20, 30)
        assertEquals(0, ChartMath.nearestIndex(xs, -5))
        assertEquals(1, ChartMath.nearestIndex(xs, 12))
        assertEquals(2, ChartMath.nearestIndex(xs, 17))
        assertEquals(3, ChartMath.nearestIndex(xs, 99))
        assertEquals(-1, ChartMath.nearestIndex(LongArray(0), 5))
    }
}

package com.vinex.chargemonitor.ui.live

import com.vinex.chargemonitor.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSeriesTest {

    private fun sample(t: Long, mV: Int = 4000, mA: Int = 2000) =
        Sample(sessionId = 1, tMs = t, mV = mV, mA = mA, level10 = 500, temp10 = 300, screenOn = false)

    @Test
    fun mergeDropsPersistedPointsInsideTheBuffer() {
        val persisted = listOf(sample(0), sample(10_000), sample(20_000), sample(30_000))
        val buffer = listOf(LivePoint(18_000, 9f, 2f), LivePoint(20_000, 9f, 2f))
        val merged = LiveSeries.merge(persisted, buffer)
        assertEquals(listOf(0L, 10_000L, 18_000L, 20_000L), merged.map { it.tMs })
        assertEquals(8f, merged.first().watts, 1e-4f)
        assertEquals(2f, merged.first().amps, 1e-4f)
    }

    @Test
    fun mergeWithEmptyBufferUsesAllPersisted() {
        assertEquals(2, LiveSeries.merge(listOf(sample(0), sample(1)), emptyList()).size)
    }

    @Test
    fun fastChargeNeedsSustainedTenWatts() {
        val fast = (0..6).map { LivePoint(it * 5_000L, 12f, 3f) }
        assertTrue(LiveSeries.isFastCharging(fast))
        assertFalse(LiveSeries.isFastCharging(fast.take(1)))
        val slow = (0..6).map { LivePoint(it * 5_000L, 7.5f, 1.8f) }
        assertFalse(LiveSeries.isFastCharging(slow))
    }

    @Test
    fun fastChargeLooksOnlyAtTheRecentWindow() {
        // Fast earlier, slow for the last 40 s: no longer fast.
        val pts = (0..10).map { LivePoint(it * 5_000L, 15f, 3f) } + (11..19).map { LivePoint(it * 5_000L, 5f, 1f) }
        assertFalse(LiveSeries.isFastCharging(pts))
    }

    @Test
    fun recentCurrentsAreInMilliampsWithinWindow() {
        val pts = (0..60).map { LivePoint(it * 5_000L, 5f, 1.5f) }
        val currents = LiveSeries.recentCurrentsMa(pts)
        assertEquals(25, currents.size) // 120 s window at 5 s spacing, inclusive
        assertEquals(1500.0, currents.last(), 1e-6)
    }
}

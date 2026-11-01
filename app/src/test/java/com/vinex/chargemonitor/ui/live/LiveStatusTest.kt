package com.vinex.chargemonitor.ui.live

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStatusTest {

    private fun snap(mA: Double, plug: PlugType = PlugType.USB, status: ChargeStatus = ChargeStatus.CHARGING) =
        BatterySnapshot.EMPTY.copy(tMs = 1_000L, voltageMv = 4000, currentMa = mA, levelPct = 50f, plug = plug, status = status)

    @Test
    fun headlineCoversEveryState() {
        assertEquals(Headline.WAITING, LiveStatus.headline(null, false))
        assertEquals(Headline.WAITING, LiveStatus.headline(BatterySnapshot.EMPTY, false))
        assertEquals(Headline.ON_BATTERY, LiveStatus.headline(snap(-500.0, PlugType.NONE, ChargeStatus.DISCHARGING), false))
        assertEquals(Headline.CHARGING, LiveStatus.headline(snap(2000.0), true))
        assertEquals(Headline.FULL, LiveStatus.headline(snap(0.0, status = ChargeStatus.FULL), false))
        assertEquals(Headline.PLUGGED_IDLE, LiveStatus.headline(snap(0.0, status = ChargeStatus.NOT_CHARGING), false))
        assertEquals(Headline.PLUGGED_DRAINING, LiveStatus.headline(snap(-300.0, status = ChargeStatus.NOT_CHARGING), false))
    }

    @Test
    fun noiseLevelPowerIsNotCharging() {
        // 4 V x 5 mA = 0.02 W: below the noise floor even if the status says charging.
        assertEquals(Headline.PLUGGED_IDLE, LiveStatus.headline(snap(5.0), true))
    }

    @Test
    fun etasOnlyWhileChargingAndSkipPassedTarget() {
        assertTrue(LiveStatus.etas(Headline.PLUGGED_IDLE, 50f, 80, 1000L, 2000L).isEmpty())
        assertEquals(
            listOf("80 % in" to 1000L, "Full in" to 2000L),
            LiveStatus.etas(Headline.CHARGING, 50f, 80, 1000L, 2000L),
        )
        assertEquals(listOf("Full in" to 2000L), LiveStatus.etas(Headline.CHARGING, 85f, 80, 1000L, 2000L))
        assertTrue(LiveStatus.etas(Headline.CHARGING, 50f, 80, null, null).isEmpty())
    }

    @Test
    fun chartPointsClampNegativesToZero() {
        val pts = listOf(LivePoint(0, 5f, 1f), LivePoint(1, -2f, -0.5f))
        assertTrue(LiveStatus.hasNegative(pts))
        val out = LiveStatus.chartPoints(pts)
        assertEquals(pts[0], out[0])
        assertEquals(LivePoint(1, 0f, 0f), out[1])
        assertFalse(LiveStatus.hasNegative(out))
    }

    @Test
    fun sourceDetailShowsRating() {
        val bank = PowerSource(name = "Anker", kind = SourceKind.POWER_BANK, ratedMah = 10000, colorArgb = 0)
        assertEquals("Power bank · 10,000 mAh", LiveStatus.sourceDetail(bank))
        val charger = PowerSource(name = "Wall", kind = SourceKind.CHARGER, ratedW = 25.0, colorArgb = 0)
        assertEquals("Charger · 25.0 W rated", LiveStatus.sourceDetail(charger))
        val laptop = PowerSource(name = "Laptop", kind = SourceKind.LAPTOP, colorArgb = 0, builtIn = true)
        assertEquals("Laptop USB", LiveStatus.sourceDetail(laptop))
    }
}

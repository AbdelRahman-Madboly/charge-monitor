package com.vinex.chargemonitor.mock

import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockBatterySourceTest {
    @Test
    fun rampsFrom15To80ThenUnplugs() {
        var now = 1_000_000L
        val mock = MockBatterySource(clock = { now })
        assertEquals(15f, mock.read().levelPct, 0.01f)
        now += 59_999
        val nearEnd = mock.read()
        assertEquals(80f, nearEnd.levelPct, 0.1f)
        assertEquals(PlugType.AC, nearEnd.plug)
        now += 1
        assertEquals(PlugType.NONE, mock.read().plug)
        assertTrue(mock.isMock)
    }

    @Test
    fun voltageAndCurrentBehaveLikeARealCharge() {
        var now = 0L
        val mock = MockBatterySource(clock = { now })
        val readings = (0L until 60_000L step 1_000L).map { now = it; mock.read() }
        val charging = readings.filter { it.status == ChargeStatus.CHARGING }

        assertTrue(readings.all { it.voltageMv in 3700..4200 })
        assertEquals(3700, readings.first().voltageMv)
        assertEquals(4200, charging.last().voltageMv)
        // Tapers: last charging current well below the constant-current phase.
        assertTrue(charging.last().currentMa < charging.first().currentMa / 2)
        assertTrue(readings.any { it.screenOn })
        assertEquals(ChargeStatus.FULL, readings.last().status)
        assertTrue(readings.all { it.currentMa > 0 })
    }
}

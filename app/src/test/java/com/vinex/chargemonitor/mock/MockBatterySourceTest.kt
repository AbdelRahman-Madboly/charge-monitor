package com.vinex.chargemonitor.mock

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
}

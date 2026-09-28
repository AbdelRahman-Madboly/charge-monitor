package com.vinex.chargemonitor.session

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAccumulatorTest {

    private fun snap(
        tMs: Long,
        ma: Double = 1000.0,
        mv: Int = 4000,
        level: Float = 50f,
        plug: PlugType = PlugType.AC,
        status: ChargeStatus = ChargeStatus.CHARGING,
        screenOn: Boolean = false,
        tempC: Float = 30f,
        counterUah: Long? = null,
    ) = BatterySnapshot(
        tMs = tMs, voltageMv = mv, currentUaRaw = (ma * 1000).toLong(), currentMa = ma,
        levelPct = level, chargeCounterUah = counterUah, tempC = tempC, plug = plug,
        status = status, screenOn = screenOn,
    )

    private fun foldAll(vararg snaps: BatterySnapshot) =
        snaps.fold(SessionAccumulator.EMPTY) { acc, s -> acc.fold(s) }

    @Test
    fun linearRampIsIntegratedExactly() {
        // I(t) = 500 -> 1500 mA over one hour: exactly 1000 mAh.
        // V(t) = 3600 -> 4200 mV, so P = V*I is quadratic: integral computed analytically below.
        val hourMs = 3_600_000L
        var acc = SessionAccumulator.EMPTY
        // 6 s steps keep the millivolt values integral, so only the integration error is measured.
        for (t in 0..hourMs step 6_000L) {
            val x = t.toDouble() / hourMs
            acc = acc.fold(snap(t, ma = 500 + 1000 * x, mv = Math.round(3600 + 600 * x).toInt()))
        }
        assertEquals(1000.0, acc.totals.mah, 1e-9)
        // ∫0..1 (3.6 + 0.6x)(0.5 + x) dx = 1.8 + 3.6/2 + 0.3/2 + 0.6/3 = 3.95 Wh
        assertEquals(3.95, acc.totals.wh, 3.95 * 1e-4)
        assertEquals(3.95, acc.totals.avgW, 3.95 * 1e-4)
        assertEquals(3600 * 1000, acc.integratedMs.toInt())
    }

    @Test
    fun curvedCurrentIsIntegratedAccurately() {
        // A CC-CV-like decay: I(t) = 3000 e^(-t/τ), τ = 40 min, over 2 h.
        val tau = 40 * 60_000.0
        val endMs = 2 * 3_600_000L
        var acc = SessionAccumulator.EMPTY
        for (t in 0..endMs step 10_000L) acc = acc.fold(snap(t, ma = 3000 * Math.exp(-t / tau)))
        val analyticMah = 3000 * tau / 3_600_000.0 * (1 - Math.exp(-endMs / tau))
        assertEquals(analyticMah, acc.totals.mah, analyticMah * 1e-5)
    }

    @Test
    fun firstFoldSetsStartAndConstantPowerAverages() {
        val acc = foldAll(
            snap(1_000, level = 20f, tempC = 31f),
            snap(11_000, level = 20.4f, tempC = 36f),
            snap(21_000, level = 20.8f, tempC = 33f),
        )
        val t = acc.totals
        assertEquals(1_000L, t.startMs)
        assertEquals(21_000L, t.lastMs)
        assertEquals(20f, t.startLevel, 0f)
        assertEquals(20.8f, t.endLevel, 0f)
        assertEquals(0.8f, t.levelAdded, 1e-5f)
        assertEquals(3, t.sampleCount)
        assertEquals(36f, t.maxTempC!!, 0f)
        assertEquals(4.0, t.peakW, 1e-9)
        assertEquals(4.0, t.avgW, 1e-9)
        assertEquals(1000.0 * 20_000 / 3_600_000, t.mah, 1e-9)
    }

    @Test
    fun peakTracksHighestPowerAndNegativeCurrentSubtracts() {
        val acc = foldAll(
            snap(0, ma = 2000.0),
            snap(10_000, ma = 5000.0, mv = 4200),
            snap(20_000, ma = -500.0),
            snap(30_000, ma = -500.0),
        )
        assertEquals(21.0, acc.totals.peakW, 1e-9)
        val expectedMah = ((2000 + 5000) / 2.0 + (5000 - 500) / 2.0 - 500) * 10_000 / 3_600_000
        assertEquals(expectedMah, acc.totals.mah, 1e-9)
    }

    @Test
    fun reachedFullLatches() {
        val acc = foldAll(
            snap(0),
            snap(10_000, status = ChargeStatus.FULL),
            snap(20_000, status = ChargeStatus.CHARGING),
        )
        assertTrue(acc.totals.reachedFull)
        assertFalse(foldAll(snap(0), snap(10_000)).totals.reachedFull)
    }

    @Test
    fun screenFractionIsTimeWeightedFromTheEarlierSnapshot() {
        // Five 60 s intervals; the screen is on at the start of one of them: 20 %, not "used".
        val light = foldAll(
            snap(0, screenOn = true), snap(60_000), snap(120_000), snap(180_000), snap(240_000),
            snap(300_000, screenOn = true), // attributed to nothing: it is the last point
        )
        assertEquals(0.2f, light.totals.screenOnFraction, 1e-6f)
        assertFalse(light.totals.usedWhileCharging)

        val heavy = foldAll(
            snap(0, screenOn = true), snap(60_000, screenOn = true), snap(120_000),
            snap(180_000), snap(240_000), snap(300_000),
        )
        assertEquals(0.4f, heavy.totals.screenOnFraction, 1e-6f)
        assertTrue(heavy.totals.usedWhileCharging)
    }

    @Test
    fun reconnectGraceIsMeasuredFromTheLastPluggedReading() {
        val beforeUnplug = foldAll(snap(0), snap(10_000), snap(100_000))
        val acc = beforeUnplug.fold(snap(105_000, ma = -300.0, plug = PlugType.NONE, status = ChargeStatus.DISCHARGING))

        assertTrue(acc.isWithinReconnectGrace(130_000))
        assertTrue(acc.isWithinReconnectGrace(100_000 + SessionAccumulator.RECONNECT_GRACE_MS))
        assertFalse(acc.isWithinReconnectGrace(100_001 + SessionAccumulator.RECONNECT_GRACE_MS))
        assertFalse(acc.isWithinReconnectGrace(90_000)) // before the last reading: clock went back
        assertFalse(SessionAccumulator.EMPTY.isWithinReconnectGrace(0))

        // The unplugged reading changes nothing but the reference point.
        assertEquals(beforeUnplug.totals, acc.totals)

        // A replug 15 s later continues; the disconnected interval is not integrated.
        val replugged = acc.fold(snap(120_000)).fold(snap(130_000))
        val expected = beforeUnplug.mah + 1000.0 * 10_000 / 3_600_000
        assertEquals(expected, replugged.totals.mah, 1e-9)
        assertEquals(5, replugged.totals.sampleCount)
        assertEquals(130_000L, replugged.totals.lastMs)
    }

    @Test
    fun unpluggedReadingsBeforeTheStartAreIgnored() {
        val acc = SessionAccumulator.EMPTY.fold(snap(0, plug = PlugType.NONE))
        assertTrue(acc.isEmpty)
        assertEquals(SessionAccumulator.EMPTY, acc)
    }

    @Test
    fun shortGapIsIntegratedLongGapUsesTheCounter() {
        // 2 min stall: still a straight line.
        val short = foldAll(snap(0), snap(120_000))
        assertEquals(1000.0 * 120_000 / 3_600_000, short.totals.mah, 1e-9)

        // 20 min stall with a counter: 2500 mAh -> 2800 mAh is 300 mAh, whatever the endpoints say.
        val long = foldAll(
            snap(0, ma = 3000.0, mv = 3900, counterUah = 2_500_000),
            snap(20 * 60_000L, ma = 100.0, mv = 4100, counterUah = 2_800_000),
        )
        assertEquals(300.0, long.totals.mah, 1e-9)
        assertEquals(300.0 * 4.0 / 1000, long.totals.wh, 1e-9)
        assertEquals(20 * 60_000L, long.integratedMs)
        assertEquals(0L, long.screenTrackedMs)
        assertEquals(300.0, long.totals.counterMah!!, 1e-9)
    }

    @Test
    fun longGapWithoutCounterIsSkipped() {
        val acc = foldAll(snap(0), snap(10_000), snap(10_000 + 10 * 60_000L), snap(20_000 + 10 * 60_000L))
        assertEquals(1000.0 * 20_000 / 3_600_000, acc.totals.mah, 1e-9)
        assertEquals(20_000L, acc.integratedMs)
        assertEquals(4, acc.totals.sampleCount)
        assertNull(acc.totals.counterMah)
    }

    @Test
    fun clockGoingBackwardsIsNotIntegrated() {
        val acc = foldAll(snap(100_000), snap(50_000), snap(60_000))
        assertEquals(1000.0 * 10_000 / 3_600_000, acc.totals.mah, 1e-9)
    }

    @Test
    fun counterDeltaCoversTheWholeSession() {
        val acc = foldAll(
            snap(0, counterUah = 1_000_000),
            snap(10_000, counterUah = null), // a missed property read must not lose the end value
            snap(20_000, counterUah = 1_010_000),
        )
        assertEquals(10.0, acc.totals.counterMah!!, 1e-9)
    }

    @Test
    fun foldNeverMutatesTheReceiver() {
        val a = foldAll(snap(0))
        val copy = a.copy()
        a.fold(snap(10_000))
        assertEquals(copy, a)
    }

    @Test
    fun accumulatorRebuiltFromAStoredSessionStaysConsistent() {
        // What a restart does: only totals and the screen fraction survive, not the raw times.
        val resumed = SessionAccumulator(
            startMs = 0, last = snap(600_000), mah = 100.0, integratedMs = 600_000,
            screenOnMs = 150_000, startLevel = 20f, endLevel = 30f, sampleCount = 60,
        )
        assertTrue(resumed.isWithinReconnectGrace(610_000))
        val next = resumed.fold(snap(660_000))
        assertEquals(150_000f / 660_000, next.totals.screenOnFraction, 1e-6f)
        assertEquals(100.0 + 1000.0 * 60_000 / 3_600_000, next.totals.mah, 1e-9)
    }

    @Test
    fun integerStartLevelIsRefinedFromTheFirstDecimalReading() {
        // 4000 mAh battery: 1 % = 40 000 µAh. Plugged at a true 41.6 %, reported as 41.
        val acc = foldAll(
            snap(0, level = 41f, counterUah = 1_664_000),
            snap(10_000, level = 41f, counterUah = 1_670_000),
            snap(20_000, level = 42.2f, counterUah = 1_688_000),
        )
        assertEquals(41.6f, acc.totals.startLevel, 0.02f)
        assertEquals(0.6f, acc.totals.levelAdded, 0.02f)
    }

    @Test
    fun refinedStartNeverContradictsTheReportedInteger() {
        // A counter that says the start was 40.2 while the system said 41: clamp to 41.
        val acc = foldAll(
            snap(0, level = 41f, counterUah = 1_608_000),
            snap(10_000, level = 42.2f, counterUah = 1_688_000),
        )
        assertEquals(41f, acc.totals.startLevel, 0.001f)
    }

    @Test
    fun decimalStartOrMissingCounterIsLeftAlone() {
        val decimal = foldAll(snap(0, level = 41.3f, counterUah = 1_652_000), snap(10_000, level = 42.2f, counterUah = 1_688_000))
        assertEquals(41.3f, decimal.totals.startLevel, 0.001f)
        val noCounter = foldAll(snap(0, level = 41f), snap(10_000, level = 42.2f))
        assertEquals(41f, noCounter.totals.startLevel, 0.001f)
    }
}

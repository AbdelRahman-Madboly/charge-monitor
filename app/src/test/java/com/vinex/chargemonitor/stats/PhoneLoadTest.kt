package com.vinex.chargemonitor.stats

import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneLoadTest {
    private val step = 10_000L

    private fun sample(i: Int, mA: Int, screenOn: Boolean, level10: Int = 500, tMs: Long = i * step) =
        Sample(sessionId = 1, tMs = tMs, mV = 4000, mA = mA, level10 = level10, temp10 = 300, screenOn = screenOn)

    /** [off] screen-off samples then [on] screen-on samples, 10 s apart. */
    private fun samples(off: Int, offMa: Int, on: Int, onMa: Int, level10: Int = 500): List<Sample> =
        List(off) { sample(it, offMa, false, level10) } + List(on) { sample(off + it, onMa, true, level10) }

    private fun session(samples: List<Sample>, mahIn: Double = 1000.0, counter: Double? = null) = ChargeSession(
        id = 1,
        sourceId = 1,
        plug = "AC",
        startMs = 0,
        endMs = (samples.maxOfOrNull { it.tMs } ?: 0L) + step,
        startLevel = 20f,
        endLevel = 80f,
        mahIn = mahIn,
        counterMahIn = counter,
    )

    @Test
    fun typicalSessionMeasuresTheDropAndIntegratesScreenOnTime() {
        // Real shape: 1500 mA screen off, 741 mA screen on -> 759 mA of phone load.
        val s = samples(off = 20, offMa = 1500, on = 360, onMa = 741)
        val load = PhoneLoad.estimateSessionLoad(s)!!
        assertEquals(759.0, load, 1e-9)
        // 360 samples x 10 s = 1 h of screen on (the last one runs to the session end).
        assertEquals(759.0, PhoneLoad.phoneUseMah(session(s), s, load), 1e-6)

        val use = PhoneLoad.sessionUse(session(s, mahIn = 900.0, counter = 1000.0), PhoneLoad.profile(session(s), s), null)
        assertEquals(PhoneLoad.Method.MEASURED, use.method)
        assertEquals(1000.0, use.intoBatteryMah, 1e-9)
        assertEquals(1759.0, use.deliveredMah, 1e-6)
    }

    @Test
    fun medianShrugsOffSpikes() {
        val s = samples(off = 7, offMa = 1500, on = 7, onMa = 700) + sample(14, -3000, true)
        assertEquals(800.0, PhoneLoad.estimateSessionLoad(s)!!, 1e-9)
    }

    @Test
    fun allScreenOnSessionUsesTypicalFallback() {
        val s = samples(off = 0, offMa = 0, on = 360, onMa = 900)
        assertNull(PhoneLoad.estimateSessionLoad(s))
        val profile = PhoneLoad.profile(session(s), s)
        val typical = PhoneLoad.typicalLoad(listOf(700.0, 760.0, 2000.0))
        assertEquals(760.0, typical!!, 1e-9)

        val use = PhoneLoad.sessionUse(session(s), profile, typical)
        assertEquals(PhoneLoad.Method.TYPICAL, use.method)
        assertEquals(760.0, use.phoneUseMah, 1e-6)
    }

    @Test
    fun noDataGivesNull() {
        assertNull(PhoneLoad.estimateSessionLoad(emptyList()))
        assertNull(PhoneLoad.typicalLoad(emptyList()))
        // Too few of one state.
        assertNull(PhoneLoad.estimateSessionLoad(samples(off = 5, offMa = 1500, on = 50, onMa = 700)))

        val s = samples(off = 0, offMa = 0, on = 10, onMa = 900)
        val use = PhoneLoad.sessionUse(session(s), PhoneLoad.profile(session(s), s), null)
        assertEquals(PhoneLoad.Method.UNKNOWN, use.method)
        assertEquals(0.0, use.phoneUseMah, 0.0)
        assertEquals(1000.0, use.deliveredMah, 1e-9)
    }

    @Test
    fun screenNeverOnAddsNothing() {
        val s = samples(off = 30, offMa = 1500, on = 0, onMa = 0)
        val use = PhoneLoad.sessionUse(session(s), PhoneLoad.profile(session(s), s), 800.0)
        assertEquals(PhoneLoad.Method.NOT_USED, use.method)
        assertEquals(0.0, use.phoneUseMah, 0.0)
    }

    @Test
    fun taperSamplesAreIgnored() {
        // Screen off only above 80 %, where the charge current tapers: no baseline.
        val tapered = samples(off = 20, offMa = 300, on = 0, onMa = 0, level10 = 900) +
            List(20) { sample(20 + it, 1000, true, level10 = 500) }
        assertNull(PhoneLoad.estimateSessionLoad(tapered))

        // Tapered screen-on samples would drag the median down; they must not count.
        val mixed = samples(off = 10, offMa = 1500, on = 10, onMa = 1000) +
            List(30) { sample(20 + it, 100, true, level10 = 950) }
        assertEquals(500.0, PhoneLoad.estimateSessionLoad(mixed)!!, 1e-9)
    }

    @Test
    fun negativeLoadIsClampedToZeroAndHugeLoadToMax() {
        // Screen on charged faster (e.g. the source stepped up): no negative use.
        assertEquals(0.0, PhoneLoad.estimateSessionLoad(samples(off = 10, offMa = 1000, on = 10, onMa = 1400))!!, 0.0)
        assertEquals(
            PhoneLoad.MAX_LOAD_MA,
            PhoneLoad.estimateSessionLoad(samples(off = 10, offMa = 2000, on = 10, onMa = -4000))!!,
            0.0,
        )
    }

    @Test
    fun longGapsAreCapped() {
        // Two screen-on samples an hour apart count as 2 min, not an hour.
        val s = listOf(sample(0, 700, true, tMs = 0), sample(1, 700, false, tMs = 3_600_000))
        val sess = session(s).copy(endMs = 3_600_000)
        assertEquals(PhoneLoad.MAX_GAP_MS, PhoneLoad.screenOnMs(sess, s))
        assertEquals(1000.0 * 2 / 60, PhoneLoad.phoneUseMah(sess, s, 1000.0), 1e-9)
    }

    @Test
    fun intoBatteryPrefersCounterAndNeverGoesNegative() {
        val s = emptyList<Sample>()
        assertEquals(1200.0, PhoneLoad.intoBatteryMah(session(s, mahIn = 900.0, counter = 1200.0)), 0.0)
        assertEquals(900.0, PhoneLoad.intoBatteryMah(session(s, mahIn = 900.0)), 0.0)
        assertEquals(0.0, PhoneLoad.intoBatteryMah(session(s, mahIn = 50.0, counter = -20.0)), 0.0)
    }

    @Test
    fun deliverySumsSessionsAndCountsMethods() {
        val measuredSamples = samples(off = 20, offMa = 1500, on = 360, onMa = 700)
        val a = session(measuredSamples, mahIn = 1000.0).copy(id = 1)
        val allOn = samples(off = 0, offMa = 0, on = 180, onMa = 900)
        val b = session(allOn, mahIn = 500.0).copy(id = 2)
        val c = session(emptyList(), mahIn = 300.0).copy(id = 3) // samples not loaded
        val profiles = mapOf(1L to PhoneLoad.profile(a, measuredSamples), 2L to PhoneLoad.profile(b, allOn))
        val typical = PhoneLoad.typicalLoad(listOfNotNull(profiles[1L]!!.measuredLoadMa))

        val d = PhoneLoad.delivery(listOf(a, b, c), profiles, typical)
        assertEquals(1800.0, d.intoBatteryMah, 1e-9)
        // 800 mA x 1 h measured + 800 mA x 0.5 h typical.
        assertEquals(1200.0, d.phoneUseMah, 1e-6)
        assertEquals(3000.0, d.deliveredMah, 1e-6)
        assertEquals(1, d.measuredSessions)
        assertEquals(1, d.typicalSessions)
        assertEquals(800.0, d.typicalLoadMa!!, 1e-9)
    }
}

package com.vinex.chargemonitor.battery

import com.vinex.chargemonitor.battery.CurrentNormalizer.Scale
import com.vinex.chargemonitor.battery.CurrentNormalizer.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentNormalizerTest {

    private val charging = ChargeStatus.CHARGING
    private val discharging = ChargeStatus.DISCHARGING

    @Test
    fun standardMicroampDeviceIsLeftAlone() {
        val n = CurrentNormalizer()
        assertEquals(1500.0, n.normalize(1_500_000, charging, PlugType.AC), 1e-9)
        assertEquals(-420.0, n.normalize(-420_000, discharging, PlugType.NONE), 1e-9)
        assertEquals(Scale.MICRO, n.verdict.scale)
        assertFalse(n.verdict.invertSign)
        assertEquals(1, n.verdict.evidence)
        assertEquals(2, n.verdict.signEvidence)
    }

    @Test
    fun samsungMilliampDeviceIsDetectedOnFirstChargingReading() {
        val n = CurrentNormalizer()
        assertEquals(1500.0, n.normalize(1500, charging, PlugType.AC), 1e-9)
        assertEquals(Scale.MILLI, n.verdict.scale)
        // Trickle near full and a charge in progress keep the verdict.
        assertEquals(35.0, n.normalize(35, charging, PlugType.AC), 1e-9)
        assertEquals(2800.0, n.normalize(2800, charging, PlugType.AC), 1e-9)
        assertEquals(Scale.MILLI, n.verdict.scale)
        // Unplugged it keeps reporting mA, negative for discharge.
        assertEquals(-310.0, n.normalize(-310, discharging, PlugType.NONE), 1e-9)
    }

    @Test
    fun negativeWhileChargingDeviceHasItsSignInverted() {
        val n = CurrentNormalizer()
        assertEquals(1200.0, n.normalize(-1_200_000, charging, PlugType.AC), 1e-9)
        assertTrue(n.verdict.invertSign)
        // Such a device reports discharge as positive; normalised it must be negative.
        assertEquals(-300.0, n.normalize(300_000, discharging, PlugType.NONE), 1e-9)
        assertTrue(n.verdict.invertSign)
        assertEquals(2, n.verdict.signEvidence)
    }

    @Test
    fun invertedMilliampDevice() {
        val n = CurrentNormalizer()
        assertEquals(2000.0, n.normalize(-2000, charging, PlugType.AC), 1e-9)
        assertEquals(Scale.MILLI, n.verdict.scale)
        assertTrue(n.verdict.invertSign)
    }

    @Test
    fun invertedSignIsAlsoLearnedFromDischargeAlone() {
        val n = CurrentNormalizer()
        n.normalize(250_000, discharging, PlugType.NONE)
        assertTrue(n.verdict.invertSign)
        assertEquals(Scale.MICRO, n.verdict.scale) // scale is only learned while charging
    }

    @Test
    fun establishedVerdictSurvivesAFewOddReadings() {
        val n = CurrentNormalizer()
        repeat(20) { n.normalize(1_800_000, charging, PlugType.AC) }
        assertEquals(20, n.verdict.evidence)
        // Heavy use while plugged: 5 mA net in µA looks like "5000 mA" and nets negative briefly.
        repeat(5) { n.normalize(5_000, charging, PlugType.AC) }
        repeat(5) { n.normalize(-60_000, charging, PlugType.AC) }
        assertEquals(Scale.MICRO, n.verdict.scale)
        assertFalse(n.verdict.invertSign)
        // The negative readings were still clearly µA, so scale evidence recovered; sign paid.
        assertEquals(20, n.verdict.evidence)
        assertEquals(15, n.verdict.signEvidence)
    }

    @Test
    fun ambiguousAndNoiseReadingsTeachNothing() {
        val n = CurrentNormalizer()
        n.normalize(19_000, charging, PlugType.AC) // between the mA and µA bands, under the sign floor
        n.normalize(40, charging, PlugType.AC) // too small for either
        n.normalize(-15_000, discharging, PlugType.NONE) // 15 mA: under the sign floor for µA
        n.normalize(900_000, ChargeStatus.FULL, PlugType.AC) // FULL is not CHARGING
        n.normalize(0, charging, PlugType.AC)
        assertEquals(Verdict.DEFAULT, n.verdict)
    }

    @Test
    fun overrideFixesScaleButSignIsStillLearned() {
        val milli = CurrentNormalizer(override = CurrentUnit.MILLI)
        assertEquals(1500.0, milli.normalize(-1500, charging, PlugType.AC), 1e-9)

        val micro = CurrentNormalizer(override = CurrentUnit.MICRO)
        assertEquals(1.5, micro.normalize(1500, charging, PlugType.AC), 1e-9)
        // Learning continues underneath so switching back to AUTO is instant.
        micro.override = CurrentUnit.AUTO
        assertEquals(1500.0, micro.normalize(1500, charging, PlugType.AC), 1e-9)
    }

    @Test
    fun restoredVerdictAppliesImmediately() {
        val restored = Verdict.decode(Verdict(Scale.MILLI, invertSign = true, evidence = 40, signEvidence = 12).encode())
        val n = CurrentNormalizer(initial = restored)
        // Unplugged idle reading: no scale learning happens, the saved verdict is what counts.
        assertEquals(-180.0, n.normalize(180, discharging, PlugType.NONE), 1e-9)
    }

    @Test
    fun sinkIsCalledOnFlipsAndRoundNumbersOnly() {
        val saved = mutableListOf<Verdict>()
        val n = CurrentNormalizer(sink = { saved += it })
        n.normalize(1500, charging, PlugType.AC) // scale flips to MILLI, sign confirmed
        assertEquals(1, saved.size)
        assertEquals(Scale.MILLI, saved.last().scale)
        repeat(8) { n.normalize(1500, charging, PlugType.AC) } // evidence 2..9: nothing
        assertEquals(1, saved.size)
        n.normalize(1500, charging, PlugType.AC) // evidence 10
        assertEquals(2, saved.size)
        assertEquals(10, saved.last().evidence)
    }

    @Test
    fun throwingSinkDoesNotBreakNormalisation() {
        val n = CurrentNormalizer(sink = { error("disk full") })
        assertEquals(1500.0, n.normalize(1500, charging, PlugType.AC), 1e-9)
    }

    @Test
    fun extremeInputsStayFinite() {
        val n = CurrentNormalizer()
        for (raw in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L)) {
            assertTrue(n.normalize(raw, ChargeStatus.UNKNOWN, PlugType.NONE).isFinite())
            assertTrue(n.normalize(raw, charging, PlugType.USB).isFinite())
        }
    }

    @Test
    fun verdictEncodingRoundTripsAndRejectsGarbage() {
        val v = Verdict(Scale.MILLI, invertSign = true, evidence = 7, signEvidence = 3)
        assertEquals(v, Verdict.decode(v.encode()))
        assertEquals(Verdict(Scale.MICRO, false, 5, 0), Verdict.decode("MICRO,false,5"))
        assertNull(Verdict.decode(null))
        assertNull(Verdict.decode(""))
        assertNull(Verdict.decode("NANO,false,1"))
        assertNull(Verdict.decode("MICRO,maybe,1"))
        assertNull(Verdict.decode("MICRO,false,-1"))
        assertNull(Verdict.decode("MICRO,false,1,2,3"))
    }
}

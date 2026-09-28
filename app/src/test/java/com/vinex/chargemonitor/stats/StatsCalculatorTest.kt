package com.vinex.chargemonitor.stats

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BuiltInSources
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class StatsCalculatorTest {

    private val bank = PowerSource(id = 10, name = "Anker 20k", kind = SourceKind.POWER_BANK, ratedMah = 20_000, colorArgb = 0)
    private val laptop = PowerSource(
        id = BuiltInSources.LAPTOP_ID, name = "Laptop", kind = SourceKind.LAPTOP, colorArgb = 0, builtIn = true,
    )

    private var nextStart = 0L
    private fun session(
        from: Float,
        to: Float?,
        sourceId: Long = bank.id,
        whIn: Double = 0.0,
        mahIn: Double = 0.0,
        used: Boolean = false,
        mock: Boolean = false,
        open: Boolean = false,
        startMs: Long = nextStart++ * 3_600_000L,
    ) = ChargeSession(
        sourceId = sourceId, plug = "AC", startMs = startMs, endMs = if (open) null else startMs + 1,
        startLevel = from, endLevel = to, mahIn = mahIn, whIn = whIn, usedWhileCharging = used, mock = mock,
    )

    // ---- full-charge equivalents ----

    @Test
    fun partialSessionsSumToFullCharges() {
        // The user's example: 15 -> 80 and 5 -> 35 is 65 + 30 = 95 points = 0.95 full charges.
        val sessions = listOf(session(15f, 80f), session(5f, 35f))
        assertEquals(0.95, StatsCalculator.fullChargeEquivalents(sessions), 1e-6)
    }

    @Test
    fun fullChargesIncludeUsedWhileChargingButNotOpenOrMockOrDraining() {
        val sessions = listOf(
            session(20f, 100f),
            session(30f, 50f, used = true),
            session(10f, null, open = true),
            session(0f, 100f, mock = true),
            session(60f, 55f), // drained while plugged: adds nothing, subtracts nothing
        )
        assertEquals(1.0, StatsCalculator.fullChargeEquivalents(sessions), 1e-6)
    }

    @Test
    fun laptopSessionsDoNotMixIntoABanksFullCharges() {
        val all = listOf(session(15f, 80f), session(10f, 90f, sourceId = laptop.id), session(5f, 35f))
        assertEquals(0.95, StatsCalculator.fullChargeEquivalents(all.filter { it.sourceId == bank.id }), 1e-6)
    }

    // ---- bank capacity ----

    private val bankSessions = listOf(
        session(15f, 80f, whIn = 10.0),
        session(5f, 35f, whIn = 12.0),
        session(20f, 40f, whIn = 5.0, used = true),
        session(0f, 100f, sourceId = BuiltInSources.CHARGER_ID, whIn = 20.0),
        session(0f, 100f, sourceId = laptop.id, whIn = 9.0),
        session(0f, 100f, whIn = 99.0, mock = true),
        session(0f, 50f, whIn = 99.0, open = true),
    )

    @Test
    fun capacityUsesSpecFormulaAndExcludesUsedWhileChargingByDefault() {
        val est = StatsCalculator.bankCapacityEstimate(bankSessions, bank, lossFactor = 0.85, includeUsedWhileCharging = false)
        val expected = 22.0 / 0.85 / 3.7 * 1000
        assertEquals(expected, est.estimatedMah!!, 1e-6)
        assertEquals(22.0, est.whIn, 1e-9)
        assertEquals(2, est.sessionsUsed)
        assertEquals(20_000, est.ratedMah)
        assertEquals(expected / 20_000, est.ratioOfRated!!, 1e-9)
    }

    @Test
    fun capacityCanIncludeUsedWhileCharging() {
        val est = StatsCalculator.bankCapacityEstimate(bankSessions, bank, 0.85, includeUsedWhileCharging = true)
        assertEquals(27.0 / 0.85 / 3.7 * 1000, est.estimatedMah!!, 1e-6)
        assertEquals(3, est.sessionsUsed)
    }

    @Test
    fun capacityHonoursNominalVoltage() {
        val bank5v = bank.copy(nominalV = 5.0)
        val est = StatsCalculator.bankCapacityEstimate(bankSessions, bank5v, 1.0, false)
        assertEquals(22.0 / 5.0 * 1000, est.estimatedMah!!, 1e-6)
    }

    @Test
    fun laptopWirelessAndChargerNeverGetABankEstimate() {
        val wireless = laptop.copy(id = BuiltInSources.WIRELESS_ID, kind = SourceKind.WIRELESS)
        val charger = laptop.copy(id = BuiltInSources.CHARGER_ID, kind = SourceKind.CHARGER)
        for (source in listOf(laptop, wireless, charger)) {
            val est = StatsCalculator.bankCapacityEstimate(bankSessions, source, 0.85, true)
            assertNull(est.estimatedMah)
            assertEquals(0, est.sessionsUsed)
            assertEquals(0.0, est.whIn, 0.0)
        }
    }

    @Test
    fun noQualifyingSessionsGivesAnEmptyEstimate() {
        val est = StatsCalculator.bankCapacityEstimate(emptyList(), bank.copy(ratedMah = null), 0.85, false)
        assertNull(est.estimatedMah)
        assertNull(est.ratedMah)
        assertNull(est.ratioOfRated)
    }

    @Test
    fun insaneLossFactorIsClamped() {
        val est = StatsCalculator.bankCapacityEstimate(bankSessions, bank, lossFactor = 0.0, includeUsedWhileCharging = false)
        assertEquals(22.0 / 3.7 * 1000, est.estimatedMah!!, 1e-6)
        val high = StatsCalculator.bankCapacityEstimate(bankSessions, bank, lossFactor = 1.4, includeUsedWhileCharging = false)
        assertEquals(22.0 / 3.7 * 1000, high.estimatedMah!!, 1e-6)
    }

    // ---- battery health ----

    @Test
    fun healthIsTheMedianOfQualifyingSessions() {
        val sessions = listOf(
            session(20f, 70f, mahIn = 2000.0), // 4000
            session(30f, 70f, mahIn = 1500.0), // 3750
            session(10f, 70f, mahIn = 2500.0), // 4166.7
            session(50f, 70f, mahIn = 1000.0), // delta 20: too small
            session(10f, 90f, mahIn = 1000.0, used = true), // used while charging
            session(10f, 90f, mahIn = 9000.0, mock = true),
            session(10f, 90f, mahIn = 3200.0, sourceId = laptop.id), // any source counts: 4000
        )
        val h = StatsCalculator.batteryHealthEstimate(sessions, designMah = 5000)
        assertEquals(4, h.qualifyingSessions)
        assertEquals(4000.0, h.estimatedMah!!, 1e-6)
        assertEquals(80.0, h.healthPct!!, 1e-6)
    }

    @Test
    fun healthUsesOnlyTheMostRecentSessions() {
        // Ten recent sessions at 4000 mAh and five old ones at 5000: the old ones are ignored.
        val old = (0 until 5).map { session(0f, 50f, mahIn = 2500.0, startMs = it.toLong()) }
        val recent = (0 until 10).map { session(0f, 50f, mahIn = 2000.0, startMs = 1_000L + it) }
        val h = StatsCalculator.batteryHealthEstimate(old + recent, designMah = null)
        assertEquals(4000.0, h.estimatedMah!!, 1e-6)
        assertEquals(15, h.qualifyingSessions)
        assertNull(h.healthPct)
        assertNull(h.designMah)
    }

    @Test
    fun healthWithNoQualifyingSessionsIsEmpty() {
        val h = StatsCalculator.batteryHealthEstimate(listOf(session(50f, 60f, mahIn = 400.0)), 4500)
        assertNull(h.estimatedMah)
        assertEquals(4500, h.designMah)
        assertEquals(0, h.qualifyingSessions)
    }

    // ---- time to level ----

    private fun snap(level: Float, ma: Double = 2000.0, status: ChargeStatus = ChargeStatus.CHARGING, counterUah: Long? = null) =
        BatterySnapshot(
            tMs = 0, voltageMv = 4000, currentUaRaw = 0, currentMa = ma, levelPct = level,
            chargeCounterUah = counterUah, tempC = 30f, plug = PlugType.AC, status = status, screenOn = false,
        )

    @Test
    fun belowTaperTimeIsChargeOverCurrent() {
        // 30 % of 4000 mAh = 1200 mAh at 2000 mA = 0.6 h.
        val ms = StatsCalculator.timeToLevel(snap(50f), 80f, listOf(2000.0), 4000)!!
        assertEquals(2_160_000.0, ms.toDouble(), 1_000.0)
    }

    @Test
    fun taperAbove80MatchesTheAnalyticIntegral() {
        // I(p) = I80 (1 - 0.85 (p-80)/20): t = C/100/I80 × 20/0.85 × ln(1/0.15) hours.
        val analyticH = 4000 / 100.0 / 2000.0 * 20 / 0.85 * ln(1 / 0.15)
        val ms = StatsCalculator.timeToLevel(snap(80f), 100f, listOf(2000.0), 4000)!!
        assertEquals(analyticH * 3_600_000, ms.toDouble(), analyticH * 3_600_000 * 1e-3)
    }

    @Test
    fun currentMeasuredInsideTheTaperIsScaledBack() {
        // At 90 % the charger gives 57.5 % of its pre-taper 2000 mA; prediction must match that model.
        val at90 = 2000.0 * StatsCalculator.taperFactor(90.0)
        assertEquals(1150.0, at90, 1e-9)
        val fromSnapshot = StatsCalculator.timeToLevel(snap(90f), 100f, listOf(at90), 4000)!!
        // Analytic from 90: C/100/I80 × 20/0.85 × ln(0.575/0.15).
        val analyticH = 4000 / 100.0 / 2000.0 * 20 / 0.85 * ln(0.575 / 0.15)
        assertEquals(analyticH * 3_600_000, fromSnapshot.toDouble(), analyticH * 3_600_000 * 1e-3)
    }

    @Test
    fun medianSmoothingIgnoresASpike() {
        val ms = StatsCalculator.timeToLevel(snap(50f, ma = 9000.0), 80f, listOf(2000.0, 2000.0, 9000.0, 1990.0, 2010.0), 4000)!!
        assertEquals(2_160_000.0, ms.toDouble(), 1_000.0)
    }

    @Test
    fun capacityFallsBackToTheChargeCounter() {
        // 2000 mAh left in at 50 % -> 4000 mAh battery.
        val ms = StatsCalculator.timeToLevel(snap(50f, counterUah = 2_000_000), 80f, emptyList())!!
        assertEquals(2_160_000.0, ms.toDouble(), 1_000.0)
    }

    @Test
    fun unpredictableCasesAreNull() {
        assertNull(StatsCalculator.timeToLevel(snap(50f, status = ChargeStatus.DISCHARGING), 80f, listOf(2000.0), 4000))
        assertNull(StatsCalculator.timeToLevel(snap(80f), 80f, listOf(2000.0), 4000))
        assertNull(StatsCalculator.timeToLevel(snap(85f), 80f, listOf(2000.0), 4000))
        assertNull(StatsCalculator.timeToLevel(snap(50f), 80f, listOf(0.0), 4000))
        assertNull(StatsCalculator.timeToLevel(snap(50f), 80f, listOf(-300.0), 4000))
        assertNull(StatsCalculator.timeToLevel(snap(50f), 80f, listOf(2000.0), batteryCapacityMah = null))
        // 0.1 mA would take years: not a prediction.
        assertNull(StatsCalculator.timeToLevel(snap(50f), 80f, listOf(0.1), 4000))
    }
}

package com.vinex.chargemonitor.stats

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
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
        confirmed: Boolean = true,
        startMs: Long = nextStart++ * 3_600_000L,
    ) = ChargeSession(
        sourceId = sourceId, plug = "AC", startMs = startMs, endMs = if (open) null else startMs + 1,
        startLevel = from, endLevel = to, mahIn = mahIn, whIn = whIn, usedWhileCharging = used, mock = mock,
        sourceConfirmed = confirmed,
        // Cell energy as the model would record it at a flat 85 %, so the figures stay easy to check.
        cellsWh = whIn / 0.85, portWh = whIn / 0.85 * 0.92,
    )

    private fun mark(tMs: Long, kind: BankEventKind, sourceId: Long = bank.id) =
        BankEvent(sourceId = sourceId, tMs = tMs, kind = kind)

    private val hour = 3_600_000L

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

    @Test
    fun unconfirmedSessionsDoNotCountTowardABankWhenConfirmationIsRequired() {
        // The overnight wall charge whose prompt nobody answered, provisionally on the bank.
        val sessions = listOf(session(15f, 80f), session(10f, 100f, confirmed = false))
        assertEquals(0.65, StatsCalculator.fullChargeEquivalents(sessions, requireConfirmed = true), 1e-6)
        assertEquals(1.55, StatsCalculator.fullChargeEquivalents(sessions), 1e-6)
    }

    // ---- bank capacity ----

    private fun perWh(loss: Double = 0.85, volts: Double = 3.7) = 1.0 / loss / volts * 1000

    @Test
    fun withoutMarksThereIsNoCapacityOnlyADeliveredTotal() {
        val sessions = listOf(
            session(15f, 80f, whIn = 10.0),
            session(5f, 35f, whIn = 12.0),
            session(20f, 40f, whIn = 5.0, used = true),
            session(0f, 100f, sourceId = BuiltInSources.CHARGER_ID, whIn = 20.0),
            session(0f, 100f, whIn = 99.0, mock = true),
            session(0f, 50f, whIn = 99.0, open = true),
            session(0f, 50f, whIn = 40.0, confirmed = false),
        )
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank)
        assertNull(est.cycleOutputMah)
        assertNull(est.outputRatio)
        assertNull(est.moduleEfficiency)
        assertEquals(0, est.completedCycles)
        // Delivered energy is a total, so used-while-charging counts; guesses, mock and open do not.
        assertEquals(27.0, est.deliveredWh, 1e-9)
        assertEquals(27.0 * perWh(), est.deliveredMah, 1e-6)
        assertEquals(27.0 * perWh() / 20_000, est.deliveredBankFulls!!, 1e-9)
        assertEquals(3, est.sessionsUsed)
    }

    @Test
    fun deliveredTotalGrowsButCycleOutputDoesNot() {
        // A second drain must not read as 190 % of the label.
        val events = listOf(
            mark(0, BankEventKind.RECHARGED),
            mark(10 * hour, BankEventKind.EMPTY),
            mark(20 * hour, BankEventKind.RECHARGED),
            mark(30 * hour, BankEventKind.EMPTY),
        )
        val sessions = listOf(
            session(10f, 60f, whIn = 30.0, startMs = 1 * hour),
            session(20f, 70f, whIn = 29.0, startMs = 5 * hour),
            session(10f, 60f, whIn = 31.0, startMs = 21 * hour),
            session(20f, 70f, whIn = 30.0, startMs = 25 * hour),
        )
        val rated = bank.copy(outputMah = 10_000)
        val est = StatsCalculator.bankCapacityEstimate(sessions, rated, events)
        assertEquals(2, est.completedCycles)
        val port = 60.0 / 0.85 * 0.92 // median of 59 and 61 Wh into the battery, back to the port
        assertEquals(port / 5.0 * 1000, est.cycleOutputMah!!, 1e-6)
        assertEquals(port / 5.0 * 1000 / 10_000, est.outputRatio!!, 1e-9)
        assertEquals(120.0, est.deliveredWh, 1e-9)
        assertNull(est.sinceRechargeWh) // last mark is EMPTY
    }

    @Test
    fun moduleEfficiencyIsPortOverTheCellsFullEnergy() {
        // 20,000 mAh at 3.7 V is 74 Wh, 71.04 Wh down to the cutoff. 50 Wh into the battery is
        // 54.12 Wh at the port, so the module gave 76 % of the cells' energy.
        val events = listOf(mark(0, BankEventKind.RECHARGED), mark(10 * hour, BankEventKind.EMPTY))
        val sessions = listOf(session(10f, 60f, whIn = 30.0, startMs = hour), session(10f, 40f, whIn = 20.0, startMs = 2 * hour))
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        val port = 50.0 / 0.85 * 0.92
        val cellWh = 74.0 * PowerSource.USABLE_TO_CUTOFF
        assertEquals(port / cellWh, est.moduleEfficiency!!, 1e-9)
        val drain = est.drains.single()
        assertEquals(cellWh - port, drain.moduleLossWh!!, 1e-9)
        // With the module measured, the cells behind every session are the port over it.
        assertEquals(cellWh, est.drawnWh, 1e-9)
        assertEquals(port / est.moduleEfficiency!!, StatsCalculator.energy(sessions, moduleEff = est.moduleEfficiency).cellsWh, 1e-9)
    }

    @Test
    fun theDatasheetCellCapacityStandsInForTheLabel() {
        val events = listOf(mark(0, BankEventKind.RECHARGED), mark(10 * hour, BankEventKind.EMPTY))
        val sessions = listOf(session(10f, 60f, whIn = 30.0, startMs = hour))
        val datasheet = bank.copy(cellsMah = 18_000)
        val est = StatsCalculator.bankCapacityEstimate(sessions, datasheet, events)
        assertEquals(30.0 / 0.85 * 0.92 / (18_000 * 3.7 / 1000.0 * PowerSource.USABLE_TO_CUTOFF), est.moduleEfficiency!!, 1e-9)
    }

    @Test
    fun partialDrainsCountAndVerifiedDrainsSetTheEfficiency() {
        val events = listOf(
            // A partial window: 3.95 V at the Recharged mark.
            BankEvent(sourceId = bank.id, tMs = 0, kind = BankEventKind.RECHARGED, cellV = 3.95),
            mark(10 * hour, BankEventKind.EMPTY),
            // No voltages.
            mark(20 * hour, BankEventKind.RECHARGED), mark(30 * hour, BankEventKind.EMPTY),
            // Verified both ends.
            BankEvent(sourceId = bank.id, tMs = 40 * hour, kind = BankEventKind.RECHARGED, cellV = 4.19),
            BankEvent(sourceId = bank.id, tMs = 50 * hour, kind = BankEventKind.EMPTY, cellV = 3.01),
        )
        val sessions = listOf(
            session(10f, 60f, whIn = 20.0, startMs = hour),
            session(10f, 60f, whIn = 40.0, startMs = 21 * hour),
            session(10f, 60f, whIn = 50.0, startMs = 41 * hour),
        )
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        assertEquals(3, est.drains.size)
        assertEquals(listOf(true, false, false), est.drains.map { it.partial })
        assertEquals(listOf(false, false, true), est.drains.map { it.verified })
        assertEquals(3, est.completedCycles)
        assertEquals(0, est.skippedCycles)
        assertEquals(1, est.verifiedCycles)
        assertEquals(50.0 / 0.85 * 0.92 / (74.0 * PowerSource.USABLE_TO_CUTOFF * 0.990), est.moduleEfficiency!!, 1e-9)
    }

    @Test
    fun aSessionRunningWhenRechargedWasTappedBelongsToTheDrain() {
        val events = listOf(mark(hour, BankEventKind.RECHARGED), mark(10 * hour, BankEventKind.EMPTY))
        val started = ChargeSession(
            sourceId = bank.id, plug = "AC", startMs = hour - 20_000, endMs = 2 * hour, startLevel = 10f, endLevel = 40f,
            whIn = 12.0, sourceConfirmed = true, cellsWh = 12.0 / 0.85, portWh = 12.0 / 0.85 * 0.92,
        )
        val before = started.copy(startMs = 0, endMs = hour - 60_000)
        val est = StatsCalculator.bankCapacityEstimate(listOf(before, started), bank, events)
        assertEquals(1, est.drains.single().sessions)
        assertEquals(12.0, est.drains.single().batteryWh, 1e-9)
    }

    @Test
    fun phoneChargesPerDrainIsTheMedianOverCompletedCycles() {
        val events = listOf(
            mark(0, BankEventKind.RECHARGED), mark(10 * hour, BankEventKind.EMPTY),
            mark(20 * hour, BankEventKind.RECHARGED), mark(30 * hour, BankEventKind.EMPTY),
            mark(40 * hour, BankEventKind.RECHARGED), mark(50 * hour, BankEventKind.EMPTY),
            mark(60 * hour, BankEventKind.RECHARGED), mark(70 * hour, BankEventKind.EMPTY),
        )
        val sessions = listOf(
            session(10f, 60f, whIn = 10.0, startMs = hour), session(20f, 90f, whIn = 14.0, startMs = 5 * hour), // 1.2
            session(10f, 90f, whIn = 16.0, startMs = 21 * hour), // 0.8
            session(0f, 100f, whIn = 20.0, startMs = 41 * hour), session(50f, 100f, whIn = 10.0, startMs = 45 * hour), // 1.5
            session(0f, 100f, whIn = 20.0, startMs = 61 * hour, confirmed = false), // skipped: source guessed
        )
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        assertEquals(3, est.completedCycles)
        assertEquals(1, est.skippedCycles)
        assertEquals(1.2, est.cyclePhoneCharges!!, 1e-6)
    }

    @Test
    fun noCompletedCycleMeansNoPhoneChargesPerDrain() {
        val sessions = listOf(session(10f, 60f, whIn = 10.0))
        assertNull(StatsCalculator.bankCapacityEstimate(sessions, bank).cyclePhoneCharges)
    }

    @Test
    fun labelPhoneChargesFromRatedEnergyLossAndPhoneSize() {
        // 20,000 mAh at 3.7 V is 74 Wh; 85 % of it into a 5,000 mAh (19.35 Wh) phone.
        val expected = 74.0 * PowerSource.USABLE_TO_CUTOFF * 0.85 / (5_000 * StatsCalculator.PHONE_NOMINAL_V / 1000.0)
        assertEquals(expected, StatsCalculator.labelPhoneCharges(bank, 5_000, 0.85)!!, 1e-9)
        // A pack rated at its own 11.1 V compares on energy, not on mAh.
        val pack = bank.copy(ratedMah = 17_492, nominalV = 11.1)
        assertEquals(17_492 * 11.1 / 1000.0, pack.ratedWh!!, 1e-9)
    }

    @Test
    fun labelPhoneChargesNeedsARatingAPhoneSizeAndASaneLoss() {
        assertNull(StatsCalculator.labelPhoneCharges(bank.copy(ratedMah = null), 5_000, 0.85))
        assertNull(StatsCalculator.labelPhoneCharges(bank, null, 0.85))
        assertNull(StatsCalculator.labelPhoneCharges(bank, 0, 0.85))
        assertNull(StatsCalculator.labelPhoneCharges(bank, 5_000, 0.0))
        assertNull(StatsCalculator.labelPhoneCharges(bank, 5_000, Double.NaN))
        assertNull(StatsCalculator.labelPhoneCharges(bank.copy(nominalV = 0.0), 5_000, 0.85))
    }

    @Test
    fun partialDrainScalesOutputPhoneChargesAndBatteryToAFullBank() {
        val events = listOf(
            mark(0, BankEventKind.RECHARGED).copy(cellV = 3.90),
            mark(10 * hour, BankEventKind.EMPTY).copy(cellV = 3.00),
        )
        val est = StatsCalculator.bankCapacityEstimate(listOf(session(10f, 80f, whIn = 35.0, startMs = hour)), bank, events)
        val d = est.drains.single()
        assertEquals(0.70, d.window, 1e-9)
        assertEquals(true, d.partial)
        assertEquals(true, d.counted)
        assertEquals(bank.usableCellWh!! * 0.70, d.cellWh!!, 1e-9)
        assertEquals(d.portWh / d.cellWh!!, d.moduleEfficiency!!, 1e-9)
        assertEquals(d.outputMah / 0.70, est.cycleOutputMah!!, 1e-9)
        assertEquals(1.0, est.cyclePhoneCharges!!, 1e-9)
        assertEquals(50.0, est.cycleBatteryWh!!, 1e-9)
    }

    @Test
    fun tooShortDrainCountsForNothingIncludingCalibration() {
        val events = listOf(
            mark(0, BankEventKind.RECHARGED).copy(cellV = 3.50),
            mark(10 * hour, BankEventKind.EMPTY).copy(cellV = 3.00, meterWh = 12.0),
        )
        val est = StatsCalculator.bankCapacityEstimate(listOf(session(10f, 30f, whIn = 5.0, startMs = hour)), bank, events)
        assertEquals(false, est.drains.single().counted)
        assertEquals(0, est.completedCycles)
        assertEquals(1, est.skippedCycles)
        assertEquals(0, est.verifiedCycles)
        assertEquals(1.0, est.portScale, 0.0)
        assertNull(est.cycleOutputMah)
        assertNull(est.cyclePhoneCharges)
        assertNull(est.cycleBatteryWh)
        assertNull(est.moduleEfficiency)
    }

    @Test
    fun meterSetsPortEnergyAndCalibratesUnmeteredDrains() {
        val modelled = 40.0 / 0.85 * 0.92
        val events = listOf(
            mark(0, BankEventKind.RECHARGED).copy(cellV = 4.20),
            mark(10 * hour, BankEventKind.EMPTY).copy(cellV = 3.00, meterWh = modelled * 1.10),
            mark(20 * hour, BankEventKind.RECHARGED).copy(cellV = 3.90),
            mark(30 * hour, BankEventKind.EMPTY).copy(cellV = 3.00),
            mark(40 * hour, BankEventKind.RECHARGED),
            mark(50 * hour, BankEventKind.EMPTY),
        )
        val sessions = listOf(
            session(10f, 80f, whIn = 40.0, startMs = hour),
            session(10f, 80f, whIn = 30.0, startMs = 21 * hour),
            session(10f, 80f, whIn = 20.0, startMs = 41 * hour),
        )
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        assertEquals(1.10, est.portScale, 1e-9)
        assertEquals(modelled * 1.10, est.drains[0].portWh, 1e-9)
        assertEquals(30.0 / 0.85 * 0.92 * 1.10, est.drains[1].portWh, 1e-9)
        assertEquals(listOf(true, false, false), est.drains.map { it.metered })
        assertEquals(listOf(DrainGrade.A, DrainGrade.B, DrainGrade.C), est.drains.map { it.grade })
        assertEquals(2, est.verifiedCycles)
    }

    @Test
    fun calibrationUsesMedianAndClampsWithoutClampingMeterReadings() {
        val modelled = 30.0 / 0.85 * 0.92
        for ((factor, expected) in listOf(0.5 to 0.80, 2.0 to 1.25)) {
            val events = listOf(mark(0, BankEventKind.RECHARGED), mark(10 * hour, BankEventKind.EMPTY).copy(meterWh = modelled * factor))
            val est = StatsCalculator.bankCapacityEstimate(listOf(session(10f, 80f, whIn = 30.0, startMs = hour)), bank, events)
            assertEquals(expected, est.portScale, 1e-9)
            assertEquals(modelled * factor, est.drains.single().portWh, 1e-9)
            assertEquals(DrainGrade.C, est.drains.single().grade)
        }
        val events = (0..2).flatMap { i ->
            listOf(mark(i * 20 * hour, BankEventKind.RECHARGED),
                mark((i * 20 + 10) * hour, BankEventKind.EMPTY).copy(meterWh = modelled * listOf(0.9, 1.1, 1.5)[i]))
        }
        val sessions = (0..2).map { session(10f, 80f, whIn = 30.0, startMs = (it * 20 + 1) * hour) }
        assertEquals(1.1, StatsCalculator.bankCapacityEstimate(sessions, bank, events).portScale, 1e-9)
    }

    @Test
    fun reversedVoltageWindowIsNotCounted() {
        val events = listOf(mark(0, BankEventKind.RECHARGED).copy(cellV = 3.50), mark(10 * hour, BankEventKind.EMPTY).copy(cellV = 3.90))
        val est = StatsCalculator.bankCapacityEstimate(listOf(session(10f, 30f, whIn = 5.0, startMs = hour)), bank, events)
        assertEquals(false, est.drains.single().counted)
        assertNull(est.moduleEfficiency)
    }

    // ---- chargers ----

    private fun chargerSession(from: Float, to: Float, hours: Double, peakW: Double, used: Boolean = false, full: Boolean = false) =
        ChargeSession(
            sourceId = 12, plug = "AC", startMs = 0, endMs = (hours * hour).toLong(), startLevel = from, endLevel = to,
            peakW = peakW, usedWhileCharging = used, reachedFull = full, sourceConfirmed = true,
        )

    @Test
    fun chargerStatsTakePeakFromAllAndSpeedFromAdmittedSessions() {
        val sessions = listOf(
            chargerSession(20f, 80f, hours = 1.0, peakW = 21.5),
            chargerSession(50f, 100f, hours = 1.5, peakW = 18.0, full = true),
            chargerSession(10f, 30f, hours = 1.0, peakW = 23.0, used = true), // screen on: peak only
        )
        val stats = StatsCalculator.chargerStats(sessions, ratedW = 30.0, includeUsedWhileCharging = false)
        assertEquals(23.0, stats.peakW!!, 1e-9)
        assertEquals(23.0 / 30.0, stats.peakOfRated!!, 1e-9)
        assertEquals((60.0 + 50.0) / 2.5, stats.pctPerHour!!, 1e-9)
        assertEquals(1, stats.fullCount)

        val all = StatsCalculator.chargerStats(sessions, ratedW = 30.0, includeUsedWhileCharging = true)
        assertEquals((60.0 + 50.0 + 20.0) / 3.5, all.pctPerHour!!, 1e-9)
    }

    @Test
    fun chargerStatsWithoutDataOrRating() {
        val empty = StatsCalculator.chargerStats(emptyList(), ratedW = 30.0, includeUsedWhileCharging = false)
        assertNull(empty.peakW)
        assertNull(empty.peakOfRated)
        assertNull(empty.pctPerHour)
        val unrated = StatsCalculator.chargerStats(listOf(chargerSession(20f, 80f, 1.0, 20.0)), null, false)
        assertEquals(20.0, unrated.peakW!!, 1e-9)
        assertNull(unrated.peakOfRated)
    }

    @Test
    fun openCycleReportsEnergySinceRecharge() {
        val events = listOf(mark(0, BankEventKind.RECHARGED))
        val sessions = listOf(session(10f, 60f, whIn = 20.0, startMs = hour), session(10f, 30f, whIn = 8.0, startMs = 2 * hour))
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        assertNull(est.cycleOutputMah)
        assertEquals(28.0, est.sinceRechargeWh!!, 1e-9)
        assertEquals(28.0 * perWh(), est.sinceRechargeMah!!, 1e-6)
    }

    @Test
    fun aDrainCountsSessionsUsedWhileChargingButNotUnconfirmedOnes() {
        val events = listOf(
            mark(0, BankEventKind.RECHARGED), mark(10 * hour, BankEventKind.EMPTY),
            mark(20 * hour, BankEventKind.RECHARGED), mark(30 * hour, BankEventKind.EMPTY),
        )
        val sessions = listOf(
            session(10f, 60f, whIn = 30.0, startMs = hour, used = true),
            session(10f, 60f, whIn = 30.0, startMs = 21 * hour, confirmed = false),
        )
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        assertEquals(1, est.completedCycles)
        assertEquals(1, est.skippedCycles)
        assertEquals(30.0 / 0.85 * 0.92 / 5.0 * 1000, est.cycleOutputMah!!, 1e-6)
    }

    @Test
    fun emptyWithoutAPrecedingRechargeAndOtherBanksMarksAreIgnored() {
        val events = listOf(
            mark(0, BankEventKind.EMPTY),
            mark(hour / 2, BankEventKind.RECHARGED, sourceId = 99),
            mark(10 * hour, BankEventKind.EMPTY, sourceId = 99),
        )
        val sessions = listOf(session(10f, 60f, whIn = 30.0, startMs = hour))
        val est = StatsCalculator.bankCapacityEstimate(sessions, bank, events)
        assertEquals(0, est.completedCycles)
        assertEquals(0, est.skippedCycles)
    }

    @Test
    fun cellEnergyHonoursNominalVoltage() {
        val bank5v = bank.copy(nominalV = 5.0)
        assertEquals(20_000 * 5.0 / 1000 * PowerSource.USABLE_TO_CUTOFF, bank5v.usableCellWh!!, 1e-9)
        assertEquals(20_000 * 3.7 / 1000 * PowerSource.USABLE_TO_CUTOFF, bank.usableCellWh!!, 1e-9)
    }

    @Test
    fun aSessionWithoutModelledEnergyFallsBackToTheModelAtItsAveragePower() {
        // 10 Wh over one hour is 10 W into the battery: the model's own figure at 10 W.
        val legacy = ChargeSession(
            sourceId = bank.id, plug = "AC", startMs = 0, endMs = hour, startLevel = 10f, endLevel = 60f,
            whIn = 10.0, sourceConfirmed = true,
        )
        val expected = EnergyModel.flow(10.0, screenOn = false).cellsW
        assertEquals(expected, StatsCalculator.cellsWh(legacy), 1e-9)
        assertEquals(10.0 / expected, StatsCalculator.energy(legacy).efficiency!!, 1e-9)
    }

    @Test
    fun laptopWirelessAndChargerNeverGetABankEstimate() {
        val wireless = laptop.copy(id = BuiltInSources.WIRELESS_ID, kind = SourceKind.WIRELESS)
        val charger = laptop.copy(id = BuiltInSources.CHARGER_ID, kind = SourceKind.CHARGER)
        val sessions = listOf(session(0f, 100f, sourceId = laptop.id, whIn = 9.0))
        for (source in listOf(laptop, wireless, charger)) {
            val est = StatsCalculator.bankCapacityEstimate(sessions, source)
            assertNull(est.cycleOutputMah)
            assertEquals(0, est.sessionsUsed)
            assertEquals(0.0, est.deliveredWh, 0.0)
        }
    }

    @Test
    fun noSessionsGivesAnEmptyEstimate() {
        val est = StatsCalculator.bankCapacityEstimate(emptyList(), bank.copy(ratedMah = null))
        assertNull(est.cycleOutputMah)
        assertNull(est.ratedMah)
        assertNull(est.outputRatio)
        assertNull(est.deliveredBankFulls)
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

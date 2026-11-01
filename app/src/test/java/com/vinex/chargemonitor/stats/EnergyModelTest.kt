package com.vinex.chargemonitor.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyModelTest {

    @Test
    fun eachStageAddsItsLossAtThePowerItCarries() {
        val f = EnergyModel.flow(10.0, screenOn = false)
        assertEquals(0.30, f.phoneUseW, 1e-9)
        assertEquals(10.30, f.intoPhoneW, 1e-9)
        assertEquals(10.30 + EnergyModel.PHONE_CHARGER.at(10.30), f.portW, 1e-9)
        assertEquals(f.portW + EnergyModel.BANK_22W5.at(f.portW), f.cellsW, 1e-9)
        assertEquals(f.cellsW - 10.0, f.totalLossW, 1e-9)
        assertEquals(f.phoneLossW + f.bankLossW + f.phoneUseW, f.totalLossW, 1e-9)
    }

    @Test
    fun theBankModuleMatchesA22W5BoostModule() {
        fun eff(out: Double) = out / (out + EnergyModel.BANK_22W5.at(out))
        assertEquals(0.91, eff(5.0), 0.01)
        assertEquals(0.90, eff(10.0), 0.01)
        assertEquals(0.89, eff(18.0), 0.01)
        assertTrue(eff(0.4) < 0.80)
    }

    @Test
    fun efficiencyFallsAtTheTaperWhereOneFixedFactorOverstatesTheBank() {
        val fast = EnergyModel.efficiencyAt(15.0)
        val taper = EnergyModel.efficiencyAt(1.0)
        assertEquals(0.80, fast, 0.02)
        assertTrue("taper $taper", taper < 0.65)
    }

    @Test
    fun theScreenAddsItsDrawToWhatTheBankPaysFor() {
        val off = EnergyModel.flow(8.0, screenOn = false)
        val on = EnergyModel.flow(8.0, screenOn = true)
        assertEquals(EnergyModel.DEFAULT_PHONE_SCREEN_W, on.phoneUseW - off.phoneUseW, 1e-9)
        assertTrue(on.cellsW > off.cellsW + EnergyModel.DEFAULT_PHONE_SCREEN_W)
    }

    @Test
    fun aDrainingBatteryChargesOnlyWhatTheSourceCovered() {
        // Phone needs 1.6 W, the battery gives 1.0 W of it: the source gave 0.6 W.
        val partly = EnergyModel.flow(-1.0, screenOn = true)
        assertEquals(0.6, partly.intoPhoneW, 1e-9)
        assertEquals(0.6, partly.phoneUseW, 1e-9)
        // Battery covers everything: the bank is off and costs nothing.
        val off = EnergyModel.flow(-2.0, screenOn = true)
        assertEquals(0.0, off.cellsW, 0.0)
        assertNull(off.efficiency)
    }

    @Test
    fun aBridgedGapUsesTheAveragePower() {
        val f = EnergyModel.bridged(batteryWh = 5.0, hours = 0.5, screenOn = false)!!
        assertEquals(EnergyModel.flow(10.0, screenOn = false).cellsW * 0.5, f.cellsW, 1e-9)
        assertNull(EnergyModel.bridged(5.0, 0.0, screenOn = false))
    }

    @Test
    fun phoneDrawComesFromTheParameters() {
        val p = EnergyModel.Params(phoneIdleW = 0.5, phoneScreenW = 2.0)
        assertEquals(2.5, EnergyModel.flow(5.0, screenOn = true, p).phoneUseW, 1e-9)
    }
}

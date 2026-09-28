package com.vinex.chargemonitor.notify

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {

    private val config = AlertRules.Config(
        targetLevel = 80,
        tempThresholdC = 42f,
        targetEnabled = true,
        tempEnabled = true,
        stoppedEnabled = true,
    )

    private fun snap(level: Float, temp: Float = 30f, status: ChargeStatus = ChargeStatus.CHARGING) =
        BatterySnapshot.EMPTY.copy(levelPct = level, tempC = temp, status = status, plug = PlugType.AC)

    private fun run(vararg snaps: BatterySnapshot, cfg: AlertRules.Config = config): List<AlertKind> {
        var state = AlertRules.State.INITIAL
        val all = mutableListOf<AlertKind>()
        for (s in snaps) {
            val (next, alerts) = AlertRules.evaluate(state, s, cfg)
            state = next
            all += alerts
        }
        return all
    }

    @Test
    fun targetFiresOnceOnUpwardCrossing() {
        assertEquals(listOf(AlertKind.TARGET), run(snap(78f), snap(80f), snap(81f), snap(82f)))
    }

    @Test
    fun targetStaysQuietWhenSessionStartsAboveIt() {
        assertTrue(run(snap(85f), snap(86f)).isEmpty())
    }

    @Test
    fun temperatureFiresOnce() {
        assertEquals(listOf(AlertKind.TEMPERATURE), run(snap(50f, 41f), snap(50f, 42.5f), snap(50f, 43f)))
    }

    @Test
    fun fullFiresOnceAndSuppressesStopped() {
        val alerts = run(
            snap(99f, status = ChargeStatus.FULL),
            snap(99f, status = ChargeStatus.NOT_CHARGING),
            snap(99f, status = ChargeStatus.NOT_CHARGING),
        )
        assertEquals(listOf(AlertKind.FULL), alerts)
    }

    @Test
    fun stoppedNeedsTwoConsecutiveReadings() {
        assertTrue(run(snap(50f, status = ChargeStatus.NOT_CHARGING), snap(50f)).isEmpty())
        assertEquals(
            listOf(AlertKind.STOPPED),
            run(snap(50f, status = ChargeStatus.NOT_CHARGING), snap(50f, status = ChargeStatus.NOT_CHARGING)),
        )
    }

    @Test
    fun disabledTogglesSilenceAlerts() {
        val off = config.copy(targetEnabled = false, tempEnabled = false, stoppedEnabled = false)
        assertTrue(run(snap(79f), snap(81f, 50f, ChargeStatus.FULL), cfg = off).isEmpty())
    }
}

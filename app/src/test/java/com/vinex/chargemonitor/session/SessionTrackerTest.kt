package com.vinex.chargemonitor.session

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.ChargeStatus
import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BuiltInSources
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.FakeSharedPreferences
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Sample
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.data.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {

    private class FakeStore : SessionStore {
        val sessions = linkedMapOf<Long, ChargeSession>()
        val samples = mutableListOf<Sample>()
        val sources = mutableMapOf(
            BuiltInSources.CHARGER_ID to PowerSource(BuiltInSources.CHARGER_ID, "Charger", SourceKind.CHARGER, colorArgb = 0, builtIn = true),
            BuiltInSources.LAPTOP_ID to PowerSource(BuiltInSources.LAPTOP_ID, "Laptop", SourceKind.LAPTOP, colorArgb = 0, builtIn = true),
            BuiltInSources.WIRELESS_ID to PowerSource(BuiltInSources.WIRELESS_ID, "Wireless", SourceKind.WIRELESS, colorArgb = 0, builtIn = true),
            10L to PowerSource(10L, "Bank A", SourceKind.POWER_BANK, ratedMah = 10_000, colorArgb = 0),
            11L to PowerSource(11L, "Bank B", SourceKind.POWER_BANK, ratedMah = 20_000, colorArgb = 0),
        )
        private var nextId = 1L

        override suspend fun openSession() = sessions.values.filter { it.isOpen }.maxByOrNull { it.startMs }
        override suspend fun session(id: Long) = sessions[id]
        override suspend fun source(id: Long) = sources[id]
        override suspend fun startSession(session: ChargeSession): Long {
            val id = nextId++
            sessions[id] = session.copy(id = id)
            return id
        }
        override suspend fun checkpoint(session: ChargeSession, pending: List<Sample>) {
            sessions[session.id] = session
            samples += pending
        }
    }

    private val store = FakeStore()
    private val settings = SettingsStore(FakeSharedPreferences())

    private fun snap(
        tMs: Long,
        plug: PlugType = PlugType.AC,
        level: Float = 50f,
        status: ChargeStatus = if (plug == PlugType.NONE) ChargeStatus.DISCHARGING else ChargeStatus.CHARGING,
    ) = BatterySnapshot(
        tMs = tMs,
        voltageMv = 4000,
        currentUaRaw = 2_000_000,
        currentMa = if (plug == PlugType.NONE) -200.0 else 2000.0,
        levelPct = level,
        chargeCounterUah = (level * 45_000).toLong(),
        tempC = 30f,
        plug = plug,
        status = status,
        screenOn = false,
    )

    private val tenS = 10_000L

    @Test
    fun acPlugInOpensSessionWithPromptAndChargerFallback() = runTest {
        settings.rememberBank(11L)
        settings.rememberBank(10L)
        val tracker = SessionTracker(store, settings)
        val events = tracker.onSnapshot(snap(0), isMock = false, persistEveryMs = tenS)

        val opened = events.single() as SessionTracker.Event.Opened
        assertTrue(opened.decision.prompt)
        assertEquals(BuiltInSources.CHARGER_ID, opened.session.sourceId)
        assertEquals(listOf(10L, 11L, BuiltInSources.CHARGER_ID), opened.promptOptions.map { it.id })
        assertTrue(tracker.hasOpenSession)
    }

    @Test
    fun usbGoesToLaptopAndOffersLastBank() = runTest {
        settings.rememberBank(11L)
        val tracker = SessionTracker(store, settings)
        val opened = tracker.onSnapshot(snap(0, PlugType.USB), false, tenS).single() as SessionTracker.Event.Opened
        assertEquals(BuiltInSources.LAPTOP_ID, opened.session.sourceId)
        assertFalse(opened.decision.prompt)
        assertEquals(11L, opened.laptopBank?.id)
    }

    @Test
    fun samplesPersistOnCadenceAndCheckpointEveryMinute() = runTest {
        val tracker = SessionTracker(store, settings)
        // Live screen cadence: a read every 2 s for 70 s.
        for (t in 0L..70_000L step 2_000L) tracker.onSnapshot(snap(t, level = 20f + t / 10_000f), false, tenS)

        // One sample every ~10 s, flushed with the 60 s checkpoint; the later ones still buffered.
        val persistedTimes = store.samples.map { it.tMs }
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L), persistedTimes)
        val row = store.sessions.values.single()
        assertTrue(row.isOpen)
        assertEquals(60_000L, row.updatedMs)
        assertEquals(7, row.sampleCount)
        assertEquals(26f, row.endLevel!!, 0.01f)
    }

    @Test
    fun replugWithinGraceContinuesSession() = runTest {
        val tracker = SessionTracker(store, settings)
        tracker.onSnapshot(snap(0), false, tenS)
        tracker.onSnapshot(snap(10_000), false, tenS)
        val unplugged = tracker.onSnapshot(snap(12_000, PlugType.NONE), false, tenS)
        assertTrue(unplugged.single() is SessionTracker.Event.Unplugged)
        val replug = tracker.onSnapshot(snap(25_000), false, tenS)

        assertTrue(replug.single() is SessionTracker.Event.Reconnected)
        assertEquals(1, store.sessions.size)
        assertTrue(tracker.hasOpenSession)
    }

    @Test
    fun replugWithinGraceDoesNotCountUnpluggedGap() = runTest {
        val tracker = SessionTracker(store, settings)
        tracker.onSnapshot(snap(0), false, tenS)
        tracker.onSnapshot(snap(10_000), false, tenS)
        val beforeGap = tracker.totals!!.mah
        tracker.onSnapshot(snap(12_000, PlugType.NONE), false, tenS)
        tracker.onSnapshot(snap(40_000), false, tenS)

        // 10 s at 2 A before the blip; the 30 s spent unplugged adds nothing.
        assertEquals(2000.0 * 10 / 3600, beforeGap, 1e-9)
        assertEquals(beforeGap, tracker.totals!!.mah, 1e-9)
    }

    @Test
    fun unplugPastGraceClosesAtLastPluggedReading() = runTest {
        val tracker = SessionTracker(store, settings)
        tracker.onSnapshot(snap(0, level = 40f), false, tenS)
        tracker.onSnapshot(snap(10_000, level = 45f), false, tenS)
        tracker.onSnapshot(snap(20_000, PlugType.NONE, level = 45f), false, tenS)
        assertTrue(tracker.hasOpenSession)
        val events = tracker.onSnapshot(snap(41_000, PlugType.NONE, level = 45f), false, tenS)

        val closed = (events.single() as SessionTracker.Event.Closed).session
        assertEquals(10_000L, closed.endMs)
        assertEquals(45f, closed.endLevel!!, 0.01f)
        assertEquals(5f, closed.levelAdded, 0.01f)
        assertNotNull(closed.counterMahIn)
        assertFalse(tracker.hasOpenSession)
        assertNull(tracker.totals)
    }

    @Test
    fun replugWithDifferentPlugStartsNewSession() = runTest {
        val tracker = SessionTracker(store, settings)
        tracker.onSnapshot(snap(0), false, tenS)
        tracker.onSnapshot(snap(5_000, PlugType.NONE), false, tenS)
        val events = tracker.onSnapshot(snap(10_000, PlugType.USB), false, tenS)

        assertTrue(events[0] is SessionTracker.Event.Closed)
        assertTrue(events[1] is SessionTracker.Event.Opened)
        assertEquals(2, store.sessions.size)
    }

    @Test
    fun restartResumesRecentOpenSessionOnSamePlug() = runTest {
        SessionTracker(store, settings).apply {
            onSnapshot(snap(0, level = 30f), false, tenS)
            onSnapshot(snap(60_000, level = 35f), false, tenS) // checkpoint written
        }
        val restarted = SessionTracker(store, settings)
        val events = restarted.onSnapshot(snap(90_000, level = 36f), false, tenS)

        assertTrue(events.single() is SessionTracker.Event.Resumed)
        assertEquals(1, store.sessions.size)
        assertEquals(30f, restarted.totals!!.startLevel, 0.01f)
        assertEquals(36f, restarted.totals!!.endLevel, 0.01f)
    }

    @Test
    fun restartWhileUnpluggedClosesStaleSession() = runTest {
        SessionTracker(store, settings).apply {
            onSnapshot(snap(0, level = 30f), false, tenS)
            onSnapshot(snap(60_000, level = 35f), false, tenS)
        }
        val restarted = SessionTracker(store, settings)
        restarted.onSnapshot(snap(120_000, PlugType.NONE, level = 34f), false, tenS)

        val row = store.sessions.values.single()
        assertEquals(60_000L, row.endMs)
        assertEquals(35f, row.endLevel!!, 0.01f)
        assertFalse(restarted.hasOpenSession)
    }

    @Test
    fun switchingToMockClosesRealSession() = runTest {
        val tracker = SessionTracker(store, settings)
        tracker.onSnapshot(snap(0), false, tenS)
        val events = tracker.onSnapshot(snap(2_000), isMock = true, persistEveryMs = tenS)

        assertTrue(events[0] is SessionTracker.Event.Closed)
        val opened = events[1] as SessionTracker.Event.Opened
        assertTrue(opened.session.mock)
    }

    @Test
    fun appliedSourceSurvivesCheckpoint() = runTest {
        val tracker = SessionTracker(store, settings)
        tracker.onSnapshot(snap(0), false, tenS)
        val id = tracker.sessionId!!
        tracker.applySource(id, 10L)
        tracker.onSnapshot(snap(60_000), false, tenS)

        assertEquals(10L, store.sessions[id]!!.sourceId)
        assertEquals("Bank A", tracker.source?.name)
    }

    // ---- source confirmation ----

    @Test
    fun acSessionIsUnconfirmedUntilTheUserPicks() = runTest {
        settings.lastNonLaptopSourceId.set(10L)
        val tracker = SessionTracker(store, settings)
        val opened = tracker.onSnapshot(snap(0), false, tenS).single() as SessionTracker.Event.Opened
        // Provisionally the last bank, as the spec says, but only a guess.
        assertEquals(10L, opened.session.sourceId)
        assertFalse(opened.session.sourceConfirmed)
        tracker.onSnapshot(snap(60_000), false, tenS)
        assertFalse(store.sessions.values.single().sourceConfirmed)

        tracker.applySource(opened.session.id, 10L)
        assertTrue(store.sessions.values.single().sourceConfirmed)
        tracker.onSnapshot(snap(120_000), false, tenS)
        assertTrue(store.sessions.values.single().sourceConfirmed)
    }

    @Test
    fun usbAndWirelessAreConfirmedByThePlugType() = runTest {
        val tracker = SessionTracker(store, settings)
        val usb = tracker.onSnapshot(snap(0, PlugType.USB), false, tenS).single() as SessionTracker.Event.Opened
        assertTrue(usb.session.sourceConfirmed)
    }

    // ---- restart after process death ----

    private suspend fun chargeThenUnplug(unplugAt: Long) {
        SessionTracker(store, settings).apply {
            onSnapshot(snap(0, level = 30f), false, tenS)
            onSnapshot(snap(60_000, level = 31f), false, tenS)
            onSnapshot(snap(unplugAt, PlugType.NONE, level = 31f), false, tenS)
        }
    }

    @Test
    fun unplugIsStoredSoARestartDoesNotMergeTheNextCharge() = runTest {
        // Unplug from bank A, process dies inside the grace, bank B (also AC) plugged in 5 min later.
        chargeThenUnplug(unplugAt = 65_000)
        assertEquals(65_000L, store.sessions.values.single().unpluggedAtMs)

        val restarted = SessionTracker(store, settings)
        val events = restarted.onSnapshot(snap(365_000, level = 31f), false, tenS)

        assertTrue(events.single() is SessionTracker.Event.Opened)
        assertEquals(2, store.sessions.size)
        val old = store.sessions.getValue(1L)
        assertEquals(60_000L, old.endMs)
        assertNull(old.unpluggedAtMs)
    }

    @Test
    fun restartInsideTheGraceAfterAnUnplugReconnects() = runTest {
        chargeThenUnplug(unplugAt = 65_000)
        val restarted = SessionTracker(store, settings)
        val events = restarted.onSnapshot(snap(80_000, level = 31f), false, tenS)

        assertTrue(events[0] is SessionTracker.Event.Resumed)
        assertTrue(events[1] is SessionTracker.Event.Reconnected)
        assertEquals(1, store.sessions.size)
        assertNull(store.sessions.values.single().unpluggedAtMs)
    }

    @Test
    fun restartWhileStillUnpluggedKeepsTheGraceRunning() = runTest {
        chargeThenUnplug(unplugAt = 65_000)
        val restarted = SessionTracker(store, settings)
        assertTrue(restarted.onSnapshot(snap(75_000, PlugType.NONE, level = 31f), false, tenS).single() is SessionTracker.Event.Resumed)
        assertTrue(restarted.hasOpenSession)
        val closed = restarted.onSnapshot(snap(95_000, PlugType.NONE, level = 31f), false, tenS).single()
        assertEquals(60_000L, (closed as SessionTracker.Event.Closed).session.endMs)
    }

    @Test
    fun restartBridgesTheDeadIntervalWithTheChargeCounter() = runTest {
        SessionTracker(store, settings).apply {
            onSnapshot(snap(0, level = 30f), false, tenS)
            onSnapshot(snap(60_000, level = 31f), false, tenS)
        }
        val beforeMah = store.sessions.values.single().mahIn
        val restarted = SessionTracker(store, settings)
        restarted.onSnapshot(snap(300_000, level = 40f), false, tenS)

        // 9 % at 45 mAh per percent charged while the process was dead.
        val bridged = 9 * 45.0
        assertEquals(beforeMah + bridged, restarted.totals!!.mah, 1e-6)
        // The counter cross-check covers the whole session, dead interval included.
        assertEquals(bridged + 45.0, restarted.totals!!.counterMah!!, 1e-6)
        assertEquals(10f, restarted.totals!!.levelAdded, 0.01f)
    }

    @Test
    fun restartWithAFallenChargeCounterClosesTheOldSession() = runTest {
        SessionTracker(store, settings).apply {
            onSnapshot(snap(0, level = 30f), false, tenS)
            onSnapshot(snap(60_000, level = 31f), false, tenS)
        }
        val restarted = SessionTracker(store, settings)
        val events = restarted.onSnapshot(snap(300_000, level = 20f), false, tenS)

        assertTrue(events.single() is SessionTracker.Event.Opened)
        assertEquals(60_000L, store.sessions.getValue(1L).endMs)
    }
}

package com.vinex.chargemonitor.data

import com.vinex.chargemonitor.battery.CurrentUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun defaultsMatchSpec() {
        val s = SettingsStore(FakeSharedPreferences())
        assertEquals(0.85f, s.lossFactor.value)
        assertEquals(80, s.targetLevel.value)
        assertEquals(42f, s.tempThresholdC.value)
        assertEquals(10, s.sampleIntervalSec.value)
        assertEquals(CurrentUnit.AUTO, s.currentUnit.value)
        assertEquals(emptyList<Long>(), s.lastBankIds.value)
        assertNull(s.lastNonLaptopSourceId.value)
        assertFalse(s.onboardingDone.value)
    }

    @Test
    fun valuesSurviveANewInstance() {
        val prefs = FakeSharedPreferences()
        SettingsStore(prefs).apply {
            lossFactor.set(0.8f)
            currentUnit.set(CurrentUnit.MILLI)
            lastNonLaptopSourceId.set(7L)
            lastBankIds.set(listOf(5L, 4L))
        }
        val reloaded = SettingsStore(prefs)
        assertEquals(0.8f, reloaded.lossFactor.value)
        assertEquals(CurrentUnit.MILLI, reloaded.currentUnit.value)
        assertEquals(7L, reloaded.lastNonLaptopSourceId.value)
        assertEquals(listOf(5L, 4L), reloaded.lastBankIds.value)
    }

    @Test
    fun flowReflectsSet() {
        val s = SettingsStore(FakeSharedPreferences())
        s.targetLevel.set(90)
        assertEquals(90, s.targetLevel.flow.value)
    }

    @Test
    fun rememberBankMovesToFrontWithoutDuplicates() {
        val s = SettingsStore(FakeSharedPreferences())
        s.rememberBank(4)
        s.rememberBank(5)
        s.rememberBank(4)
        assertEquals(listOf(4L, 5L), s.lastBankIds.value)
    }
}

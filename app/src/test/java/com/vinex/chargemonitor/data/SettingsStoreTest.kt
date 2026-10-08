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
        assertEquals(0.30f, s.phoneIdleW.value)
        assertEquals(1.30f, s.phoneScreenW.value)
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
            phoneIdleW.set(0.4f)
            currentUnit.set(CurrentUnit.MILLI)
            lastNonLaptopSourceId.set(7L)
            lastBankIds.set(listOf(5L, 4L))
        }
        val reloaded = SettingsStore(prefs)
        assertEquals(0.4f, reloaded.phoneIdleW.value)
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

    @Test
    fun rememberSourceTeachesThePromptByKind() {
        val s = SettingsStore(FakeSharedPreferences())
        val bank = PowerSource(id = 4, name = "ADATA", kind = SourceKind.POWER_BANK, colorArgb = 0)
        val charger = PowerSource(id = 9, name = "Anker 30W", kind = SourceKind.CHARGER, colorArgb = 0, ratedW = 30.0)
        val laptop = PowerSource(id = BuiltInSources.LAPTOP_ID, name = "Laptop", kind = SourceKind.LAPTOP, colorArgb = 0, builtIn = true)

        s.rememberSource(bank)
        assertEquals(listOf(4L), s.lastBankIds.value)
        assertEquals(4L, s.lastNonLaptopSourceId.value)
        assertNull(s.lastChargerId.value)

        s.rememberSource(charger)
        assertEquals(listOf(4L), s.lastBankIds.value)
        assertEquals(9L, s.lastChargerId.value)
        assertEquals(9L, s.lastNonLaptopSourceId.value)

        s.rememberSource(laptop)
        assertEquals(9L, s.lastNonLaptopSourceId.value)
    }

    @Test
    fun themeModeDefaultsToSystemAndPersists() {
        val prefs = FakeSharedPreferences()
        assertEquals(ThemeMode.SYSTEM, SettingsStore(prefs).themeMode.value)
        SettingsStore(prefs).themeMode.set(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, SettingsStore(prefs).themeMode.value)
    }
}

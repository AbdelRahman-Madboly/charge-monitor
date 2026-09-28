package com.vinex.chargemonitor.battery

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelInterpolatorTest {

    // A 4000 mAh battery: 1 % is 40 000 µAh.
    private fun counter(pct: Double): Long = (pct * 40_000).toLong()

    @Test
    fun withoutCounterTheIntegerLevelIsReturned() {
        val li = LevelInterpolator()
        assertEquals(42f, li.levelPct(42f, null), 0f)
        assertEquals(43f, li.levelPct(43f, null), 0f)
    }

    @Test
    fun beforeTheFirstStepTheIntegerLevelIsReturned() {
        val li = LevelInterpolator()
        assertEquals(41f, li.levelPct(41f, counter(41.3)), 0f)
        assertEquals(41f, li.levelPct(41f, counter(41.7)), 0f)
    }

    @Test
    fun chargingInterpolatesFromTheStep() {
        val li = LevelInterpolator()
        li.levelPct(41f, counter(41.8))
        assertEquals(42.0f, li.levelPct(42f, counter(42.0)), 0.001f)
        assertEquals(42.5f, li.levelPct(42f, counter(42.5)), 0.051f)
        assertEquals(42.9f, li.levelPct(42f, counter(42.93)), 0.051f)
        // Counter says past 43 but the system still says 42: never contradict it.
        assertEquals(42.9f, li.levelPct(42f, counter(43.2)), 0.001f)
    }

    @Test
    fun dischargingInterpolatesDownFromTheUpperBoundary() {
        val li = LevelInterpolator()
        li.levelPct(43f, counter(43.1))
        assertEquals(42.9f, li.levelPct(42f, counter(43.0)), 0.001f)
        assertEquals(42.5f, li.levelPct(42f, counter(42.5)), 0.051f)
    }

    @Test
    fun aJumpOfSeveralLevelsDropsTheAnchor() {
        val li = LevelInterpolator()
        li.levelPct(40f, counter(40.9))
        li.levelPct(41f, counter(41.0))
        assertEquals(46f, li.levelPct(46f, counter(46.4)), 0f)
    }

    @Test
    fun implausibleEstimateFallsBackToInteger() {
        val li = LevelInterpolator()
        li.levelPct(41f, counter(41.9))
        li.levelPct(42f, counter(42.0))
        // Counter recalibrated by the gauge (big step down): result would be far below 42.
        assertEquals(42f, li.levelPct(42f, counter(39.0)), 0f)
    }

    @Test
    fun aStepAcrossAPlugChangeIsNotAnAnchor() {
        // Unplugged at 50, replugged at 49 hours later: not a crossing that just happened.
        val li = LevelInterpolator()
        li.levelPct(50f, counter(50.3), tMs = 0, plugged = false)
        assertEquals(49f, li.levelPct(49f, counter(49.9), tMs = 60_000, plugged = true), 0f)
    }

    @Test
    fun aStepAfterALongGapIsNotAnAnchor() {
        val li = LevelInterpolator()
        li.levelPct(50f, counter(50.3), tMs = 0, plugged = false)
        assertEquals(49f, li.levelPct(49f, counter(49.9), tMs = 5 * 3_600_000L, plugged = false), 0f)
    }

    @Test
    fun aPromptStepStillAnchors() {
        val li = LevelInterpolator()
        li.levelPct(41f, counter(41.9), tMs = 0, plugged = true)
        li.levelPct(42f, counter(42.0), tMs = 10_000, plugged = true)
        assertEquals(42.5f, li.levelPct(42f, counter(42.5), tMs = 20_000, plugged = true), 0.051f)
    }
}

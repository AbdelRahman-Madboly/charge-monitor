package com.vinex.chargemonitor.data

import android.content.SharedPreferences
import com.vinex.chargemonitor.battery.CurrentUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings, backed by SharedPreferences and exposed as StateFlows.
 *
 * Each value is read once at construction and then kept in its flow; setters write through to
 * preferences with apply(). Only this class writes these keys, so there is no need to listen for
 * external changes.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    inner class Setting<T>(
        private val key: String,
        private val default: T,
        private val read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(if (prefs.contains(key)) prefs.read(key, default) else default)
        val flow: StateFlow<T> = state.asStateFlow()
        val value: T get() = state.value

        fun set(value: T) {
            prefs.edit().apply { write(key, value) }.apply()
            state.value = value
        }
    }

    private fun float(key: String, default: Float) =
        Setting(key, default, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })

    private fun int(key: String, default: Int) =
        Setting(key, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun bool(key: String, default: Boolean) =
        Setting(key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun string(key: String, default: String?) =
        Setting(key, default, { k, d -> getString(k, d) }, { k, v -> putString(k, v) })

    /** Bank boost + phone charger efficiency used by the capacity estimate. */
    val lossFactor = float("loss_factor", DEFAULT_LOSS_FACTOR)

    /** Phone battery design capacity in mAh; 0 means unknown. */
    val designCapacityMah = int("design_capacity_mah", 0)

    val targetLevel = int("target_level", 80)
    val tempThresholdC = float("temp_threshold_c", 42f)

    val alertTargetEnabled = bool("alert_target", true)
    val alertTempEnabled = bool("alert_temp", true)
    val alertStoppedEnabled = bool("alert_stopped", true)

    /** Background persistence cadence while charging. */
    val sampleIntervalSec = int("sample_interval_s", 10)

    val currentUnit = Setting(
        "current_unit",
        CurrentUnit.AUTO,
        { k, d -> getString(k, null)?.let { runCatching { CurrentUnit.valueOf(it) }.getOrNull() } ?: d },
        { k, v -> putString(k, v.name) },
    )

    /** CurrentNormalizer.Verdict.encode() output, persisted across restarts. */
    val normalizerVerdict = string("normalizer_verdict", null)

    /** Most recently used power banks, newest first. */
    val lastBankIds = Setting(
        "last_bank_ids",
        emptyList<Long>(),
        { k, d -> getString(k, null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: d },
        { k, v -> putString(k, v.joinToString(",")) },
    )

    /** Kept when the "Connected to?" prompt is ignored; -1 means none yet. */
    val lastNonLaptopSourceId = Setting(
        "last_non_laptop_source",
        null as Long?,
        { k, d -> getLong(k, -1L).takeIf { it >= 0 } ?: d },
        { k, v -> putLong(k, v ?: -1L) },
    )

    /** Dashboard toggle; capacity estimates exclude such sessions unless this is on. */
    val includeUsedWhileCharging = bool("include_used_while_charging", false)

    val onboardingDone = bool("onboarding_done", false)

    /** Debug builds only: use the synthetic battery source. */
    val mockMode = bool("mock_mode", false)

    /** Moves [id] to the front of [lastBankIds], keeping at most [keep] entries. */
    fun rememberBank(id: Long, keep: Int = 4) {
        lastBankIds.set((listOf(id) + lastBankIds.value.filter { it != id }).take(keep))
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val DEFAULT_LOSS_FACTOR = 0.85f
    }
}

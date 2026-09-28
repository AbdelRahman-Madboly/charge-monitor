package com.vinex.chargemonitor

import android.annotation.SuppressLint
import android.content.Context
import com.vinex.chargemonitor.battery.BatteryReader
import com.vinex.chargemonitor.battery.BatterySource
import com.vinex.chargemonitor.battery.CurrentNormalizer
import com.vinex.chargemonitor.data.AppDatabase
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.mock.MockBatterySource

/**
 * Hand-rolled dependency holder; the app is small enough that a DI framework would cost more
 * than it saves. [init] is called from [ChargeMonitorApp.onCreate], so every component (service,
 * receivers, UI) can rely on it being ready.
 *
 * Tests do not touch this; they construct the pure units directly.
 */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    val db: AppDatabase by lazy { AppDatabase.build(appContext) }

    val repository: Repository by lazy { Repository(db) }

    val settings: SettingsStore by lazy {
        SettingsStore(appContext.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE))
    }

    val liveBus: LiveBus get() = LiveBus

    /** Shared so the verdict it learns is the one persisted, whichever reader is active. */
    val currentNormalizer: CurrentNormalizer by lazy {
        CurrentNormalizer(
            override = settings.currentUnit.value,
            initial = CurrentNormalizer.Verdict.decode(settings.normalizerVerdict.value),
        )
    }

    private val realSource: BatterySource by lazy { BatteryReader(appContext, currentNormalizer) }
    private val mockSource: BatterySource by lazy { MockBatterySource() }

    /**
     * Re-evaluated on every access so toggling mock mode takes effect on the service's next tick.
     * The settings toggle is honoured only in debug builds.
     */
    val batterySource: BatterySource
        get() = if (isMockRequested()) mockSource else realSource

    fun isMockRequested(): Boolean =
        systemProperty(MOCK_PROPERTY) == "1" || (BuildConfig.DEBUG && settings.mockMode.value)

    const val MOCK_PROPERTY = "debug.chargemonitor.mock"

    // SystemProperties is hidden API; reflection is the only way to honour
    // `adb shell setprop debug.chargemonitor.mock 1`. Any failure simply means "not set".
    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String
    }.getOrNull()
}

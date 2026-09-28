package com.vinex.chargemonitor.battery

import android.annotation.SuppressLint
import android.content.Context
import kotlin.math.roundToInt

/**
 * Reads the battery design capacity the vendor put in the framework power profile.
 *
 * `PowerProfile` is internal API, so this is reflection and may be blocked or return a
 * placeholder on some builds; anything outside a plausible phone range counts as unknown and the
 * user enters the figure in Settings instead.
 */
object DesignCapacity {
    // AOSP ships 1000 mAh as a placeholder (emulators report it), so the floor sits above it.
    private val PLAUSIBLE_MAH = 1_500..20_000

    @SuppressLint("PrivateApi")
    fun fromPowerProfile(context: Context): Int? = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(context)
        (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).roundToInt()
    }.getOrNull()?.takeIf { it in PLAUSIBLE_MAH }
}

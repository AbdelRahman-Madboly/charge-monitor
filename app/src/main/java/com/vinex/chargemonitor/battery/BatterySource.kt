package com.vinex.chargemonitor.battery

/**
 * Anything that can produce a [BatterySnapshot] on demand: the real [BatteryReader], the debug
 * mock, or a test fake.
 *
 * Contract: [read] is synchronous, cheap (no I/O beyond platform binder calls), safe to call
 * from any thread, and never throws; on a platform error it returns the best partial reading.
 * The caller owns the cadence; a source never schedules its own timers.
 */
interface BatterySource {
    /** True for synthetic data, so the UI can show a "mock" badge and sessions can be discarded. */
    val isMock: Boolean

    fun read(): BatterySnapshot
}

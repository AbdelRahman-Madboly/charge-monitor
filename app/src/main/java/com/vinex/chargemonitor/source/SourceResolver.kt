package com.vinex.chargemonitor.source

import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BuiltInSources

/**
 * Decides which source a new session belongs to, from the plug type alone. Pure.
 *
 * Contract (spec section 3, "Automatic source selection"):
 * - USB -> Laptop, no prompt, never counts toward a bank.
 * - WIRELESS -> Wireless, no prompt, never counts toward a bank.
 * - AC -> provisional source is [lastNonLaptopSourceId] (or Charger if none), prompt = true so the
 *   "Connected to?" notification offers [lastBankIds] (up to two) + Charger. The provisional
 *   choice is what is kept if the prompt is ignored for 60 s. [Decision.countsTowardBank] is true
 *   when the provisional source is one of [lastBankIds], i.e. known to be a bank.
 * - NONE -> no source, no prompt.
 */
object SourceResolver {

    data class Decision(
        val sourceId: Long?,
        val prompt: Boolean,
        val countsTowardBank: Boolean,
    )

    /** How long the AC prompt waits before keeping the provisional source. */
    const val PROMPT_TIMEOUT_MS = 60_000L

    fun decide(plug: PlugType, lastBankIds: List<Long>, lastNonLaptopSourceId: Long?): Decision =
        when (plug) {
            PlugType.USB -> Decision(BuiltInSources.LAPTOP_ID, prompt = false, countsTowardBank = false)
            PlugType.WIRELESS -> Decision(BuiltInSources.WIRELESS_ID, prompt = false, countsTowardBank = false)
            PlugType.NONE -> Decision(null, prompt = false, countsTowardBank = false)
            PlugType.AC -> {
                val id = lastNonLaptopSourceId ?: BuiltInSources.CHARGER_ID
                Decision(id, prompt = true, countsTowardBank = id in lastBankIds)
            }
        }
}

package com.vinex.chargemonitor.source

import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BuiltInSources

/**
 * Decides which source a new session belongs to, from the plug type alone. Pure.
 *
 * Spec section 3, "Automatic source selection":
 * - USB -> Laptop, no prompt, never counts toward a bank. (The notification still offers
 *   "It's a power bank", but that reassignment is the tracker's job.)
 * - WIRELESS -> Wireless, no prompt, never counts toward a bank.
 * - AC -> provisional source is the last used non-laptop source (Charger if none), prompt = true
 *   so the "Connected to?" notification offers [Decision.options]: the last two banks + Charger.
 *   The provisional choice is what is kept if the prompt is ignored for [PROMPT_TIMEOUT_MS].
 *   [Decision.countsTowardBank] is true when the provisional source is one of [lastBankIds].
 * - NONE -> no source, no prompt.
 *
 * [lastBankIds] is most recent first, as `SettingsStore.rememberBank` keeps it.
 */
object SourceResolver {

    data class Decision(
        val sourceId: Long?,
        val prompt: Boolean,
        val countsTowardBank: Boolean,
        /** Source ids for the prompt's action buttons, in display order; empty when no prompt. */
        val options: List<Long> = emptyList(),
    )

    /** How long the AC prompt waits before keeping the provisional source. */
    const val PROMPT_TIMEOUT_MS = 60_000L

    /** The notification has room for three actions; the third is always Charger. */
    const val MAX_BANK_OPTIONS = 2

    fun decide(plug: PlugType, lastBankIds: List<Long>, lastNonLaptopSourceId: Long?): Decision =
        when (plug) {
            PlugType.USB -> Decision(BuiltInSources.LAPTOP_ID, prompt = false, countsTowardBank = false)
            PlugType.WIRELESS -> Decision(BuiltInSources.WIRELESS_ID, prompt = false, countsTowardBank = false)
            PlugType.NONE -> Decision(null, prompt = false, countsTowardBank = false)
            PlugType.AC -> {
                // A cable cannot be the wireless pad, so a last-used Wireless falls back to Charger.
                val id = lastNonLaptopSourceId
                    ?.takeUnless { it == BuiltInSources.LAPTOP_ID || it == BuiltInSources.WIRELESS_ID }
                    ?: BuiltInSources.CHARGER_ID
                val banks = lastBankIds.asSequence()
                    .filterNot { it in BUILT_IN_IDS }
                    .distinct()
                    .take(MAX_BANK_OPTIONS)
                    .toList()
                Decision(
                    sourceId = id,
                    prompt = true,
                    countsTowardBank = id !in BUILT_IN_IDS && id in lastBankIds,
                    options = banks + BuiltInSources.CHARGER_ID,
                )
            }
        }

    private val BUILT_IN_IDS = setOf(
        BuiltInSources.CHARGER_ID,
        BuiltInSources.LAPTOP_ID,
        BuiltInSources.WIRELESS_ID,
    )
}

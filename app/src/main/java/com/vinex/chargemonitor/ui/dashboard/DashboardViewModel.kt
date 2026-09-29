package com.vinex.chargemonitor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinex.chargemonitor.ServiceLocator
import com.vinex.chargemonitor.data.BankEvent
import com.vinex.chargemonitor.data.BankEventKind
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.stats.StatsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything one source's card shows. */
data class SourceCard(
    val source: PowerSource,
    /** Exact; includes used-while-charging sessions by design (spec section 2). */
    val fullCharges: Double,
    val whIn: Double,
    val sessionCount: Int,
    /** Mean of per-session averages over the sessions the toggle admits; null without any. */
    val avgW: Double?,
    /** Only for power banks. */
    val capacity: StatsCalculator.CapacityEstimate?,
    /** Sessions left out of the estimate because the phone was in use. */
    val usedExcluded: Int,
    /** Bank sessions whose source was guessed (prompt unanswered); not in any figure above. */
    val unconfirmedCount: Int = 0,
    val unconfirmedFullCharges: Double = 0.0,
    /** The bank's most recent refill mark, for the "last marked" line and undo. */
    val lastMark: BankEvent? = null,
    /** Full phone charges the label promises per full bank; null without rating or phone size. */
    val labelPhoneCharges: Double? = null,
    /** Only for wall chargers. */
    val charger: StatsCalculator.ChargerStats? = null,
    /** End of the source's most recent closed session; null without any. */
    val lastUsedMs: Long? = null,
)

/** Totals over every closed, non-mock session, for the summary strip. */
data class DashboardSummary(
    val sessions: Int = 0,
    val whIn: Double = 0.0,
    val fullCharges: Double = 0.0,
)

data class DashboardUi(
    val sources: List<PowerSource> = emptyList(),
    /** Null means "All". */
    val filter: Long? = null,
    val cards: List<SourceCard> = emptyList(),
    /** Closed sessions matching the filter, oldest first, for the charts. */
    val chartSessions: List<ChargeSession> = emptyList(),
    val includeUsed: Boolean = false,
    val lossFactor: Float = SettingsStore.DEFAULT_LOSS_FACTOR,
    val health: StatsCalculator.HealthEstimate? = null,
    val summary: DashboardSummary = DashboardSummary(),
    val tab: DashboardTab = DashboardTab.BANKS,
    /** Tabs that have at least one card, in display order. */
    val tabs: List<DashboardTab> = emptyList(),
    val sort: DashboardSort = DashboardSort.PHONE_CHARGES,
    /** Cards of [tab], ordered by [sort]. */
    val tabCards: List<SourceCard> = emptyList(),
    val loaded: Boolean = false,
)

class DashboardViewModel(
    private val repository: Repository = ServiceLocator.repository,
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    private val filter = MutableStateFlow<Long?>(null)

    /** Null until the user picks one, so the first tab with cards is shown. */
    private val tab = MutableStateFlow<DashboardTab?>(null)
    private val sorts = MutableStateFlow(DashboardTab.entries.associateWith { it.defaultSort })

    val ui: StateFlow<DashboardUi> = combine(
        repository.observeSources(),
        repository.observeSessions(),
        combine(filter, tab, sorts) { f, t, s -> Triple(f, t, s) },
        settings.includeUsedWhileCharging.flow,
        combine(settings.lossFactor.flow, settings.designCapacityMah.flow, repository.observeBankEvents()) { l, d, e ->
            Triple(l, d, e)
        },
    ) { sources, sessions, (selected, pickedTab, sortByTab), includeUsed, (loss, design, events) ->
        build(sources, sessions, selected, includeUsed, loss, design, events).let { ui ->
            val tabs = DashboardTab.entries.filter { t -> ui.cards.any { t.admits(it.source.kind) } }
            val shownTab = pickedTab?.takeIf { it in tabs } ?: tabs.firstOrNull() ?: DashboardTab.BANKS
            val sort = sortByTab[shownTab] ?: shownTab.defaultSort
            ui.copy(
                tab = shownTab,
                tabs = tabs,
                sort = sort,
                tabCards = sortCards(ui.cards.filter { shownTab.admits(it.source.kind) }, sort),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    fun setFilter(sourceId: Long?) {
        filter.value = sourceId
    }

    fun setTab(t: DashboardTab) {
        tab.value = t
    }

    fun setSort(t: DashboardTab, sort: DashboardSort) {
        sorts.value = sorts.value + (t to sort)
    }

    fun setIncludeUsed(include: Boolean) = settings.includeUsedWhileCharging.set(include)

    fun markBank(sourceId: Long, kind: BankEventKind) {
        viewModelScope.launch {
            repository.addBankEvent(BankEvent(sourceId = sourceId, tMs = System.currentTimeMillis(), kind = kind))
        }
    }

    fun undoMark(eventId: Long) {
        viewModelScope.launch { repository.deleteBankEvent(eventId) }
    }

    private fun build(
        sources: List<PowerSource>,
        sessions: List<ChargeSession>,
        selected: Long?,
        includeUsed: Boolean,
        loss: Float,
        design: Int,
        events: List<BankEvent>,
    ): DashboardUi {
        val closed = sessions.filter { !it.isOpen && !it.mock }
        val bySource = closed.groupBy { it.sourceId }
        // A selected source that has since been archived stays selectable until the filter changes.
        val filter = selected?.takeIf { id -> sources.any { it.id == id } }

        // Every card is always listed; the source filter narrows the charts only.
        val shown = sources.filter { s -> bySource.containsKey(s.id) || (!s.builtIn && !s.archived) }.sortedWith(compareBy<PowerSource> { it.kind.ordinal }.thenBy { it.builtIn }.thenBy { it.name.lowercase() })

        val cards = shown.map { s ->
            val isBank = s.kind == SourceKind.POWER_BANK
            val all = bySource[s.id].orEmpty()
            // A guessed source must not feed a bank; built-ins keep every session.
            val own = if (isBank) all.filter { it.sourceConfirmed } else all
            val unconfirmed = if (isBank) all.filterNot { it.sourceConfirmed } else emptyList()
            val admitted = own.filter { includeUsed || !it.usedWhileCharging }
            SourceCard(
                source = s,
                fullCharges = StatsCalculator.fullChargeEquivalents(own),
                whIn = own.sumOf { it.whIn },
                sessionCount = own.size,
                avgW = admitted.takeIf { it.isNotEmpty() }?.map { it.avgW }?.average(),
                capacity = if (isBank) {
                    StatsCalculator.bankCapacityEstimate(all, s, loss.toDouble(), includeUsed, events)
                } else {
                    null
                },
                usedExcluded = if (includeUsed) 0 else own.count { it.usedWhileCharging },
                unconfirmedCount = unconfirmed.size,
                unconfirmedFullCharges = StatsCalculator.fullChargeEquivalents(unconfirmed),
                lastMark = if (isBank) events.lastOrNull { it.sourceId == s.id } else null,
                charger = if (s.kind == SourceKind.CHARGER) {
                    StatsCalculator.chargerStats(own, s.ratedW, includeUsed)
                } else {
                    null
                },
                labelPhoneCharges = if (isBank) {
                    StatsCalculator.labelPhoneCharges(s, design.takeIf { it > 0 }, loss.toDouble())
                } else {
                    null
                },
                lastUsedMs = all.maxOfOrNull { it.endMs ?: it.startMs },
            )
        }

        val kindOf = sources.associate { it.id to it.kind }
        val chart = closed
            .filter { filter == null || it.sourceId == filter }
            .filter { it.sourceConfirmed || kindOf[it.sourceId] != SourceKind.POWER_BANK }
            .sortedBy { it.startMs }

        return DashboardUi(
            sources = sources,
            filter = filter,
            cards = cards,
            chartSessions = chart,
            includeUsed = includeUsed,
            lossFactor = loss,
            health = StatsCalculator.batteryHealthEstimate(closed, design.takeIf { it > 0 }),
            summary = DashboardSummary(
                sessions = closed.size,
                whIn = closed.sumOf { it.whIn },
                fullCharges = StatsCalculator.fullChargeEquivalents(closed),
            ),
            loaded = true,
        )
    }

    companion object {
        /**
         * [cards] ordered by [sort]: biggest first for figures, A to Z for names, newest first
         * for last used. Cards without the figure go last; ties fall back to the name.
         */
        fun sortCards(cards: List<SourceCard>, sort: DashboardSort): List<SourceCard> {
            val byName = compareBy<SourceCard> { it.source.name.lowercase() }
            if (sort == DashboardSort.NAME) return cards.sortedWith(byName)
            val key: (SourceCard) -> Double? = when (sort) {
                DashboardSort.PHONE_CHARGES -> { c -> c.fullCharges }
                DashboardSort.PER_FULL_BANK -> { c -> c.capacity?.cyclePhoneCharges ?: c.labelPhoneCharges }
                DashboardSort.REAL_CAPACITY -> { c -> c.capacity?.ratioOfRated }
                DashboardSort.PEAK_POWER -> { c -> c.charger?.peakW }
                DashboardSort.AVG_POWER -> { c -> c.avgW?.takeIf { it > 0.0 } }
                DashboardSort.SPEED -> { c -> c.charger?.pctPerHour }
                DashboardSort.ENERGY -> { c -> c.whIn }
                DashboardSort.LAST_USED -> { c -> c.lastUsedMs?.toDouble() }
                DashboardSort.NAME -> error("handled above")
            }
            return cards.sortedWith(
                compareBy<SourceCard> { key(it) == null }
                    .thenByDescending { key(it) ?: 0.0 }
                    .then(byName),
            )
        }
    }
}

/** The segmented control's groups; a card belongs to exactly one. */
enum class DashboardTab(val label: String, val defaultSort: DashboardSort, val sorts: List<DashboardSort>) {
    BANKS(
        "Power banks",
        DashboardSort.PHONE_CHARGES,
        listOf(
            DashboardSort.PHONE_CHARGES, DashboardSort.PER_FULL_BANK, DashboardSort.REAL_CAPACITY,
            DashboardSort.ENERGY, DashboardSort.AVG_POWER, DashboardSort.LAST_USED, DashboardSort.NAME,
        ),
    ),
    CHARGERS(
        "Chargers",
        DashboardSort.PEAK_POWER,
        listOf(
            DashboardSort.PEAK_POWER, DashboardSort.AVG_POWER, DashboardSort.SPEED, DashboardSort.PHONE_CHARGES,
            DashboardSort.ENERGY, DashboardSort.LAST_USED, DashboardSort.NAME,
        ),
    ),
    OTHER(
        "Other",
        DashboardSort.PHONE_CHARGES,
        listOf(DashboardSort.PHONE_CHARGES, DashboardSort.ENERGY, DashboardSort.LAST_USED, DashboardSort.NAME),
    ),
    ;

    fun admits(kind: SourceKind): Boolean = when (this) {
        BANKS -> kind == SourceKind.POWER_BANK
        CHARGERS -> kind == SourceKind.CHARGER
        OTHER -> kind == SourceKind.LAPTOP || kind == SourceKind.WIRELESS
    }
}

enum class DashboardSort(val label: String) {
    PHONE_CHARGES("Phone charges"),
    PER_FULL_BANK("Per full bank"),
    REAL_CAPACITY("Real capacity"),
    PEAK_POWER("Peak power"),
    AVG_POWER("Average power"),
    SPEED("Charging speed"),
    ENERGY("Energy delivered"),
    LAST_USED("Last used"),
    NAME("Name"),
}

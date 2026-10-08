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
import com.vinex.chargemonitor.stats.EnergyModel
import com.vinex.chargemonitor.stats.PhoneLoad
import com.vinex.chargemonitor.stats.StatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    /**
     * mAh the source gave the phone over the sessions [fullCharges] counts: into the battery plus
     * the estimated use while the screen was on. Null until the samples have loaded.
     */
    val delivery: PhoneLoad.Delivery? = null,
    /**
     * [fullCharges] plus the phone's use in phone-battery (design) units; null without a design
     * capacity or any phone use to add.
     */
    val fairPhoneCharges: Double? = null,
    /** Banks: where the counted sessions' energy went, battery back to cells. */
    val energy: StatsCalculator.EnergyTotals? = null,
    /**
     * Banks: cells-to-battery efficiency the promise and the charge-left figures use. The bank's
     * own sessions once it has given at least [MIN_MEASURED_WH], else the model at a typical
     * charge ([efficiencyFromSessions] says which).
     */
    val efficiency: Double? = null,
    val efficiencyFromSessions: Boolean = false,
    /** Banks marked Recharged last: share of the label energy left, and phone charges in it. */
    val chargeLeft: Double? = null,
    val phoneChargesLeft: Double? = null,
    /** Banks marked Recharged last: charge left (0..1) at the mark and after each session since. */
    val chargeHistory: List<Pair<Long, Double>> = emptyList(),
)

/** Totals over every closed, non-mock session, for the summary strip. */
data class DashboardSummary(
    val sessions: Int = 0,
    val whIn: Double = 0.0,
    val fullCharges: Double = 0.0,
    /** [fullCharges] including phone use while charging; null when it cannot be worked out. */
    val fairFullCharges: Double? = null,
)

data class DashboardUi(
    val sources: List<PowerSource> = emptyList(),
    /** Null means "All". */
    val filter: Long? = null,
    val cards: List<SourceCard> = emptyList(),
    /** Closed sessions matching the filter, oldest first, for the charts. */
    val chartSessions: List<ChargeSession> = emptyList(),
    val includeUsed: Boolean = false,
    val health: StatsCalculator.HealthEstimate? = null,
    val summary: DashboardSummary = DashboardSummary(),
    val tab: DashboardTab = DashboardTab.BANKS,
    /** Tabs that have at least one card, in display order. */
    val tabs: List<DashboardTab> = emptyList(),
    val sort: DashboardSort = DashboardSort.DELIVERED_MAH,
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

    /** Closed sessions never change, so their sample profiles are read once; keyed by id. */
    private val profileCache = ConcurrentHashMap<Long, CachedProfile>()

    private data class CachedProfile(val endMs: Long?, val sampleCount: Int, val profile: PhoneLoad.Profile)

    /**
     * Phone-load profiles of every closed, non-mock session, read off the main thread. A session
     * that reopens and closes again (reconnect grace) changes end and sample count, so it is re-read.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val profiles: Flow<Map<Long, PhoneLoad.Profile>> = repository.observeSessions()
        .mapLatest { sessions ->
            withContext(Dispatchers.IO) {
                val closed = sessions.filter { !it.isOpen && !it.mock }
                closed.forEach { s ->
                    val hit = profileCache[s.id]
                    if (hit == null || hit.endMs != s.endMs || hit.sampleCount != s.sampleCount) {
                        profileCache[s.id] = CachedProfile(s.endMs, s.sampleCount, PhoneLoad.profile(s, repository.samples(s.id)))
                    }
                }
                closed.mapNotNull { s -> profileCache[s.id]?.let { s.id to it.profile } }.toMap()
            }
        }
        .onStart { emit(emptyMap()) }
        .distinctUntilChanged()

    val ui: StateFlow<DashboardUi> = combine(
        repository.observeSources(),
        repository.observeSessions(),
        combine(filter, tab, sorts) { f, t, s -> Triple(f, t, s) },
        combine(
            settings.includeUsedWhileCharging.flow,
            profiles,
        ) { i, p -> i to p },
        combine(
            combine(settings.phoneIdleW.flow, settings.phoneScreenW.flow) { _, _ -> settings.energyModel() },
            settings.designCapacityMah.flow,
            repository.observeBankEvents(),
        ) { m, d, e -> Triple(m, d, e) },
    ) { sources, sessions, (selected, pickedTab, sortByTab), (includeUsed, profileMap), (model, design, events) ->
        build(sources, sessions, selected, includeUsed, model, design, events, profileMap).let { ui ->
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
        model: EnergyModel.Params,
        design: Int,
        events: List<BankEvent>,
        profiles: Map<Long, PhoneLoad.Profile>,
    ): DashboardUi {
        val closed = sessions.filter { !it.isOpen && !it.mock }
        // The phone's own load is the same whatever feeds it, so the typical comes from every source.
        val typicalLoad = PhoneLoad.typicalLoad(closed.mapNotNull { profiles[it.id]?.measuredLoadMa })
        val designMah = design.takeIf { it > 0 }
        fun deliveryOf(group: List<ChargeSession>) =
            if (profiles.isEmpty() || group.isEmpty()) null else PhoneLoad.delivery(group, profiles, typicalLoad)
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
            val fullCharges = StatsCalculator.fullChargeEquivalents(own)
            val delivery = deliveryOf(own)
            val capacity = if (isBank) StatsCalculator.bankCapacityEstimate(all, s, includeUsed, events, model) else null
            val measuredEff = capacity?.efficiency?.takeIf { capacity.drawnWh >= MIN_MEASURED_WH }
            val eff = if (isBank) measuredEff ?: EnergyModel.efficiencyAt(EnergyModel.TYPICAL_BATTERY_W, model) else null
            val lastMark = if (isBank) events.lastOrNull { it.sourceId == s.id } else null
            val left = remaining(s, capacity, lastMark)
            SourceCard(
                source = s,
                fullCharges = fullCharges,
                whIn = own.sumOf { it.whIn },
                sessionCount = own.size,
                avgW = admitted.takeIf { it.isNotEmpty() }?.map { it.avgW }?.average(),
                capacity = capacity,
                usedExcluded = if (includeUsed) 0 else own.count { it.usedWhileCharging },
                unconfirmedCount = unconfirmed.size,
                unconfirmedFullCharges = StatsCalculator.fullChargeEquivalents(unconfirmed),
                lastMark = lastMark,
                charger = if (s.kind == SourceKind.CHARGER) {
                    StatsCalculator.chargerStats(own, s.ratedW, includeUsed)
                } else {
                    null
                },
                labelPhoneCharges = eff?.let { StatsCalculator.labelPhoneCharges(s, designMah, it) },
                lastUsedMs = all.maxOfOrNull { it.endMs ?: it.startMs },
                delivery = delivery,
                fairPhoneCharges = fairCharges(fullCharges, delivery, designMah),
                energy = if (isBank) StatsCalculator.energy(own, model) else null,
                efficiency = eff,
                efficiencyFromSessions = measuredEff != null,
                chargeLeft = left?.let { wh -> s.ratedWh?.let { wh / it } },
                chargeHistory = chargeHistory(s, own, lastMark, model),
                phoneChargesLeft = left?.let { wh ->
                    eff?.let { e -> designMah?.let { wh * e / (it * StatsCalculator.PHONE_NOMINAL_V / 1000.0) } }
                },
            )
        }

        val kindOf = sources.associate { it.id to it.kind }
        val chart = closed
            .filter { filter == null || it.sourceId == filter }
            .filter { it.sourceConfirmed || kindOf[it.sourceId] != SourceKind.POWER_BANK }
            .sortedBy { it.startMs }

        val allCharges = StatsCalculator.fullChargeEquivalents(closed)
        return DashboardUi(
            sources = sources,
            filter = filter,
            cards = cards,
            chartSessions = chart,
            includeUsed = includeUsed,
            health = StatsCalculator.batteryHealthEstimate(closed, design.takeIf { it > 0 }),
            summary = DashboardSummary(
                sessions = closed.size,
                whIn = closed.sumOf { it.whIn },
                fullCharges = allCharges,
                fairFullCharges = fairCharges(allCharges, deliveryOf(closed), designMah),
            ),
            loaded = true,
        )
    }

    companion object {
        /** Cell energy a bank's own sessions need before their efficiency replaces the model's. */
        const val MIN_MEASURED_WH = 1.0

        /**
         * Label energy, Wh, a bank still holds when its latest mark is Recharged: the rating less
         * the cell energy the closed sessions since then took. Null otherwise.
         */
        fun remaining(bank: PowerSource, capacity: StatsCalculator.CapacityEstimate?, lastMark: BankEvent?): Double? {
            if (lastMark?.kind != BankEventKind.RECHARGED) return null
            val rated = bank.ratedWh ?: return null
            val used = capacity?.sinceRechargeCellsWh ?: 0.0
            return (rated - used).coerceAtLeast(0.0)
        }

        /**
         * Charge left as a share of the label after each of [sessions] that ended since
         * [lastMark], starting at 1 on the mark itself; empty unless the mark is Recharged.
         */
        fun chargeHistory(
            bank: PowerSource,
            sessions: List<ChargeSession>,
            lastMark: BankEvent?,
            model: EnergyModel.Params,
        ): List<Pair<Long, Double>> {
            if (lastMark?.kind != BankEventKind.RECHARGED) return emptyList()
            val rated = bank.ratedWh ?: return emptyList()
            var used = 0.0
            val after = sessions.filter { !it.isOpen && !it.mock && it.startMs >= lastMark.tMs }
                .sortedBy { it.endMs }
                .map { s ->
                    used += StatsCalculator.cellsWh(s, model)
                    (s.endMs ?: s.startMs) to ((rated - used) / rated).coerceAtLeast(0.0)
                }
            return listOf(lastMark.tMs to 1.0) + after
        }

        /**
         * Phone charges including use while charging: the measured charges (percent added / 100)
         * plus the estimated phone use expressed in design-capacity phone batteries. Built on the
         * percent figure rather than mAh / design so a worn battery never makes it read lower than
         * the plain figure. Null without a design capacity or any phone use to add.
         */
        fun fairCharges(fullCharges: Double, delivery: PhoneLoad.Delivery?, designMah: Int?): Double? {
            val use = delivery?.phoneUseMah?.takeIf { it > 0.0 } ?: return null
            val design = designMah?.takeIf { it > 0 } ?: return null
            return fullCharges + use / design
        }

        /**
         * [cards] ordered by [sort]: biggest first for figures, A to Z for names, newest first
         * for last used. Cards without the figure go last; ties fall back to the name.
         */
        fun sortCards(cards: List<SourceCard>, sort: DashboardSort): List<SourceCard> {
            val byName = compareBy<SourceCard> { it.source.name.lowercase() }
            if (sort == DashboardSort.NAME) return cards.sortedWith(byName)
            val key: (SourceCard) -> Double? = when (sort) {
                DashboardSort.PHONE_CHARGES -> { c -> c.fullCharges }
                DashboardSort.DELIVERED_MAH -> { c -> c.delivery?.deliveredMah?.takeIf { it > 0.0 } }
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
        DashboardSort.DELIVERED_MAH,
        listOf(
            DashboardSort.DELIVERED_MAH, DashboardSort.PHONE_CHARGES, DashboardSort.PER_FULL_BANK, DashboardSort.REAL_CAPACITY,
            DashboardSort.ENERGY, DashboardSort.AVG_POWER, DashboardSort.LAST_USED, DashboardSort.NAME,
        ),
    ),
    CHARGERS(
        "Chargers",
        DashboardSort.PEAK_POWER,
        listOf(
            DashboardSort.PEAK_POWER, DashboardSort.AVG_POWER, DashboardSort.SPEED, DashboardSort.PHONE_CHARGES,
            DashboardSort.DELIVERED_MAH,
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
    DELIVERED_MAH("Delivered mAh"),
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

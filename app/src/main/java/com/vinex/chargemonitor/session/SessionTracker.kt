package com.vinex.chargemonitor.session

import com.vinex.chargemonitor.battery.BatterySnapshot
import com.vinex.chargemonitor.battery.PlugType
import com.vinex.chargemonitor.data.BuiltInSources
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.Sample
import com.vinex.chargemonitor.data.SettingsStore
import com.vinex.chargemonitor.data.SourceKind
import com.vinex.chargemonitor.source.SourceResolver
import com.vinex.chargemonitor.stats.EnergyModel
import kotlin.math.roundToInt

/** The slice of persistence the tracker needs; lets tests run it against an in-memory fake. */
interface SessionStore {
    suspend fun openSession(): ChargeSession?
    suspend fun session(id: Long): ChargeSession?
    suspend fun source(id: Long): PowerSource?
    suspend fun startSession(session: ChargeSession): Long
    suspend fun checkpoint(session: ChargeSession, pending: List<Sample>)
}

class RepositorySessionStore(private val repository: Repository) : SessionStore {
    override suspend fun openSession() = repository.openSession()
    override suspend fun session(id: Long) = repository.session(id)
    override suspend fun source(id: Long) = repository.source(id)
    override suspend fun startSession(session: ChargeSession) = repository.startSession(session)
    override suspend fun checkpoint(session: ChargeSession, pending: List<Sample>) =
        repository.checkpoint(session, pending)
}

/**
 * Turns the stream of battery readings into session rows and samples.
 *
 * Not thread-safe: the service calls it from one coroutine only. Every decision is made from the
 * snapshot's own timestamp, so the tracker has no clock of its own and tests can drive it with
 * synthetic readings.
 *
 * Rules (spec section 3):
 * - plug-in opens a session whose source comes from [SourceResolver];
 * - every reading is folded into a [SessionAccumulator]; a [Sample] is kept only on the persisted
 *   cadence, buffered, and written together with the running totals every [checkpointMs], so a
 *   process death loses at most that much;
 * - unplug starts the reconnect grace; a replug with the same plug type inside it continues the
 *   session, otherwise the session is closed at its last plugged reading;
 * - after a restart, an open row is resumed only if it can be the same physical charge: same
 *   plug, no unplug recorded (or a replug still inside the reconnect grace), written recently,
 *   and a charge counter that did not fall meanwhile. Otherwise it is closed as it stands;
 * - a source the plug type decides (USB, wireless) is confirmed at once; an AC session's source
 *   is a guess until the user picks one, see [ChargeSession.sourceConfirmed].
 */
class SessionTracker(
    private val store: SessionStore,
    private val settings: SettingsStore,
    private val checkpointMs: Long = CHECKPOINT_MS,
) {
    sealed interface Event {
        /** A new session. [promptOptions] are the "Connected to?" buttons, [laptopBank] the bank
         *  offered by the laptop notice's "It's a power bank" action. */
        data class Opened(
            val session: ChargeSession,
            val source: PowerSource?,
            val decision: SourceResolver.Decision,
            val promptOptions: List<PowerSource>,
            val laptopBank: PowerSource?,
        ) : Event

        /** An unconfirmed AC session turned out to be a computer's USB port, see [BatteryReader]. */
        data class BecameLaptop(val session: ChargeSession, val laptopBank: PowerSource?) : Event

        data class Resumed(val session: ChargeSession) : Event
        data class Unplugged(val sessionId: Long) : Event
        data class Reconnected(val sessionId: Long) : Event
        data class Closed(val session: ChargeSession) : Event
    }

    private var restored = false
    private var row: ChargeSession? = null
    private var acc = SessionAccumulator.EMPTY
    private var unpluggedAtMs: Long? = null
    private var lastSampleMs = Long.MIN_VALUE
    private var lastCheckpointMs = 0L
    private var persistedSamples = 0
    private var startCounterUah: Long? = null
    private var lastCounterUah: Long? = null
    private val pending = mutableListOf<Sample>()

    var source: PowerSource? = null
        private set

    val sessionId: Long? get() = row?.id
    val hasOpenSession: Boolean get() = row != null
    val sourceConfirmed: Boolean get() = row?.sourceConfirmed == true
    val totals: SessionTotals? get() = if (row != null && !acc.isEmpty) {
        acc.totals.let { t -> bestTotals(t).let { t.copy(mah = it.mah, wh = it.wh) } }
    } else {
        null
    }

    /**
     * Feeds one reading. [persistEveryMs] is the sample persistence cadence (the settings
     * interval); readings in between are folded into the totals but not stored.
     */
    suspend fun onSnapshot(snapshot: BatterySnapshot, isMock: Boolean, persistEveryMs: Long): List<Event> {
        val events = mutableListOf<Event>()
        if (!restored) {
            restored = true
            restore(snapshot, isMock, events)
        }

        // Real and synthetic readings must never end up in the same session.
        row?.let { if (it.mock != isMock) close(events) }

        if (snapshot.isPlugged) {
            val open = row
            if (open != null && unpluggedAtMs != null) {
                if (acc.isWithinReconnectGrace(snapshot.tMs) && open.plug == snapshot.plug.name) {
                    unpluggedAtMs = null
                    events += Event.Reconnected(open.id)
                    // Clears the stored unplug mark now, so a restart does not mistake the
                    // continued charge for one that ended at the unplug.
                    checkpoint()
                } else {
                    close(events)
                }
            } else if (open != null && open.plug == PlugType.AC.name && snapshot.plug == PlugType.USB &&
                !open.sourceConfirmed
            ) {
                // The computer's data link enumerates a moment after power arrives, so a laptop
                // first reads as AC. Same physical charge: relabel it rather than split it.
                becameLaptop(open, events)
            } else if (open != null && open.plug != snapshot.plug.name) {
                // Swapped cables faster than the unplug broadcast could be noticed.
                close(events)
            }
            if (row == null) open(snapshot, isMock, events) else fold(snapshot, persistEveryMs)
        } else {
            val open = row
            if (open != null) {
                // Folding the unplug reading breaks the integration chain, so a quick replug does
                // not count the unplugged gap as charging time.
                acc = acc.fold(snapshot)
                if (unpluggedAtMs == null) {
                    unpluggedAtMs = snapshot.tMs
                    events += Event.Unplugged(open.id)
                    checkpoint()
                }
                if (!acc.isWithinReconnectGrace(snapshot.tMs)) close(events)
            }
        }
        return events
    }

    /** Called after a notification action or the UI reassigned [sessionId] to [sourceId]. */
    suspend fun applySource(sessionId: Long, sourceId: Long) {
        val open = row ?: return
        if (open.id != sessionId) return
        row = open.copy(sourceId = sourceId, sourceConfirmed = true)
        source = store.source(sourceId)
        // Written now, from the tracker's own copy, so an in-flight checkpoint that read the row
        // just before the reassignment cannot put the old source back.
        checkpoint(sourceOverride = sourceId)
    }

    private suspend fun becameLaptop(open: ChargeSession, events: MutableList<Event>) {
        row = open.copy(plug = PlugType.USB.name, sourceId = BuiltInSources.LAPTOP_ID, sourceConfirmed = true)
        source = store.source(BuiltInSources.LAPTOP_ID)
        checkpoint(sourceOverride = BuiltInSources.LAPTOP_ID)
        val laptopBank = settings.lastBankIds.value
            .mapNotNull { store.source(it) }
            .firstOrNull { it.countsTowardBank && !it.archived }
        events += Event.BecameLaptop(row!!, laptopBank)
    }

    /** Writes the running totals now, e.g. before the service stops. */
    suspend fun flush() {
        if (row != null) checkpoint()
    }

    private suspend fun restore(snapshot: BatterySnapshot, isMock: Boolean, events: MutableList<Event>) {
        val open = store.openSession() ?: return
        val gapMs = snapshot.tMs - open.updatedMs
        val samePlug = snapshot.isPlugged && open.plug == snapshot.plug.name
        val mode = when {
            open.mock != isMock -> null
            open.unpluggedAtMs != null -> when {
                // The unplug was recorded; only the loose-connector grace can still join them.
                gapMs !in 0..SessionAccumulator.RECONNECT_GRACE_MS -> null
                samePlug -> RestoreMode.RECONNECTED
                !snapshot.isPlugged -> RestoreMode.STILL_UNPLUGGED
                else -> null
            }
            samePlug && gapMs in 0..RESUME_MAX_GAP_MS && counterContinuous(open, snapshot) -> RestoreMode.CONTINUED
            else -> null
        }
        if (mode == null) {
            // Totals are as good as the last checkpoint; the level at that time is the best end.
            store.checkpoint(
                open.copy(endMs = open.updatedMs, endLevel = open.endLevel ?: open.startLevel, unpluggedAtMs = null),
                emptyList(),
            )
            return
        }

        val reference = open.lastCounterUah ?: snapshot.chargeCounterUah
        val startCounter = reference?.let { ref -> open.counterMahIn?.let { ref - (it * 1000).toLong() } ?: ref }
        val integratedMs = (open.updatedMs - open.startMs).coerceAtLeast(0L)
        var base = SessionAccumulator(
            startMs = open.startMs,
            last = snapshot,
            mah = open.mahIn,
            wh = open.whIn,
            peakW = open.peakW,
            maxTempC = open.maxTempC,
            integratedMs = integratedMs,
            screenOnMs = (open.screenOnFraction * integratedMs).toLong(),
            reachedFull = open.reachedFull,
            startLevel = open.startLevel,
            endLevel = if (snapshot.isPlugged) snapshot.levelPct else open.endLevel ?: open.startLevel,
            sampleCount = open.sampleCount,
            lastPluggedMs = if (snapshot.isPlugged) snapshot.tMs else open.updatedMs,
            firstCounterUah = startCounter,
            lastCounterUah = if (snapshot.isPlugged) snapshot.chargeCounterUah ?: reference else reference,
            startRefined = true,
            phoneUseWh = open.phoneUseWh ?: 0.0,
            portWh = open.portWh ?: 0.0,
            cellsWh = open.cellsWh ?: 0.0,
            model = settings.energyModel(),
        )
        if (mode == RestoreMode.CONTINUED) base = base.bridgeDeadInterval(open, snapshot)
        // The current reading becomes the new integration reference rather than being folded
        // against a guessed predecessor; the dead interval, if any, was bridged above.
        acc = base
        row = open
        source = store.source(open.sourceId)
        persistedSamples = open.sampleCount
        lastSampleMs = open.updatedMs
        lastCheckpointMs = snapshot.tMs
        unpluggedAtMs = if (mode == RestoreMode.STILL_UNPLUGGED) open.unpluggedAtMs else null
        lastCounterUah = base.lastCounterUah
        startCounterUah = startCounter
        events += Event.Resumed(open)
        if (mode == RestoreMode.RECONNECTED) {
            events += Event.Reconnected(open.id)
            checkpoint()
        }
    }

    private enum class RestoreMode { CONTINUED, RECONNECTED, STILL_UNPLUGGED }

    /** A counter that fell noticeably while the process was dead means the phone ran on battery. */
    private fun counterContinuous(open: ChargeSession, snapshot: BatterySnapshot): Boolean {
        val before = open.lastCounterUah ?: return true
        val now = snapshot.chargeCounterUah ?: return true
        return now - before >= -COUNTER_DROP_TOLERANCE_UAH
    }

    /** Charge taken while the process was dead, from the counter; Wh at the current voltage. */
    private fun SessionAccumulator.bridgeDeadInterval(open: ChargeSession, snapshot: BatterySnapshot): SessionAccumulator {
        val before = open.lastCounterUah ?: return this
        val now = snapshot.chargeCounterUah ?: return this
        val gapMs = (snapshot.tMs - open.updatedMs).coerceAtLeast(0L)
        val gapMah = (now - before) / 1000.0
        val gapWh = gapMah * snapshot.voltageMv / 1_000_000.0
        val f = EnergyModel.bridged(gapWh, gapMs / 3_600_000.0, screenOn = false, model)
        return copy(
            mah = mah + gapMah,
            wh = wh + gapWh,
            integratedMs = integratedMs + gapMs,
            phoneUseWh = phoneUseWh + (f?.phoneUseW ?: 0.0),
            portWh = portWh + (f?.portW ?: 0.0),
            cellsWh = cellsWh + (f?.cellsW ?: 0.0),
        )
    }

    /** The named charger last used, while it is still active; else the built-in Charger. */
    private suspend fun promptCharger(): PowerSource? {
        val named = settings.lastChargerId.value
            ?.let { store.source(it) }
            ?.takeIf { it.kind == SourceKind.CHARGER && !it.archived }
        return named ?: store.source(BuiltInSources.CHARGER_ID)
    }

    private suspend fun open(snapshot: BatterySnapshot, isMock: Boolean, events: MutableList<Event>) {
        val charger = promptCharger()
        val decision = SourceResolver.decide(
            snapshot.plug,
            settings.lastBankIds.value,
            settings.lastNonLaptopSourceId.value,
            charger?.id ?: BuiltInSources.CHARGER_ID,
        )
        val sourceId = decision.sourceId ?: return
        val draft = ChargeSession(
            sourceId = sourceId,
            plug = snapshot.plug.name,
            startMs = snapshot.tMs,
            startLevel = snapshot.levelPct,
            endLevel = snapshot.levelPct,
            mock = isMock,
            sourceConfirmed = !decision.prompt,
        )
        val id = store.startSession(draft)
        row = draft.copy(id = id)
        source = store.source(sourceId)
        acc = SessionAccumulator(model = settings.energyModel())
        unpluggedAtMs = null
        pending.clear()
        persistedSamples = 0
        lastSampleMs = Long.MIN_VALUE
        lastCheckpointMs = snapshot.tMs
        startCounterUah = snapshot.chargeCounterUah
        lastCounterUah = snapshot.chargeCounterUah
        fold(snapshot, persistEveryMs = 0L)

        val recentBanks = settings.lastBankIds.value
            .mapNotNull { store.source(it) }
            .filter { it.countsTowardBank && !it.archived }
        val promptOptions = if (decision.prompt) {
            recentBanks.take(2) + listOfNotNull(charger)
        } else {
            emptyList()
        }
        val laptopBank = if (snapshot.plug == PlugType.USB) recentBanks.firstOrNull() else null
        events += Event.Opened(row!!, source, decision, promptOptions, laptopBank)
    }

    private suspend fun fold(snapshot: BatterySnapshot, persistEveryMs: Long) {
        val open = row ?: return
        acc = acc.fold(snapshot)
        snapshot.chargeCounterUah?.let { lastCounterUah = it }

        // A little slack so a 10 s cadence whose ticks land at 9.98 s still persists every tick.
        val slack = minOf(1_000L, persistEveryMs / 4)
        if (lastSampleMs == Long.MIN_VALUE || snapshot.tMs - lastSampleMs >= persistEveryMs - slack) {
            pending += snapshot.toSample(open.id)
            lastSampleMs = snapshot.tMs
            persistedSamples++
        }
        if (snapshot.tMs - lastCheckpointMs >= checkpointMs) checkpoint()
    }

    private suspend fun checkpoint(sourceOverride: Long? = null, closeAtMs: Long? = null) {
        val open = row ?: return
        // Re-read so a source change made elsewhere (session screen) is kept, not overwritten.
        val stored = store.session(open.id) ?: open
        val sourceId = sourceOverride ?: stored.sourceId
        val t = acc.totals
        val best = bestTotals(t)
        val updated = stored.copy(
            sourceId = sourceId,
            // The tracker owns the plug; it changes only when an AC session turns out to be a laptop.
            plug = open.plug,
            // Confirmation only ever goes one way: from the prompt, the picker or History.
            sourceConfirmed = stored.sourceConfirmed || open.sourceConfirmed || sourceOverride != null,
            unpluggedAtMs = if (closeAtMs != null) null else unpluggedAtMs,
            lastCounterUah = lastCounterUah,
            endMs = closeAtMs,
            updatedMs = if (acc.isEmpty) open.updatedMs else acc.totals.lastMs,
            endLevel = if (acc.isEmpty) stored.endLevel else t.endLevel,
            mahIn = best.mah,
            counterMahIn = counterMah(),
            whIn = best.wh,
            phoneUseWh = t.phoneUseWh,
            portWh = best.portWh,
            cellsWh = best.cellsWh,
            peakW = t.peakW,
            avgW = t.avgW,
            maxTempC = t.maxTempC,
            reachedFull = t.reachedFull,
            usedWhileCharging = t.usedWhileCharging,
            screenOnFraction = t.screenOnFraction,
            sampleCount = persistedSamples,
        )
        store.checkpoint(updated, pending.toList())
        pending.clear()
        lastCheckpointMs = acc.last?.tMs ?: lastCheckpointMs
        row = updated
        if (source?.id != sourceId) source = store.source(sourceId)
    }

    private suspend fun close(events: MutableList<Event>) {
        val open = row ?: return
        // The last plugged reading, not the unplug reading that may have been folded after it.
        checkpoint(closeAtMs = if (acc.isEmpty) open.startMs else acc.totals.lastMs)
        val closed = row!!
        row = null
        source = null
        acc = SessionAccumulator.EMPTY
        unpluggedAtMs = null
        startCounterUah = null
        lastCounterUah = null
        events += Event.Closed(closed)
    }

    /**
     * Charge and energy to report. The fuel gauge's own counter wins once it has moved enough
     * to outweigh its step size: sampling a current that swings between +0.6 A and -1.2 A every
     * few seconds (phone in use) misses most of it; on a Galaxy A54 a 10 s integral read 12 mAh
     * where the counter read 20. Energy keeps the integral's average voltage.
     */
    private fun bestTotals(t: SessionTotals): Totals {
        val counter = counterMah()
        if (counter == null || counter < COUNTER_TRUST_MAH) return Totals(t.mah, t.wh, t.portWh, t.cellsWh)
        val meanV = if (t.mah > 1.0) t.wh * 1000.0 / t.mah else null
        val v = meanV?.takeIf { it in 3.0..4.6 } ?: ((acc.last?.voltageMv ?: 3_850) / 1000.0)
        val wh = counter * v / 1000.0
        // Battery energy the readings missed still came through the bank, at the session's own
        // rate of port and cell energy per Wh that reached the phone.
        val reached = t.wh.coerceAtLeast(0.0) + t.phoneUseWh
        val extra = wh - t.wh
        if (reached <= 0.0) return Totals(counter, wh, t.portWh, t.cellsWh)
        val scale = ((reached + extra) / reached).coerceAtLeast(0.0)
        return Totals(counter, wh, t.portWh * scale, t.cellsWh * scale)
    }

    private data class Totals(val mah: Double, val wh: Double, val portWh: Double, val cellsWh: Double)

    private fun counterMah(): Double? {
        val start = startCounterUah ?: return null
        val end = lastCounterUah ?: return null
        return (end - start) / 1000.0
    }

    private fun BatterySnapshot.toSample(sessionId: Long) = Sample(
        sessionId = sessionId,
        tMs = tMs,
        mV = voltageMv,
        mA = currentMa.roundToInt(),
        level10 = (levelPct * 10).roundToInt(),
        temp10 = (tempC * 10).roundToInt(),
        screenOn = screenOn,
    )

    companion object {
        /** How often the running totals and buffered samples are written. */
        const val CHECKPOINT_MS = 60_000L

        /**
         * An open row older than this after a restart is closed rather than resumed: the phone was
         * very likely unplugged and replugged while the process was dead.
         */
        const val RESUME_MAX_GAP_MS = 10 * 60_000L

        /** Heavy use while plugged can drain a little; more than this was a real unplug. */
        const val COUNTER_DROP_TOLERANCE_UAH = 50_000L

        /** Six steps of the 5 mAh counter a Galaxy reports; below this the integral is finer. */
        const val COUNTER_TRUST_MAH = 30.0
    }
}

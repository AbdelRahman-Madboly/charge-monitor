package com.vinex.chargemonitor.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * The single entry point to persisted data for the service and the view models. Thin on purpose:
 * rules about when to open or close a session live in the session tracker, not here.
 */
class Repository(private val db: AppDatabase) {
    private val sources = db.sourceDao()
    private val sessions = db.sessionDao()
    private val samples = db.sampleDao()

    // Sources
    fun observeSources(): Flow<List<PowerSource>> = sources.observeAll()
    fun observeActiveSources(): Flow<List<PowerSource>> = sources.observeActive()
    fun observeBanks(): Flow<List<PowerSource>> = sources.observeBanks()
    fun observeSource(id: Long): Flow<PowerSource?> = sources.observe(id)
    suspend fun source(id: Long): PowerSource? = sources.get(id)
    suspend fun allSources(): List<PowerSource> = sources.getAll()
    suspend fun addSource(source: PowerSource): Long = sources.insert(source.copy(id = 0, builtIn = false))
    suspend fun updateSource(source: PowerSource) = sources.update(source)
    suspend fun setArchived(id: Long, archived: Boolean) = sources.setArchived(id, archived)

    // Sessions
    fun observeSessions(): Flow<List<ChargeSession>> = sessions.observeAll()
    fun observeSessionsBySource(sourceId: Long): Flow<List<ChargeSession>> = sessions.observeBySource(sourceId)
    fun observeSessionsInRange(fromMs: Long, toMs: Long): Flow<List<ChargeSession>> =
        sessions.observeInRange(fromMs, toMs)
    fun observeSession(id: Long): Flow<ChargeSession?> = sessions.observe(id)
    fun observeOpenSession(): Flow<ChargeSession?> = sessions.observeOpen()
    fun observeLastClosedSession(): Flow<ChargeSession?> = sessions.observeLastClosed()
    fun observeTotals(includeUsedWhileCharging: Boolean): Flow<List<SourceTotals>> =
        sessions.observeTotals(includeUsedWhileCharging)
    suspend fun session(id: Long): ChargeSession? = sessions.get(id)
    suspend fun openSession(): ChargeSession? = sessions.getOpen()
    suspend fun startSession(session: ChargeSession): Long = sessions.insert(session)
    suspend fun updateSession(session: ChargeSession) = sessions.update(session)
    suspend fun setSessionSource(sessionId: Long, sourceId: Long) = sessions.setSource(sessionId, sourceId)
    suspend fun deleteSession(id: Long) = sessions.delete(id)
    suspend fun recentBankIds(limit: Int = 2): List<Long> = sessions.recentBankIds(limit)
    suspend fun sessionsForExport(): List<ChargeSession> = sessions.getAllForExport()

    // Samples
    fun observeSamples(sessionId: Long): Flow<List<Sample>> = samples.observeForSession(sessionId)
    suspend fun samples(sessionId: Long): List<Sample> = samples.getForSession(sessionId)
    suspend fun addSample(sample: Sample) = samples.insert(sample)
    suspend fun addSamples(batch: List<Sample>) = samples.insertAll(batch)

    /** Writes updated running totals and any buffered samples atomically. */
    suspend fun checkpoint(session: ChargeSession, pending: List<Sample>) = db.withTransaction {
        sessions.update(session)
        if (pending.isNotEmpty()) samples.insertAll(pending)
    }
}

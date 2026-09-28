package com.vinex.chargemonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PowerSourceDao {
    @Query("SELECT * FROM power_source ORDER BY builtIn DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PowerSource>>

    @Query("SELECT * FROM power_source WHERE archived = 0 ORDER BY builtIn DESC, name COLLATE NOCASE")
    fun observeActive(): Flow<List<PowerSource>>

    @Query("SELECT * FROM power_source WHERE kind = 'POWER_BANK' AND archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeBanks(): Flow<List<PowerSource>>

    @Query("SELECT * FROM power_source WHERE id = :id")
    fun observe(id: Long): Flow<PowerSource?>

    @Query("SELECT * FROM power_source WHERE id = :id")
    suspend fun get(id: Long): PowerSource?

    @Query("SELECT * FROM power_source")
    suspend fun getAll(): List<PowerSource>

    @Insert
    suspend fun insert(source: PowerSource): Long

    @Update
    suspend fun update(source: PowerSource)

    @Query("UPDATE power_source SET archived = :archived WHERE id = :id AND builtIn = 0")
    suspend fun setArchived(id: Long, archived: Boolean)
}

/** Per-source aggregate over closed sessions, for the dashboard header rows. */
data class SourceTotals(
    val sourceId: Long,
    val sessionCount: Int,
    /** Sum of percentage points added; divide by 100 for full-charge equivalents. */
    val levelAdded: Double,
    val mahIn: Double,
    val whIn: Double,
    val avgW: Double,
)

@Dao
interface ChargeSessionDao {
    @Insert
    suspend fun insert(session: ChargeSession): Long

    @Update
    suspend fun update(session: ChargeSession)

    @Query("SELECT * FROM charge_session WHERE id = :id")
    suspend fun get(id: Long): ChargeSession?

    @Query("SELECT * FROM charge_session WHERE id = :id")
    fun observe(id: Long): Flow<ChargeSession?>

    /** At most one session is open at a time; the newest wins if a crash ever left two. */
    @Query("SELECT * FROM charge_session WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    suspend fun getOpen(): ChargeSession?

    @Query("SELECT * FROM charge_session WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    fun observeOpen(): Flow<ChargeSession?>

    @Query("SELECT * FROM charge_session WHERE endMs IS NOT NULL ORDER BY startMs DESC LIMIT 1")
    fun observeLastClosed(): Flow<ChargeSession?>

    @Query("SELECT * FROM charge_session WHERE mock = 0 ORDER BY startMs DESC")
    fun observeAll(): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_session WHERE sourceId = :sourceId AND mock = 0 ORDER BY startMs DESC")
    fun observeBySource(sourceId: Long): Flow<List<ChargeSession>>

    /** Sessions that started in [fromMs, toMs). */
    @Query("SELECT * FROM charge_session WHERE startMs >= :fromMs AND startMs < :toMs AND mock = 0 ORDER BY startMs DESC")
    fun observeInRange(fromMs: Long, toMs: Long): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_session WHERE mock = 0 ORDER BY startMs")
    suspend fun getAllForExport(): List<ChargeSession>

    @Query(
        """
        SELECT sourceId,
               COUNT(*) AS sessionCount,
               COALESCE(SUM(MAX(endLevel - startLevel, 0)), 0) AS levelAdded,
               COALESCE(SUM(mahIn), 0) AS mahIn,
               COALESCE(SUM(whIn), 0) AS whIn,
               COALESCE(AVG(avgW), 0) AS avgW
        FROM charge_session
        WHERE endMs IS NOT NULL AND mock = 0 AND (:includeUsedWhileCharging OR usedWhileCharging = 0)
        GROUP BY sourceId
        """,
    )
    fun observeTotals(includeUsedWhileCharging: Boolean): Flow<List<SourceTotals>>

    /** Most recently used, non-archived power banks, newest first; feeds the "Connected to?" buttons. */
    @Query(
        """
        SELECT s.sourceId FROM charge_session s
        JOIN power_source p ON p.id = s.sourceId
        WHERE p.kind = 'POWER_BANK' AND p.archived = 0
        GROUP BY s.sourceId
        ORDER BY MAX(s.startMs) DESC
        LIMIT :limit
        """,
    )
    suspend fun recentBankIds(limit: Int): List<Long>

    @Query("UPDATE charge_session SET sourceId = :sourceId WHERE id = :id")
    suspend fun setSource(id: Long, sourceId: Long)

    @Query("DELETE FROM charge_session WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: Sample): Long

    @Insert
    suspend fun insertAll(samples: List<Sample>)

    @Query("SELECT * FROM sample WHERE sessionId = :sessionId ORDER BY tMs")
    fun observeForSession(sessionId: Long): Flow<List<Sample>>

    @Query("SELECT * FROM sample WHERE sessionId = :sessionId ORDER BY tMs")
    suspend fun getForSession(sessionId: Long): List<Sample>

    @Query("SELECT COUNT(*) FROM sample WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int
}

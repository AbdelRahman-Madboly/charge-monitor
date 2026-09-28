package com.vinex.chargemonitor.ui.history

import android.content.Context
import android.net.Uri
import com.vinex.chargemonitor.data.ChargeSession
import com.vinex.chargemonitor.data.PowerSource
import com.vinex.chargemonitor.data.Repository
import com.vinex.chargemonitor.data.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

/**
 * CSV export. The file is written through the Storage Access Framework URI the user picked, so no
 * storage permission is needed (spec section 7). Times are ISO-8601 UTC plus raw epoch ms, so the
 * file sorts correctly and imports into any spreadsheet without locale surprises.
 */
object CsvExport {
    enum class Kind(val fileStem: String) { SESSIONS("charge-sessions"), SAMPLES("charge-samples") }

    val SESSION_HEADER = listOf(
        "session_id", "source", "source_kind", "plug", "start_iso", "end_iso", "start_ms", "end_ms",
        "duration_s", "start_level", "end_level", "level_added", "mah_in", "counter_mah_in", "wh_in",
        "peak_w", "avg_w", "max_temp_c", "reached_full", "used_while_charging", "screen_on_fraction",
        "sample_count",
    )

    val SAMPLE_HEADER = listOf("session_id", "source", "t_iso", "t_ms", "mv", "ma", "w", "level", "temp_c", "screen_on")

    fun sessionRow(s: ChargeSession, source: PowerSource?): List<String> = listOf(
        s.id.toString(),
        source?.name.orEmpty(),
        source?.kind?.name.orEmpty(),
        s.plug,
        iso(s.startMs),
        s.endMs?.let(::iso).orEmpty(),
        s.startMs.toString(),
        s.endMs?.toString().orEmpty(),
        s.endMs?.let { ((it - s.startMs) / 1000).toString() }.orEmpty(),
        num(s.startLevel.toDouble(), 1),
        s.endLevel?.let { num(it.toDouble(), 1) }.orEmpty(),
        num(s.levelAdded.toDouble(), 1),
        num(s.mahIn, 1),
        s.counterMahIn?.let { num(it, 1) }.orEmpty(),
        num(s.whIn, 3),
        num(s.peakW, 2),
        num(s.avgW, 2),
        s.maxTempC?.let { num(it.toDouble(), 1) }.orEmpty(),
        s.reachedFull.toString(),
        s.usedWhileCharging.toString(),
        num(s.screenOnFraction.toDouble(), 3),
        s.sampleCount.toString(),
    )

    fun sampleRow(x: Sample, sourceName: String): List<String> = listOf(
        x.sessionId.toString(),
        sourceName,
        iso(x.tMs),
        x.tMs.toString(),
        x.mV.toString(),
        x.mA.toString(),
        num(x.mV.toDouble() * x.mA / 1_000_000.0, 3),
        num(x.level10 / 10.0, 1),
        num(x.temp10 / 10.0, 1),
        x.screenOn.toString(),
    )

    /** RFC 4180 quoting: only fields containing a comma, quote or line break are quoted. */
    fun line(fields: List<String>): String = fields.joinToString(",") { f ->
        if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f
    }

    /** Writes the chosen export to [uri]; returns the number of data rows written. */
    suspend fun write(context: Context, uri: Uri, kind: Kind, repository: Repository): Int = withContext(Dispatchers.IO) {
        val sessions = repository.sessionsForExport()
        val sources = repository.allSources().associateBy { it.id }
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the chosen file")
        var rows = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            when (kind) {
                Kind.SESSIONS -> {
                    w.appendLine(line(SESSION_HEADER))
                    sessions.forEach { w.appendLine(line(sessionRow(it, sources[it.sourceId]))); rows++ }
                }
                Kind.SAMPLES -> {
                    w.appendLine(line(SAMPLE_HEADER))
                    // One session at a time keeps memory flat however long the history is.
                    sessions.forEach { s ->
                        val name = sources[s.sourceId]?.name.orEmpty()
                        repository.samples(s.id).forEach { w.appendLine(line(sampleRow(it, name))); rows++ }
                    }
                }
            }
        }
        rows
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun num(v: Double, decimals: Int): String =
        if (v.isFinite()) "%.${decimals}f".format(Locale.US, v) else ""
}

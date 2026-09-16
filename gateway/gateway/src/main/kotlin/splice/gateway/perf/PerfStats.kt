// NEW: the per-turn perf JSONL sink + reader (bottleneck instrument, pairs with core TurnPerf).
// One row per finished turn: {ts, model, outcome, compact, <marks>, <counters>}. Append is
// asynchronous best-effort — I/O failure must never kill a turn (same doctrine as CompactStats).
// Reads are TAIL-BOUNDED (readJsonlTail) so the control-plane aggregation never heap-loads an
// unbounded history; the file is additive state (a new `<head>-perf.jsonl` beside the HUD
// contract files, not part of the frozen name set).
package splice.gateway.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.perf.PerfSnapshot
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

/** The string facts a perf row carries beside the numeric snapshot. */
public data class PerfRowMeta(
    val model: String,
    val outcome: String,
    val compact: Boolean,
    /** The client's session tag (first 8 of x-claude-code-session-id), so an abort or a stall in
     *  the perf log is attributable to ONE Claude Code session in a single grep (2026-09-02: seven
     *  client aborts in two hours could only be tied to sessions by cross-reading transcripts). */
    val session: String? = null,
    val account: String? = null,
    val cacheCold: Boolean = false,
)

private const val DEFAULT_TAIL = 200

// ~256 KiB of trailing JSONL bounds parse cost regardless of file age.
private const val READ_TAIL_BYTES = 256 * 1024

public class PerfStats(
    private val file: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
) {

    private val unreadableLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private val json = Json { ignoreUnknownKeys = true }

    // append is best-effort by design: the turn builds an immutable row and the bounded file lane
    // owns filesystem latency.
    public fun record(meta: PerfRowMeta, snap: PerfSnapshot) {
        val row = buildJsonObject {
            put("ts", clock())
            put("model", meta.model)
            put("outcome", meta.outcome)
            put("compact", meta.compact)
            meta.session?.let { put("session", it) }
            meta.account?.let { account ->
                put("account", account)
                put("cache_cold", meta.cacheCold)
            }
            snap.marks.forEach { (k, v) -> put(k, v) }
            snap.counters.forEach { (k, v) -> put(k, v) }
        }.toString()
        AsyncFileIo.submit {
            Cancellables.runCatchingCancellable {
                Files.createDirectories(file.parent)
                JsonlSink.appendLine(file, row)
            }
        }
    }

    /** Numeric fields of the last [tailN] rows, newest last — the aggregation input. */
    public fun tailNumeric(tailN: Int = DEFAULT_TAIL): List<Map<String, Long>> =
        tailRows().takeLast(tailN).map { numericFields(it) }

    /** Numeric fields of EVERY row in the byte-bounded tail belonging to ONE client session,
     *  newest last. No row cap on purpose: the read is already bounded by bytes, and a session's
     *  spend must not be truncated by a count that a long session would exceed.
     *
     *  V4-37: the statusline's cost segment replaces a per-SESSION number, so it must be summed from
     *  one session's rows. The row on disk stores the session TRUNCATED (SESSION_TAG_CHARS in
     *  TurnDrive.kt); this reader is the one place that knows it, so the caller passes the full id it
     *  holds and the truncation stays with the writer instead of being duplicated in another module.
     *  An empty [sessionId] matches nothing at all — an empty tag would otherwise `startsWith` every
     *  row in the file and quietly become a head-wide total. */
    public fun tailNumericFor(sessionId: String): List<Map<String, Long>> {
        if (sessionId.isEmpty()) return emptyList()
        return tailRows().filter { row -> belongsTo(row, sessionId) }.map { numericFields(it) }
    }

    /** True when [row]'s stored tag is the truncated form of [sessionId]. */
    private fun belongsTo(row: JsonObject, sessionId: String): Boolean {
        val tag = sessionTagOf(row)?.takeIf { it.isNotEmpty() } ?: return false
        return sessionId.startsWith(tag)
    }

    private fun sessionTagOf(row: JsonObject): String? =
        (row["session"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // read is best-effort by design: a missing/corrupt file yields empty; a bad line is skipped.
    private fun tailRows(): List<JsonObject> {
        AsyncFileIo.drain()
        // DR-60 (class law): only PROVEN absence — NoSuch with no NOFOLLOW entry — is the quiet
        // empty; an inaccessible perf log degrades the same but leaves a trace instead of a
        // silently-blank instrument.
        val rows = Cancellables.runCatchingCancellable {
            JsonlSink.readTail(file, READ_TAIL_BYTES).mapNotNull { line ->
                Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
        }.onSuccess {
            // ANY healthy read — an empty or all-skipped tail included — closes the unreadable
            // episode so the next one logs again; guarded on isNotEmpty, a recovered-but-empty
            // file kept the latch armed and the second episode silent (sweep 2026-08-31).
            unreadableLogged.set(false)
        }.getOrElse { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!genuinelyAbsent && unreadableLogged.compareAndSet(false, true)) {
                log("[perf] $file unreadable (${SafeFailureText.render(failure)}) — stats rendered empty\n")
            }
            if (genuinelyAbsent) unreadableLogged.set(false)
            emptyList()
        }
        return rows
    }

    private fun numericFields(row: JsonObject): Map<String, Long> = buildMap {
        row.forEach { (k, v) ->
            (v as? JsonPrimitive)?.longOrNull?.let { put(k, it) }
        }
    }
}

// NEW: the per-turn perf JSONL sink + reader (bottleneck instrument, pairs with core TurnPerf).
// One row per finished turn: {ts, model, outcome, compact, <marks>, <counters>}. Append is
// asynchronous best-effort — I/O failure must never kill a turn (same doctrine as CompactStats).
// Reads are TAIL-BOUNDED (readJsonlTail) so the control-plane aggregation never heap-loads an
// unbounded history; the file is additive state (a new `<head>-perf.jsonl` beside the HUD
// contract files, not part of the frozen name set).
//
// V4-133, FEATURES.md §6 ("a perf retention design belongs in the same change"): JsonlSink's
// 64 MB one-generation rotate keeps exactly one rolled `.1` and DISCARDS the generation before it
// — `claudex-perf.jsonl.1` was already 67 MB of history one rotate away from gone on the operator's
// own machine. [archiveDir] is OPT-IN (null = today's exact behaviour, the NEVER-BELOW-STATUS-QUO
// default) and, when set, copies each about-to-be-discarded `.1` into it before JsonlSink
// overwrites it, timestamped so two rotates in one process never collide, and sweeps archived files
// past [archiveRetentionDays]. Team and project tracking then reads a directory of whole rolled
// generations instead of one that is always about to lose its oldest.
package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.config.Knob
import splice.core.perf.PerfSnapshot
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.RotationArchive
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    /** V4-117: WHY this turn failed, as the taxonomy's cause, so a perf row can be grouped by cause
     *  rather than by the wire type the client happened to be told (the two differ by design — see
     *  WireType). Null for a turn that did not fail. */
    val cause: String? = null,
    /** V4-117: how many upstream attempts the retry loop made, as RECORDED by the loop itself.
     *  Written only when it is non-zero, so a row without retries looks exactly as it did before
     *  this field existed — the alternative would put layers=0 on every success in the file. */
    val layers: Int = 0,
)

private const val DEFAULT_TAIL = 200

// ~256 KiB of trailing JSONL bounds parse cost regardless of file age.
private const val READ_TAIL_BYTES = 256 * 1024

// why: one rotate is rare (64 MB of turns) and per-second is unique enough that two rotates of the
// same file in one process cannot collide on the archived name.
private val ARCHIVE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

// why: archiveRetentionDays is a day count; the sweep compares epoch millis against a millis window.
private const val DAY_MS = 86_400_000L

public class PerfStats(
    private val file: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
    /** V4-133: where rolled-out generations are archived before JsonlSink overwrites them. Null
     *  (every construction site before this row, and every one this row did not touch) is today's
     *  exact behaviour — one generation, then discard. */
    private val archiveDir: Path? = null,
    private val archiveRetentionDays: Int = (Knob.PERF_ARCHIVE_RETENTION_DAYS.default as Long).toInt(),
    /** V4-133: the rotate threshold [record] appends against — JsonlSink's own default for every
     *  construction site this row did not touch, injectable so a test can force a rotation (and
     *  therefore the archive hook) without writing 64 MB of turns. */
    private val maxBytes: Long = JsonlSink.DEFAULT_MAX_BYTES,
) {
    private val archive: RotationArchive =
        if (archiveDir == null) JsonlSink.NO_ARCHIVE else RotationArchive { rolled -> archiveRolled(rolled) }

    private val unreadableLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    // V4-45: rows this reader DROPPED. A torn append leaves a length-extended NUL hole, the parse
    // fails, and the row used to vanish with no trace — on the COST path, so the operator's spend
    // read low by exactly those turns with nothing anywhere saying so. The control-plane reader
    // (PerfRowsFileSource) already counts its rejects; this one, which feeds V4-37's statusline
    // cost, did not. Counting is not enough on its own — an unread counter is the same silence —
    // so the first skip of an episode also logs once, the way the unreadable-file latch above does.
    private val skippedRows = java.util.concurrent.atomic.AtomicLong(0)

    private val skippedLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Rows dropped by [tailRows] since this instance was built, for a caller that renders cost and
     *  must say when that number is short. Monotonic: a healthy read does not reset it, because the
     *  rows it counted are still missing from every figure summed afterwards. */
    public fun skippedRowCount(): Long = skippedRows.get()

    private val json = Json { ignoreUnknownKeys = true }

    // append is best-effort by design: the turn builds an immutable row and the bounded file lane
    // owns filesystem latency.
    //
    // V4-134: RETURNS the row's `ts`, the only key the row has — /api/perf/turns reports each row
    // under it (PerfRoutes), so it is what the console's turn.end carries to join the stream to the
    // poll. Two rows of one head stamped in the same millisecond share it; that is the route's
    // existing key, and a new id here would be one the route could not look up.
    public fun record(meta: PerfRowMeta, snap: PerfSnapshot): Long {
        val ts = clock()
        val row = buildJsonObject {
            put("ts", ts)
            put("model", meta.model)
            put("outcome", meta.outcome)
            put("compact", meta.compact)
            meta.session?.let { put("session", it) }
            meta.cause?.let { put("cause", it) }
            if (meta.layers > 0) put("layers", meta.layers)
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
                JsonlSink.appendLine(file, row, maxBytes = maxBytes, archive = archive)
            }
        }
        return ts
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
                // ast-grep-ignore: kt-no-silent-result-collapse -- null is counted and logged by noteSkippedRow, PerfStats.kt:142 and :167
                val row = Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (row == null) noteSkippedRow()
                row
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

    /** Count a dropped row, and say so ONCE per episode. Once rather than per row because a badly
     *  torn file can drop thousands and a line each would bury the signal — the COUNT carries the
     *  magnitude and [skippedRowCount] hands it to whoever renders cost. The latch is keyed on the
     *  instance, not on the file, so a long-lived head logs at most one line however many holes it
     *  accumulates; that is the same trade the unreadable-file latch above already makes. */
    private fun noteSkippedRow() {
        val total = skippedRows.incrementAndGet()
        if (skippedLogged.compareAndSet(false, true)) {
            log(
                "[perf] $file has unreadable rows (first skip at this read, $total so far) — " +
                    "any figure summed from this file is LOW by those turns\n",
            )
        }
    }

    private fun numericFields(row: JsonObject): Map<String, Long> = buildMap {
        row.forEach { (k, v) ->
            (v as? JsonPrimitive)?.longOrNull?.let { put(k, it) }
        }
    }

    /** [JsonlSink.RotationArchive]'s hook: copy the generation JsonlSink is about to overwrite into
     *  [archiveDir], named so two rotates of the same file never collide, then sweep archived files
     *  past [archiveRetentionDays]. Runs on the file-IO lane already inside [Cancellables]'s guard
     *  (JsonlSink.rotateIfOver), so a failure here is silent by the SAME contract every other write
     *  in this class already accepts — the append it rides is never blocked by it. */
    private fun archiveRolled(rolled: Path) {
        val dir = archiveDir ?: return
        Files.createDirectories(dir)
        val stamp = ARCHIVE_STAMP.format(Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC))
        val target = dir.resolve("${file.fileName}-$stamp")
        Files.copy(rolled, target, StandardCopyOption.REPLACE_EXISTING)
        sweepArchive(dir)
    }

    /** Deletes archived generations older than [archiveRetentionDays], relative to now. Today
     *  counts as one of the kept days, the same convention [splice.core.activity.ActivityDays]
     *  uses for the console's stores. */
    private fun sweepArchive(dir: Path) {
        val oldest = clock() - archiveRetentionDays.coerceAtLeast(1) * DAY_MS
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { entry -> entry.fileName.toString().startsWith("${file.fileName}-") }
                .forEach { entry ->
                    if (Files.getLastModifiedTime(entry).toMillis() < oldest) Files.deleteIfExists(entry)
                }
        }
    }
}

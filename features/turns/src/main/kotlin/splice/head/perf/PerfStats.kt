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
// own machine. [archiveDir] (null = the one-generation rotate) is passed by production
// (ManagedHeadFactory, unless perfArchiveRetentionDays is 0) and, when set, each about-to-be-discarded
// `.1` is copied into it before JsonlSink overwrites it, named by PerfArchiveName so two rotates in
// one process never collide, and archived files past [archiveRetentionDays] are swept. Team and
// project tracking then reads a directory of whole rolled generations instead of one that is always
// about to lose its oldest.
package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.config.Knob
import splice.core.perf.InputDigest
import splice.core.perf.InputPrefix
import splice.core.perf.LivenessProbe
import splice.core.perf.PerfArchiveName
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTurn
import splice.core.perf.PerfSnapshot
import splice.core.perf.UpstreamMilestones
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.RotationArchive
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The string facts a perf row carries beside the numeric snapshot. */
public data class PerfRowMeta(
    val model: String,
    val outcome: String,
    val compact: Boolean,
    /** The client's session tag (first 8 of x-claude-code-session-id), so an abort or a stall in
     *  the perf log is attributable to ONE Claude Code session in a single grep (2026-09-02: seven
     *  client aborts in two hours could only be tied to sessions by cross-reading transcripts). */
    val session: String? = null,
    val account: PerfAccount = PerfAccount(),
    val failure: PerfFailure = PerfFailure(),
    val transcript: PerfTranscriptIds = PerfTranscriptIds(),
) {
    /** These optional string facts never enter the row's numeric snapshot. */
    internal fun putTranscriptFacts(into: JsonObjectBuilder) {
        transcript.sessionId?.let { into.put("session_id", it) }
        transcript.responseMessageId?.let { into.put("response_message_id", it) }
        transcript.turns.trace?.let { into.put("turn", it) }
        transcript.turns.request?.let { into.put("turn_id", it) }
    }
}

private const val DEFAULT_TAIL = 200

// why: bound in-memory sizing witnesses; a missing one passes through to the provider.
private const val MAX_PREFLIGHT_WITNESSES = 512

/** The measured prefix is a lower bound; one token per added text byte is the upper bound. */
internal data class InputEstimate(val lowerTokens: Long, val upperTokens: Long, val basis: String)

/** Latest observed upstream input for one session, conversation and model; no prompt bytes persist. */
internal class MeasuredInputs {
    private data class Key(val session: String, val conversation: String, val model: String)
    private data class Sample(val input: Long, val prefix: InputPrefix)

    private val samples = LinkedHashMap<Key, Sample>()
    private val lock = Any()

    fun remember(meta: PerfRowMeta, snap: PerfSnapshot, request: JsonObject?) {
        val key = meta.transcript.sessionId?.let { session ->
            meta.transcript.conversationKey?.let { conversation -> Key(session, conversation, meta.model) }
        }
        val sample = snap.counters[PerfKeys.IN_TOKENS]?.takeIf { it > 0 }?.let { input ->
            request?.let(InputDigest::capture)?.let { prefix -> Sample(input, prefix) }
        }
        if (key != null && sample != null) {
            synchronized(lock) {
                samples.remove(key)
                samples[key] = sample
                if (samples.size > MAX_PREFLIGHT_WITNESSES) samples.remove(samples.keys.first())
            }
        }
    }

    fun estimate(session: String?, conversation: String?, model: String, request: JsonObject): InputEstimate? {
        val key = if (session != null && conversation != null) Key(session, conversation, model) else null
        val measured = key?.let { synchronized(lock) { samples[it] } }
        val growth = measured?.prefix?.textGrowthBytes(request)
        return if (measured != null && growth != null) {
            InputEstimate(measured.input, measured.input + growth, "measured-text-prefix")
        } else {
            null
        }
    }
}

// ~256 KiB of trailing JSONL bounds parse cost regardless of file age.
private const val READ_TAIL_BYTES = 256 * 1024

// why: archiveRetentionDays is a day count; the sweep compares epoch millis against a millis window.
private const val DAY_MS = 86_400_000L

public class PerfStats(
    private val file: Path,
    /** Internal for the turn whose row waits on a streaming round: that row keeps the time its turn ended. */
    internal val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
    /** V4-133: where rolled-out generations are archived before JsonlSink overwrites them. Null
     *  (every construction site before this row, and every one this row did not touch) is today's
     *  exact behaviour — one generation, then discard. */
    private val archiveDir: Path? = null,
    private val archiveRetentionDays: Int = Knob.PERF_ARCHIVE_RETENTION_DAYS.count().toInt(),
    /** V4-133: the rotate threshold [record] appends against — JsonlSink's own default for every
     *  construction site this row did not touch, injectable so a test can force a rotation (and
     *  therefore the archive hook) without writing 64 MB of turns. */
    private val maxBytes: Long = JsonlSink.DEFAULT_MAX_BYTES,
    /** V4-244: each session's running total, fed by [record] with the row it appends and by nothing
     *  else, so a total is the sum of its session's rows. Null (every construction site but the
     *  managed head's) keeps none, and the status line reads the tail. Public for its readers (the
     *  status line's source, and the head's stop flush): a property read costs this class none of
     *  detekt's 15 functions, which a wrapper for each would. */
    public val totals: SessionTotals? = null,
) {
    private val archiveName = PerfArchiveName(file.fileName.toString())

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
    private val appendDrops = PerfAppendDrops(log)

    /** Count perf rows rejected by the bounded file-lane admission boundary. */
    public val droppedRowCount: Long get() = appendDrops.count

    /** Rows dropped by [tailRows] since this instance was built, for a caller that renders cost and
     *  must say when that number is short. Monotonic: a healthy read does not reset it, because the
     *  rows it counted are still missing from every figure summed afterwards. */
    public fun skippedRowCount(): Long = skippedRows.get()

    private val json = Json { ignoreUnknownKeys = true }
    internal val measuredInputs = MeasuredInputs()

    // append is best-effort by design: the turn builds an immutable row and the bounded file lane
    // owns filesystem latency.
    //
    // V4-134: RETURNS the row's `ts`, the only key the row has — /api/perf/turns reports each row
    // under it (PerfRoutes), so it is what the console's turn.end carries to join the stream to the
    // poll. Two rows of one head stamped in the same millisecond share it; that is the route's
    // existing key, and a new id here would be one the route could not look up.
    public fun record(meta: PerfRowMeta, snap: PerfSnapshot, request: JsonObject? = null, at: Long = clock()): Long {
        measuredInputs.remember(meta, snap, request)
        // A row held for a streaming round is appended late with the time its turn ended, so a row can follow
        // newer ones in the file: a reader that wants the newest rows orders them by ts, not by position.
        val ts = at
        val row = JsonWire.string(
            buildJsonObject {
                put("ts", ts)
                put("model", meta.model)
                put("outcome", meta.outcome)
                put("compact", meta.compact)
                meta.session?.let { put("session", it) }
                meta.putTranscriptFacts(this)
                meta.failure.cause?.let { put("cause", it) }
                if (meta.failure.layers > 0) put("layers", meta.failure.layers)
                meta.account.label?.let { account ->
                    put("account", account)
                    put("cache_cold", meta.account.cacheCold)
                }
                TransportTimings.putTransportTimings(this, snap)
                put(PerfKeys.RETRIES, snap.counters.getOrDefault(PerfKeys.RETRIES, 0L))
                (snap.marks.asSequence() + snap.counters.asSequence()).forEach { (k, v) -> put(k, v) }
                snap.upstreamGapEnd?.let { put(PerfKeys.UP_GAP_END, it.wire) }
            },
        )
        meta.session?.let { session ->
            Cancellables.discard(
                Cancellables.runCatchingCancellable { totals?.add(session, meta.model, snap.counters, ts) },
                "telemetry is best-effort; a turn must never fail on its session's running total",
            )
        }
        val accepted = AsyncFileIo.submitFor(file) {
            Cancellables.runCatchingCancellable {
                Files.createDirectories(file.parent)
                JsonlSink.appendLine(
                    file,
                    row,
                    maxBytes = maxBytes,
                    archive = archive,
                    force = JsonlSink.PAGE_CACHE_FORCE,
                )
                // The next successful row ends a prior explicit deletion, on the same file lane.
                Files.deleteIfExists(file.parent.resolve(TURN_STATS_DELETED_MARKER))
                Unit
            }.onFailure { appendDrops.writeFailed() }.getOrThrow()
        }
        appendDrops.accepted(accepted)
        return ts
    }

    /** Every row declares both transport pairs; unobserved boundaries are explicit JSON null. */
    private object TransportTimings {
        fun putTransportTimings(row: JsonObjectBuilder, snap: PerfSnapshot) {
            for (milestones in UpstreamMilestones.entries) {
                row.put(milestones.arrival, snap.counters[milestones.arrival])
                row.put(milestones.wait, snap.counters[milestones.wait])
            }
        }
    }

    /** Numeric fields of the last [tailN] rows, newest last — the aggregation input. */
    public fun tailNumeric(tailN: Int = DEFAULT_TAIL): List<Map<String, Long>> =
        tailRows().takeLast(tailN).map { numericFields(it) }

    /** EVERY row in the byte-bounded tail belonging to ONE client session, newest last, each with
     *  the model its turn recorded beside its numeric fields. No row cap on purpose: the read is
     *  already bounded by bytes, and a session's spend must not be truncated by a count that a long
     *  session would exceed.
     *
     *  V4-37: the statusline's cost segment replaces a per-SESSION number, so it must be summed from
     *  one session's rows. The row on disk stores the session TRUNCATED (SESSION_TAG_CHARS in
     *  TurnDrive.kt); this reader is the one place that knows it, so the caller passes the full id it
     *  holds and the truncation stays with the writer instead of being duplicated in another module.
     *  An empty [sessionId] matches nothing at all — an empty tag would otherwise `startsWith` every
     *  row in the file and quietly become a head-wide total.
     *
     *  V4-240 review: the model rides with each turn because a session can switch models, and each
     *  turn is billed at its own model's card (finding 4b). The tail's start rides along when the
     *  read could not hold the whole history (finding 4c), so a session older than it reads `≥`. */
    public fun sessionTail(sessionId: String): PerfSessionTail {
        if (sessionId.isEmpty()) return PerfSessionTail(emptyList(), null)
        val rows = tailRows()
        val turns = rows.filter { row -> belongsTo(row, sessionId) }
            .map { row -> PerfSessionTurn(modelOf(row), numericFields(row)) }
        val tailStart = if (historyBeyondTail()) rows.mapNotNull { tsOf(it) }.minOrNull() else null
        return PerfSessionTail(turns, tailStart)
    }

    /** Whether the perf history holds rows [tailRows] cannot reach: the file is past the byte bound,
     *  or a rolled generation keeps older ones. Without it the tail's start would be reported for a
     *  file read whole, and every fresh session, which always begins before its first row is written,
     *  would read as cut. Taken after the committed-tail read, without settling pending appends. A size
     *  `File.length` cannot stat is 0, so an unknown size claims no cut; an unreadable file is
     *  already said by [tailRows]. */
    private fun historyBeyondTail(): Boolean =
        Files.exists(file.resolveSibling("${file.fileName}.1")) || file.toFile().length() > READ_TAIL_BYTES

    private fun tsOf(row: JsonObject): Long? = (row["ts"] as? JsonPrimitive)?.longOrNull

    private fun modelOf(row: JsonObject): String? =
        (row["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** True when [row]'s stored tag is the truncated form of [sessionId]. */
    private fun belongsTo(row: JsonObject, sessionId: String): Boolean {
        val tag = sessionTagOf(row)?.takeIf { it.isNotEmpty() } ?: return false
        return sessionId.startsWith(tag)
    }

    private fun sessionTagOf(row: JsonObject): String? =
        (row["session"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // read is best-effort by design: a missing/corrupt file yields empty; a bad line is skipped.
    private fun tailRows(): List<JsonObject> {
        // Polled readers use only committed rows; the writer reports failures without a read barrier.
        // DR-60 (class law): only PROVEN absence — NoSuch with no NOFOLLOW entry — is the quiet
        // empty; an inaccessible perf log degrades the same but leaves a trace instead of a
        // silently-blank instrument.
        val rows = Cancellables.runCatchingCancellable {
            JsonlSink.readTail(file, READ_TAIL_BYTES).mapNotNull { line ->
                val row = JsonScalars.objectOrNull(json, line)
                if (row == null) noteSkippedRow()
                row?.takeUnless(LivenessProbe::legacyRow)
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
                log("[perf] $file unreadable (${SafeFailureText.render(failure)}); stats rendered empty\n")
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
                "[perf] $file has unreadable rows (first skip at this read, $total so far); " +
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
        val target = dir.resolve(archiveName.of(clock()))
        Files.copy(rolled, target, StandardCopyOption.REPLACE_EXISTING)
        sweepArchive(dir)
    }

    /** A perf-specific loss counter, separate from the lane's all-task drop count. */
    private class PerfAppendDrops(private val log: LogSink) {
        private val lost = AtomicLong()
        private val warned = AtomicBoolean(false)
        val count: Long get() = lost.get()

        fun accepted(yes: Boolean) {
            if (yes) {
                warned.set(false)
                return
            }
            val total = lost.incrementAndGet()
            if (warned.compareAndSet(false, true)) {
                report("[perf] file lane rejected a turn row ($total dropped so far); perf totals may read low\n")
            }
        }

        fun writeFailed() {
            if (warned.compareAndSet(false, true)) {
                report("[perf] file lane failed to write a turn row; perf totals may read low\n")
            }
        }

        private fun report(message: String) {
            Cancellables.discard(
                Cancellables.runCatchingBestEffort { log(message) },
                "perf loss reporting is best-effort; a broken log sink must not fail the turn",
            )
        }
    }

    /** Deletes archived generations older than [archiveRetentionDays], relative to now. Today
     *  counts as one of the kept days, the same convention [splice.core.storage.ActivityDays]
     *  uses for the console's stores. */
    private fun sweepArchive(dir: Path) {
        val oldest = clock() - archiveRetentionDays.coerceAtLeast(1) * DAY_MS
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { entry -> archiveName.rotatedAt(entry.fileName.toString()) != null }
                .forEach { entry ->
                    if (Files.getLastModifiedTime(entry).toMillis() < oldest) Files.deleteIfExists(entry)
                }
        }
    }
}

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
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
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
import splice.core.util.FileIoTask
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

private const val DEFAULT_TAIL = 200

// ~256 KiB of trailing JSONL bounds parse cost regardless of file age.
private const val READ_TAIL_BYTES = 256 * 1024

public class PerfStats(
    private val file: Path,
    /** Internal for the turn whose row waits on a streaming round: that row keeps the time its turn ended. */
    internal val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
    /** V4-133: where rolled-out generations are archived before JsonlSink overwrites them. Null
     *  (every construction site before this row, and every one this row did not touch) is today's
     *  exact behaviour — one generation, then discard. */
    private val archiveDir: Path? = null,
    /** How far back this install keeps its history, the person's one setting (Settings > Your
     *  data): the same window the hourly totals are kept over, because they are the same days read
     *  from two files. Defaults to what a fresh install writes. */
    private val kept: KeptHistory = KeptHistory { HistoryWindow(HISTORY_DEFAULT_DAYS) },
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
    /** Each session's newest ending, noted by [record] as the row is appended (SessionEndings): how Sessions
     *  and Teams learn a request was turned away at a limit or for want of a credential. Memory, seeded at boot from
     *  the file's tail so a restart does not turn a session at its limit into an idle one. */
    public val endings: SessionEndings = SessionEndings()

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
    private val lane = PerfLineAppend(file, maxBytes, archive, appendDrops)

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
    public fun record(
        meta: PerfRowMeta,
        snap: PerfSnapshot,
        request: JsonObject? = null,
        at: Long = clock(),
        whole: FileIoTask = FileIoTask { },
    ): Long {
        measuredInputs.remember(meta, snap, request)
        // A row held for a streaming round is appended late with the time its turn ended, so a row can follow
        // newer ones in the file: a reader that wants the newest rows orders them by ts, not by position.
        val ts = at
        val row = JsonWire.string(
            buildJsonObject {
                put("ts", ts)
                meta.model?.let { put("model", it) }
                put("outcome", meta.outcome)
                put("compact", meta.compact)
                meta.session?.let { put("session", it) }
                meta.putTranscriptFacts(this)
                meta.putFailureFacts(this)
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
            val reset = snap.counters[PerfKeys.EARLIEST_RESET_EPOCH_SECONDS]
            val retries = snap.counters.getOrDefault(PerfKeys.RETRIES, 0L)
            endings.note(session, SessionEnding(meta.outcome, meta.account.label, reset, ts, meta.model, retries))
            Cancellables.discard(
                Cancellables.runCatchingCancellable { meta.model?.let { totals?.add(session, it, snap.counters, ts) } },
                "telemetry is best-effort; a turn must never fail on its session's running total",
            )
        }
        lane.append(row, whole)
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
            .map { row -> PerfSessionTurn(modelOf(row), numericFields(row), tsOf(row)) }
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

    // why: `longOrNull` answers a value that is not a number by throwing and catching inside the parser, and a row is
    // mostly names, nulls and booleans; every status-line refresh paid a stack trace for each of them. A Long's text
    // starts with a digit or a minus sign, so anything else is skipped before the parser sees it.
    private fun numericFields(row: JsonObject): Map<String, Long> = buildMap {
        row.forEach { (k, v) ->
            val value = v as? JsonPrimitive ?: return@forEach
            val first = value.content.firstOrNull() ?: return@forEach
            if (first == '-' || first in '0'..'9') value.longOrNull?.let { put(k, it) }
        }
    }

    /** [JsonlSink.RotationArchive]'s hook: copy the generation JsonlSink is about to overwrite into
     *  [archiveDir], named so two rotates of the same file never collide, then sweep archived files
     *  past [window]. Runs on the file-IO lane already inside [Cancellables]'s guard
     *  (JsonlSink.rotateIfOver), so a failure here is silent by the SAME contract every other write
     *  in this class already accepts — the append it rides is never blocked by it. */
    private fun archiveRolled(rolled: Path) {
        val dir = archiveDir ?: return
        Files.createDirectories(dir)
        val target = dir.resolve(archiveName.of(clock()))
        Files.copy(rolled, target, StandardCopyOption.REPLACE_EXISTING)
        sweepArchive(dir)
    }

    /** One request-log line onto the file lane. */
    private class PerfLineAppend(
        private val file: Path,
        private val maxBytes: Long,
        private val archive: RotationArchive,
        private val appendDrops: PerfAppendDrops,
    ) {
        /** [whole] runs after the line is on disk, or at once when the lane refused the row. */
        fun append(row: String, whole: FileIoTask) {
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
                }.onFailure { appendDrops.writeFailed() }.also { whole() }.getOrThrow()
            }
            appendDrops.accepted(accepted)
            if (!accepted) whole()
        }
    }

    /** A perf-specific loss counter, separate from the lane's all-task drop count. */
    private class PerfAppendDrops(private val log: LogSink) {
        private val lost = AtomicLong()
        private val warned = AtomicBoolean(false)

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

    /** Deletes archived generations older than [window], relative to now. A generation's modified
     *  time is the moment it was archived, and rows are appended in time order, so a file older than
     *  the cutoff holds no row inside the window. A window that keeps everything sweeps nothing: the
     *  null cutoff returns before the scan. A window of zero keeps the generations rotated today,
     *  which is what a daily budget reads after a 64 MB roll, and drops them at midnight. */
    private fun sweepArchive(dir: Path) {
        val oldest = kept.now().cutoffMs(clock()) ?: return
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { entry -> archiveName.rotatedAt(entry.fileName.toString()) != null }
                .forEach { entry ->
                    if (Files.getLastModifiedTime(entry).toMillis() < oldest) Files.deleteIfExists(entry)
                }
        }
    }

    init {
        endings.restore(tailRows())
    }
}

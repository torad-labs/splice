// NEW: v0.4.0 (FEATURES.md §3): the perf JSONL with its outcome tags, both generations (`<file>.1`
// is the rotated one, 64 MiB each ≈ 20 days here), read as ONE coherent window. Appended rows decode
// once into a byte- and row-bounded compact cache; unchanged polls select those retained facts.
// Older evicted prefixes still stream on demand, skipping writer-shaped pre-cutoff rows as before.
// The parsed top-level numeric ts remains the only authority. Bytes decode with replacement
// (a torn multi-byte char makes one row unparseable, not the generation unreadable) and a
// replacement char inside an outcome makes that row unattributed rather than a new failure tag.
// A malformed line is skipped, never fatal; an absent generation is quiet; any other failure of
// the scan that produced the data is carried as the window's readError. A rotation during the
// read (the generations' file keys changed under it) is read again, once.
//
// ARCHIVED GENERATIONS (V4-133's archive, wired 2026-09-23): every generation a rotation retired is
// kept in [archiveDir] under PerfArchiveName, so the history the source reads reaches past `.1`.
// They are read first, oldest first, and one that ended a full second before the window's cutoff is
// skipped UNOPENED — its rotation second is an upper bound on every row inside — so a today-window
// never pays for months of archive. A skipped generation still counts as retention evidence: the
// files provably reach back to its rotation second, which is all the coverage check needs to tell
// "quiet" from "rotated away".
//
// THE NEWEST ROW (newestHeldTs, the summary's `last_ts`) is read across the same generations,
// whatever the window: every parsed row updates it, and when the window holds no row the few
// highest pre-cutoff lines skipped unparsed are parsed at the end, so an idle head still names its
// last turn without the scan parsing its history. An archived generation skipped unopened cannot
// hold it while `.1` holds any row: a rotation archives the old `.1` before the live file replaces it.
package splice.app.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.perf.LivenessProbe
import splice.core.perf.PerfArchiveName
import splice.core.perf.PerfKeys
import splice.core.perf.PerfTurnIds
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.usage.perf.PerfProjectionRead
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsProjection
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.PerfTranscriptLink
import splice.usage.perf.PerfTurnFacts
import splice.usage.perf.ProjectedPerfRowsSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private const val REPLACEMENT_CHAR = '\uFFFD'

/** Pre-cutoff counter samples kept; a run of more torn lines than this before the cutoff costs
 *  the baseline, not the window (JsonlSink heals a torn tail before the next append). */
private const val BASELINE_CANDIDATES = 4

/** Pre-cutoff lines skipped unparsed that are kept as the newest row's evidence, highest leading ts
 *  first; a run of more torn lines than this at the newest end costs `newestHeldTs` precision (it
 *  names an older valid row), never validity. */
private const val NEWEST_CANDIDATES = 4

/** A baseline sample: the counter already parsed, or the raw writer-shaped line still to parse. */
private data class Baseline(val drops: Long? = null, val raw: String? = null)

/** A pre-cutoff line skipped unparsed, ordered by its leading-ts [hint]; only its parse is a row. */
private data class Skipped(val hint: Long, val raw: String? = null, val ts: Long? = null)
private enum class PerfSelection { WORK, ECONOMICS }

/** One settled scan supplies the work and probe sides of historical economics reconciliation. */
internal data class EconomicsPerfEvidence(val work: PerfRowsWindow, val probes: List<PerfRow>)
private const val UNATTRIBUTED = "?"

// V4-127: the perf ROW HEADER keys — the writer's string-and-flag facts, spelled as PerfStats.record
// writes them. NOT PerfKeys members, because PerfKeys is the catalogue of marks and counters and these
// five are the row's header; the header keys are literals inside PerfStats.record, one module away.
// NAMED HERE rather than inlined five more times so that a key renamed on the write side reads as ONE
// place to look rather than five (the split is reported; the writer is outside this row's fence).
private const val MODEL_KEY = "model"
private const val SESSION_KEY = "session"
private const val SESSION_ID_KEY = "session_id"
private const val RESPONSE_ID_KEY = "response_message_id"
private const val ACCOUNT_KEY = "account"
private const val CACHE_COLD_KEY = "cache_cold"
private const val COMPACT_KEY = "compact"

// Trace lookup and request ownership stay separate, including on a head with capture off.
private const val TURN_KEY = "turn"
private const val REQUEST_TURN_KEY = "turn_id"

public class PerfRowsFileSource internal constructor(
    private val file: Path,
    /** Where PerfStats archives retired generations; null reads the two live ones only. */
    private val archiveDir: Path? = null,
    private val cache: PerfRowsCache = PerfRowsCache(),
    private val sessionAccounts: PerfSessionAccountIndex = PerfSessionAccountIndex(),
) : PerfRowsSource, ProjectedPerfRowsSource {
    private val json = Json { ignoreUnknownKeys = true }
    private var cacheSince: Long? = null

    /** Both views report the same single cache; no projection or display copy is retained. */
    internal val projectedBytes: Long get() = cache.retainedBytes

    /** JSON decodes performed by this source, including rejected rows and baseline candidates. */
    internal var parsedLines: Long = 0L
        private set

    internal val cachedBytes: Long get() = cache.retainedBytes
    internal val cachedLines: Int get() = cache.retainedRows

    private val archiveName = PerfArchiveName(file.fileName.toString())

    /** The writer's own row shape: ts first, unquoted. Only a rejection hint, never row authority. */
    private val ownRow = Regex("""^\{"ts":(\d+)[,}]""")

    /** A skipped line that carries the cumulative drops counter (any JSON spacing): a candidate. */
    private val dropsField = Regex(""""${PerfKeys.ASYNC_IO_DROPS}"\s*:\s*-?\d""")

    // Only empty-model candidates pay for parsing before the cutoff; JSON remains the authority.
    private val emptyModel = Regex(""""model"\s*:\s*""""")

    private val generations = listOf(file.resolveSibling("${file.fileName}.1"), file)

    @Synchronized
    override fun window(sinceMs: Long): PerfRowsWindow = settledRead(sinceMs, PerfSelection.WORK).window()

    /** Full session identity, never the shortened cost tag. Unchanged reads reuse a bounded latest-known index. */
    @Synchronized
    internal fun sessionAccount(session: String): String? = sessionAccounts(setOf(session)).accounts[session]

    @Synchronized
    internal fun sessionAccounts(sessions: Set<String>): PerfSessionAccountIndex.Snapshot {
        val scan = Scan(0L, PerfSelection.WORK, cache)
        val archives = archived(scan).map { it.first }
        if (scan.errors.isNotEmpty() || !AsyncFileIo.awaitFile(file)) {
            return PerfSessionAccountIndex.Snapshot(emptyMap(), false, emptySet())
        }
        return sessionAccounts.accounts(sessions, archives, generations, PerfLineDecode(scan::decode))
    }

    @Synchronized
    override fun <T> projected(sinceMs: Long, read: PerfProjectionRead<T>): T {
        val retained = cache
        return try {
            projectedRead(sinceMs, retained, read)
        } catch (_: PerfProjectionChanged) {
            // No response has escaped. Try once more, then use an already-complete coherent window.
            try {
                projectedRead(sinceMs, retained, read)
            } catch (_: PerfProjectionChanged) {
                val full = window(sinceMs)
                read(object : PerfRowsProjection {
                    override val window: PerfRowsWindow = full
                    override fun complete(rows: List<PerfRow>): List<PerfRow> = rows
                })
            }
        }
    }

    private fun <T> projectedRead(sinceMs: Long, retained: PerfRowsCache, read: PerfProjectionRead<T>): T {
        val scan = settledRead(sinceMs, PerfSelection.WORK, retained)
        val versions = retained.versions()
        val projection = object : PerfRowsProjection {
            override val window: PerfRowsWindow = scan.window()
            override fun complete(rows: List<PerfRow>): List<PerfRow> {
                if (versions.any { !it.coherent() }) throw PerfProjectionChanged()
                return rows
            }
        }
        val result = read(projection)
        if (versions.any { !it.coherent() }) throw PerfProjectionChanged()
        return result
    }

    /** Both sides are read together, so lost or clock-shifted evidence cannot justify subtraction. */
    @Synchronized
    internal fun economicsEvidence(sinceMs: Long): EconomicsPerfEvidence {
        val scan = settledRead(sinceMs, PerfSelection.ECONOMICS)
        return EconomicsPerfEvidence(scan.window(), scan.probes)
    }

    private fun settledRead(sinceMs: Long, selection: PerfSelection, rowsCache: PerfRowsCache = cache): Scan {
        if (cacheSince?.let { sinceMs < it } == true) rowsCache.clear()
        cacheSince = minOf(cacheSince ?: sinceMs, sinceMs)
        val settled = AsyncFileIo.awaitFile(file)
        var keys = fileKeys()
        var read = readAll(sinceMs, selection, rowsCache)
        var again = fileKeys()
        if (again != keys) {
            keys = again
            read = readAll(sinceMs, selection, rowsCache)
            again = fileKeys()
        }
        val incomplete = if (settled) null else "${file.fileName}: pending perf write did not settle"
        val rotated = if (again == keys) null else "${file.fileName}: rotated during the read"
        read.errors += listOfNotNull(incomplete, rotated)
        return read
    }

    private fun fileKeys(): List<Any?> = generations.map { generation ->
        // An absent generation has no fileKey; null is the normal reading and rotation is judged by comparing the SAME
        // two reads before and after, so a null on both passes correctly reports 'no rotation'.
        try { Files.getAttribute(generation, "fileKey") } catch (_: IOException) { null }
    }

    private fun readAll(sinceMs: Long, selection: PerfSelection, rowsCache: PerfRowsCache): Scan {
        val scan = Scan(sinceMs, selection, rowsCache)
        val archives = archived(scan)
        archives.forEachIndexed { priority, (generation, rotatedAt) ->
            if (archiveName.endsBefore(rotatedAt, sinceMs)) {
                scan.heldBack(rotatedAt)
            } else {
                read(scan, generation, priority)
            }
        }
        generations.forEachIndexed { priority, generation ->
            read(scan, generation, archives.size + priority)
        }
        return scan
    }

    private fun read(scan: Scan, generation: Path, priority: Int) {
        Cancellables.runCatchingCancellable {
            scan.rowsCache.read(
                generation,
                priority,
                scan,
                PerfLineDecode(scan::decode),
            )
        }
            .exceptionOrNull()
            ?.takeUnless { it is NoSuchFileException }
            ?.let { scan.errors += "${generation.fileName}: ${SafeFailureText.render(it)}" }
    }

    /** This file's archived generations with their rotation seconds, oldest first. No archive
     *  directory is quiet (nothing has rotated out yet, or the archive is off); any other failure to
     *  list it is a read error, because the history it holds may be the window's. */
    private fun archived(scan: Scan): List<Pair<Path, Long>> {
        val dir = archiveDir ?: return emptyList()
        val listed = Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.toList() } }
        listed.exceptionOrNull()?.takeUnless { it is NoSuchFileException }
            ?.let { scan.errors += "${dir.fileName}: ${SafeFailureText.render(it)}" }
        return listed.getOrDefault(emptyList())
            .mapNotNull { path -> archiveName.rotatedAt(path.fileName.toString())?.let { path to it } }
            .sortedBy { it.second }
    }

    private fun drops(obj: JsonObject): Long? = (obj[PerfKeys.ASYNC_IO_DROPS] as? JsonPrimitive)?.longOrNull

    private fun timestamp(obj: JsonObject): Long? =
        (obj["ts"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    /** The state one window read accumulates across generations, oldest generation first. */
    private inner class Scan(
        private val sinceMs: Long,
        private val selection: PerfSelection,
        val rowsCache: PerfRowsCache,
    ) : PerfLineVisit {
        val rows = ArrayList<PerfRow>()
        val probes = ArrayList<PerfRow>()
        val errors = ArrayList<String>()
        private var skipped = 0
        private var windowSkipped = 0
        private var afterInWindowRow = false

        /** The minimum valid top-level timestamp seen (physical order is not a retention premise). */
        private var oldest: Long? = null

        /** The maximum valid top-level timestamp PARSED, in or before the window. */
        private var newest: Long? = null

        /** The newest pre-cutoff counter samples, newest last: a parsed row contributes its parsed
         *  counter, a skipped writer-shaped line its raw text (parsed only if it is needed), so a
         *  torn or counter-less line never erases a sample and spacing never hides one. */
        private val before = ArrayDeque<Baseline>(BASELINE_CANDIDATES)

        /** The pre-cutoff lines skipped unparsed with the highest leading-ts hints, ascending: the
         *  newest row's evidence when the window itself holds none (a head idle past the window).
         *  Parsed only then, and only the parse is a row's time — a hint never is. */
        private val latest = ArrayList<Skipped>(NEWEST_CANDIDATES + 1)

        override fun beforeCutoff(line: String): Long? =
            if (afterInWindowRow || emptyModel.containsMatchIn(line)) null else provablyBefore(line)

        override fun canSkip(minimum: Long, maximum: Long): Boolean =
            !afterInWindowRow && (oldest?.let { minimum >= it && maximum < sinceMs } ?: false)

        override fun knownSpan(minimum: Long, maximum: Long) {
            oldest = minOf(oldest ?: minimum, minimum)
            if (maximum < sinceMs) {
                afterInWindowRow = false
                newest = maxOf(newest ?: maximum, maximum)
                latestCandidate(Skipped(maximum, ts = maximum))
            }
        }

        /** A writer-shaped line provably inside the held span and before the cutoff is skipped unparsed;
         *  every other line is parsed and its top-level unquoted ts decides where it goes. */
        override fun raw(line: String) {
            val hint = provablyBefore(line).takeUnless { afterInWindowRow }
            if (hint != null && !emptyModel.containsMatchIn(line)) {
                if (dropsField.containsMatchIn(line)) candidate(Baseline(raw = line))
                latestCandidate(Skipped(hint, line))
                return
            }
            kept(decode(line))
        }

        fun decode(line: String): PerfCachedLine = rowsCache.decoder.decode(line).fold(
            onSuccess = { decoded ->
                parsedLines++
                PerfCachedLine(
                    row = decoded.row,
                    numericBytes = decoded.numericBytes,
                    leadingTs = ownRow.find(line)?.groupValues?.get(1)?.toLongOrNull(),
                    emptyModel = emptyModel.containsMatchIn(line),
                    drops = PerfDropsHint(candidate = dropsField.containsMatchIn(line), count = decoded.drops),
                    probe = false,
                )
            },
            onFailure = { decodeTree(line) },
        )

        fun decodeTree(line: String): PerfCachedLine {
            val obj = parse(line)
            val ts = obj?.let(::timestamp)
            val facts = PerfCachedLine(
                row = null,
                numericBytes = 0L,
                leadingTs = ownRow.find(line)?.groupValues?.get(1)?.toLongOrNull(),
                emptyModel = emptyModel.containsMatchIn(line),
                drops = PerfDropsHint(candidate = dropsField.containsMatchIn(line), count = obj?.let(::drops)),
                probe = false,
            )
            if (obj == null || ts == null) return facts
            val fields = rowsCache.fields(obj)
            return facts.copy(
                row = row(ts, obj, fields),
                numericBytes = fields.retainedBytes,
                probe = LivenessProbe.legacyRow(obj),
            )
        }

        override fun kept(line: PerfCachedLine) {
            val row = line.row
            if (row == null) {
                rejected(line)
                return
            }
            afterInWindowRow = row.ts >= sinceMs
            if (skipBefore(line)) return
            oldest = minOf(oldest ?: row.ts, row.ts)
            if (row.ts < sinceMs) line.drops.count?.let { candidate(Baseline(drops = it)) }
            if (line.probe) {
                probe(row)
                return
            }
            newest = maxOf(newest ?: row.ts, row.ts)
            if (row.ts >= sinceMs) rows += row
        }

        private fun rejected(line: PerfCachedLine) {
            if (afterInWindowRow) windowSkipped++
            // Preserve the original baseline and economics proof, without giving this line a row time.
            if (!skipBefore(line)) skipped++
        }

        private fun skipBefore(line: PerfCachedLine): Boolean {
            val known = oldest
            val hint = line.row?.ts ?: line.leadingTs
            if (known == null || hint == null) return false
            val before = hint < sinceMs && hint >= known
            if (!before || line.emptyModel) return false
            if (line.drops.candidate) candidate(Baseline(drops = line.drops.count))
            latestCandidate(Skipped(hint, ts = line.row?.ts))
            return true
        }

        private fun probe(row: PerfRow) {
            if (row.ts >= sinceMs && selection == PerfSelection.ECONOMICS) probes += row
        }

        /** The leading ts of a writer-shaped line that is before the cutoff and not older than the
         *  retention evidence already parsed — it moves neither the oldest timestamp nor the window —
         *  or null when the line must be parsed. */
        private fun provablyBefore(line: String): Long? {
            val header = ownRow.find(line) ?: return null
            val canonical = line.endsWith("}") && !line.contains("\\u")
            if (!canonical || line.indexOf("\"ts\"", header.range.last + 1) >= 0) return null
            return oldest?.let { known ->
                header.groupValues[1].toLongOrNull()?.takeIf { it < sinceMs && it >= known }
            }
        }

        /** Keeps [sample] while it can still be the newest row: once the window holds a row, every
         *  pre-cutoff line is older than it, and a hint at or under a parsed ts cannot beat that ts. */
        private fun latestCandidate(sample: Skipped) {
            if (rows.isNotEmpty() || sample.hint <= (newest ?: Long.MIN_VALUE)) return
            val at = latest.indexOfFirst { it.hint > sample.hint }.takeIf { it >= 0 } ?: latest.size
            latest.add(at, sample)
            if (latest.size > NEWEST_CANDIDATES) latest.removeAt(0)
        }

        /** The newest valid row's ts: the parsed maximum, or — when the window is empty — the highest
         *  skipped line that PARSES, whichever is later. */
        private fun newestHeld(): Long? {
            if (rows.isNotEmpty()) return newest
            val unparsed = latest.asReversed().firstNotNullOfOrNull { it.ts ?: it.raw?.let(::parse)?.let(::timestamp) }
            return listOfNotNull(newest, unparsed).maxOrNull()
        }

        /** Only samples appended BEFORE the window's first row can be its baseline: a later line
         *  stamped before the cutoff (a clock step) was sampled after it. */
        private fun candidate(sample: Baseline) {
            if (rows.isNotEmpty()) return
            if (before.size == BASELINE_CANDIDATES) before.removeFirst()
            before.addLast(sample)
        }

        /** A generation skipped unread because it ended before the cutoff: the files provably hold rows
         *  from before [rotatedAt], so it is retention evidence without being a row. */
        fun heldBack(rotatedAt: Long) {
            oldest = minOf(oldest ?: rotatedAt, rotatedAt)
        }

        fun window(): PerfRowsWindow = PerfRowsWindow(
            rows = rows,
            oldestHeldTs = oldest,
            dropsBefore = before.asReversed().firstNotNullOfOrNull { it.drops ?: it.raw?.let(::parse)?.let(::drops) },
            readError = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            skipped = skipped,
            newestHeldTs = newestHeld(),
            windowSkipped = windowSkipped,
        )

        private fun parse(line: String): JsonObject? {
            parsedLines++
            // One malformed row in a live-appended JSONL is normal; whole-file read failures are already routed into
            // Scan.errors -> readError by readAll() above.
            return JsonScalars.objectOrNull(json, line)
        }

        private fun row(ts: Long, obj: JsonObject, fields: Map<String, Long> = rowsCache.fields(obj)): PerfRow {
            val outcome = JsonScalars.str(obj, "outcome")?.takeUnless { REPLACEMENT_CHAR in it } ?: UNATTRIBUTED
            // V4-127: the writer's string-and-flag facts, read BY NAME off the same parsed object the
            // numeric bag came from. Read by name, never by position, because four of the five are
            // nullable and two of the strings are adjacent — a positional read silently swaps them.
            return PerfRow(
                ts = ts,
                outcome = outcome,
                cause = text(obj, "cause"),
                fields = fields,
                facts = PerfTurnFacts(
                    model = text(obj, MODEL_KEY),
                    session = text(obj, SESSION_KEY),
                    account = text(obj, ACCOUNT_KEY),
                    cacheCold = (obj[CACHE_COLD_KEY] as? JsonPrimitive)?.booleanOrNull,
                    compact = (obj[COMPACT_KEY] as? JsonPrimitive)?.booleanOrNull,
                ),
                transcript = PerfTranscriptLink(
                    sessionId = text(obj, SESSION_ID_KEY),
                    responseMessageId = text(obj, RESPONSE_ID_KEY),
                ),
                turns = PerfTurnIds(trace = text(obj, TURN_KEY), request = text(obj, REQUEST_TURN_KEY)),
            )
        }

        /** A descriptive string field, ABSENT when the row does not carry it or carries it TORN.
         *  The replacement-char rule the outcome tag above already applies, for the same reason: a
         *  torn multi-byte char means the decoded text is not what was written, and a payload the
         *  operator is meant to trust never carries U+FFFD as if it were a value. Absent is the
         *  honest reading; a null and an empty string are different facts and stay different. */
        private fun text(obj: JsonObject, key: String): String? =
            JsonScalars.str(obj, key)?.takeUnless { REPLACEMENT_CHAR in it }
    }
}

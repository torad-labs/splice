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
import splice.app.sources.dense.PerfLineSpot
import splice.core.perf.LivenessProbe
import splice.core.perf.PerfArchiveName
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.usage.perf.PerfProjectedKeys
import splice.usage.perf.PerfProjectionRead
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsProjection
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.ProjectedPerfRowsSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private enum class PerfSelection { WORK, ECONOMICS }

/** One settled scan supplies the work and probe sides of historical economics reconciliation. [probeTears] holds, for
 *  each line the scan could not read as a row that might still be a legacy liveness probe, its leading timestamp (null
 *  where it names none): a line whose readable start does not show a model, since every probe row carries an empty one. */
internal data class EconomicsPerfEvidence(
    val work: PerfRowsWindow,
    val probes: List<PerfRow>,
    val probeTears: List<Long?>,
)

public class PerfRowsFileSource internal constructor(
    private val file: Path,
    /** Where PerfStats archives retired generations; null reads the two live ones only. */
    private val archiveDir: Path? = null,
    /** The whole rows the work and economics readers ask for, newest first under their own bound. */
    private val cache: PerfRowsCache = PerfRowsCache(PERF_FULL_TIER_BYTES),
    /** What the turns route filters and counts over a window: a compact row for every line, read back whole by spot. */
    private val compact: PerfRowsCache = PerfRowsCache(
        limitBytes = PERF_COMPACT_BYTES,
        keep = PerfCompactRetention(),
        limitRows = Int.MAX_VALUE,
        numeric = PerfProjectedKeys.numeric,
        dense = true,
    ),
    private val sessionAccounts: PerfSessionAccountIndex = PerfSessionAccountIndex(),
) : PerfRowsSource, ProjectedPerfRowsSource {
    private val json = Json { ignoreUnknownKeys = true }

    /** The compact cache the turns route reads; no display copy of a row is retained beyond it. */
    internal val projectedBytes: Long get() = compact.retainedBytes

    /** JSON decodes performed by this source, including rejected rows and baseline candidates. */
    internal var parsedLines: Long = 0L
        private set

    internal val cachedBytes: Long get() = cache.retainedBytes
    internal val cachedLines: Int get() = cache.retainedRows

    private val archiveName = PerfArchiveName(file.fileName.toString())

    private val generations = listOf(file.resolveSibling("${file.fileName}.1"), file)

    /** The files this source reads, for telling a later look at them from this one. */
    internal val files = PerfReadFiles(file, archiveDir, archiveName, generations)

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
        val retained = compact
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
        val whole = PerfWholeRows(scan.rows, scan.spots, PerfLineDecode(Scan(0L, PerfSelection.WORK, cache)::decode))
        val projection = object : PerfRowsProjection {
            override val window: PerfRowsWindow = scan.window()
            override fun complete(rows: List<PerfRow>): List<PerfRow> {
                if (versions.any { !it.coherent() }) throw PerfProjectionChanged()
                return whole.of(rows)
            }
        }
        val result = read(projection)
        if (versions.any { !it.coherent() }) throw PerfProjectionChanged()
        return result
    }

    /** Both sides are read together, so lost or clock-shifted evidence cannot justify subtraction. */
    @Synchronized
    internal fun economicsEvidence(sinceMs: Long): EconomicsPerfEvidence {
        val stamp = files.stamp()
        val scan = settledRead(sinceMs, PerfSelection.ECONOMICS)
        val evidence = EconomicsPerfEvidence(scan.window(), scan.probes, scan.probeTears)
        if (sharing > 0) shared = stamp?.let { SharedEvidence(sinceMs, it, evidence) }
        return evidence
    }

    /** The evidence from [sinceMs], whose rows may reach further back: for a reader that looks only at the hours
     *  from [sinceMs] on. Inside [sharingEvidence] it is the read an earlier ask of the same unchanged files already
     *  took, its probes and torn lines cut at [sinceMs] as that reader's own read would cut them. */
    @Synchronized
    internal fun economicsEvidenceSince(sinceMs: Long): EconomicsPerfEvidence {
        val earlier = shared?.takeIf { sharing > 0 && sinceMs >= it.sinceMs && files.unchanged(it.stamp) }
            ?: return economicsEvidence(sinceMs)
        val read = earlier.evidence
        return read.copy(
            probes = read.probes.filter { it.ts >= sinceMs },
            probeTears = read.probeTears.filter { it == null || it >= sinceMs },
        )
    }

    // why: one hourly-totals read asked the log three times (backfill, probe check, reprice), each from its own
    // oldest hour, and a week of whole rows outgrows the full tier, so each ask decoded the week again: two decodes of
    // every line in the startup scan (jfr on desk d, Oct 12). Held only while that one read runs, never between reads.
    // Until it is closed, each economics ask that tolerates an earlier cutoff is answered by the read before it.
    @Synchronized
    internal fun sharingEvidence(): AutoCloseable {
        sharing++
        return AutoCloseable { synchronized(this) { if (--sharing == 0) shared = null } }
    }

    private var sharing = 0
    private var shared: SharedEvidence? = null

    private data class SharedEvidence(
        val sinceMs: Long,
        val stamp: PerfSourceStamp,
        val evidence: EconomicsPerfEvidence,
    )

    private fun settledRead(sinceMs: Long, selection: PerfSelection, rowsCache: PerfRowsCache = cache): Scan {
        if (rowsCache.since?.let { sinceMs < it } == true) rowsCache.clear()
        rowsCache.since = minOf(rowsCache.since ?: sinceMs, sinceMs)
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

    /** The state one window read accumulates across generations, oldest generation first. */
    private inner class Scan(
        private val sinceMs: Long,
        private val selection: PerfSelection,
        val rowsCache: PerfRowsCache,
    ) : PerfLineVisit {
        val rows = ArrayList<PerfRow>()

        /** Where each of [rows] lies on disk, in step with it; null for a row a whole cache read. */
        val spots = ArrayList<PerfLineSpot?>()
        val probes = ArrayList<PerfRow>()
        val probeTears = ArrayList<Long?>()
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

        /** The pre-cutoff lines skipped unparsed with the highest leading-ts hints, ascending: the
         *  newest row's evidence when the window itself holds none (a head idle past the window).
         *  Parsed only then, and only the parse is a row's time — a hint never is. */
        private val held = PerfCandidates()
        private val rowShape = PerfRowShape()

        override fun beforeCutoff(line: String): Long? =
            if (afterInWindowRow || PerfLineHints.hasEmptyModel(line)) null else provablyBefore(line)

        override fun canSkip(minimum: Long, maximum: Long): Boolean =
            !afterInWindowRow && (oldest?.let { minimum >= it && maximum < sinceMs } ?: false)

        override fun knownSpan(minimum: Long, maximum: Long) {
            oldest = minOf(oldest ?: minimum, minimum)
            if (maximum < sinceMs) {
                afterInWindowRow = false
                newest = maxOf(newest ?: maximum, maximum)
                held.skipped(Skipped(maximum, ts = maximum), rows.isNotEmpty(), newest)
            }
        }

        /** A writer-shaped line provably inside the held span and before the cutoff is skipped unparsed;
         *  every other line is parsed and its top-level unquoted ts decides where it goes. */
        override fun raw(line: String) {
            val hint = provablyBefore(line).takeUnless { afterInWindowRow }
            if (hint != null && !PerfLineHints.hasEmptyModel(line)) {
                if (PerfLineHints.carriesDrops(line)) held.baseline(Baseline(raw = line), rows.isNotEmpty())
                held.skipped(Skipped(hint, line), rows.isNotEmpty(), newest)
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
                    leadingTs = PerfLineHints.ownRow.find(line)?.groupValues?.get(1)?.toLongOrNull(),
                    model = PerfLineHints.decodedModelOf(line),
                    drops = PerfLineHints.dropsOf(line, decoded.drops),
                    probe = false,
                )
            },
            onFailure = { decodeTree(line) },
        )

        fun decodeTree(line: String): PerfCachedLine {
            val obj = parse(line)
            val ts = obj?.let(PerfLineHints::tsIn)
            val facts = PerfCachedLine(
                row = null,
                numericBytes = 0L,
                leadingTs = PerfLineHints.ownRow.find(line)?.groupValues?.get(1)?.toLongOrNull(),
                model = PerfLineHints.modelOf(line),
                drops = PerfLineHints.dropsOf(line, obj?.let(PerfLineHints::dropsIn)),
                probe = false,
            )
            if (obj == null || ts == null) return facts
            val fields = rowsCache.fields(obj)
            return facts.copy(
                row = rowShape.row(ts, obj, fields),
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
            held.cutoffSample(line.drops.count, row.ts < sinceMs, rows.isNotEmpty())
            if (line.probe) {
                if (row.ts >= sinceMs && selection == PerfSelection.ECONOMICS) probes += row
                return
            }
            newest = maxOf(newest ?: row.ts, row.ts)
            if (row.ts >= sinceMs) {
                rows += row
                spots += line.spot
            }
        }

        private fun rejected(line: PerfCachedLine) {
            if (afterInWindowRow) windowSkipped++
            // Preserve the original baseline and economics proof, without giving this line a row time.
            if (!skipBefore(line)) {
                skipped++
                if (line.model != PerfModelHint.NAMED) probeTears += line.leadingTs
            }
        }

        private fun skipBefore(line: PerfCachedLine): Boolean {
            val known = oldest
            val hint = line.row?.ts ?: line.leadingTs
            if (known == null || hint == null) return false
            val before = hint < sinceMs && hint >= known
            if (!before || line.emptyModel) return false
            if (line.drops.candidate) held.baseline(Baseline(drops = line.drops.count), rows.isNotEmpty())
            held.skipped(Skipped(hint, ts = line.row?.ts), rows.isNotEmpty(), newest)
            return true
        }

        /** The leading ts of a writer-shaped line that is before the cutoff and not older than the
         *  retention evidence already parsed — it moves neither the oldest timestamp nor the window —
         *  or null when the line must be parsed. */
        private fun provablyBefore(line: String): Long? {
            val header = PerfLineHints.ownRow.find(line) ?: return null
            val canonical = line.endsWith("}") && !line.contains("\\u")
            if (!canonical || line.indexOf("\"ts\"", header.range.last + 1) >= 0) return null
            return oldest?.let { known ->
                header.groupValues[1].toLongOrNull()?.takeIf { it < sinceMs && it >= known }
            }
        }

        /** The newest valid row's ts: the parsed maximum, or — when the window is empty — the highest
         *  skipped line that PARSES, whichever is later. */
        private fun newestHeld(): Long? {
            if (rows.isNotEmpty()) return newest
            val unparsed = held.latest.asReversed().firstNotNullOfOrNull {
                it.ts ?: it.raw?.let(::parse)?.let(PerfLineHints::tsIn)
            }
            return listOfNotNull(newest, unparsed).maxOrNull()
        }

        /** A generation skipped unread because it ended before the cutoff: the files provably hold rows
         *  from before [rotatedAt], so it is retention evidence without being a row. */
        fun heldBack(rotatedAt: Long) {
            oldest = minOf(oldest ?: rotatedAt, rotatedAt)
        }

        fun window(): PerfRowsWindow = PerfRowsWindow(
            rows = rows,
            oldestHeldTs = oldest,
            dropsBefore = held.before.asReversed().firstNotNullOfOrNull {
                it.drops ?: it.raw?.let(::parse)?.let(PerfLineHints::dropsIn)
            },
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
    }
}

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
// A TORN LINE KEEPS ITS PLACE (Oct 23, 2026): each line skipped inside the window reaches the hourly totals with
// its leading ts and the rotation second of the archive it lies in (PerfTear), not only the ones that could be
// probes, so the backfill leaves out the hours a tear could belong to instead of refusing every hour.
package splice.app.sources

import kotlinx.serialization.json.Json
import splice.app.sources.dense.PerfLineSpot
import splice.core.perf.PerfArchiveName
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.usage.perf.PerfProjectedKeys
import splice.usage.perf.PerfProjectionRead
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsProjection
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.ProjectedPerfRowsSource
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: a row held for a streaming round is appended after newer ones with the time its turn ended (PerfStats.record);
// an hour covers that lag, so a torn line's own row is not older than the newest row ahead of it less this. It is a
// margin, not a bound: nothing caps the hold while the round keeps making progress (Watchdog caps only a silent one).
// The latest any row landed across 1.05 M rows of claudex and claude-muse was 11.4 min after its time (Oct 23, 2026).
private const val LATE_ROW_MS = 3_600_000L

public class PerfRowsFileSource internal constructor(
    private val file: Path,
    /** Where PerfStats archives retired generations; null reads the two live ones only. */
    private val archiveDir: Path? = null,
    /** The whole rows the work readers ask for, newest first under their own bound. */
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

    /** JSON decodes performed by this source, including rejected rows and baseline candidates. */
    internal var parsedLines: Long = 0L
        private set

    private val archiveName = PerfArchiveName(file.fileName.toString())

    private val generations = listOf(file.resolveSibling("${file.fileName}.1"), file)

    /** The files this source reads, for telling a later look at them from this one. */
    internal val files = PerfReadFiles(file, archiveDir, archiveName, generations)

    @Synchronized
    override fun window(sinceMs: Long): PerfRowsWindow = settledRead(sinceMs, PerfSelection.WORK).window()

    /** Full session identity, never the shortened cost tag. Unchanged reads reuse a bounded latest-known index. */
    @Synchronized
    internal fun sessionAccounts(sessions: Set<String>): PerfSessionAccountIndex.Snapshot {
        val scan = Scan(0L, PerfSelection.WORK, cache)
        val archives = files.archived(scan.errors).map { it.first }
        if (scan.errors.isNotEmpty() || !AsyncFileIo.awaitFile(file)) {
            return PerfSessionAccountIndex.Snapshot(emptyMap())
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

    /** Both sides are read together, so lost or clock-shifted evidence cannot justify subtraction. Read from the
     *  compact rows, which keep every figure an hour is rebuilt from (PerfProjectedKeys), so the window the warmup
     *  reads next from the oldest of those hours is already decoded: the startup scan decoded every line twice, whole
     *  for the totals and compact for the window (claude-splice's 445 MB file on desk d, Oct 12). */
    @Synchronized
    internal fun economicsEvidence(sinceMs: Long): EconomicsPerfEvidence {
        val stamp = files.stamp()
        val scan = settledRead(sinceMs, PerfSelection.ECONOMICS, compact)
        val evidence = EconomicsPerfEvidence(scan.window(), scan.probes, scan.tears)
        share.keep(sinceMs, stamp, evidence)
        return evidence
    }

    /** The evidence from [sinceMs], whose rows may reach further back: for a reader that looks only at the hours
     *  from [sinceMs] on. Inside [sharingEvidence] it is the read an earlier ask of the same unchanged files already
     *  took (see [EconomicsEvidenceShare]). */
    @Synchronized
    internal fun economicsEvidenceSince(sinceMs: Long): EconomicsPerfEvidence =
        share.since(sinceMs) ?: economicsEvidence(sinceMs)

    /** Opens one hourly-totals read, whose economics asks share one decode of the log until it is closed. */
    internal fun sharingEvidence(): AutoCloseable = share.open()

    private val share = EconomicsEvidenceShare(files)

    private fun settledRead(sinceMs: Long, selection: PerfSelection, rowsCache: PerfRowsCache = cache): Scan {
        if (rowsCache.since?.let { sinceMs < it } == true) rowsCache.clear()
        rowsCache.since = minOf(rowsCache.since ?: sinceMs, sinceMs)
        val settled = AsyncFileIo.awaitFile(file)
        var keys = files.keys()
        var read = readAll(sinceMs, selection, rowsCache)
        var again = files.keys()
        if (again != keys) {
            keys = again
            read = readAll(sinceMs, selection, rowsCache)
            again = files.keys()
        }
        val incomplete = if (settled) null else "${file.fileName}: pending perf write did not settle"
        val rotated = if (again == keys) null else "${file.fileName}: rotated during the read"
        read.errors += listOfNotNull(incomplete, rotated)
        return read
    }

    private fun readAll(sinceMs: Long, selection: PerfSelection, rowsCache: PerfRowsCache): Scan {
        val scan = Scan(sinceMs, selection, rowsCache)
        val archives = files.archived(scan.errors)
        archives.forEachIndexed { priority, (generation, rotatedAt) ->
            if (archiveName.endsBefore(rotatedAt, sinceMs)) {
                scan.heldBack(rotatedAt)
            } else {
                scan.enter(rotatedAt)
                read(scan, generation, priority)
            }
        }
        generations.forEachIndexed { priority, generation ->
            scan.enter(null)
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

        /** Every line inside the window this scan could not read as a row, with where it lies. */
        val tears = ArrayList<PerfTear>()

        /** The rotation second of the archived generation being read, or null while a live one is. */
        private var rotatedAt: Long? = null

        /** The newest row time ahead of this point in the generation being read. Each line is appended after the
         *  ones ahead of it, and a row's ts is when its turn ended, before its line was written. */
        private var newestAhead: Long? = null
        val errors = ArrayList<String>()
        private var skipped = 0
        private var afterInWindowRow = false

        /** The minimum valid top-level timestamp seen (physical order is not a retention premise). */
        private var oldest: Long? = null

        /** The newest pre-cutoff counter samples, newest last: a parsed row contributes its parsed
         *  counter, a skipped writer-shaped line its raw text (parsed only if it is needed), so a
         *  torn or counter-less line never erases a sample and spacing never hides one. */
        private val held = PerfCandidates()
        private val lines = PerfLineDecoder(rowsCache, json, ParseTally { parsedLines++ })

        override fun beforeCutoff(line: String): Long? =
            if (afterInWindowRow || PerfLineHints.hasEmptyModel(line)) null else provablyBefore(line)

        override fun canSkip(minimum: Long, maximum: Long): Boolean =
            !afterInWindowRow && (oldest?.let { minimum >= it && maximum < sinceMs } ?: false)

        /** Starts reading the generation archived at [rotated], or a live one when null. */
        fun enter(rotated: Long?) {
            rotatedAt = rotated
            newestAhead = null
        }

        override fun knownSpan(minimum: Long, maximum: Long) {
            oldest = minOf(oldest ?: minimum, minimum)
            newestAhead = maxOf(newestAhead ?: maximum, maximum)
            if (maximum < sinceMs) afterInWindowRow = false
        }

        /** A writer-shaped line provably inside the held span and before the cutoff is skipped unparsed;
         *  every other line is parsed and its top-level unquoted ts decides where it goes. */
        override fun raw(line: String) {
            val hint = provablyBefore(line).takeUnless { afterInWindowRow }
            if (hint != null && !PerfLineHints.hasEmptyModel(line)) {
                if (PerfLineHints.carriesDrops(line)) held.baseline(Baseline(raw = line), rows.isNotEmpty())
                return
            }
            kept(decode(line))
        }

        fun decode(line: String): PerfCachedLine = lines.decode(line)

        override fun kept(line: PerfCachedLine) {
            val row = line.row
            if (row == null) {
                rejected(line)
                return
            }
            afterInWindowRow = row.ts >= sinceMs
            newestAhead = maxOf(newestAhead ?: row.ts, row.ts)
            if (skipBefore(line)) return
            oldest = minOf(oldest ?: row.ts, row.ts)
            held.cutoffSample(line.drops.count, row.ts < sinceMs, rows.isNotEmpty())
            if (line.probe) {
                if (row.ts >= sinceMs && selection == PerfSelection.ECONOMICS) probes += row
                return
            }
            if (row.ts >= sinceMs) {
                rows += row
                spots += line.spot
            }
        }

        private fun rejected(line: PerfCachedLine) {
            // Preserve the original baseline and economics proof, without giving this line a row time.
            if (!skipBefore(line)) {
                skipped++
                tears += PerfTear(
                    line.leadingTs,
                    line.model == PerfModelHint.NAMED,
                    PerfTearSpan(newestAhead?.minus(LATE_ROW_MS), rotatedAt),
                )
            }
        }

        private fun skipBefore(line: PerfCachedLine): Boolean {
            val known = oldest
            val hint = line.row?.ts ?: line.leadingTs
            if (known == null || hint == null) return false
            val before = hint < sinceMs && hint >= known
            if (!before || line.emptyModel) return false
            if (line.drops.candidate) held.baseline(Baseline(drops = line.drops.count), rows.isNotEmpty())
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

        /** A generation skipped unread because it ended before the cutoff: the files provably hold rows
         *  from before [rotatedAt], so it is retention evidence without being a row. */
        fun heldBack(rotatedAt: Long) {
            oldest = minOf(oldest ?: rotatedAt, rotatedAt)
        }

        fun window(): PerfRowsWindow = PerfRowsWindow(
            rows = rows,
            oldestHeldTs = oldest,
            dropsBefore = held.before.asReversed().firstNotNullOfOrNull {
                it.drops ?: it.raw?.let(lines::parse)?.let(PerfLineHints::dropsIn)
            },
            readError = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            skipped = skipped,
        )
    }
}

// NEW: v0.4.0 (FEATURES.md §3): the perf JSONL with its outcome tags, both generations (`<file>.1`
// is the rotated one, 64 MiB each ≈ 20 days here), read as ONE coherent window. Reads STREAM: a
// generation is never held whole — the writer's own rows open with `{"ts":N,`, so a row that is
// provably before the cutoff is skipped unparsed; every other line is parsed, and the parsed
// top-level numeric ts is the only authority for a row's time. Bytes are decoded with replacement
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
import splice.core.perf.PerfArchiveName
import splice.core.perf.PerfKeys
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
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
private data class Skipped(val hint: Long, val raw: String)
private const val UNATTRIBUTED = "?"

// V4-127: the perf ROW HEADER keys — the writer's string-and-flag facts, spelled as PerfStats.record
// writes them. NOT PerfKeys members, because PerfKeys is the catalogue of marks and counters and these
// five are the row's header; the header keys are literals inside PerfStats.record, one module away.
// NAMED HERE rather than inlined five more times so that a key renamed on the write side reads as ONE
// place to look rather than five (the split is reported; the writer is outside this row's fence).
private const val MODEL_KEY = "model"
private const val SESSION_KEY = "session"
private const val ACCOUNT_KEY = "account"
private const val CACHE_COLD_KEY = "cache_cold"
private const val COMPACT_KEY = "compact"

/** What one pass over a generation's lines does (a lambda at the call site, never a stored seam). */
private fun interface LineScan {
    fun over(lines: Sequence<String>)
}

public class PerfRowsFileSource(
    private val file: Path,
    /** Where PerfStats archives this file's retired generations; null reads the two live ones only. */
    private val archiveDir: Path? = null,
) : PerfRowsSource {
    private val json = Json { ignoreUnknownKeys = true }

    private val archiveName = PerfArchiveName(file.fileName.toString())

    /** The writer's own row shape: ts first, unquoted. Only a rejection hint, never row authority. */
    private val ownRow = Regex("""^\{"ts":(\d+)[,}]""")

    /** A skipped line that carries the cumulative drops counter (any JSON spacing): a candidate. */
    private val dropsField = Regex(""""${PerfKeys.ASYNC_IO_DROPS}"\s*:\s*-?\d""")

    private val generations = listOf(file.resolveSibling("${file.fileName}.1"), file)

    override fun window(sinceMs: Long): PerfRowsWindow {
        var keys = fileKeys()
        var read = readAll(sinceMs)
        var again = fileKeys()
        if (again != keys) {
            keys = again
            read = readAll(sinceMs)
            again = fileKeys()
        }
        return if (again == keys) read.window() else read.window("${file.fileName}: rotated during the read")
    }

    private fun fileKeys(): List<Any?> = generations.map { generation ->
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): an absent generation has no fileKey; null is the normal reading and rotation is judged by comparing the SAME two reads before and after, so a null on both passes correctly reports 'no rotation'.
        Cancellables.runCatchingCancellable {
            Files.getAttribute(generation, "fileKey")
        }.getOrNull()
    }

    private fun readAll(sinceMs: Long): Scan {
        val scan = Scan(sinceMs)
        archived(scan).forEach { (generation, rotatedAt) ->
            if (archiveName.endsBefore(rotatedAt, sinceMs)) scan.heldBack(rotatedAt) else read(scan, generation)
        }
        generations.forEach { read(scan, it) }
        return scan
    }

    private fun read(scan: Scan, generation: Path) {
        Cancellables.runCatchingCancellable { stream(generation) { lines -> lines.forEach(scan::line) } }
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

    /** One streaming pass over the generation's lines through a replacing UTF-8 decoder. */
    private fun stream(path: Path, read: LineScan) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        BufferedReader(InputStreamReader(Files.newInputStream(path), decoder)).use { r -> read.over(r.lineSequence()) }
    }

    /** The state one window read accumulates across both generations, oldest generation first. */
    private inner class Scan(private val sinceMs: Long) {
        val rows = ArrayList<PerfRow>()
        val errors = ArrayList<String>()
        private var skipped = 0

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

        /** A writer-shaped line provably inside the held span and before the cutoff is skipped unparsed;
         *  every other line is parsed and its top-level unquoted ts decides where it goes. */
        fun line(line: String) {
            val hint = provablyBefore(line)
            if (hint != null) {
                if (dropsField.containsMatchIn(line)) candidate(Baseline(raw = line))
                latestCandidate(Skipped(hint, line))
                return
            }
            val obj = parse(line)
            val ts = obj?.let(::timestamp)
            if (obj == null || ts == null) {
                skipped += 1
                return
            }
            oldest = minOf(oldest ?: ts, ts)
            newest = maxOf(newest ?: ts, ts)
            if (ts >= sinceMs) rows += row(ts, obj) else drops(obj)?.let { candidate(Baseline(drops = it)) }
        }

        /** The leading ts of a writer-shaped line that is before the cutoff and not older than the
         *  retention evidence already parsed — it moves neither the oldest timestamp nor the window —
         *  or null when the line must be parsed. */
        private fun provablyBefore(line: String): Long? {
            val hint = ownRow.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val known = oldest ?: return null
            return hint.takeIf { it < sinceMs && it >= known }
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
            val unparsed = latest.asReversed().firstNotNullOfOrNull { parse(it.raw)?.let(::timestamp) }
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

        private fun drops(obj: JsonObject): Long? = (obj[PerfKeys.ASYNC_IO_DROPS] as? JsonPrimitive)?.longOrNull

        fun window(rotated: String? = null): PerfRowsWindow = PerfRowsWindow(
            rows = rows,
            oldestHeldTs = oldest,
            dropsBefore = before.asReversed().firstNotNullOfOrNull { it.drops ?: it.raw?.let(::parse)?.let(::drops) },
            readError = (errors + listOfNotNull(rotated)).takeIf { it.isNotEmpty() }?.joinToString("; "),
            skipped = skipped,
            newestHeldTs = newestHeld(),
        )

        private fun parse(line: String): JsonObject? =
            // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): one malformed row in a live-appended JSONL is normal; whole-file read failures are already routed into Scan.errors -> readError by readAll() above.
            Cancellables.runCatchingCancellable { json.parseToJsonElement(line) as? JsonObject }.getOrNull()

        private fun timestamp(obj: JsonObject): Long? =
            (obj["ts"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        private fun row(ts: Long, obj: JsonObject): PerfRow {
            val fields = buildMap {
                obj.forEach { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { n -> put(k, n) } }
            }
            val outcome = JsonScalars.str(obj, "outcome")?.takeUnless { REPLACEMENT_CHAR in it } ?: UNATTRIBUTED
            // V4-127: the writer's string-and-flag facts, read BY NAME off the same parsed object the
            // numeric bag came from. Read by name, never by position, because four of the five are
            // nullable and two of the strings are adjacent — a positional read silently swaps them.
            return PerfRow(
                ts = ts,
                outcome = outcome,
                fields = fields,
                model = text(obj, MODEL_KEY),
                session = text(obj, SESSION_KEY),
                account = text(obj, ACCOUNT_KEY),
                cacheCold = (obj[CACHE_COLD_KEY] as? JsonPrimitive)?.booleanOrNull,
                compact = (obj[COMPACT_KEY] as? JsonPrimitive)?.booleanOrNull,
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

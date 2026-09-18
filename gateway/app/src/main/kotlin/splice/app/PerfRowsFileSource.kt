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
package splice.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.control.PerfRowsWindow
import splice.core.perf.PerfKeys
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
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

/** A baseline sample: the counter already parsed, or the raw writer-shaped line still to parse. */
private data class Baseline(val drops: Long? = null, val raw: String? = null)
private const val UNATTRIBUTED = "?"

/** What one pass over a generation's lines does (a lambda at the call site, never a stored seam). */
private fun interface LineScan {
    fun over(lines: Sequence<String>)
}

public class PerfRowsFileSource(private val file: Path) : PerfRowsSource {
    private val json = Json { ignoreUnknownKeys = true }

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
        generations.forEach { generation ->
            Cancellables.runCatchingCancellable { stream(generation) { lines -> lines.forEach(scan::line) } }
                .exceptionOrNull()
                ?.takeUnless { it is NoSuchFileException }
                ?.let { scan.errors += "${generation.fileName}: ${SafeFailureText.render(it)}" }
        }
        return scan
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

        /** The newest pre-cutoff counter samples, newest last: a parsed row contributes its parsed
         *  counter, a skipped writer-shaped line its raw text (parsed only if it is needed), so a
         *  torn or counter-less line never erases a sample and spacing never hides one. */
        private val before = ArrayDeque<Baseline>(BASELINE_CANDIDATES)

        /** A writer-shaped line provably inside the held span and before the cutoff is skipped unparsed;
         *  every other line is parsed and its top-level unquoted ts decides where it goes. */
        fun line(line: String) {
            if (provablyBefore(line)) {
                if (dropsField.containsMatchIn(line)) candidate(Baseline(raw = line))
                return
            }
            val obj = parse(line)
            val ts = obj?.let(::timestamp)
            if (obj == null || ts == null) {
                skipped += 1
                return
            }
            oldest = minOf(oldest ?: ts, ts)
            if (ts >= sinceMs) rows += row(ts, obj) else drops(obj)?.let { candidate(Baseline(drops = it)) }
        }

        /** A writer-shaped line whose leading ts is before the cutoff and not older than the retention
         *  evidence already parsed: it moves neither the oldest timestamp nor the window. */
        private fun provablyBefore(line: String): Boolean {
            val hint = ownRow.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return false
            val known = oldest ?: return false
            return hint < sinceMs && hint >= known
        }

        /** Only samples appended BEFORE the window's first row can be its baseline: a later line
         *  stamped before the cutoff (a clock step) was sampled after it. */
        private fun candidate(sample: Baseline) {
            if (rows.isNotEmpty()) return
            if (before.size == BASELINE_CANDIDATES) before.removeFirst()
            before.addLast(sample)
        }

        private fun drops(obj: JsonObject): Long? = (obj[PerfKeys.ASYNC_IO_DROPS] as? JsonPrimitive)?.longOrNull

        fun window(rotated: String? = null): PerfRowsWindow = PerfRowsWindow(
            rows = rows,
            oldestHeldTs = oldest,
            dropsBefore = before.asReversed().firstNotNullOfOrNull { it.drops ?: it.raw?.let(::parse)?.let(::drops) },
            readError = (errors + listOfNotNull(rotated)).takeIf { it.isNotEmpty() }?.joinToString("; "),
            skipped = skipped,
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
            return PerfRow(ts, outcome, fields)
        }
    }
}

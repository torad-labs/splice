// Oct 25, 2026 — PerfStats archives a rotated `.1` by copying it before JsonlSink renames the live file over it, so the
// archive is a new file over bytes a long-lived source already holds under `.1`'s old identity. That source must answer
// what a cold read of the same files answers and hold no more than one: the copy answers from the rows held under
// `.1`, never from a second decode kept beside them. Driven through the real PerfStats rotation, which the older
// rotation tests are not (they rotate with Files.move).
package splice.app.sources

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.perf.PerfArchiveName
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.AsyncFileIo
import splice.head.perf.PerfAccount
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.usage.UsageHead
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.perf.PerfProjectedKeys
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private const val SEQUENCES = 20
private const val STEPS = 30
private const val MOST_ROWS_PER_STEP = 300

// A generation of 64 KiB holds a hundred rows or more, so a sequence rotates often and stays under the 64 generations
// a cache keeps identities for.
private const val GENERATION_BYTES = 64L * 1_024
private const val CACHED_GENERATIONS = 64
private const val ROW_MS = 60_000L
private const val START_MS = 1_800_000_000_000L

// The newest rows an answer shows whole: past the live file and `.1` into the archives, whose rows are read back by
// spot from wherever their generation now lies.
private const val SHOWN = 500

// Bookkeeping may differ between a source that read a generation in steps and one that read it at once. A generation
// held twice costs at least one 16 KiB dense chunk.
private const val SLACK_BYTES = 4L * 1_024

class PerfArchiveCopyTest {
    @Test
    fun `a long-lived source answers and holds what a cold read does across copied rotations`(@TempDir dir: Path) {
        for (seed in 1..SEQUENCES) sequence(Files.createDirectories(dir.resolve("sequence-$seed")), seed)
    }

    @Test
    fun `an archive of the same size with different bytes answers the rows it holds`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val rotated = dir.resolve("head-perf.jsonl.1")
        val archive = Files.createDirectory(dir.resolve("archive"))
        val tokens = (1L..8L).map { 1_000L + it }
        Files.write(rotated, (0 until 5).map { line(it, tokens[it]) })
        Files.write(file, (5 until 8).map { line(it, tokens[it]) })
        val route = PerfTurnsRoute(head(PerfRowsFileSource(file, archive, compact = compact())))
        assertEquals(8L, route.turns(START_MS, n = 20).getValue("count").jsonPrimitive.long, "`.1` and live are held")

        // The rotation by hand: an archive of `.1`'s size whose third row reads 1903 where `.1` reads 1003, then the
        // live file renamed over `.1` and a new live file for the row that rotated it.
        val changed = tokens.toMutableList().apply { this[2] = 1_903L }
        val archived = archive.resolve(PerfArchiveName(file.fileName.toString()).of(START_MS + 9 * ROW_MS))
        Files.write(archived, (0 until 5).map { line(it, changed[it]) })
        assertEquals(Files.size(rotated), Files.size(archived), "the archive is the size of the `.1` it replaces")
        Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING)
        Files.write(file, listOf(line(8, 1_009L)))

        val answer = route.turns(START_MS, n = 20)
        val cold = PerfTurnsRoute(head(PerfRowsFileSource(file, archive, compact = compact()))).turns(START_MS, n = 20)
        assertEquals(cold.toString(), answer.toString(), "the long-lived source answers what a cold read does")
        val shown = answer.getValue("rows").jsonArray.map { it.jsonObject.getValue("in_tokens").jsonPrimitive.long }
        assertEquals(changed + 1_009L, shown, "the archive's rows are the ones it holds")
    }

    /** One seeded run: steps of rows through PerfStats' own rotation, each read by one source that lives through all of
     *  them and by a cold one, from the first row on. */
    private fun sequence(dir: Path, seed: Int) {
        val random = Random(seed)
        val file = dir.resolve("head-perf.jsonl")
        val archive = dir.resolve("archive")
        // Every read of the clock is a minute after the last. PerfStats names an archive by its clock when the write
        // lane gets to the rotation, which can be after the loop has queued the whole step: a clock the loop advanced
        // could hand two rotations of one step the same minute, and the second copy would then replace the first
        // archive, which a daemon rotating 64 MiB generations never does.
        val ticks = AtomicLong(START_MS)
        val stats = PerfStats(
            file,
            clock = { ticks.addAndGet(ROW_MS) },
            archiveDir = archive,
            kept = KeptHistory { HistoryWindow(null) },
            maxBytes = GENERATION_BYTES,
        )
        val held = compact()
        val route = PerfTurnsRoute(head(PerfRowsFileSource(file, archive, compact = held)))
        var written = 0
        repeat(STEPS) { step ->
            repeat(random.nextInt(1, MOST_ROWS_PER_STEP + 1)) { stats.record(meta(written++), perf(random)) }
            assertTrue(AsyncFileIo.drain(), "the step's rows are on disk")
            val coldTier = compact()
            val cold = PerfTurnsRoute(head(PerfRowsFileSource(file, archive, compact = coldTier)))
                .turns(START_MS + ROW_MS, SHOWN)
            val answer = route.turns(START_MS + ROW_MS, SHOWN)
            val at = "seed $seed, step $step: $written rows, ${archived(archive)} archived generations"
            assertEquals(cold.toString(), answer.toString(), "the long-lived source answers what a cold read does, $at")
            assertTrue(
                held.retainedBytes <= coldTier.retainedBytes + SLACK_BYTES,
                "the long-lived source holds ${held.retainedBytes} bytes, a cold read ${coldTier.retainedBytes}, $at",
            )
        }
        val generations = archived(archive) + 2
        assertTrue(generations in 4 until CACHED_GENERATIONS, "seed $seed rotated into $generations generations")
    }

    private fun archived(archive: Path): Int =
        if (Files.isDirectory(archive)) Files.list(archive).use { it.count() }.toInt() else 0

    private fun meta(i: Int) = PerfRowMeta(
        model = "m${i % 3}",
        outcome = "ok",
        compact = i % 13 == 0,
        session = "s${i % 5}",
        account = PerfAccount(label = "a${i % 2}"),
    )

    private fun perf(random: Random) = TurnPerf { 0L }.apply {
        setCount(PerfKeys.IN_TOKENS, random.nextLong(1, 2_000_000))
        setCount(PerfKeys.OUT_TOKENS, random.nextLong(1, 5_000))
    }.snapshot()

    private fun line(i: Int, tokens: Long): String =
        """{"ts":${START_MS + (i + 1) * ROW_MS},"outcome":"ok","model":"m","session":"s1","in_tokens":$tokens}"""

    /** The compact tier a source keeps by default, passed in so its retained bytes can be read. */
    private fun compact() = PerfRowsCache(
        limitBytes = PERF_COMPACT_BYTES,
        keep = PerfCompactRetention(),
        limitRows = Int.MAX_VALUE,
        numeric = PerfProjectedKeys.numeric,
        dense = true,
    )

    private fun head(source: PerfRowsSource) = UsageHead(
        "synthetic",
        "claude-synthetic",
        HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(perfRows = source),
    )
}

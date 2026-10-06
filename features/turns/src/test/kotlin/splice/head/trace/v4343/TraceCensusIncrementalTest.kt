// NEW: V4-343 — the trace count kept between reads, as the console's route keeps it for the daemon's life. A day
// file only grows (JsonlSink appends, heals a torn tail by appending, rotates by rename), so a kept read counts
// only the bytes appended since the last one, and knows a file it counted by its first bytes and the last ones it
// counted, under whatever name the file has now; at every step a day file takes, it answers what a fresh read does.
// A count read on several lanes at once answers what one read on the calling thread does.
package splice.head.trace.v4343

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.DayFiles
import splice.head.trace.TraceAsk
import splice.head.trace.TraceCensus
import splice.head.trace.TraceRead
import splice.head.trace.TraceRows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val HEAD = "claudex"

// why: an attempt of about two kilobytes, so two whole turns cover the four kilobytes a file is known by at each
// end, and a turn written between them lies where only a read of every byte looks
private const val BODY_CHARS = 2048

// why: more turns than any store here holds, so the listing is every turn on disk
private const val LIST_ALL = 100

// why: 2026-09-27T00:00Z, the day the files are named for
private const val FIRST_TS = 1_790_467_200_000L

// why: records a second apart, as a turn's are
private const val SECOND = 1_000L

// why: the lines of a file's first two turns, an attempt and a turn record each: more than the first bytes a file is
// known by, kept as they were when it is written anew
private const val HEAD_LINES = 4

// why: more days than lanes, so each lane reads several files and their counts are added up together
private const val LANE_DAYS = 24

// why: turns enough per day that a tally the lanes shared would lose some: tens of thousands of adds at once
private const val TURNS_PER_DAY = 4000

// why: a machine of sixteen cores, whose quarter is the four lanes the count takes at most
private const val LANE_CORES = 16

class TraceCensusIncrementalTest {
    private var ts = FIRST_TS

    private fun attempt(turn: String): String {
        ts += SECOND
        return """{"kind":"attempt","turn":"$turn","ts":$ts,"session":"s1","attempt":1,""" +
            """"request":{"body":"${"x".repeat(BODY_CHARS)}"}}"""
    }

    private fun ending(turn: String): String {
        ts += SECOND
        return """{"kind":"turn","turn":"$turn","ts":$ts,"session":"s1","attempts":1,"outcome":"ok"}"""
    }

    /** Whole turns, an attempt and a turn record each, one record a line. */
    private fun turns(vararg ids: String): String = ids.joinToString("") { "${attempt(it)}\n${ending(it)}\n" }

    private fun day(dir: Path, date: String = "2026-09-27"): Path = dir.resolve("$HEAD-$date.jsonl")

    private fun rolled(file: Path): Path = file.resolveSibling("${file.fileName}.1")

    private fun append(file: Path, text: String) {
        val _ = Files.write(file, text.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** JsonlSink's rotation: the live file moved over its rolled half, the next append starting a new one. */
    private fun rotate(file: Path) {
        val _ = Files.move(file, rolled(file), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun read(rows: TraceRows, dir: Path): TraceRead = rows.read(dir, HEAD, TraceAsk(LIST_ALL))

    /** One byte of [file] changed in place, as no writer does: the `{` opening [turn]'s attempt made `[`, a line
     *  no read decodes, so a read that counts it again says one more skipped line. */
    private fun breakInPlace(file: Path, turn: String) {
        val text = Files.readString(file)
        val at = text.indexOf("""{"kind":"attempt","turn":"$turn"""")
        val _ = Files.writeString(file, text.substring(0, at) + "[" + text.substring(at + 1))
    }

    /** [file] written anew under its name: its first two turns' lines as they were, then other turns, longer. */
    private fun rewriteKeepingHead(file: Path) {
        val head = Files.readAllLines(file).take(HEAD_LINES).joinToString("") { "$it\n" }
        val _ = Files.writeString(file, head + turns("r1", "r2", "r3"))
    }

    /** [file] written anew under its name with its first line, an attempt of [from], made an attempt of [to] and
     *  every other byte as it was: one turn more on disk, which a count kept from before does not see. */
    private fun rewriteChangingHead(file: Path, from: String, to: String) {
        val lines = Files.readAllLines(file)
        val first = lines.first().replace("\"turn\":\"$from\"", "\"turn\":\"$to\"")
        val _ = Files.writeString(file, (listOf(first) + lines.drop(1)).joinToString("") { "$it\n" })
    }

    @Test
    fun `a kept read counts only the bytes appended since the last`(@TempDir dir: Path) {
        val file = day(dir)
        append(file, turns("t1", "t2", "mid", "t4", "t5"))
        val kept = TraceRows(heap = splice.head.syntheticHeapBudget())
        assertEquals(5, read(kept, dir).onDisk)
        breakInPlace(file, "mid")
        append(file, turns("t6"))

        val again = read(kept, dir)

        assertEquals(
            1,
            read(TraceRows(heap = splice.head.syntheticHeapBudget()), dir).skippedLines,
            "a fresh read reads every byte, mid's attempt too",
        )
        assertEquals(0, again.skippedLines, "the kept read went back over bytes it had counted")
        assertEquals(6, again.onDisk)
    }

    @Test
    fun `a rolled half is known again under its new name`(@TempDir dir: Path) {
        val file = day(dir)
        append(file, turns("t1", "t2", "mid", "t4", "t5"))
        val kept = TraceRows(heap = splice.head.syntheticHeapBudget())
        assertEquals(5, read(kept, dir).onDisk)
        rotate(file)
        breakInPlace(rolled(file), "mid")
        append(file, turns("t6"))

        val again = read(kept, dir)

        assertEquals(
            1,
            read(TraceRows(heap = splice.head.syntheticHeapBudget()), dir).skippedLines,
            "a fresh read reads every byte, mid's attempt too",
        )
        assertEquals(0, again.skippedLines, "the kept read counted the rolled half again under its new name")
        assertEquals(6, again.onDisk)
    }

    @Test
    fun `a kept read answers what a fresh read answers at every step a day file takes`(@TempDir dir: Path) {
        val kept = TraceRows(heap = splice.head.syntheticHeapBudget())
        val file = day(dir)
        val steps = listOf<Pair<String, () -> Unit>>(
            "no file yet" to {},
            "a file shorter than the bytes it is known by" to { append(file, ending("s1") + "\n") },
            "grown past them" to { append(file, turns("t1", "t2", "t3")) },
            "a torn tail" to { append(file, attempt("t4").take(BODY_CHARS / 2)) },
            "the torn tail healed onto a line of its own" to { append(file, "\n" + turns("t5")) },
            "a \\r ending the file, which a \\n may join" to { append(file, ending("t6") + "\r") },
            "the \\n that joins it" to { append(file, "\n" + turns("t7")) },
            "empty lines" to { append(file, "\n\n" + turns("t8")) },
            "rotated" to {
                rotate(file)
                append(file, turns("t9"))
            },
            "rotated again, over the rolled half" to {
                append(file, turns("t10"))
                rotate(file)
                append(file, turns("t11", "t12", "t13"))
            },
            "a new day" to { append(day(dir, "2026-09-28"), turns("t14")) },
            "written anew, its first bytes kept" to { rewriteKeepingHead(file) },
            "written anew, its first bytes changed and the last ones counted kept" to {
                rewriteChangingHead(file, "t11", "h11")
            },
            "purged" to {
                Files.delete(file)
                Files.delete(rolled(file))
            },
            "written again after the purge" to { append(file, turns("p1", "p2", "p3")) },
        )
        steps.forEach { (step, change) ->
            change()
            assertEquals(read(TraceRows(heap = splice.head.syntheticHeapBudget()), dir), read(kept, dir), step)
        }
    }

    /** A record of about seventy bytes, so many thousands of them pass through the lanes in a moment. */
    private fun small(turn: String): String = """{"kind":"attempt","turn":"$turn","ts":1,"attempt":1}"""

    @Test
    fun `a count on four lanes answers what one on the calling thread does, over many files of many turns`(
        @TempDir dir: Path,
    ) {
        // The lanes share nothing: a tally they shared would lose turns under this many at once.
        (1..LANE_DAYS).forEach { d ->
            val turns = (1..TURNS_PER_DAY).joinToString("") { n -> small("d$d-t$n") + "\n" }
            append(day(dir, "2026-08-%02d".format(d)), turns + "not a record\n")
        }
        val json = Json { ignoreUnknownKeys = true }
        val days = DayFiles(dir, HEAD)
        val none = DayFiles(Files.createDirectory(dir.resolve("empty")), HEAD)

        val calling = TraceCensus(json, processors = 1, heap = splice.head.syntheticHeapBudget()).count(days)

        assertEquals(TraceCensus.Count(LANE_DAYS * TURNS_PER_DAY, LANE_DAYS), calling)
        assertEquals(
            calling,
            TraceCensus(json, processors = LANE_CORES, heap = splice.head.syntheticHeapBudget()).count(days),
            "four lanes",
        )
        assertEquals(
            TraceCensus.Count(0, 0),
            TraceCensus(json, processors = LANE_CORES, heap = splice.head.syntheticHeapBudget()).count(none),
            "no days",
        )
    }
}

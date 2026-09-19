// NEW: PerfStats JSONL contract — record appends one row with ts/model/outcome/compact + the
// numeric snapshot; tailNumeric returns only numeric fields, newest last, bounded by tailN; a
// corrupt line is skipped, a missing file reads empty.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.gateway.perf.PerfRowMeta
import splice.gateway.perf.PerfStats
import java.nio.file.Files

class PerfStatsTest {

    @Test
    fun `record then tailNumeric roundtrips numeric fields`() {
        val tmp = Files.createTempDirectory("perf-stats")
        val stats = PerfStats(tmp.resolve("perf.jsonl"), clock = { 123L })
        val perf = TurnPerf { 0L }
        perf.setCount(PerfKeys.OUT_TOKENS, 850)
        perf.add(PerfKeys.FRAMES_OUT, 12)
        stats.record(PerfRowMeta(model = "gpt-5.6-sol", outcome = "ok", compact = false), perf.snapshot())

        val rows = stats.tailNumeric(10)
        assertEquals(1, rows.size)
        assertEquals(123L, rows[0]["ts"])
        assertEquals(850L, rows[0][PerfKeys.OUT_TOKENS])
        assertEquals(12L, rows[0][PerfKeys.FRAMES_OUT])
        // string fields (model/outcome) are not numeric and must not leak into aggregation input
        assertTrue("model" !in rows[0] && "outcome" !in rows[0])
    }

    @Test
    fun `a row names the client session when the turn carried one`() {
        val file = Files.createTempDirectory("perf-stats").resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 5L })
        val tagged = PerfRowMeta("m", "client_abort", compact = false, session = "a6b15bd7")
        stats.record(tagged, TurnPerf { 0L }.snapshot())
        stats.record(PerfRowMeta("m", "ok", compact = false), TurnPerf { 0L }.snapshot())
        val rows = stats.tailNumeric(10) // drains the writer
        assertEquals(2, rows.size)
        val lines = Files.readAllLines(file)
        assertTrue(lines[0].contains("\"session\":\"a6b15bd7\""), lines[0])
        assertTrue("session" !in lines[1], lines[1])
        assertTrue(lines.none { it.contains("cache_cold") }, "one-account rows stay byte-compatible: $lines")
        assertTrue("session" !in rows[0], "a string field never reaches the numeric aggregation input")
    }

    @Test
    fun `a switched turn records its account and cold cache`() {
        val file = Files.createTempDirectory("perf-stats").resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 7L })
        val meta = PerfRowMeta("m", "ok", compact = false, account = "backup", cacheCold = true)

        stats.record(meta, TurnPerf { 0L }.snapshot())
        stats.tailNumeric(10)

        val row = Files.readString(file)
        assertTrue(row.contains("\"account\":\"backup\""), row)
        assertTrue(row.contains("\"cache_cold\":true"), row)
    }

    @Test
    fun `tailNumeric bounds to tailN newest-last and skips corrupt lines`() {
        val tmp = Files.createTempDirectory("perf-stats")
        val file = tmp.resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 1L })
        repeat(5) { i ->
            val perf = TurnPerf { 0L }
            perf.setCount(PerfKeys.OUT_TOKENS, i.toLong())
            stats.record(PerfRowMeta("m", "ok", compact = false), perf.snapshot())
        }
        stats.tailNumeric(10) // drains the asynchronous writer before injecting a corrupt row
        Files.writeString(file, Files.readString(file) + "not-json\n")
        val rows = stats.tailNumeric(2)
        assertEquals(2, rows.size)
        assertEquals(3L, rows[0][PerfKeys.OUT_TOKENS])
        assertEquals(4L, rows[1][PerfKeys.OUT_TOKENS])
    }

    /** V4-45: a torn append is a length-extended NUL hole — what a write looks like when the file
     *  grew but its data never reached the disk before the process died. Measured on the operator's
     *  machine: 3 of 6 perf files, multi-hundred-byte zero runs, 5 rows of roughly 225000.
     *
     *  The rows must be DROPPED and COUNTED. This is the COST path (tailNumeric feeds the
     *  statusline spend), so a silent drop is a figure that reads low with nothing saying so — the
     *  same silent-undercount class this campaign keeps finding. The control-plane reader already
     *  counted its rejects; this one did not. */
    @Test
    fun `a NUL hole between two valid rows yields the two rows and reports one skipped`() {
        val file = Files.createTempDirectory("perf-stats").resolve("perf.jsonl")
        val logged = mutableListOf<String>()
        val stats = PerfStats(file, clock = { 7L }, log = { logged += it })
        val hole = "\u0000".repeat(300)
        Files.write(file, "{\"ts\":1,\"out_tokens\":10}\n$hole\n{\"ts\":2,\"out_tokens\":20}\n".toByteArray())

        val rows = stats.tailNumeric(10)

        assertEquals(2, rows.size, "the two valid rows must survive a torn row between them")
        assertEquals(10L, rows[0]["out_tokens"])
        assertEquals(20L, rows[1]["out_tokens"])
        assertEquals(1L, stats.skippedRowCount(), "the hole must be counted, not silently dropped")
        assertTrue(
            logged.any { it.contains("unreadable rows") },
            "a skipped row must be said out loud somewhere: $logged",
        )
    }

    /** Saying it once, not once per row: a badly torn file can drop thousands, and a line each would
     *  bury the signal the count carries. Both halves matter — the latch must not suppress the COUNT
     *  (the magnitude lives there) and must not repeat the LOG. */
    @Test
    fun `many torn rows are counted in full and logged only once`() {
        val file = Files.createTempDirectory("perf-stats").resolve("perf.jsonl")
        val logged = mutableListOf<String>()
        val stats = PerfStats(file, clock = { 7L }, log = { logged += it })
        val hole = "\u0000".repeat(64)
        // Trailing newline is REQUIRED, and the first draft of this test omitted it, which cost a
        // red: readTail does not count an unterminated final line, and that is correct rather than
        // incidental — an unterminated last line IS a torn append, so treating it as absent is the
        // same discipline as dropping a NUL hole.
        val body = (1..6).joinToString("\n") { "$hole\n{\"ts\":$it,\"out_tokens\":$it}" }
        Files.write(file, "$body\n".toByteArray())

        val rows = stats.tailNumeric(10)

        assertEquals(6, rows.size)
        assertEquals(6L, stats.skippedRowCount())
        assertEquals(1, logged.size, "one line for the episode, not one per row: $logged")
    }

    /** THE MONOTONIC HALF, and it is load-bearing rather than decorative: whatever renders cost reads
     *  [PerfStats.skippedRowCount] long after the read that skipped, so a counter a healthy read
     *  cleared would show the operator a clean figure on a file that still has holes in it. The
     *  production comment claims this property; nothing pinned it. Pins the log latch across reads at
     *  the same time — it is keyed on the INSTANCE, so a second episode of damage adds to the count
     *  and does NOT add a second line. */
    @Test
    fun `the count survives a healthy read and a second episode adds no second log line`() {
        val file = Files.createTempDirectory("perf-stats").resolve("perf.jsonl")
        val logged = mutableListOf<String>()
        val stats = PerfStats(file, clock = { 7L }, log = { logged += it })
        val hole = "\u0000".repeat(64)

        Files.write(file, "{\"ts\":1,\"out_tokens\":1}\n$hole\n".toByteArray())
        assertEquals(1, stats.tailNumeric(10).size)
        assertEquals(1L, stats.skippedRowCount())

        // An entirely healthy file now. The count must NOT clear: the row it counted is still
        // missing from every figure summed afterwards, so forgetting it is the silence again.
        Files.write(file, "{\"ts\":2,\"out_tokens\":2}\n".toByteArray())
        assertEquals(1, stats.tailNumeric(10).size)
        assertEquals(1L, stats.skippedRowCount(), "a healthy read must not clear a real undercount")

        Files.write(file, "{\"ts\":3,\"out_tokens\":3}\n$hole\n".toByteArray())
        stats.tailNumeric(10)
        assertEquals(2L, stats.skippedRowCount(), "a second episode adds to the count")
        assertEquals(1, logged.size, "one line per instance, not one per episode: $logged")
    }

    @Test
    fun `missing file reads empty`() {
        val tmp = Files.createTempDirectory("perf-stats")
        assertTrue(PerfStats(tmp.resolve("absent.jsonl")).tailNumeric(5).isEmpty())
    }
}

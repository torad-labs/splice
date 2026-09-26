// NEW: PerfStats JSONL contract — record appends one row with ts/model/outcome/compact + the
// numeric snapshot; tailNumeric returns only numeric fields, newest last, bounded by tailN; a
// corrupt line is skipped, a missing file reads empty.
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
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

    /** V4-240 review, findings 4b and 4c, at the reader. The cost segment prices each turn at the model
     *  it ran on, so the model rides with the counters; and the reader holds a byte-bounded tail, so it
     *  names where that tail starts, but ONLY when the read did not reach the start of the history. A
     *  session always begins before its first row is written, so a start reported for a file read
     *  whole would mark every fresh session's figure `≥` over nothing cut. */
    @Test
    fun `sessionTail carries each turn's model, and the tail's start only when history was cut`() {
        val file = Files.createTempDirectory("perf-stats").resolve("perf.jsonl")
        val session = "a6b15bd7"
        fun row(ts: Long, model: String, pad: Int = 0) =
            "{\"ts\":$ts,\"model\":\"$model\",\"outcome\":\"ok\",\"compact\":false," +
                "\"session\":\"$session\",\"in_tokens\":10,\"pad\":\"${"x".repeat(pad)}\"}\n"

        Files.writeString(file, row(100L, "claude-sonnet-5") + row(200L, "claude-opus-5-5"))
        val whole = PerfStats(file).sessionTail("$session-full-client-id")
        assertEquals(listOf("claude-sonnet-5", "claude-opus-5-5"), whole.turns.map { it.model })
        assertEquals(null, whole.tailStartMs, "the read held the whole history: nothing older to miss")

        Files.writeString(file.resolveSibling("perf.jsonl.1"), row(50L, "claude-sonnet-5"))
        assertEquals(100L, PerfStats(file).sessionTail(session).tailStartMs, "a rolled generation holds older rows")
        Files.delete(file.resolveSibling("perf.jsonl.1"))

        // 400 rows of about 1 KiB each: past the 256 KiB bound, so the read starts mid-file.
        Files.writeString(file, (1L..400L).joinToString("") { ts -> row(ts, "claude-opus-5-5", pad = 900) })
        val cut = PerfStats(file).sessionTail(session)
        val readTs = cut.turns.map { it.counters.getValue("ts") }
        assertTrue(cut.turns.size in 1 until 400, "the bound cut the file: ${cut.turns.size} rows read")
        assertEquals(readTs.min(), cut.tailStartMs, "the tail starts at the oldest row it read")
        assertEquals(400L, readTs.max())
    }

    /** V4-244. The tail above cuts a long session; the running total must not. [PerfStats.record] feeds
     *  it with the very row it appends, so the total is the sum of the session's rows by construction,
     *  and a session of any length is priced whole. */
    @Test
    fun `record feeds each session's running total with the row it appends, past the tail's bound`() {
        val dir = Files.createTempDirectory("perf-stats")
        val opus = "claude-opus-5-5"
        val price = TurnPrice(
            ModelCatalog(
                discoveryPrefix = "claude-anthropic--",
                models = listOf(ModelEntry(opus, contextWindow = 1_000_000, rates = ModelRates(5.00, 0.50, 25.00))),
                defaultContextWindow = 1_000_000,
                pinnedModel = opus,
            ),
        )
        var ts = 0L
        val totals = SessionTotals(dir.resolve("totals.json"), price, { 0L })
        val stats = PerfStats(dir.resolve("perf.jsonl"), clock = { ++ts }, totals = totals)
        fun turn(): PerfSnapshot = TurnPerf { 0L }.apply {
            setCount(PerfKeys.IN_TOKENS, 100_000)
            setCount(PerfKeys.OUT_TOKENS, 1_000)
            // about 1 KiB a row, so 400 rows pass the tail's 256 KiB bound
            (0 until 40).forEach { setCount("pad_%02d".format(it), 1_234_567_890_123L) }
        }.snapshot()

        repeat(400) { stats.record(PerfRowMeta(opus, "ok", compact = false, session = "a6b15bd7"), turn()) }
        stats.record(PerfRowMeta(opus, "ok", compact = false), turn())

        val session = "a6b15bd7-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
        assertTrue(stats.sessionTail(session).turns.size < 400, "the tail holds only part of the session")
        val total = totals.totalFor(session)!!.models.getValue(opus)
        assertEquals(400L, total.turns, "every row of the session, and not the one with no session")
        assertEquals(400 * price.usd(opus, turn().counters)!!, total.usd, 1e-9)
    }
}

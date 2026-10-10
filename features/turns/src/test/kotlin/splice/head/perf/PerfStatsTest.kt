// NEW: PerfStats JSONL contract — record appends one row with ts/model/outcome/compact + the
// numeric snapshot; tailNumeric returns only numeric fields, newest last, bounded by tailN; a
// corrupt line is skipped, a missing file reads empty.
package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamAttemptTiming
import splice.core.perf.UpstreamGapEnd
import splice.core.perf.WsAttemptTiming
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PerfStatsTest {

    @Test
    fun `every row keeps absent upstream write measurements null beside its retry count`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 123L })
        stats.record(PerfRowMeta("synthetic", "error:admission-full", compact = false), TurnPerf { 0L }.snapshot())
        assertTrue(AsyncFileIo.drain())
        val row = Json.parseToJsonElement(Files.readAllLines(file).single()).jsonObject
        assertEquals(JsonNull, row["arrival_to_upstream_write_ms"])
        assertEquals(JsonNull, row["upstream_write_to_first_byte_ms"])
        assertEquals(JsonNull, row["arrival_to_ws_send_accepted_ms"])
        assertEquals(JsonNull, row["ws_send_accepted_to_first_fragment_ms"])
        assertEquals(JsonNull, row[PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS])
        assertEquals(JsonNull, row[PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS])
        assertEquals(0L, row["retries"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `a websocket row reports acceptance and fragments with socket write times null`(@TempDir tmp: Path) {
        var now = 100L
        val perf = TurnPerf { now }
        UpstreamAttemptTiming(perf).also {
            it.written()
            it.headersStarted()
            it.headersDelivered()
            it.firstByte()
        }
        val ws = WsAttemptTiming(perf)
        now = 130L
        ws.sendAccepted()
        now = 140L
        ws.firstFragment()
        val file = tmp.resolve("perf.jsonl")
        PerfStats(file, clock = { 123L }).record(PerfRowMeta("synthetic", "ok", compact = false), perf.snapshot())
        assertTrue(AsyncFileIo.drain())
        val row = Json.parseToJsonElement(Files.readAllLines(file).single()).jsonObject
        assertEquals(JsonNull, row[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS])
        assertEquals(JsonNull, row[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS])
        assertEquals(JsonNull, row[PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS])
        assertEquals(JsonNull, row[PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS])
        assertEquals(30L, row[PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(10L, row[PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(0L, row[PerfKeys.RETRIES]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `a row persists header receipt and Ktor handoff on the arrival clock`(@TempDir tmp: Path) {
        var now = 100L
        val perf = TurnPerf { now }
        perf.recordArrival(70)
        val timing = UpstreamAttemptTiming(perf)
        now = 140
        timing.written()
        now = 150
        timing.headersStarted()
        now = 165
        timing.headersDelivered()
        now = 180
        timing.firstByte()
        val file = tmp.resolve("perf.jsonl")
        PerfStats(file, clock = { 123L }).record(PerfRowMeta("synthetic", "ok", compact = false), perf.snapshot())
        assertTrue(AsyncFileIo.drain())
        val row = Json.parseToJsonElement(Files.readAllLines(file).single()).jsonObject
        assertEquals(80L, row[PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(15L, row[PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(70L, row[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(40L, row[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `a retried row persists only the latest attempt with its retry count`(@TempDir tmp: Path) {
        var now = 100L
        val perf = TurnPerf { now }
        perf.recordArrival(70)
        val first = UpstreamAttemptTiming(perf)
        now = 140L
        first.written()
        now = 160L
        first.firstByte()
        perf.add(PerfKeys.RETRIES, 1)
        val last = UpstreamAttemptTiming(perf)
        now = 730L
        last.written()
        now = 760L
        last.firstByte()
        first.written()
        val file = tmp.resolve("perf.jsonl")
        PerfStats(file, clock = { 123L }).record(PerfRowMeta("synthetic", "ok", compact = false), perf.snapshot())
        assertTrue(AsyncFileIo.drain())
        val row = Json.parseToJsonElement(Files.readAllLines(file).single()).jsonObject
        assertEquals(660L, row[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(30L, row[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS]?.jsonPrimitive?.longOrNull)
        assertEquals(1L, row[PerfKeys.RETRIES]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `preflight anchors only a proven input prefix and marks weaker estimates`(@TempDir tmp: Path) {
        val stats = PerfStats(tmp.resolve("perf.jsonl"), clock = { 123L })
        fun body(text: String) = Json.parseToJsonElement(text).jsonObject
        val previous = body("""{"model":"m","input":[{"role":"user","content":"a"}]}""")
        val appended = body("""{"model":"m","input":[{"role":"user","content":"a"},{"role":"user","content":"b"}]}""")
        val rewritten = body("""{"model":"m","input":[{"role":"user","content":"b"}]}""")
        val shrunk = body("""{"model":"m","input":[]}""")
        val perf = TurnPerf { 0L }
        perf.setCount(PerfKeys.IN_TOKENS, 200)
        stats.record(
            PerfRowMeta(
                "m",
                "ok",
                compact = false,
                transcript = PerfTranscriptIds(sessionId = "session", conversationKey = "first"),
            ),
            perf.snapshot(),
            previous,
        )
        val expected = 200 + appended.toString().toByteArray().size - previous.toString().toByteArray().size
        val estimated = requireNotNull(stats.measuredInputs.estimate("session", "first", "m", appended))
        assertEquals(200L, estimated.lowerTokens)
        assertEquals(expected.toLong(), estimated.upperTokens)
        assertEquals("measured-text-prefix", estimated.basis)
        val lite = body(
            """{"model":"m","input":[{"role":"user","content":"a"},""" +
                """{"role":"assistant","content":"thinking","phase":"commentary"}]}""",
        )
        assertTrue(
            stats.measuredInputs.estimate("session", "first", "m", lite) != null,
            "Codex lite assistant phase preserves a text-only measured prefix",
        )
        for (candidate in listOf(rewritten, shrunk)) {
            assertNull(stats.measuredInputs.estimate("session", "first", "m", candidate))
        }
        assertNull(stats.measuredInputs.estimate("session", "fork", "m", appended))
        val image = body(
            """{"model":"m","input":[{"role":"user","content":"a"},""" +
                """{"role":"user","content":[{"type":"image",""" +
                """"source":{"type":"base64","data":"${"x".repeat(500_000)}"}}]}]}""",
        )
        assertNull(
            stats.measuredInputs.estimate("session", "first", "m", image),
            "a base64 image has no safe token bound from its encoded byte length",
        )
    }

    @Test
    fun `a rejected perf append is counted and logged once while saturated`(@TempDir tmp: Path) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val notices = mutableListOf<String>()
        val accepted = AtomicInteger()
        val finished = Semaphore(0)
        assertTrue(
            AsyncFileIo.submit {
                started.countDown()
                release.await()
            },
        )
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(2_048) {
                if (AsyncFileIo.submit { finished.release() }) accepted.incrementAndGet()
            }
            val file = tmp.resolve("perf.jsonl")
            val stats = PerfStats(file, clock = { 123L }, log = {
                notices += it
                error("synthetic log sink failure")
            })
            val snapshot = TurnPerf { 0L }.snapshot()
            val laneDropsBefore = AsyncFileIo.droppedCount()
            repeat(2) { stats.record(PerfRowMeta("m", "ok", compact = false), snapshot) }
            assertEquals(2L, stats.droppedRowCount)
            assertEquals(laneDropsBefore + 2, AsyncFileIo.droppedCount())
            assertEquals(1, notices.count { "file lane rejected a turn row" in it })
            assertTrue(!AsyncFileIo.awaitFile(file), "the latest rejected row is not settled")
        } finally {
            release.countDown()
        }
        assertTrue(finished.tryAcquire(accepted.get(), 10, TimeUnit.SECONDS), "accepted tasks must finish")
    }

    @Test
    fun `measured rows carry timing fields while unmeasured rows omit them`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 123L })
        val perf = TurnPerf { 0L }
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 2_500, UpstreamGapEnd.THINKING_DELTA)
        perf.add(PerfKeys.UP_GAPS_2S, 1)
        for (key in listOf(PerfKeys.UP_BLOCKED_MAX_MS, PerfKeys.OUT_HOLD_MAX_MS, PerfKeys.OUT_GAP_MAX_MS)) {
            perf.maxCount(key, 0)
        }
        stats.record(PerfRowMeta("synthetic", "ok", compact = false), perf.snapshot())
        stats.record(PerfRowMeta("synthetic", "ok", compact = false), TurnPerf { 0L }.snapshot())
        assertTrue(AsyncFileIo.drain())
        val numeric = stats.tailNumeric(10)
        assertEquals(2_500L, numeric[0][PerfKeys.UP_GAP_MAX_MS])
        assertEquals(1L, numeric[0][PerfKeys.UP_GAPS_2S])
        for (key in listOf(PerfKeys.UP_BLOCKED_MAX_MS, PerfKeys.OUT_HOLD_MAX_MS, PerfKeys.OUT_GAP_MAX_MS)) {
            assertEquals(0L, numeric[0][key])
        }
        assertTrue(PerfKeys.UP_GAP_END !in numeric[0])
        val rows = Files.readAllLines(file).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("\"thinking_delta\"", rows[0][PerfKeys.UP_GAP_END].toString())
        assertNull(rows[1][PerfKeys.UP_GAP_END])
        assertNull(numeric[1][PerfKeys.UP_GAP_MAX_MS])
    }

    @Test
    fun `interval starts persist beside the winning maxima and stay absent when unobserved`(@TempDir tmp: Path) {
        val stats = PerfStats(tmp.resolve("perf.jsonl"), clock = { 2_000_000 })
        val perf = TurnPerf(clock = ElapsedClock { 5_000 }, wallClock = WallClock { 1_000_000 })
        for ((key, startKey) in listOf(
            PerfKeys.UP_GAP_MAX_MS to PerfKeys.UP_GAP_MAX_START_EPOCH_MS,
            PerfKeys.OUT_HOLD_MAX_MS to PerfKeys.OUT_HOLD_MAX_START_EPOCH_MS,
            PerfKeys.UP_WIRE_GAP_MAX_MS to PerfKeys.UP_WIRE_GAP_MAX_START_EPOCH_MS,
            PerfKeys.UP_READ_WAIT_MAX_MS to PerfKeys.UP_READ_WAIT_MAX_START_EPOCH_MS,
            PerfKeys.UP_READ_IDLE_MAX_MS to PerfKeys.UP_READ_IDLE_MAX_START_EPOCH_MS,
        )) {
            perf.intervals.record(key, 10, 30)
            stats.record(PerfRowMeta("synthetic", "ok", compact = false), perf.snapshot())
            stats.record(PerfRowMeta("synthetic", "ok", compact = false), TurnPerf { 0L }.snapshot())
            assertTrue(AsyncFileIo.drain())
            val rows = stats.tailNumeric(2)
            assertEquals(20L, rows[0][key])
            assertEquals(1_000_010L, rows[0][startKey])
            assertNull(rows[1][key])
            assertNull(rows[1][startKey])
        }
    }

    @Test
    fun `record then tailNumeric roundtrips numeric fields`(@TempDir tmp: Path) {
        val stats = PerfStats(tmp.resolve("perf.jsonl"), clock = { 123L })
        val perf = TurnPerf { 0L }
        perf.setCount(PerfKeys.OUT_TOKENS, 850)
        perf.add(PerfKeys.FRAMES_OUT, 12)
        stats.record(PerfRowMeta(model = "gpt-5.6-sol", outcome = "ok", compact = false), perf.snapshot())
        assertTrue(AsyncFileIo.drain())

        val rows = stats.tailNumeric(10)
        assertEquals(1, rows.size)
        assertEquals(123L, rows[0]["ts"])
        assertEquals(850L, rows[0][PerfKeys.OUT_TOKENS])
        assertEquals(12L, rows[0][PerfKeys.FRAMES_OUT])
        // string fields (model/outcome) are not numeric and must not leak into aggregation input
        assertTrue("model" !in rows[0] && "outcome" !in rows[0])
    }

    @Test
    fun `a row names the client session when the turn carried one`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 5L })
        val tagged = PerfRowMeta("m", "client_abort", compact = false, session = "a6b15bd7")
        stats.record(tagged, TurnPerf { 0L }.snapshot())
        stats.record(PerfRowMeta("m", "ok", compact = false), TurnPerf { 0L }.snapshot())
        assertTrue(AsyncFileIo.drain())
        val rows = stats.tailNumeric(10)
        assertEquals(2, rows.size)
        val lines = Files.readAllLines(file)
        assertTrue(lines[0].contains("\"session\":\"a6b15bd7\""), lines[0])
        assertTrue("session" !in lines[1], lines[1])
        assertTrue(lines.none { it.contains("cache_cold") }, "one-account rows stay byte-compatible: $lines")
        assertTrue("session" !in rows[0], "a string field never reaches the numeric aggregation input")
    }

    @Test
    fun `a switched turn records its account and cold cache`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 7L })
        val meta = PerfRowMeta("m", "ok", compact = false, account = PerfAccount("backup", cacheCold = true))

        stats.record(meta, TurnPerf { 0L }.snapshot())
        assertTrue(AsyncFileIo.drain())

        val row = Files.readString(file)
        assertTrue(row.contains("\"account\":\"backup\""), row)
        assertTrue(row.contains("\"cache_cold\":true"), row)
    }

    @Test
    fun `tailNumeric bounds to tailN newest-last and skips corrupt lines`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        val stats = PerfStats(file, clock = { 1L })
        repeat(5) { i ->
            val perf = TurnPerf { 0L }
            perf.setCount(PerfKeys.OUT_TOKENS, i.toLong())
            stats.record(PerfRowMeta("m", "ok", compact = false), perf.snapshot())
        }
        assertTrue(AsyncFileIo.drain()) // settle the writer explicitly before injecting a corrupt row
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
    fun `a NUL hole between two valid rows yields the two rows and reports one skipped`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("perf.jsonl")
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
    fun `many torn rows are counted in full and logged only once`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("perf.jsonl")
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
    fun `the count survives a healthy read and a second episode adds no second log line`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("perf.jsonl")
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
    fun `missing file reads empty`(@TempDir tmp: Path) {
        assertTrue(PerfStats(tmp.resolve("absent.jsonl")).tailNumeric(5).isEmpty())
    }

    /** V4-240 review, findings 4b and 4c, at the reader. The cost segment prices each turn at the model
     *  it ran on, so the model rides with the counters; and the reader holds a byte-bounded tail, so it
     *  names where that tail starts, but ONLY when the read did not reach the start of the history. A
     *  session always begins before its first row is written, so a start reported for a file read
     *  whole would mark every fresh session's figure `≥` over nothing cut. */
    @Test
    fun `sessionTail carries each turn's model, and the tail's start only when history was cut`(
        @TempDir tempDir: Path,
    ) {
        val file = tempDir.resolve("perf.jsonl")
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
    fun `record feeds each session's running total with the row it appends, past the tail's bound`(@TempDir dir: Path) {
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
            setCount(PerfKeys.CACHED_TOKENS, 0)
            setCount(PerfKeys.CACHE_WRITE_TOKENS, 0)
            // about 1 KiB a row, so 400 rows pass the tail's 256 KiB bound
            (0 until 40).forEach { setCount("pad_%02d".format(it), 1_234_567_890_123L) }
        }.snapshot()

        repeat(400) { stats.record(PerfRowMeta(opus, "ok", compact = false, session = "a6b15bd7"), turn()) }
        stats.record(PerfRowMeta(opus, "ok", compact = false), turn())
        assertTrue(AsyncFileIo.drain())

        val session = "a6b15bd7-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
        assertTrue(stats.sessionTail(session).turns.size < 400, "the tail holds only part of the session")
        val total = totals.totalFor(session)!!.models.getValue(opus)
        assertEquals(400L, total.turns, "every row of the session, and not the one with no session")
        assertEquals(400 * price.usd(opus, turn().counters)!!, total.usd, 1e-9)
        assertEquals(null, price.usd(opus, mapOf(PerfKeys.IN_TOKENS to 100_000L, PerfKeys.OUT_TOKENS to 1_000L)))
    }
}

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
        val stats = PerfStats(tmp.resolve("perf.jsonl")) { 123L }
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
    fun `tailNumeric bounds to tailN newest-last and skips corrupt lines`() {
        val tmp = Files.createTempDirectory("perf-stats")
        val file = tmp.resolve("perf.jsonl")
        val stats = PerfStats(file) { 1L }
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

    @Test
    fun `missing file reads empty`() {
        val tmp = Files.createTempDirectory("perf-stats")
        assertTrue(PerfStats(tmp.resolve("absent.jsonl")).tailNumeric(5).isEmpty())
    }
}

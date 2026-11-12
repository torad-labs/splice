// NEW: V4-454 — the numeric tail selects work before its row cap, without counting probes as corrupt.
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PerfProbeFilterTest {
    @Test
    fun `numeric tail selects actual work before its row cap`(@TempDir dir: Path) {
        val file = dir.resolve("perf.jsonl")
        val clean = """{"ts":1,"model":"synthetic-model","outcome":"ok","total":7}"""
        val probe = """{"ts":2,"model":"","outcome":"error:upstream-failed","req_bytes":30}"""
        Files.writeString(file, (listOf(clean) + List(60) { probe }).joinToString("\n") + "\n")
        val stats = PerfStats(file, log = { })
        assertEquals(listOf(mapOf("ts" to 1L, "total" to 7L)), stats.tailNumeric(1))
        assertEquals(0L, stats.skippedRowCount())
    }
}

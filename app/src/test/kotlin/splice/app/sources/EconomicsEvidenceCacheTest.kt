// An evicted pre-window rejection must not change economics evidence after another selection.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.usage.EconomicsBucket
import java.nio.file.Files
import java.nio.file.Path

class EconomicsEvidenceCacheTest {
    @Test
    fun `cold retained and evicted economics evidence agree at the cutoff after work selections`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        write(file)
        val expected = PerfRowsFileSource(file).economicsEvidence(100)
        assertEquals(listOf(100L, 101L, 102L), expected.work.rows.map { it.ts })
        assertEquals(listOf(100L, 102L), expected.probes.map { it.ts })
        assertEquals(0, expected.work.skipped)
        assertNull(expected.work.readError)
        for (limit in listOf(32_768L, PERF_CACHE_BYTES)) {
            for (warm in listOf(0L, 99L, 101L)) {
                val source = PerfRowsFileSource(file, cache = PerfRowsCache(limit))
                source.window(warm)
                val evidence = source.economicsEvidence(100)
                assertEquals(expected.work.rows, evidence.work.rows, "work facts must survive every cache shape")
                assertEquals(expected.probes, evidence.probes, "probe facts must survive every cache shape")
                assertEquals(0, evidence.work.skipped, "a rejected pre-window row must not poison economics")
                assertNull(evidence.work.readError)
                assertEquals(evidence, source.economicsEvidence(100), "an unchanged poll must keep the same evidence")
            }
        }
    }

    @Test
    fun `a rejected row inside the economics window stays visible after eviction`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        write(file)
        val source = PerfRowsFileSource(file, cache = PerfRowsCache(32_768))
        source.window(0)
        for (cutoff in listOf(1L, 2L)) {
            assertEquals(1, source.economicsEvidence(cutoff).work.skipped)
            val failure = ProbeEconomics(source).withoutProbes(listOf(EconomicsBucket(cutoff)))
            assertEquals(ProbeGap.UNREADABLE, (failure as ProbeDeduction.Unavailable).gap)
        }
    }

    private fun write(file: Path) {
        val rows = buildList {
            add("""{"ts":1,"outcome":"ok","model":"synthetic"}""")
            add("""{"ts":2,"outcome":"ok","model":"synthetic"""")
            repeat(96) { index -> add("""{"ts":${index + 3},"outcome":"ok","model":"synthetic","in_tokens":7}""") }
            add("""{"ts":99,"model":"","outcome":"error:upstream-failed","req_bytes":30}""")
            add("""{"ts":100,"model":"","outcome":"error:upstream-failed","req_bytes":30}""")
            add("""{"ts":100,"outcome":"ok","model":"synthetic","in_tokens":7}""")
            add("""{"ts":101,"outcome":"ok","model":"synthetic","in_tokens":8}""")
            add("""{"ts":102,"model":"","outcome":"error:upstream-failed","req_bytes":30}""")
            add("""{"ts":102,"outcome":"ok","model":"synthetic","in_tokens":9}""")
        }
        Files.writeString(file, rows.joinToString("\n", postfix = "\n"))
    }
}

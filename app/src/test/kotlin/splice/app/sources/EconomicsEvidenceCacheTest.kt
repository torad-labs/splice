// An evicted pre-window rejection must not change economics evidence after another selection.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.usage.EconomicsBucket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
                assertEquals(
                    read(evidence),
                    read(source.economicsEvidence(100)),
                    "an unchanged poll must keep the same evidence",
                )
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
            val failure = ProbeEconomics(source).withoutProbes(listOf(EconomicsBucket(0L)))
            assertEquals(ProbeGap.UNREADABLE, (failure as ProbeDeduction.Unavailable).gap)
        }
    }

    @Test
    fun `the hour a torn row is in stays not read while its log is only appended to`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        write(file)
        val source = PerfRowsFileSource(file, cache = PerfRowsCache(32_768))
        val economics = ProbeEconomics(source)
        val buckets = listOf(EconomicsBucket(0L))
        assertEquals(ProbeGap.UNREADABLE, (economics.withoutProbes(buckets) as ProbeDeduction.Unavailable).gap)
        assertEquals(ProbeGap.UNREADABLE, (economics.withoutProbes(buckets) as ProbeDeduction.Unavailable).gap)
        val appended = """{"ts":103,"outcome":"ok","model":"synthetic","in_tokens":7}""" + "\n"
        Files.writeString(file, appended, StandardOpenOption.APPEND)
        assertEquals(
            ProbeGap.UNREADABLE,
            (economics.withoutProbes(buckets) as ProbeDeduction.Unavailable).gap,
            "an appended row does not mend a torn one",
        )
    }

    @Test
    fun `a log rewritten without its torn row no longer holds its hour back`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic-perf.jsonl")
        write(file)
        val source = PerfRowsFileSource(file, cache = PerfRowsCache(32_768))
        val economics = ProbeEconomics(source)
        val buckets = listOf(EconomicsBucket(0L))
        assertEquals(ProbeGap.UNREADABLE, (economics.withoutProbes(buckets) as ProbeDeduction.Unavailable).gap)
        val mended = Files.readAllLines(file).filterNot { it.startsWith("{\"ts\":2,") }
        Files.writeString(file, mended.joinToString("\n", postfix = "\n"))
        val again = economics.withoutProbes(buckets)
        val refused = (again as? ProbeDeduction.Unavailable)?.gap == ProbeGap.UNREADABLE
        assertTrue(!refused, "a mended log does not hold the hour back for the old tear")
    }

    /** The evidence as the hourly totals read it: a row's time is its own, so the compact cache keeps no `ts` among
     *  its numeric fields while a fresh decode still carries one, and no reader looks a row's time up there. */
    private fun read(evidence: EconomicsPerfEvidence) = evidence.copy(
        work = evidence.work.copy(rows = evidence.work.rows.map { it.copy(fields = it.fields - "ts") }),
        probes = evidence.probes.map { it.copy(fields = it.fields - "ts") },
    )

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

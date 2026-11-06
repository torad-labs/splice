package splice.app.sources.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfStamp
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.head.perf.RequestEvidenceFormat
import splice.usage.perf.history.PerfEvidenceQuery
import java.nio.file.Files
import java.nio.file.Path

class PerfEvidenceCoverageTest {
    @Test
    fun `steady writes expose exact completed windows at the durable horizon`(@TempDir root: Path) {
        var now = 1_000L
        val directory = root.resolve("evidence")
        val wall = WallClock { now }
        // Ordinary app owners, exporter/cache allowance, plus the writer's real 512 KiB ledger owner.
        checkNotNull(PerfWorkBudget.acquire(5L * 1_024 * 1_024)).use {
            RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now }).use { files ->
                val writer = files.forHead("test")
                assertTrue(files.startCoverage("test", "boot"))
                now = 1_500L
                assertTrue(writer.record(event("arrival", PerfEventKind.REQUEST_ARRIVED, now)))
                now = 1_800L
                assertTrue(writer.record(event("finish", PerfEventKind.REQUEST_FINISHED, now)))
                now = 2_000L
                assertTrue(files.checkpointCoverage())
                assertTrue(AsyncFileIo.awaitDirectory(directory))
                val source = PerfEvidenceFileSource(directory, wall)
                assertEquals(2_000L, source.settledUntilMs("test"))
                now = 2_500L
                assertTrue(writer.record(event("usage", PerfEventKind.USAGE_RECORDED, 1_800L)))
                assertTrue(AsyncFileIo.awaitDirectory(directory))
                source.read(PerfEvidenceQuery(1_000L, 2_000L, "test")).use { read ->
                    assertTrue(read.complete, read.coverage.issues.toString())
                    assertEquals(1_000L, read.coverage.observedMs)
                    assertEquals(2_000L, read.asOfMs)
                    assertEquals(listOf("arrival", "finish"), read.observations.map { it.stamp.observationId })
                }
                source.read(PerfEvidenceQuery(1_000L, 2_500L, "test")).use { read ->
                    assertFalse(read.complete)
                    assertEquals(2_500L, read.coverage.requestedUntilMs)
                    assertTrue(read.coverage.issues.any { it.code == "evidence_coverage_gap" })
                }
                now = 3_000L
                assertTrue(files.checkpointCoverage())
                assertTrue(AsyncFileIo.awaitDirectory(directory))
                source.read(PerfEvidenceQuery(1_000L, 2_000L, "test")).use { read ->
                    assertTrue(read.complete)
                    assertEquals(3_000L, read.asOfMs)
                    assertEquals(listOf("arrival", "finish", "usage"), read.observations.map { it.stamp.observationId })
                }
            }
        }
    }

    @Test
    fun `loss restart and missing retained bytes stay outside proven coverage`(@TempDir root: Path) {
        var now = 1_000L
        val directory = root.resolve("evidence")
        val wall = WallClock { now }
        RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now }).use { files ->
            assertTrue(files.startCoverage("test", "boot"))
            assertTrue(files.forHead("test").record(event("first", PerfEventKind.REQUEST_ARRIVED, now)))
            now = 2_000L
            assertTrue(files.checkpointCoverage())
            assertTrue(AsyncFileIo.awaitDirectory(directory))
            now = 2_500L
            files.faultCoverage("test")
            now = 3_000L
            assertTrue(files.stopCoverage("test"))
            assertTrue(AsyncFileIo.awaitDirectory(directory))
        }
        now = 4_000L
        RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now }).use { files ->
            assertTrue(files.startCoverage("test", "next-boot"))
            now = 5_000L
            assertTrue(files.checkpointCoverage())
            assertTrue(AsyncFileIo.awaitDirectory(directory))
        }
        val source = PerfEvidenceFileSource(directory, wall)
        source.read(PerfEvidenceQuery(1_000L, 5_000L, "test")).use { read ->
            assertFalse(read.complete)
            assertEquals(1_500L, read.coverage.observedMs)
            assertTrue(read.coverage.issues.any { it.code == "evidence_coverage_gap" })
        }
        source.read(PerfEvidenceQuery(4_000L, 5_000L, "test")).use { assertTrue(it.complete) }
        val segment = Files.newDirectoryStream(directory).use { paths ->
            paths.first { RequestEvidenceFormat.isSegment(it.fileName.toString()) }
        }
        Files.delete(segment)
        missingCoverage(source, "evidence_proof_segment_missing")
        now = 6_000L
        RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now }).use { files ->
            assertTrue(files.startCoverage("test", "third-boot"))
            now = 7_000L
            assertTrue(files.checkpointCoverage())
            assertTrue(AsyncFileIo.awaitDirectory(directory))
        }
        missingCoverage(source, "evidence_retention_expired")
    }

    @Test
    fun `an enabled idle producer proves zero events without inventing event extents`(@TempDir root: Path) {
        var now = 1_000L
        val directory = root.resolve("idle")
        val wall = WallClock { now }
        RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now }).use { files ->
            assertTrue(files.startCoverage("test", "boot"))
            now = 2_000L
            assertTrue(files.checkpointCoverage())
            assertTrue(AsyncFileIo.awaitDirectory(directory))
        }
        PerfEvidenceFileSource(directory, wall).read(PerfEvidenceQuery(1_000L, 2_000L, "test")).use { read ->
            assertTrue(read.complete, read.coverage.issues.toString())
            assertEquals(1_000L, read.coverage.observedMs)
            assertTrue(read.observations.isEmpty())
        }
    }

    private fun missingCoverage(source: PerfEvidenceFileSource, reason: String) {
        source.read(PerfEvidenceQuery(4_000L, 5_000L, "test")).use { read ->
            assertFalse(read.complete, "missing bytes must stay outside certified coverage")
            assertEquals(0L, read.coverage.observedMs)
            assertTrue(read.coverage.issues.any { it.code == reason })
        }
    }

    private fun event(id: String, kind: PerfEventKind, wallMs: Long): PerfObservation = PerfObservation(
        PerfStamp(id, PerfIdentity("boot", "build", "1", "config"), wallMs, wallMs),
        PerfScope(head = "test"),
        kind,
        links = PerfLinks(requestId = "request"),
        outcome = if (kind == PerfEventKind.REQUEST_FINISHED) "ok" else null,
    )
}

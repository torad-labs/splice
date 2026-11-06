package splice.app.sources.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.head.perf.RequestEvidenceFormat
import splice.head.perf.RequestEvidenceProofFormat
import splice.head.perf.RequestEvidenceRetention
import splice.usage.perf.history.PerfEvidenceQuery
import java.nio.file.Files
import java.nio.file.Path

class PerfEvidenceProofSafetyTest {
    @Test
    fun `settled empty and populated evidence without collection proof remain unknown`(@TempDir root: Path) {
        val directory = Files.createDirectory(root.resolve("evidence"))
        Files.writeString(
            directory.resolve(RequestEvidenceFormat.RETENTION_FILE),
            Json.encodeToString(RequestEvidenceRetention(lossAccountingComplete = true)),
        )
        val wall = WallClock { 2_000L }
        val source = PerfEvidenceFileSource(directory, wall)
        val query = PerfEvidenceQuery(1_000L, 2_000L, "test")
        unknown(source, query, "evidence_coverage_unknown", emptyList())
        RequestEvidenceFiles(directory, clock = wall).use { files ->
            assertTrue(files.forHead("test").record(event("test")))
            assertTrue(AsyncFileIo.awaitDirectory(directory))
        }
        unknown(source, query, "evidence_coverage_unknown", listOf("test"))
    }

    @Test
    fun `a prefix from an undeclared head invalidates collection proof`(@TempDir root: Path) {
        var now = 1_000L
        val directory = root.resolve("evidence")
        val wall = WallClock { now }
        RequestEvidenceFiles(directory, clock = wall, elapsed = ElapsedClock { now }).use { files ->
            listOf("test", "other").forEach { head ->
                assertTrue(files.startCoverage(head, "boot"))
                assertTrue(files.forHead(head).record(event(head)))
            }
            now = 2_000L
            assertTrue(files.checkpointCoverage())
            assertTrue(AsyncFileIo.awaitDirectory(directory))
        }
        val path = directory.resolve(RequestEvidenceProofFormat.COVERAGE_FILE)
        val proof = RequestEvidenceProofFormat.decode(Files.readAllBytes(path))
        assertTrue(proof.prefixes.any { RequestEvidenceFormat.headOf(it.fileName) == "other" })
        Files.writeString(
            path,
            Json.encodeToString(
                proof.copy(
                    heads = listOf("test"),
                    intervals = proof.intervals.filter { it.head == "test" },
                    lossEpochs = proof.lossEpochs.filterKeys { it == "test" },
                    retentionEpochs = proof.retentionEpochs.filterKeys { it == "test" },
                ),
            ),
        )
        val source = PerfEvidenceFileSource(directory, wall)
        unknown(source, PerfEvidenceQuery(1_000L, 2_000L), "evidence_proof_malformed", listOf("test", "other"))
        unknown(source, PerfEvidenceQuery(1_000L, 2_000L, "test"), "evidence_proof_malformed", listOf("test"))
    }

    private fun unknown(
        source: PerfEvidenceFileSource,
        query: PerfEvidenceQuery,
        reason: String,
        expectedIds: List<String>,
    ) {
        assertNull(source.settledUntilMs(query.head))
        source.read(query).use { read ->
            assertFalse(read.complete)
            assertEquals(0L, read.coverage.observedMs)
            assertEquals("unknown", read.coverageBasis)
            assertTrue(read.coverage.issues.any { it.code == reason })
            assertEquals(expectedIds.sorted(), read.observations.map { it.stamp.observationId }.sorted())
        }
        val consumed = ArrayList<String>()
        source.consume(query) { observation ->
            consumed += observation.stamp.observationId
            true
        }.use { receipt ->
            assertFalse(receipt.complete)
            assertEquals(0L, receipt.coverage.observedMs)
            assertEquals("unknown", receipt.coverageBasis)
            assertTrue(receipt.coverage.issues.any { it.code == reason })
            assertEquals(expectedIds.sorted(), consumed.sorted())
        }
    }

    private fun event(head: String): PerfObservation = PerfObservation(
        PerfStamp(head, PerfIdentity("boot", "build", "1", "config"), 1_500L, 500L),
        PerfScope(head = head),
        PerfEventKind.REQUEST_ARRIVED,
        links = PerfLinks(requestId = "request-$head"),
    )
}

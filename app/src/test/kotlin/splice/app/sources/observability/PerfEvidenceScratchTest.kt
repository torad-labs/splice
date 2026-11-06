package splice.app.sources.observability

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.sources.observability.cursor.PerfCursorPruning
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfObservationConsumer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.coroutines.cancellation.CancellationException

class PerfEvidenceScratchTest {
    @TempDir
    lateinit var root: Path

    @AfterEach
    fun releaseCursorSweeps() {
        PerfCursorPruning.closeRoot(root)
    }

    @Test
    fun `event cursor pairs also stop at capacity without blocking their existing continuation`() {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(2)
            val source = fixture.evidence()
            val request = PerfEvidenceCursorRequest(fixture.evidenceQuery(), pageSize = 5)
            var token: String? = null
            repeat(64) {
                source.page(request).use { page ->
                    assertEquals("complete", JsonScalars.str(page.metadata, "status"))
                    assertEquals(5, page.observations.size)
                    token = requireNotNull(page.nextCursor)
                }
            }
            source.page(request).use { page ->
                assertEquals("incomplete", JsonScalars.str(page.metadata, "status"))
                assertTrue(page.metadata.toString().contains("cursor_inventory_limit"))
            }
            source.page(request.copy(cursor = requireNotNull(token))).use { page ->
                assertEquals("complete", JsonScalars.str(page.metadata, "status"))
                assertEquals(5, page.observations.size)
            }
            source.consume(fixture.evidenceQuery(), PerfObservationConsumer { true }).use { read ->
                assertTrue(read.complete, read.coverage.issues.toString())
            }
        }
    }

    @Test
    fun `a full scratch fallback reports incomplete instead of a complete partial population`() {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(411)
            overfull(root)
            val result = fixture.service().use { it.summary(fixture.query()) }
            try {
                assertEquals("false", JsonScalars.str(result.getValue("coverage").jsonObject, "complete"))
                assertEquals("0", JsonScalars.str(result.getValue("coverage").jsonObject, "observed_ms"))
                assertTrue(result.toString().contains("evidence_memory_limit"))
                assertEquals(
                    "insufficient_evidence",
                    JsonScalars.str(result.getValue("comparison").jsonObject, "status"),
                )
            } finally {
                PerfWorkBudget.release(result)
            }
        }
    }

    @Test
    fun `fallback preserves conflicting identities and consumer refusals`() {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(2)
            overfull(root)
            fixture.evidence().consume(fixture.evidenceQuery(), PerfObservationConsumer { false }).use { read ->
                assertFalse(read.complete)
                assertEquals(0L, read.coverage.observedMs)
                assertTrue(read.coverage.issues.any { it.code == "evidence_consumer_limit" })
            }
            fixture.conflict()
            val result = fixture.service().use { it.summary(fixture.query()) }
            try {
                assertEquals("false", JsonScalars.str(result.getValue("coverage").jsonObject, "complete"))
                assertTrue(result.toString().contains("evidence_duplicate_conflict"))
            } finally {
                PerfWorkBudget.release(result)
            }
        }
    }

    @Test
    fun `cancelled fallback releases its lease and a subsequent real read remains complete`() {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(2)
            failedCleanup(root)
            val source = fixture.evidence()
            val retained = PerfWorkBudget.retainedBytes()
            assertThrows(CancellationException::class.java) {
                source.consume(fixture.evidenceQuery(), PerfObservationConsumer { throw CancellationException("stop") })
            }
            assertEquals(retained, PerfWorkBudget.retainedBytes())
            source.consume(fixture.evidenceQuery(), PerfObservationConsumer { true }).use { read ->
                assertTrue(read.complete, read.coverage.issues.toString())
                assertEquals(200L, read.coverage.observedMs)
            }
        }
    }

    @Test
    fun `a failed cursor expiry cleanup does not replace a successful evidence scan`() {
        StreamingEvidenceFixture(root).use { fixture ->
            fixture.seed(2)
            failedCleanup(root)
            fixture.evidence().consume(fixture.evidenceQuery(), PerfObservationConsumer { true }).use { read ->
                assertTrue(read.complete, read.coverage.issues.toString())
                assertEquals(200L, read.coverage.observedMs)
            }
        }
    }

    private fun overfull(root: Path) {
        val directory = Files.createDirectories(root.resolve("old-cursors"))
        repeat(130) { Files.writeString(directory.resolve("old-$it.idx"), "unexpired") }
    }

    private fun failedCleanup(root: Path) {
        val stale = Files.createDirectories(root.resolve("broken-cursors/stale.idx"))
        Files.writeString(stale.resolve("occupied"), "not a cursor")
        Files.setLastModifiedTime(stale, FileTime.fromMillis(-2_000_000))
    }
}

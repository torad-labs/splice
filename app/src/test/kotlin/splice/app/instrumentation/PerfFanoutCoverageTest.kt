package splice.app.instrumentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.observability.PerfCleanup
import splice.app.observability.PerfCollection
import splice.app.observability.PerfConfigHistory
import splice.app.observability.PerfInstrumentationStatus
import splice.app.observability.PerfStatusRead
import splice.app.sources.PerfSourceFiles
import splice.core.config.StatePaths
import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFormat
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class PerfFanoutCoverageTest {
    @Test
    fun `optional observer rejection and failure preserve complete local request evidence`(@TempDir root: Path) {
        val exports = listOf(
            "rejection" to PerfObserver { false },
            "failure" to PerfObserver { throw IOException("optional fixture exporter unavailable") },
        )
        exports.forEach { (name, exporter) ->
            FanoutCoverageFixture(root.resolve(name), exporter).use { fixture ->
                assertFalse(fixture.recordRequest())
                fixture.checkpoint()
                assertTrue(AsyncFileIo.awaitDirectory(fixture.paths.perfEvidenceDir))
                fixture.assertCompleteEvidence()
                fixture.summary { summary ->
                    assertEquals("true", JsonScalars.str(summary.getValue("coverage").jsonObject, "complete"))
                    assertEquals("1", JsonScalars.str(summary.getValue("counts").jsonObject, "arrivals"))
                    assertEquals("1", JsonScalars.str(summary.getValue("counts").jsonObject, "completions"))
                }
                fixture.status { status ->
                    assertEquals("partial", JsonScalars.str(status, "status"))
                    assertTrue(status.getValue("issues").jsonArray.any { it.jsonPrimitive.content == "observer_drop" })
                    if (name == "failure") {
                        assertTrue(
                            status.getValue("issues").jsonArray.any { it.jsonPrimitive.content == "observer_failure" },
                        )
                    }
                    val evidence = status.getValue("sinks").jsonArray.map { it.jsonObject }
                        .single { JsonScalars.str(it, "sink") == "request_evidence:test" }
                    assertEquals("0", JsonScalars.str(evidence, "failed"))
                    assertEquals("0", JsonScalars.str(evidence, "dropped"))
                    assertEquals("0", JsonScalars.str(evidence, "pending"))
                }
            }
        }
    }

    @Test
    fun `actual local evidence write failure still makes the request source incomplete`(@TempDir root: Path) {
        FanoutCoverageFixture(root, NoPerfObserver).use { fixture ->
            val obstruction = fixture.paths.perfEvidenceDir.resolve(RequestEvidenceFormat.RETENTION_FILE)
            Files.createDirectories(obstruction)
            assertTrue(fixture.recordRequest())
            assertFalse(AsyncFileIo.awaitDirectory(fixture.paths.perfEvidenceDir))
            fixture.status { status ->
                val evidence = status.getValue("sinks").jsonArray.map { it.jsonObject }
                    .single { JsonScalars.str(it, "sink") == "request_evidence:test" }
                assertTrue(checkNotNull(JsonScalars.str(evidence, "failed")).toLong() > 0)
                assertEquals("0", JsonScalars.str(evidence, "pending"))
            }
            Files.delete(obstruction)
            fixture.checkpoint()
            // The directory barrier retains the actual earlier write failure after the proof has settled.
            assertFalse(AsyncFileIo.awaitDirectory(fixture.paths.perfEvidenceDir))
            fixture.sources.evidence().read(PerfEvidenceQuery(1_000, 2_000, "test")).use { read ->
                assertFalse(read.complete)
                assertTrue(read.coverage.issues.any { it.code == "evidence_collection_loss" })
            }
            fixture.summary { summary ->
                assertEquals("partial", JsonScalars.str(summary, "status"))
                assertEquals("false", JsonScalars.str(summary.getValue("coverage").jsonObject, "complete"))
            }
            fixture.status { status ->
                assertEquals("partial", JsonScalars.str(status, "status"))
                assertTrue(status.getValue("issues").jsonArray.any { it.jsonPrimitive.content == "sink_loss" })
            }
        }
    }

    @Test
    fun `aggregate rejection remains in aggregate status without invalidating persisted request evidence`(
        @TempDir root: Path,
    ) {
        FanoutCoverageFixture(root, NoPerfObserver).use { fixture ->
            fixture.collection.close()
            assertFalse(fixture.recordRequest())
            fixture.checkpoint()
            assertTrue(AsyncFileIo.awaitDirectory(fixture.paths.perfEvidenceDir))
            fixture.assertCompleteEvidence()
            assertTrue(fixture.sources.live().coverage.issues.any { it.code == "collector_closed" })
            fixture.status { status ->
                assertEquals("partial", JsonScalars.str(status, "status"))
                assertTrue(status.getValue("issues").jsonArray.any { it.jsonPrimitive.content == "observer_drop" })
            }
        }
    }
}

private class FanoutCoverageFixture(root: Path, exporter: PerfObserver) : AutoCloseable {
    val paths = StatePaths(baseOverride = root)
    private var now = 1_000L
    private val wall = WallClock { now }
    private val elapsed = ElapsedClock { now }
    private val identity = PerfIdentity("boot", "build", "version", "config")
    private val configuration = PerfConfigHistory(paths.perfConfigurationFile)
    private val cleanup = PerfCleanup(LogSink {})
    val collection = PerfCollection(paths, identity, setOf("test"), PerfSourceFiles(paths), true, wall, elapsed)
    private val observer = collection.fanout(exporter)
    private val recorder = PerfRecorder(identity, observer, elapsed, wall)
    val sources = collection.sources(configuration)
    private val instrumentation = PerfInstrumentationStatus(
        identity,
        true,
        PerfStatusRead { collection.diagnostics(configuration, observer, cleanup) },
    )

    init { collection.start() }

    fun recordRequest(): Boolean {
        now = 1_500
        val request = recorder.request(PerfScope(head = "test"), now, now)
        now = 1_800
        return request.finish("ok")
    }

    fun checkpoint() {
        now = 2_500
        collection.checkpointCoverage()
    }

    fun assertCompleteEvidence() {
        sources.evidence().read(PerfEvidenceQuery(1_000, 2_000, "test")).use { read ->
            assertTrue(read.complete, read.coverage.issues.toString())
            assertEquals(1_000L, read.coverage.observedMs)
            assertEquals(
                setOf(PerfEventKind.REQUEST_ARRIVED, PerfEventKind.REQUEST_FINISHED),
                read.observations.map { it.kind }.toSet(),
            )
            assertEquals(1, read.observations.map { it.links.requestId }.toSet().size)
        }
    }

    fun summary(assertions: (JsonObject) -> Unit) {
        PerfQueryService(sources, clock = wall).use { query ->
            val result = query.summary(PerfQuery(1_000, 2_000, mapOf("head" to "test"), source = "evidence"))
            try { assertions(result) } finally { PerfWorkBudget.release(result) }
        }
    }

    fun status(assertions: (JsonObject) -> Unit) {
        val result = instrumentation.status()
        try { assertions(result) } finally { PerfWorkBudget.release(result) }
    }

    override fun close() {
        collection.closePersistence(cleanup)
        AsyncFileIo.awaitDirectory(paths.perfEvidenceDir)
        collection.close()
        configuration.close()
    }
}

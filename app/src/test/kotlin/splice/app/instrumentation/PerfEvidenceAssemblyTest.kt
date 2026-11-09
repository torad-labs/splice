package splice.app.instrumentation

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.observability.PerfOfflineSources
import splice.app.observability.PerfTelemetry
import splice.app.sources.PerfSourceFiles
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfScope
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.usage.alerts.AlertStore
import splice.usage.perf.history.PerfEvidenceQuery
import java.nio.file.Path

class PerfEvidenceAssemblyTest {
    @Test
    fun `one composed recorder survives shutdown and offline reads without taking writer ownership`(
        @TempDir directory: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = directory)
        val owner = PerfTelemetry(
            ConfigService(paths, envReader = EnvReader { null }),
            setOf("one"),
            PerfSourceFiles(paths),
            AlertStore(directory.resolve("alerts.json")),
            LogSink {},
        )
        val from = System.currentTimeMillis()
        var requestId: String? = null
        try {
            owner.start()
            val request = owner.recorder.request(
                PerfScope(head = "one", model = "fixture"),
                MonoClock.nowMs(),
                System.currentTimeMillis(),
            )
            requestId = request.links.requestId
            assertTrue(request.finish("ok", mapOf("arrival_to_terminal_ms" to 1.0)))
        } finally {
            owner.close()
        }
        PerfOfflineSources(paths, setOf("one")).use { offline ->
            offline.evidence().read(PerfEvidenceQuery(from, System.currentTimeMillis() + 1, "one")).use { read ->
                val request = read.observations.filter { it.links.requestId == requestId }
                assertEquals(1, request.count { it.kind == PerfEventKind.REQUEST_ARRIVED })
                assertEquals(1, request.count { it.kind == PerfEventKind.REQUEST_FINISHED })
                assertEquals(setOf(owner.identity.bootId), request.map { it.stamp.identity.bootId }.toSet())
            }
        }
    }
}

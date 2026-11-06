package splice.head.perf

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class RequestEvidenceStoreTest {
    @Test
    fun `stored terminal and late usage join by stable request without changing completion time`(
        @TempDir directory: Path,
    ) {
        var now = 0L
        val wall = WallClock { 60_000L + now }
        val store = RequestEvidenceFiles(directory, clock = wall).forHead("test")
        val recorder =
            PerfRecorder(PerfIdentity("test", "build", "version", "config"), store, ElapsedClock { now }, wall)
        val request = recorder.request(PerfScope(head = "test"), 0L, wall())
        request.update(request.scope, request.links.copy(sessionId = "session"))
        val attempt = request.attempt("sse")
        val tool = request.tool()
        tool.observe(PerfEventKind.TOOL_PHASE, mapOf("tool_buffered_bytes" to 512.0))
        now = 10
        request.finish(OutcomeTag.CLIENT_ABORT.wire)
        attempt.finish("good")
        tool.finish("cancelled")
        now = 70_000
        request.usage(mapOf("input_tokens" to 100.0, "missing_usage" to 0.0))
        awaitEvidenceWrites(directory, store)
        val rows = observations(directory)
        val terminal = rows.single { it.kind == PerfEventKind.REQUEST_FINISHED }
        val usage = rows.single { it.kind == PerfEventKind.USAGE_RECORDED }
        assertEquals(terminal.links.requestId, usage.links.requestId)
        assertEquals("session", terminal.links.sessionId)
        assertTrue(
            rows.any { it.kind == PerfEventKind.ATTEMPT_STARTED && it.links.requestId == terminal.links.requestId },
        )
        assertTrue(
            rows.any { it.kind == PerfEventKind.ATTEMPT_FINISHED && it.links.attemptId == attempt.links.attemptId },
        )
        val toolTerminal = rows.single { it.kind == PerfEventKind.TOOL_FINISHED }
        assertEquals(tool.links.toolId, toolTerminal.links.toolId)
        assertEquals(terminal.links.requestId, toolTerminal.links.requestId)
        assertEquals(512.0, rows.single { it.kind == PerfEventKind.TOOL_PHASE }.values["tool_buffered_bytes"])
        assertEquals(60_010L, terminal.stamp.wallMs)
        assertEquals(terminal.stamp.wallMs, usage.stamp.wallMs)
        assertEquals(100.0, usage.values["input_tokens"])
        assertEquals(rows.size.toLong(), store.sink.status().written)
        assertEquals(0L, store.sink.status().pending)
    }

    @Test
    fun `shared evidence cap expires whole segments and records expiry for readers`(@TempDir directory: Path) {
        var now = 60_000L
        val wall = WallClock { now }
        val files = RequestEvidenceFiles(directory, clock = wall, maxBytes = 8_192L)
        val store = files.forHead("test")
        val recorder =
            PerfRecorder(PerfIdentity("test", "build", "version", "config"), store, ElapsedClock { now }, wall)
        repeat(40) {
            val request = recorder.request(PerfScope(head = "test"), now, now)
            now++
            request.finish(OutcomeTag.OK.wire)
        }
        awaitEvidenceWrites(directory, store)
        val retention = Json.decodeFromString<RequestEvidenceRetention>(
            Files.readString(directory.resolve("retention.json")),
        )
        assertTrue(retention.expiredSegments > 0)
        assertTrue(Files.list(directory).use { paths -> paths.mapToLong(Files::size).sum() } <= 8_192L)
        assertTrue(observations(directory).isNotEmpty())
        assertEquals(0L, store.sink.status().failed)
    }

    private fun awaitEvidenceWrites(directory: Path, store: RequestEvidenceStore) {
        // Each append persists retention metadata before its directory-scoped write completes.
        assertTrue(AsyncFileIo.awaitDirectory(directory)) {
            "Evidence writes did not settle: sink=${store.sink.status()}, lane=${AsyncFileIo.workSnapshot}"
        }
    }

    private fun observations(directory: Path): List<PerfObservation> = Files.list(directory).use { paths ->
        paths.filter { RequestEvidenceFormat.isSegment(it.fileName.toString()) }.toList()
            .flatMap { file -> Files.readAllLines(file).map { Json.decodeFromString<PerfObservation>(it) } }
    }
}

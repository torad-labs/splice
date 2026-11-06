package splice.control.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.WallClock
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class McpObservationTest : McpHostFixture() {
    private val events = CopyOnWriteArrayList<PerfObservation>()

    @Test
    fun `owned MCP calls retain independent session links without inference lifecycle events`() = runBlocking {
        observe()
        boot()
        val first = checkNotNull(host.post("fake", null, MCP_HOST_INIT, head = "codex").sessionId)
        val second = checkNotNull(host.post("fake", null, MCP_HOST_INIT, head = "claude").sessionId)
        val results = listOf(
            async { call(first, 7, "echo", "private-first") },
            async { call(second, 7, "echo", "private-second") },
        ).awaitAll()
        assertTrue(text(results[0]).endsWith("echo=private-first"))
        assertTrue(text(results[1]).endsWith("echo=private-second"))
        val completed = events.filter { it.kind == PerfEventKind.TOOL_FINISHED }
        assertEquals(2, completed.size)
        assertEquals(2, completed.map { it.links.toolId }.toSet().size)
        assertEquals(setOf(first, second), completed.map { it.links.sessionId }.toSet())
        assertEquals(setOf("codex", "claude"), completed.map { it.scope.head }.toSet())
        assertTrue(completed.all { it.scope.transport == "mcp" })
        completed.forEach {
            assertEquals(OutcomeTag.OK.wire, it.outcome)
            assertNull(it.links.requestId)
            assertNull(it.links.attemptId)
            assertTrue(checkNotNull(it.links.traceId).length == 32)
        }
        assertFalse(
            events.any { it.kind == PerfEventKind.REQUEST_ARRIVED || it.kind == PerfEventKind.REQUEST_FINISHED },
        )
        assertFalse(events.toString().contains("private-first"))
        assertTrue(events.any { it.values["tool_cold_start_ms"] == 0.0 })
        assertTrue(events.any { it.values.containsKey("tool_admission_capacity") })
    }

    @Test
    fun `cancelled caller leaves its child operation open until the real reply`(
        @TempDir directory: Path,
    ) = runBlocking {
        observe()
        boot()
        val session = init()
        val ready = directory.resolve("held")
        val waiting = async(Dispatchers.IO) { call(session, 7, "hold", ready.toString()) }
        awaitFile(ready)
        waiting.cancelAndJoin()
        assertTrue(events.any { it.values["tool_cancelled"] == 1.0 })
        assertFalse(events.any { it.kind == PerfEventKind.TOOL_FINISHED })
        assertEquals(202, host.post("fake", session, FakeMcpServer.RELEASE).status)
        withTimeout(MCP_HOST_STREAM_WAIT_MS) {
            while (events.none { it.kind == PerfEventKind.TOOL_FINISHED }) yield()
        }
        assertEquals(1, events.count { it.kind == PerfEventKind.TOOL_FINISHED })
        assertEquals(OutcomeTag.OK.wire, events.single { it.kind == PerfEventKind.TOOL_FINISHED }.outcome)
    }

    @Test
    fun `timeout reports an unfinished child until its eventual response`(@TempDir directory: Path) = runBlocking {
        observe()
        boot(requestTimeout = 100.milliseconds)
        val session = init()
        val ready = directory.resolve("held")
        val waiting = async(Dispatchers.IO) { call(session, 7, "hold", ready.toString()) }
        awaitFile(ready)
        assertTrue(waiting.await().containsKey("error"))
        assertEquals(1, events.count { it.values["tool_timeouts"] == 1.0 })
        assertFalse(events.any { it.kind == PerfEventKind.TOOL_FINISHED })
        assertEquals(202, host.post("fake", session, FakeMcpServer.RELEASE).status)
        withTimeout(MCP_HOST_STREAM_WAIT_MS) {
            while (events.none { it.kind == PerfEventKind.TOOL_FINISHED }) yield()
        }
        assertEquals(1, events.count { it.kind == PerfEventKind.TOOL_FINISHED })
    }

    private fun observe() {
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "config"),
            PerfObserver { events.add(it); true },
            ElapsedClock(MonoClock::nowMs),
            WallClock(System::currentTimeMillis),
        )
        observations = McpPerfRecorders { recorder }
    }
}

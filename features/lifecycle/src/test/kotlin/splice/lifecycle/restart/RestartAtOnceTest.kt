// restart-at-once: handsOver default, --wait / ?wait=1, --now --wait refusal, stop-whole-only compaction wait,
// and fail-closed config validation before asking the launcher.
package splice.lifecycle.restart

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import splice.core.util.LogSink
import splice.lifecycle.upgrade.CompactionSlot
import splice.lifecycle.upgrade.CompactionWait
import splice.lifecycle.upgrade.CompactionWaitMode
import splice.lifecycle.upgrade.InflightRead
import splice.lifecycle.upgrade.UpgradeArgParser
import splice.lifecycle.upgrade.UpgradeInflight
import java.util.concurrent.atomic.AtomicInteger

internal class RestartAtOnceTest {
    @Test
    fun `hands-over daemon drains at once while a hands_over head compacts, waits for a stop-whole head`() =
        testApplication {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                var slots = listOf(CompactionSlot("open", 1_000L))
                val compactions = object : CompactionsInFlight {
                    override fun invoke(): List<CompactionSlot> = slots
                    override fun stopWholeHeads(): Set<String> = setOf("pinned")
                }
                val restarts = DaemonRestarts(RestartAfterCompactions(compactions, scope, LogSink {}, pollMs = 5))
                val routes = DaemonRoutes(restarts, drainMs = 0L)
                val shutdowns = AtomicInteger()
                routing {
                    get("/api/daemon/restart") { routes.statusJson(call, handsOver = true) }
                    post("/api/daemon/restart") {
                        routes.restartJson(call, { shutdowns.incrementAndGet() }, { true }, handsOver = true)
                    }
                }

                val status = Json.parseToJsonElement(client.get("/api/daemon/restart").bodyAsText()).jsonObject
                assertEquals(true, status["hands_over"]!!.jsonPrimitive.boolean)

                val conflict = client.post("/api/daemon/restart?now=1&wait=1")
                assertEquals(HttpStatusCode.BadRequest, conflict.status)
                assertEquals(0, shutdowns.get())

                val immediate = client.post("/api/daemon/restart")
                assertEquals(HttpStatusCode.Accepted, immediate.status)
                val body = Json.parseToJsonElement(immediate.bodyAsText()).jsonObject
                assertEquals("draining", body["status"]!!.jsonPrimitive.content)
                assertEquals(1, shutdowns.get())

                // Now with a stop-whole head compacting on a fresh restart controller, it waits.
                slots = listOf(CompactionSlot("pinned", 2_000L))
                val waitingRestarts = DaemonRestarts(
                    RestartAfterCompactions(compactions, scope, LogSink {}, pollMs = 5),
                )
                val phase = waitingRestarts.take(false, {}, { true }, waitAll = false, handsOver = true)
                assertTrue(phase is RestartTaken.Accepted && phase.phase is RestartPhase.Waiting)
                val waiting = (phase as RestartTaken.Accepted).phase as RestartPhase.Waiting
                assertEquals(1, waiting.compactions.size)
                assertEquals("pinned", waiting.compactions.single().head)
                slots = emptyList()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `wait=1 waits for hands_over head compactions and invalid config refuses with 422`() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var slots = listOf(CompactionSlot("open", 1_000L))
            val compactions = object : CompactionsInFlight {
                override fun invoke(): List<CompactionSlot> = slots
                override fun stopWholeHeads(): Set<String> = emptySet()
            }
            val restarts = DaemonRestarts(RestartAfterCompactions(compactions, scope, LogSink {}, pollMs = 5))
            var configErr: String? = "unknown provider nobody for head ghost"
            val routes = DaemonRoutes(restarts, drainMs = 0L, configCheck = RestartConfigCheck { configErr })
            val shutdowns = AtomicInteger()
            routing {
                post("/api/daemon/restart") {
                    routes.restartJson(call, { shutdowns.incrementAndGet() }, { true }, handsOver = true)
                }
            }

            val refused = client.post("/api/daemon/restart")
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertTrue(refused.bodyAsText().contains("ghost"))
            assertEquals(0, shutdowns.get())

            configErr = null
            val waited = client.post("/api/daemon/restart?wait=1")
            assertEquals(HttpStatusCode.Accepted, waited.status)
            val body = Json.parseToJsonElement(waited.bodyAsText()).jsonObject
            assertEquals("waiting", body["status"]!!.jsonPrimitive.content)
            assertEquals(1, body["compactions"]!!.jsonPrimitive.int)
            slots = emptyList()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `STOPS_WHOLE_ONLY holds and waits only on stop-whole heads and prints explanation once`() {
        val lines = mutableListOf<String>()
        val heldHeads = mutableListOf<Collection<String>?>()
        val stopWhole = setOf("pinned")
        val reads = ArrayDeque<InflightRead>(
            listOf(
                InflightRead.Count(
                    2,
                    listOf(CompactionSlot("open", 500), CompactionSlot("pinned", 500)),
                    stopWhole,
                ),
                InflightRead.Count(
                    2,
                    listOf(CompactionSlot("open", 600), CompactionSlot("pinned", 600)),
                    stopWhole,
                ),
                InflightRead.Count(1, listOf(CompactionSlot("open", 700)), stopWhole),
            ),
        )
        val inflight = object : UpgradeInflight {
            override fun invoke(): InflightRead = reads.removeFirstOrNull() ?: InflightRead.Count(0)
            override fun hold(forMs: Long, heads: Collection<String>?): String? {
                heldHeads += heads
                return null
            }
        }
        CompactionWait(TerminalOutput { lines += it }, inflight, pollMs = 1).await(CompactionWaitMode.STOPS_WHOLE_ONLY)
        assertEquals(listOf<Collection<String>?>(setOf("pinned")), heldHeads)
        assertTrue(lines.any { it.contains("waiting first for the compactions running on pinned") }, "$lines")
        assertTrue(lines.any { it.contains("waiting for 1 compaction (pinned,") }, "$lines")
    }

    @Test
    fun `upgrade arg parser accepts --wait and refuses --now --wait`() {
        val parser = UpgradeArgParser(TerminalOutput {})
        assertEquals(true, parser.parse(listOf("--wait"))?.wait)
        assertNull(parser.parse(listOf("--now", "--wait")))
    }
}

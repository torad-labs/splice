// `[server]`'s load, streaming and drain settings at boot (#791, docs/specs/server-mode.md): the caps on what the whole
// server holds, the answer a turn past them gets, the deadline on a silent stream and the drain at stop, as
// `check-config` lets them through or refuses each by its key and its reason.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyFinding
import java.nio.file.Path

class ServerLoadConfigFindingsTest {
    @TempDir lateinit var directory: Path

    /** A head served as `claude-team`, on a provider signing with an API key. */
    private fun head(): String =
        """
        [providers.box]
        dialect = "openai-chat"
        base_url = "http://127.0.0.1:8080/v1"
        auth = $LOAD_API_KEY

        [[providers.box.models]]
        id = "team-model"
        label = "team-model"
        context_window = 131072

        [heads.box]
        provider = "box"
        port = 3190
        discovery_prefix = "claude-box--"
        pinned_model = "team-model"
        [heads.box.claude]
        command = "claude-team"
        """.trimIndent() + "\n"

    private fun server(lines: String = ""): String =
        "[server]\nlisten = \"0.0.0.0:3443\"\ncommands = [\"claude-team\"]\n$lines\n"

    /** The findings under `server`, as `check-config` lists them. */
    private fun refused(toml: String): List<TopologyFinding> {
        val read = ConfigFindings.read(toml, directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Refused::class.java, read).findings.filter { it.path.startsWith("server") }
    }

    @Test
    fun `the server's load caps, its stream idle deadline and its drain boot when they can be served`() {
        val lines = """
            stream_idle_seconds = 120
            drain_seconds = 30
            [server.limits]
            inflight_total = 4
            inflight_per_upstream = 2
            busy_status = 503
            busy_retry_after = 10
            busy_message = "The GPUs are busy; try again shortly."
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val server = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
        assertEquals(4, server.limits.inflightTotal)
        assertEquals(2, server.limits.inflightPerUpstream)
        assertEquals(503, server.limits.busyStatus)
        assertEquals(10, server.limits.busyRetryAfter)
        assertEquals("The GPUs are busy; try again shortly.", server.limits.busyMessage)
        assertEquals(120, server.streamIdleSeconds)
        assertEquals(30, server.drainSeconds)
        val plain = assertInstanceOf(ConfigRead.Ready::class.java, ConfigFindings.read(server() + head(), directory))
        assertEquals(429, plain.topology.server.limits.busyStatus, "a server that sets no busy_status answers 429")
        val idle = plain.topology.server.streamIdleSeconds
        assertEquals(0, idle, "a silent stream is ended though the operator asked for no deadline")
    }

    @Test
    fun `a load cap, a busy answer, a stream idle deadline or a drain that cannot be served is each refused`() {
        val lines = """
            stream_idle_seconds = -1
            drain_seconds = 600
            [server.limits]
            inflight_total = -1
            inflight_per_upstream = -2
            busy_status = 500
            busy_retry_after = -5
        """.trimIndent()
        val found = refused(server(lines) + head())
        assertEquals(
            setOf(
                "server.stream_idle_seconds",
                "server.drain_seconds",
                "server.limits.inflight_total",
                "server.limits.inflight_per_upstream",
                "server.limits.busy_status",
                "server.limits.busy_retry_after",
            ),
            found.map { it.path }.toSet(),
        )
        val status = found.single { it.path == "server.limits.busy_status" }.message
        assertTrue(listOf("429", "503").all(status::contains), status)
    }
}

private const val LOAD_API_KEY = """{ kind = "api-key", env = "BOX_KEY" }"""

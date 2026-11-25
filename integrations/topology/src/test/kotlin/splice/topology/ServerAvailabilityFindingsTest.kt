// `[server.state]` and `[server.probe]` at boot (docs/specs/server-mode.md): the external upstream signal and the
// pooled upstreams' readiness probe, each refusal naming its key, beside the findings ServerConfigFindingsTest pins.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyFinding
import java.nio.file.Path

class ServerAvailabilityFindingsTest {
    @TempDir lateinit var directory: Path

    /** A head served as `claude-team`, on an API-key provider. */
    private fun head(): String =
        """
        [providers.box]
        dialect = "openai-chat"
        base_url = "http://127.0.0.1:8080/v1"
        auth = $API_KEY

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
    fun `a state source and a completion probe boot with the words, cadence and gate the operator set`() {
        val lines = """
            [server.state]
            file = "box-state.txt"
            every_seconds = 10
            message_starting = "The box is warming up."
            message_off = "The box is off until {until}."
            retry_after_starting = 45
            [server.probe]
            kind = "completion"
            model = "team-model"
            timeout_seconds = 20
            every_seconds = 90
            at_start = false
        """.trimIndent()
        val read = ConfigFindings.read(server(lines) + head(), directory, configDir = directory)
        val config = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
        assertEquals("box-state.txt", config.state?.file)
        assertEquals(10, config.state?.everySeconds)
        assertEquals("The box is warming up.", config.state?.messageStarting)
        assertEquals("The box is off until {until}.", config.state?.messageOff)
        assertEquals(45, config.state?.retryAfterStarting)
        assertEquals("completion", config.probe.kind)
        assertEquals("team-model", config.probe.model)
        assertEquals(20, config.probe.timeoutSeconds)
        assertEquals(90, config.probe.everySeconds)
        assertEquals(false, config.probe.atStart)
    }

    @Test
    fun `each state source boots on its own, and one alone takes the documented defaults`() {
        val sources = listOf(
            "url = \"http://box.lan:8080/state\"",
            "command = [\"/usr/local/bin/box-state\", \"--json\"]",
        )
        for (source in sources) {
            val toml = server("[server.state]\n$source") + head()
            assertInstanceOf(ConfigRead.Ready::class.java, ConfigFindings.read(toml, directory, configDir = directory))
        }
        val single = server("[server.state]\n${sources.first()}") + head()
        val read = ConfigFindings.read(single, directory, configDir = directory)
        val config = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
        assertEquals(5, config.state?.everySeconds)
        assertEquals("Upstream is starting; retry shortly.", config.state?.messageStarting)
        assertEquals("Upstream is off until {until}.", config.state?.messageOff)
        assertEquals(30, config.state?.retryAfterStarting)
        assertEquals("health", config.probe.kind)
        assertEquals(null, config.probe.model)
        assertEquals(30, config.probe.timeoutSeconds)
        assertEquals(60, config.probe.everySeconds)
        assertEquals(true, config.probe.atStart)
    }

    @Test
    fun `a file with no state table configures no external state`() {
        val read = ConfigFindings.read(server() + head(), directory, configDir = directory)
        val config = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology.server
        assertEquals(null, config.state)
    }

    @Test
    fun `each wrong state field is refused by its key`() {
        val cases = listOf(
            "file = \"box-state.txt\"\nurl = \"http://box.lan:8080/state\"" to "server.state",
            "every_seconds = 10\nmessage_off = \"Off until {until}.\"" to "server.state",
            "file = \" \"" to "server.state.file",
            "url = \"ftp://box.lan/state\"" to "server.state.url",
            "command = []" to "server.state.command",
            "command = [\" \"]" to "server.state.command",
            "file = \"box-state.txt\"\nevery_seconds = 0" to "server.state.every_seconds",
            "file = \"box-state.txt\"\nretry_after_starting = -1" to "server.state.retry_after_starting",
        )
        for ((lines, path) in cases) {
            assertEquals(listOf(path), refused(server("[server.state]\n$lines") + head()).map { it.path }, lines)
        }
    }

    @Test
    fun `each wrong probe field is refused by its key`() {
        val cases = listOf(
            "kind = \"ping\"" to "server.probe.kind",
            "kind = \"completion\"" to "server.probe.model",
            "kind = \"completion\"\nmodel = \" \"" to "server.probe.model",
            "timeout_seconds = 0" to "server.probe.timeout_seconds",
            "every_seconds = 0" to "server.probe.every_seconds",
        )
        for ((lines, path) in cases) {
            assertEquals(listOf(path), refused(server("[server.probe]\n$lines") + head()).map { it.path }, lines)
        }
    }

    @Test
    fun `wrong state and probe fields are refused together in one read`() {
        val lines = """
            [server.state]
            url = "not a url"
            every_seconds = 0
            [server.probe]
            kind = "completion"
            timeout_seconds = 0
        """.trimIndent()
        assertEquals(
            setOf(
                "server.state.url",
                "server.state.every_seconds",
                "server.probe.model",
                "server.probe.timeout_seconds",
            ),
            refused(server(lines) + head()).map { it.path }.toSet(),
        )
    }

    @Test
    fun `a state or probe table alone declares a server, asked where it listens and what it serves`() {
        val tables = listOf(
            "[server.state]\nfile = \"box-state.txt\"\n",
            "[server.probe]\nkind = \"completion\"\nmodel = \"team-model\"\n",
        )
        for (table in tables) {
            val paths = refused(table + head()).map { it.path }.toSet()
            assertEquals(setOf("server.listen", "server.commands"), paths, table)
        }
    }
}

private const val API_KEY = """{ kind = "api-key", env = "BOX_KEY" }"""

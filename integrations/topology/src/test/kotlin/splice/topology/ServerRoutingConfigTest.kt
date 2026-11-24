// `[server.client]` and `[server.aliases]` at boot (#791): the client minimum a launcher is held to, and the one-segment
// aliases a client that cannot change its base path reaches a served head or the team's door at, each refusal naming
// its key and its reason.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyFinding
import java.nio.file.Path

class ServerRoutingConfigTest {
    @TempDir lateinit var directory: Path

    /** A head served as `claude-team`, on a provider signing with [auth]. */
    private fun head(dialect: String = "openai-chat", auth: String = API_KEY): String =
        """
        [providers.box]
        dialect = "$dialect"
        base_url = "http://127.0.0.1:8080/v1"
        auth = $auth

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

    private fun server(lines: String = "", commands: String = "\"claude-team\""): String =
        "[server]\nlisten = \"0.0.0.0:3443\"\ncommands = [$commands]\n$lines\n"

    /** The findings under `server`, as `check-config` lists them. */
    private fun refused(toml: String): List<TopologyFinding> {
        val read = ConfigFindings.read(toml, directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Refused::class.java, read).findings.filter { it.path.startsWith("server") }
    }

    // #791: the client minimum read from a launcher's own header or a check-in, and what an older one is answered with.
    @Test
    fun `a client table with its own header, status, words and check-in boots, and a wrong header or status is not`() {
        val good = """
            [server.client]
            minimum = "0.10.0"
            version_header = "x-team-launcher"
            outdated_status = 426
            outdated_message = "Run team-update: {version} is older than {minimum}."
            checkin = true
        """.trimIndent()
        val ready = ConfigFindings.read(server(good) + head(), directory, configDir = directory)
        val client = assertInstanceOf(ConfigRead.Ready::class.java, ready, ready.toString()).topology.server.client
        assertEquals("x-team-launcher", client.header)
        assertEquals(426, client.outdatedStatus)
        assertTrue(client.checkin)
        for (status in listOf(200, 400)) {
            val read = ConfigFindings.read(
                server("[server.client]\noutdated_status = $status") + head(),
                directory,
                configDir = directory,
            )
            assertInstanceOf(ConfigRead.Ready::class.java, read, "$status: $read")
        }

        val wrong = "[server.client]\nversion_header = \"x team: launcher\"\noutdated_status = 503"
        val bad = refused(server(wrong) + head())
        assertEquals(listOf("server.client.version_header", "server.client.outdated_status"), bad.map { it.path })
    }

    // #791: an alias is one plain path segment standing for a served command or the door.
    @Test
    fun `aliases to a served command or the door boot, and every alias that cannot be served is refused by name`() {
        val ready = ConfigFindings.read(
            server("[server.aliases]\n\"/v1\" = \"claude-team\"\n\"/openai\" = \"door\"") + head(),
            directory,
            configDir = directory,
        )
        val served = assertInstanceOf(ConfigRead.Ready::class.java, ready, ready.toString()).topology.server
        assertEquals(mapOf("v1" to "claude-team", "openai" to "door"), served.aliasSegments)

        val aliases = """
            [server.aliases]
            "v1" = "claude-team"
            "/a/b" = "claude-team"
            "/server" = "door"
            "/health" = "door"
            "/claude-team" = "door"
            "/door" = "claude-team"
            "/v2" = "claude-nobody"
        """.trimIndent()
        val found = refused(server(aliases) + head())
        assertEquals(7, found.size, found.toString())
        assertTrue(found.all { it.path == "server.aliases" }, found.toString())
        val named = listOf("'v1'", "'/a/b'", "'/server'", "'/health'", "'/claude-team'", "'/door'", "'claude-nobody'")
        for (alias in named) {
            assertTrue(found.any { alias in it.message }, "$alias: $found")
        }
    }

    // #791: the listener serves a head at its command and at its head key alike, and a provider with no head only at
    // the door, so boot judges an alias by the same names the listener answers to.
    @Test
    fun `an alias may not take a served head's key, stand for a headless provider, or be written twice`() {
        val shadow = refused(server("[server.aliases]\n\"/box\" = \"door\"") + head()).single()
        assertTrue("'/box'" in shadow.message && "already a path" in shadow.message, shadow.message)

        val byKey = server("[server.aliases]\n\"/v1\" = \"box\"") + head()
        assertInstanceOf(ConfigRead.Ready::class.java, ConfigFindings.read(byKey, directory, configDir = directory))

        val headless = head().substringBefore("[heads.box]")
        val provider = refused(server("[server.aliases]\n\"/v1\" = \"box\"", commands = "\"box\"") + headless).single()
        assertTrue("no head" in provider.message && "\"door\"" in provider.message, provider.message)
        val atDoor = server("[server.aliases]\n\"/v1\" = \"door\"", commands = "\"box\"") + headless
        assertInstanceOf(ConfigRead.Ready::class.java, ConfigFindings.read(atDoor, directory, configDir = directory))

        val twice = refused(server("[server.aliases]\n\"/v1\" = \"claude-team\"\n\" /v1\" = \"door\"") + head())
        assertEquals(1, twice.size, twice.toString())
        assertTrue("'/v1' is written more than once" in twice.single().message, twice.toString())
    }

    // #791: the decode keeps only the last of one key written twice, so an exact repeat never reaches the alias checks
    // and must be refused before it; boot names the table and the key.
    @Test
    fun `an alias key written twice exactly, in either quotes, is refused rather than the last one kept`() {
        for (again in listOf("\"/v1\"", "'/v1'")) {
            val toml = server("[server.aliases]\n\"/v1\" = \"claude-team\"\n$again = \"door\"") + head()
            val read = ConfigFindings.read(toml, directory, configDir = directory)
            val found = assertInstanceOf(ConfigRead.Refused::class.java, read, "$again: $read").findings
            val twice = found.single { "duplicate key /v1" in it.message }
            assertTrue("[server.aliases]" in twice.message, twice.message)
        }
    }

    // #791: a client's URL resolves `.` and `..` away before it is sent, so such an alias would never be asked for;
    // an empty, nested, encoded or spaced segment is no single segment either.
    @Test
    fun `a dot segment or any alias that is no single plain segment is refused by name`() {
        val aliases = listOf("/.", "/..", "/v1/.", "/v1/..", "/", "/v1/", "/%2e", "/v 1")
        for (alias in aliases) {
            val found = refused(server("[server.aliases]\n\"$alias\" = \"claude-team\"") + head())
            assertEquals(1, found.size, "$alias: $found")
            assertTrue("'$alias'" in found.single().message, found.toString())
        }
        for (dots in listOf("/.", "/..")) {
            val found = refused(server("[server.aliases]\n\"$dots\" = \"claude-team\"") + head()).single()
            assertTrue("dot segment" in found.message, found.message)
        }
    }

    // #791: the listener answers `/server/...` and `/health` itself, so an alias standing for a head whose command or key
    // is so named would never reach that head.
    @Test
    fun `an alias may not stand for one of the listener's own routes, even where a served head is so named`() {
        for (name in listOf("server", "health")) {
            val named = head().replace("command = \"claude-team\"", "command = \"$name\"")
            val found = refused(server("[server.aliases]\n\"/v1\" = \"$name\"", commands = "\"$name\"") + named)
            val reserved = found.single { it.path == "server.aliases" }
            assertTrue("'/v1' stands for '$name'" in reserved.message, found.toString())
            assertTrue("listener's own routes" in reserved.message, reserved.message)
        }
    }
}

private const val API_KEY = """{ kind = "api-key", env = "BOX_KEY" }"""

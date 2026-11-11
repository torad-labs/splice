// Nov 11, 2026 (the Temple's lead, on the President's ruling of that day): `splice mcp install temple --endpoint URL`,
// for a Temple that serves its MCP tools itself. The two halves of the command that need no daemon run here as the
// command runs them: the arguments are parsed and the registration is written. Then the launch plan's own reader says
// what every client is handed.
package splice.app.cli.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

private const val ENDPOINT = "http://127.0.0.1:7740/mcp"

// why: the old Temple's address, which its stdio tools server was given as TEMPLE_URL
private const val APP = "http://127.0.0.1:7741"

class McpServiceEndpointInstallTest {
    @TempDir
    lateinit var tmp: Path

    private val nothingSet = EnvReader { null }
    private val parser = McpServiceRequestParser()
    private val home: Path get() = tmp.resolve("home")
    private val source by lazy { TempleMcpEntry(home, nothingSet, StatePaths(tmp.resolve("state"), nothingSet, home)) }

    @Test
    fun `an install by endpoint replaces a registered launch with the Temple's own HTTP entry, and back again`() {
        val launch = Files.writeString(tmp.resolve("launch.json"), """{"command":"bun","args":["tools-server.ts"]}""")

        install("--launch-file", launch.toString(), "--url", APP)
        val launched = json("""{"command":"bun","args":["tools-server.ts"],"env":{"TEMPLE_URL":"$APP"}}""")
        assertEquals(launched, source.server()?.entry)

        install("--endpoint", ENDPOINT)
        assertEquals(json("""{"type":"http","url":"$ENDPOINT"}"""), source.server()?.entry)

        install("--launch-file", launch.toString(), "--url", APP)
        assertEquals(launched, source.server()?.entry, "a launch keeps nothing of the endpoint it replaces")
    }

    @Test
    fun `an endpoint is refused beside a launch, beside the app's address, and when it is no http address`() {
        assertNull(asked("--endpoint", ENDPOINT, "--script", tmp.resolve("tools-server.ts").toString()))
        assertNull(asked("--endpoint", ENDPOINT, "--url", APP))
        assertNull(asked("--endpoint", "file:///etc/hosts"))
        assertNull(asked("--endpoint", "127.0.0.1:7740/mcp"))
    }

    private fun asked(vararg flags: String): McpServiceRequestParser.Request? =
        parser.parse(listOf("install", "temple") + flags).request

    private fun install(vararg flags: String) {
        McpServiceRegistration(nothingSet).persist(source, checkNotNull(asked(*flags)) { "the install was refused" })
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
}

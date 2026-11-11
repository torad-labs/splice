// Nov 11, 2026 (the Temple's lead, on the President's ruling of that day): the rebuilt Temple serves its MCP tools
// itself, on one HTTP endpoint, and its registration names that endpoint instead of a process to launch. Every session
// splice started was still handed the old Temple's stdio server, whose files were gone, so no session held a Temple
// tool. What a session holds is the file its launch hands Claude Code (HostedMcpOverlay), so that file is read here.
package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

// why: the entry `splice mcp install temple --endpoint` registers for a Temple that answers on its own port
private const val ENDPOINT = """{"type":"http","url":"http://127.0.0.1:7740/mcp"}"""
private const val REGISTERED = """{"server":$ENDPOINT,"provenance":"registered_service"}"""

class TempleEndpointLaunchTest {
    @TempDir
    lateinit var tmp: Path

    private val state: Path get() = tmp.resolve("state")
    private val home: Path get() = tmp.resolve("home")
    private val nothingSet = EnvReader { null }
    private val temple by lazy { TempleMcpEntry(home, nothingSet, StatePaths(state, nothingSet, home)) }
    private val sharing by lazy {
        val key = McpAccessKey(stateDir = state) { "synthetic" }
        McpSharing(enabled = true, exclude = emptySet(), endpointPrefix = "http://127.0.0.1:3096/mcp/", bearer = key)
    }

    @Test
    fun `a Temple registered by its own endpoint reaches a wrapped launch as that entry, and splice hosts nothing`() {
        Files.createDirectories(temple.registrationFile.parent)
        Files.writeString(temple.registrationFile, REGISTERED)

        val file = checkNotNull(HostedMcpOverlay(home, sharing, state, LogSink {}, temple).file("claude-splice")) {
            "the launch was handed no --mcp-config file, so its session holds no Temple tool"
        }

        val servers = Json.parseToJsonElement(Files.readString(file)).jsonObject.getValue("mcpServers").jsonObject
        assertEquals(mapOf("temple" to Json.parseToJsonElement(ENDPOINT)), servers.toMap())
        assertEquals(emptySet<String>(), sharing.plan(temple.inputs()).hosted.keys, "the Temple serves it itself")
    }
}

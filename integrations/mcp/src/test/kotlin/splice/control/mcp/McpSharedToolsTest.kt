package splice.control.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpSharing
import splice.control.mcp.hub.McpHubCalls
import splice.control.mcp.hub.McpHubOff
import splice.core.util.LogSink

class McpSharedToolsTest : McpHostFixture() {
    @Test
    fun `native snapshots see only the current explicitly shared plan and cannot add another server`() {
        val raw = json.parseToJsonElement(
            """{"tools":[{"name":"native","inputSchema":{"type":"object"}}]}""",
        ).jsonObject
        val declarations = buildJsonObject {
            put("fake", FakeMcpServer.entry())
            put("session", buildJsonObject { put("command", "fixture"); put("cwd", "/synthetic-project") })
            put("remote", buildJsonObject { put("type", "http"); put("url", "http://127.0.0.1:1/mcp") })
        }
        val hub = object : McpHubCalls by McpHubOff("fixture") {
            override fun sharedTools(specs: Map<String, McpServerSpec>): Map<String, JsonObject> {
                return mapOf("fake" to raw, "session" to raw, "remote" to raw, "unknown" to raw)
            }
        }
        host = host(declarations, hub)
        assertEquals(mapOf("fake" to raw), host.catalogs.sharedTools("codex"))
        assertEquals(mapOf("fake" to raw, "session" to null, "remote" to null), host.catalogs.searchCatalogs("codex"))
        host.sharing.rootsAsked(host.sharing.plan(declarations).hosted.getValue("fake"))
        assertTrue(host.catalogs.sharedTools("codex").isEmpty(), "current roots evidence removes the shared tuple")
        assertEquals(mapOf("fake" to null, "session" to null, "remote" to null), host.catalogs.searchCatalogs("codex"))
    }

    @Test
    fun `a real in-process cached listing must agree with the native retained snapshot`() = runBlocking {
        var native = emptyMap<String, JsonObject>()
        val hub = object : McpHubCalls by McpHubOff("fixture") {
            override fun sharedTools(specs: Map<String, McpServerSpec>): Map<String, JsonObject> = native
        }
        host = host(buildJsonObject { put("fake", FakeMcpServer.entry()) }, hub)
        val session = init()
        val response = host.post("fake", session, MCP_HOST_LIST)
        assertEquals(200, response.status)
        val raw = json.parseToJsonElement(checkNotNull(response.body)).jsonObject.getValue("result").jsonObject
        assertEquals(mapOf("fake" to raw), host.catalogs.sharedTools("codex"))
        native = mapOf("fake" to raw)
        assertEquals(mapOf("fake" to raw), host.catalogs.sharedTools("codex"))
        native = mapOf("fake" to JsonObject(raw + ("different-metadata" to JsonPrimitive(true))))
        assertFalse(host.catalogs.sharedTools("codex").containsKey("fake"), "disagreement cannot select either catalog")
        native = emptyMap()
        assertEquals(mapOf("fake" to raw), host.catalogs.sharedTools("codex"))
    }

    private fun host(declarations: JsonObject, hub: McpHubCalls): McpHost = McpHost(
        McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "fixture" }, DirectoryProbe { false }),
        { declarations },
        McpHostConfig(runtime = McpHostConfig.Runtime(clock = clock)),
        log = LogSink {},
        hub = hub,
    )
}

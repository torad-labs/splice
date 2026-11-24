// NEW: modes-mcp (Nov 21, 2026) — a server the daemon answers itself, as a client meets it through the host: it lists
// its tools and resources with their `_meta` untouched, answers a call and a read in the reply to the request, keeps
// whatever session id the client holds, and leaves every other name to the hosted servers.
package splice.control.mcp.inprocess

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import splice.control.mcp.McpHost
import splice.control.mcp.McpReply
import splice.core.util.LogSink

class InProcessMcpServedTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A server with one tool that shows an app, and the app's page as its one resource. */
    private class Harbor : InProcessMcpServer {
        val calls = mutableListOf<String>()

        override fun tools(): JsonArray = JsonArray(
            listOf(
                buildJsonObject {
                    put("name", "show_tide")
                    put("description", "Shows the tide table.")
                    put("inputSchema", buildJsonObject { put("type", "object") })
                    put(
                        "_meta",
                        buildJsonObject { put("ui", buildJsonObject { put("resourceUri", "ui://harbor/tide") }) },
                    )
                },
            ),
        )

        override suspend fun call(tool: String, arguments: JsonObject, session: String?): JsonObject {
            calls += "$tool ${arguments["port"]?.jsonPrimitive?.content} for $session"
            return buildJsonObject {
                put(
                    "content",
                    JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "High at 04:10.") })),
                )
                put("isError", false)
            }
        }

        override fun resources(): JsonArray = JsonArray(
            listOf(
                buildJsonObject {
                    put("uri", "ui://harbor/tide")
                    put("name", "tide")
                    put("mimeType", "text/html;profile=mcp-app")
                },
            ),
        )

        override fun instructions(): String = "Show the tide when asked."

        override fun read(uri: String): JsonArray? = if (uri != "ui://harbor/tide") {
            null
        } else {
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("uri", uri)
                        put("mimeType", "text/html;profile=mcp-app")
                        put("text", "<h1>Tide</h1>")
                    },
                ),
            )
        }
    }

    private val harbor = Harbor()

    /** A host that answers `harbor` itself, for a client on the head `acme` only, and hosts nothing else. */
    private val host = McpHost(
        McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "k" }, DirectoryProbe { false }),
        { JsonObject(emptyMap()) },
        log = LogSink { },
    ).also { it.inProcess = InProcessMcp { name, head -> harbor.takeIf { name == "harbor" && head == "acme" } } }

    private fun ask(body: String, session: String? = null, name: String = "harbor", head: String? = "acme"): McpReply =
        runBlocking { host.post(name, session, body, head = head) }

    private fun result(reply: McpReply): JsonObject {
        assertEquals(200, reply.status, reply.body)
        return json.parseToJsonElement(reply.body!!).jsonObject.getValue("result").jsonObject
    }

    @Test
    fun `a client initializes, lists the tool with its _meta, and calls it`() {
        val opened = ask(
            """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                """"params":{"protocolVersion":"2025-06-18","capabilities":{}}}""",
        )
        val said = result(opened)
        assertEquals("2025-06-18", said.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("harbor", said.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("Show the tide when asked.", said.getValue("instructions").jsonPrimitive.content)
        assertTrue(
            "tools" in said.getValue(
                "capabilities",
            ).jsonObject && "resources" in said.getValue("capabilities").jsonObject,
        )
        val session = checkNotNull(opened.sessionId) { "initialize gives the client a session id" }
        assertEquals(202, ask("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session).status)

        val tool = result(
            ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session),
        ).getValue("tools").jsonArray.single().jsonObject
        assertEquals("show_tide", tool.getValue("name").jsonPrimitive.content)
        assertEquals(
            "ui://harbor/tide",
            tool.getValue("_meta").jsonObject.getValue("ui").jsonObject.getValue("resourceUri").jsonPrimitive.content,
        )

        val called = result(
            ask(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call",""" +
                    """"params":{"name":"show_tide","arguments":{"port":"north"}}}""",
                session,
            ),
        )
        assertEquals(
            "High at 04:10.",
            called.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals(listOf("show_tide north for $session"), harbor.calls)
    }

    @Test
    fun `its resources are listed and read, and one it does not have is refused by name`() {
        val listed = result(
            ask("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""", "s-1"),
        ).getValue("resources").jsonArray.single().jsonObject
        assertEquals("text/html;profile=mcp-app", listed.getValue("mimeType").jsonPrimitive.content)
        val read =
            result(
                ask(
                    """{"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"ui://harbor/tide"}}""",
                    "s-1",
                ),
            )
        assertEquals(
            "<h1>Tide</h1>",
            read.getValue("contents").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )

        val missing =
            ask("""{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"ui://harbor/chart"}}""", "s-1")
        assertEquals(
            """{"jsonrpc":"2.0","id":6,"error":{"code":-32002,"message":"Resource not found: ui://harbor/chart"}}""",
            missing.body,
        )
    }

    // The host adopts a session id it does not know for a hosted server, because a client told its session is unknown
    // stops using the server for good (V4-148). Here nothing is kept for a client, so an id is never unknown.
    @Test
    fun `a session id the daemon never gave is answered, as after a restart`() {
        val tools = result(ask("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""", "from-before-the-restart"))
        assertEquals(1, tools.getValue("tools").jsonArray.size)
    }

    @Test
    fun `a tool it does not have and a method it does not speak are errors under the request's id`() {
        assertEquals(
            """{"jsonrpc":"2.0","id":8,"error":{"code":-32602,"message":"Unknown tool: sink_ship"}}""",
            ask("""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"sink_ship"}}""", "s-1").body,
        )
        assertEquals(
            """{"jsonrpc":"2.0","id":9,"error":{"code":-32601,"message":"Method not found: prompts/list"}}""",
            ask("""{"jsonrpc":"2.0","id":9,"method":"prompts/list"}""", "s-1").body,
        )
        assertTrue(harbor.calls.isEmpty())
    }

    @Test
    fun `the name is the daemon's only for the head it is on, and every other name goes to the hosted servers`() {
        assertTrue(host.answeredHere("harbor", "acme"))
        assertFalse(host.answeredHere("harbor", "globex"))
        assertFalse(host.answeredHere("harbor", null))
        // on another head the name is nobody's: the hosted path answers, in its own words
        val elsewhere = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", head = "globex")
        assertEquals(503, elsewhere.status)
        assertEquals(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"'harbor' is not a hosted MCP server"}}""",
            elsewhere.body,
        )
        assertNull(elsewhere.sessionId)
    }
}

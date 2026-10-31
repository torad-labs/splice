// S8 #16: Settings' Test has its own bounds. The start is bounded by the handshake budget and the tool list by a short
// one of its own, so a hung server answers "timeout" while the person is looking, and mcpRequestTimeoutMs stays for
// real tool calls.
package splice.control.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class McpProbeTest {
    private val global = buildJsonObject { put("fake", FakeMcpServer.entry()) }
    private val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "k" }, DirectoryProbe { false })
    private var host: McpHost? = null

    private fun hostWith(config: McpHostConfig): McpHost =
        McpHost(sharing, { global }, config, log = { }).also { host = it }

    @AfterEach
    fun stop() {
        host?.stop()
    }

    private fun answer(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a server that cannot list its tools in the time given is given up on, by kind`() = runBlocking {
        val result = answer(hostWith(McpHostConfig(testListTimeout = Duration.ZERO)).probe.test("fake").json())
        assertEquals("false", result.getValue("ok").jsonPrimitive.content, "$result")
        assertEquals("timeout", result.getValue("kind").jsonPrimitive.content, "$result")
    }

    @Test
    fun `a handshake the child never finishes is given up on, by kind`() = runBlocking {
        val result = answer(hostWith(McpHostConfig(initializeTimeout = { Duration.ZERO })).probe.test("fake").json())
        assertEquals("false", result.getValue("ok").jsonPrimitive.content, "$result")
        assertEquals("timeout", result.getValue("kind").jsonPrimitive.content, "$result")
    }

    @Test
    fun `a child that is gone before its handshake could not be started, by kind`() = runBlocking {
        // A contained child whose program is missing goes this way: systemd-run starts, its exec fails, and it
        // exits, often before the initialize line is written. This launcher hands over a child that has already
        // exited, so the write always meets a dead one.
        val exited = McpProcessLauncher { spec ->
            ProcessBuilder(listOf(spec.command) + spec.args).start().also { it.waitFor() }
        }
        val gone = buildJsonObject { put("gone", buildJsonObject { put("command", "true") }) }
        val tested = McpHost(sharing, { gone }, McpHostConfig(), launcher = exited, log = { }).also { host = it }
        val result = answer(tested.probe.test("gone").json())
        assertEquals("false", result.getValue("ok").jsonPrimitive.content, "$result")
        assertEquals("spawn", result.getValue("kind").jsonPrimitive.content, "$result")
    }

    @Test
    fun `a server that answers in time lists its tools and leaves no session behind`() = runBlocking {
        val tested = hostWith(McpHostConfig(requestTimeout = { 1.minutes }))
        val result = answer(tested.probe.test("fake").json())
        assertEquals("true", result.getValue("ok").jsonPrimitive.content, "$result")
        assertFalse(result.containsKey("kind"), "$result")
        val sessions = answer(tested.statusJson()).getValue("servers").jsonObject.getValue("fake").jsonObject
        assertEquals("0", sessions.getValue("sessions").jsonPrimitive.content)
    }
}

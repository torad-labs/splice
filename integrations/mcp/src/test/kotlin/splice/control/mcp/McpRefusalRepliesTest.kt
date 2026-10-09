// NEW: the host's refusals reach the client as the same status and the same bytes they did when a refusal was an exception.
package splice.control.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class McpRefusalRepliesTest : McpHostFixture() {

    /** The one reading of a refusal: its status, and its body byte for byte. */
    private fun assertRefusal(reply: McpReply, status: Int, expectedBody: String) {
        assertEquals(status, reply.status)
        assertEquals(expectedBody, reply.body)
    }

    @Test
    fun `initializing a name that is not hosted answers 503 with the words`(@TempDir dir: Path) = runBlocking {
        boot(dir)
        val reply = host.post("nope", null, MCP_HOST_INIT)
        assertRefusal(
            reply,
            503,
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"'nope' is not a hosted MCP server"}}""",
        )
    }

    @Test
    fun `a request during the crash backoff answers 200 with the words under the request id`(
        @TempDir dir: Path,
    ) = runBlocking {
        boot(dir)
        val a = init()
        val crash = """{"jsonrpc":"2.0","id":9,"method":"tools/call",""" +
            """"params":{"name":"echo","arguments":{"op":"crash"}}}"""
        host.post("fake", a, crash)
        call(a, 1, "echo", "x")
        host.post("fake", a, crash)
        val reply = host.post("fake", a, MCP_HOST_LIST)
        assertRefusal(
            reply,
            200,
            """{"jsonrpc":"2.0","id":3,"error":{"code":-32000,""" +
                """"message":"hosted MCP server 'fake' keeps crashing (2 times); next restart in 6 s"}}""",
        )
    }

    @Test
    fun `the reading of a refusal fails on a changed status, code, id or words`() {
        val good = """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"m"}}"""
        assertRefusal(McpReply(503, good), 503, good)
        listOf(
            McpReply(200, good),
            McpReply(503, good.replace("-32000", "-32603")),
            McpReply(503, good.replace("\"id\":1", "\"id\":2")),
            McpReply(503, good.replace("\"m\"", "\"other\"")),
        ).forEach { mutant ->
            assertThrows(AssertionError::class.java) { assertRefusal(mutant, 503, good) }
        }
    }
}

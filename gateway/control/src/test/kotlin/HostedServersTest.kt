import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import splice.control.mcp.HostedServers
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpHostConfig
import splice.control.mcp.McpHostException
import splice.control.mcp.McpSessions
import java.io.IOException

/** The registry alone, no child ever launched: a server reserved by an in-flight initialize is never
 *  the eviction victim, and eviction resumes once the reservation is released (review 3). */
class HostedServersTest {

    private var global = Json.parseToJsonElement(
        """{"a":{"command":"srv-a"},"b":{"command":"srv-b"}}""",
    ).jsonObject

    private fun registry(): HostedServers {
        val config = McpHostConfig(maxServers = 1)
        val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "K" }, DirectoryProbe { false })
        return HostedServers(
            sharing,
            { global },
            config,
            { throw IOException("never launched") },
            JsonRpcCodec(),
            { },
            McpSessions(config.clock),
        )
    }

    @Test
    fun `a tuple replaced between acquire and release still ends its reservation`() {
        val servers = registry()
        val original = global
        val a = servers.acquire("a")
        global = Json.parseToJsonElement("""{"a":{"command":"srv-replacement"}}""").jsonObject
        val replacement = servers.acquire("a")
        assertTrue(!servers.release("a", a), "old tuple no longer bound")
        assertTrue(!servers.release("a", replacement), "replacement never launched")
        assertTrue(!servers.reserved("a"))
        global = original
        val again = servers.acquire("a")
        assertTrue(servers.reserved("a"))
        assertTrue(!servers.release("a", again), "never launched, so not alive")
        // With the leak, a's ghost reservation would exclude it from eviction and capacity would refuse b.
        servers.acquire("b")
    }

    @Test
    fun `a reserved server is not evicted at capacity, a released one is`() {
        val servers = registry()
        val a = servers.acquire("a")
        val atCapacity = assertThrows(McpHostException::class.java) { servers.acquire("b") }
        assertTrue(atCapacity.message.orEmpty().contains("capacity"), atCapacity.message)
        assertSame(a, servers.get("a"))
        // release() answers "bound AND alive"; this registry never launched a child, so it is false
        // here while the binding itself stays until eviction.
        assertTrue(!servers.release("a", a), "a was never started, so it is not live")
        assertSame(a, servers.get("a"), "still bound after release")
        val b = servers.acquire("b")
        assertNotSame(a, b)
        assertEquals(listOf("b"), servers.names(), "a was evicted once unreserved")
        assertTrue(!servers.release("a", a), "a is no longer bound")
    }
}

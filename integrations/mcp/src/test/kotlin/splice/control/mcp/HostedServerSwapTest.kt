// A child that dies while a replacement is already in the process slot. The old child's exit is held open (the
// ChildReaped seam) so the swap happens inside that window, and a session that lists tools afterwards must get the
// new child's list, not the answer the old child gave.
package splice.control.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val WAIT_MS = 10_000L

class HostedServerSwapTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val global = buildGlobal()

    private fun buildGlobal(): JsonObject =
        json.parseToJsonElement("""{"fake":${FakeMcpServer.entry()}}""").jsonObject

    private val exitEntered = CountDownLatch(1)
    private val exitMayFinish = CountDownLatch(1)
    private val exits = AtomicInteger()

    /** The first child to exit is held in its exit wait until the test lets it go; later ones are reaped as usual. */
    private val holdFirstExit = ChildReaped { child ->
        if (exits.getAndIncrement() == 0) {
            exitEntered.countDown()
            check(exitMayFinish.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "the test never released the old child" }
        }
        child.waitFor(1, TimeUnit.SECONDS)
    }

    private fun registry(): HostedServers {
        val config = McpHostConfig()
        val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "K" }, DirectoryProbe { false })
        val launcher = McpProcessLauncher { spec ->
            ProcessBuilder(listOf(spec.command) + spec.args)
                .apply { environment().putAll(spec.env) }
                .start()
        }
        return HostedServers(sharing, { global }, config, launcher, JsonRpcCodec(), { }, McpSessions(config.clock))
            .apply { reaped = holdFirstExit }
    }

    private fun request(id: Int, method: String, params: String? = null): JsonObject {
        val tail = params?.let { ""","params":$it""" }.orEmpty()
        return json.parseToJsonElement("""{"jsonrpc":"2.0","id":$id,"method":"$method"$tail}""").jsonObject
    }

    private fun op(id: Int, name: String): JsonObject =
        request(id, "tools/call", """{"name":"echo","arguments":{"op":"$name"}}""")

    private suspend fun HostedServer.listing(id: Int): String {
        val reply = call("s", JsonPrimitive(id) as JsonElement, request(id, "tools/list"))
        return reply["result"]!!.jsonObject["listing"]!!.jsonPrimitive.content
    }

    @Test
    fun `a session listing tools after a replacement took the slot during the old child's exit gets the new list`() =
        runBlocking {
            val servers = registry()
            val server = (servers.acquire("fake") as McpResult.Served).value
            try {
                assertEquals("1", server.listing(1))
                server.call("s", JsonPrimitive(2), op(2, "notify"))
                assertEquals("2", server.listing(3), "the old child's second listing, now cached")

                val crashed = async(Dispatchers.Default) { server.call("s", JsonPrimitive(4), op(4, "crash")) }
                assertTrue(exitEntered.await(WAIT_MS, TimeUnit.MILLISECONDS), "the old child's exit was reached")

                val afterSwap = withTimeout(WAIT_MS) { server.listing(5) }

                assertEquals("1", afterSwap, "the replacement's first listing, not the old child's cached second one")
                exitMayFinish.countDown()
                withTimeout(WAIT_MS) { crashed.await() }
                Unit
            } finally {
                exitMayFinish.countDown()
                servers.closeAll("test")
            }
        }
}

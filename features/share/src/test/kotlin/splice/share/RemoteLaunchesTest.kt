// The context window a command another machine serves launches Claude Code with (docs/specs/server-mode.md, F7): the
// one the server declares for the model it pins, or the one this laptop pinned for the command in its place.
package splice.share

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.core.topology.RemoteConfig
import splice.launch.HeadTrees
import splice.launch.RemoteLaunch
import java.net.InetSocketAddress
import java.nio.file.Path

class RemoteLaunchesTest {
    @TempDir lateinit var home: Path

    /** A team's box serving `claude-team` on one model with a 200k window. */
    private val box = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/claude-team/launch") { exchange ->
            val models = SharedModels("big", listOf("big"), mapOf("big" to "Big"), SERVER_WINDOW, JsonArray(listOf()))
            val launch = SharedLaunch("gpu-box", "claude-team", "team", models, TIMEOUT_MS, SharedSignIn("", "Team"))
            val body = Json.encodeToString(SharedLaunch.serializer(), launch).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val address = "http://127.0.0.1:${box.address.port}/claude-team"

    @AfterEach
    fun stop() = box.stop(0)

    /** The window `claude-team` launches with, linked as [remote]. */
    private fun window(remote: RemoteConfig): Long {
        val trees = RemoteTrees { HeadTrees(home.resolve(".claude-$it")) }
        val policy = ClaudePolicy(emptySet(), emptySet())
        val launches = RemoteLaunches(mapOf("claude-team" to remote), trees, policy, Dispatchers.IO)
        val ready = assertInstanceOf(RemoteLaunch.Ready::class.java, runBlocking { launches.launch("claude-team") })
        return ready.spec.models.contextWindow
    }

    @Test
    fun `a linked command launches with the window its server declares`() {
        assertEquals(SERVER_WINDOW, window(RemoteConfig(address)))
    }

    @Test
    fun `a window the laptop pinned takes the server's place`() {
        assertEquals(64_000L, window(RemoteConfig(address, window = 64_000L)))
    }
}

private const val SERVER_WINDOW = 200_000L
private const val TIMEOUT_MS = 600_000L

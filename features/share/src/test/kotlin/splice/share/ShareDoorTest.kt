// The door on the share listener (docs/specs/door-network.md): what another machine's `claude` reads to launch, and the
// turns it posts, over real HTTP against a listener on loopback. What a person sees: a launch that names the machine, its
// models and its window; a turn that reaches the door; a daemon with no door that says so.
package splice.share

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ShareDoorTest {
    private val sharePort = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private var listener: ShareListener? = null

    private val launch = SharedDoorLaunch(
        machine = "devbox",
        window = 500_000,
        model = "gpt-6-astra",
        rows = listOf(
            SharedDoorRow("gpt-6-astra", "GPT-6 Astra", "claudex"),
            SharedDoorRow("kimi-k3", "Kimi K3", "kimi"),
        ),
    )

    private val door = object : ShareDoor {
        var ready = true
        var turns = 0
        var counts = 0

        override suspend fun launch(): SharedDoorLaunch? = launch.takeIf { ready }

        override suspend fun answer(call: ApplicationCall, counting: Boolean) {
            if (counting) counts++ else turns++
            call.respondText("{}")
        }
    }

    private fun listening(state: Path, withDoor: Boolean) {
        listener = ShareListener(
            ShareConfig(enabled = true, port = sharePort),
            object : SharedHeads {
                override val machine: String = "devbox"

                override fun offered(): List<SharedHead> = emptyList()
            },
            TurnKey(MgmtKey(StatePaths(baseOverride = state))),
            controlPort = TestPorts.reserve(),
            io = Dispatchers.IO,
            log = {},
            door = door.takeIf { withDoor },
        ).also { it.start() }
    }

    @AfterEach
    fun stop() {
        listener?.stop()
    }

    private fun send(path: String, post: Boolean = false, type: String = "application/json"): Pair<Int, String> {
        val uri = URI.create("http://127.0.0.1:$sharePort$path")
        val builder = HttpRequest.newBuilder(uri).header("Content-Type", type)
        val body = HttpRequest.BodyPublishers.ofString("{}")
        val request = (if (post) builder.POST(body) else builder.GET()).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    @Test
    fun `another machine reads the models, window and machine its claude launches with`(@TempDir state: Path) {
        listening(state, withDoor = true)
        val (status, body) = send("/door/launch")
        assertEquals(200, status)
        val read = Json.decodeFromString(SharedDoorLaunch.serializer(), body)
        assertEquals(launch, read)
    }

    @Test
    fun `a turn and a count posted to the door reach it`(@TempDir state: Path) {
        listening(state, withDoor = true)
        assertEquals(200, send("/door/v1/messages?beta=true", post = true).first)
        assertEquals(200, send("/door/v1/messages/count_tokens", post = true).first)
        assertEquals(1, door.turns)
        assertEquals(1, door.counts)
    }

    @Test
    fun `a turn that is not JSON is refused before the door sees it`(@TempDir state: Path) {
        listening(state, withDoor = true)
        assertEquals(400, send("/door/v1/messages", post = true, type = "text/plain").first)
        assertEquals(0, door.turns)
    }

    @Test
    fun `a door whose heads are still starting says when it opens`(@TempDir state: Path) {
        door.ready = false
        listening(state, withDoor = true)
        val (status, body) = send("/door/launch")
        assertEquals(503, status)
        assertTrue(body.contains("opens once its heads are up"), body)
    }

    @Test
    fun `a daemon with no door says devbox runs none`(@TempDir state: Path) {
        listening(state, withDoor = false)
        val (status, body) = send("/door/launch")
        assertEquals(404, status)
        assertTrue(body.contains("devbox runs no door"), body)
    }

    @Test
    fun `an unknown path under the door is a 404 in the machine's name`(@TempDir state: Path) {
        listening(state, withDoor = true)
        assertEquals(404, send("/door/v1/other", post = true).first)
    }
}

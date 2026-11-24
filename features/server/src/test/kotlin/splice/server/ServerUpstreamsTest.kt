// Server mode over a provider that is several servers (docs/specs/server-mode.md, F4), as its three kinds of people
// meet it: the operator who lists the upstreams and sets a person's preferred one, the person whose turn is handed to
// the head naming it, and the person who is told which upstreams are down when none can take a turn.
package splice.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.pool.UpstreamPool
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

internal class ServerUpstreamsTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()

    /** The upstream each turn that reached the head named as its person's, "" for a turn that named none. */
    private val named = CopyOnWriteArrayList<String>()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            named += exchange.requestHeaders.getFirst("x-splice-upstream").orEmpty()
            val answer = """{"type":"message","content":[{"type":"text","text":"from the box"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        start()
    }

    private val pool = UpstreamPool(
        "box",
        listOf(
            UpstreamConfig("gpu-1", "http://10.0.0.21:8080/v1"),
            UpstreamConfig("gpu-2", "http://10.0.0.22:8080/v1"),
        ),
        LogSink { },
    )

    /** The server's providers as the daemon would describe them: the head `team`'s is [pool]'s two servers. */
    private val rig = ServerRig(
        upstreams = object : HeadUpstreams {
            override fun all(): List<UpstreamPool> = listOf(pool)

            override fun of(head: String): UpstreamPool? = pool.takeIf { head == "team" }
        },
    )

    /** Server mode as the daemon opens it, over one head whose provider is [pool]'s two servers. */
    private fun serve(): ServerMode = rig.open(state, listOf(StandInHead(head.address.port)))

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
    }

    /** A turn as [key]'s holder sends it, with [sent] as a header of the caller's own. */
    private fun turn(key: String, sent: Pair<String, String>? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString("""{"model":"m","messages":[]}"""))
        sent?.let { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun set(mode: ServerMode, person: String, upstream: String): ServerAnswer =
        runBlocking { mode.personApi.set(person, buildJsonObject { put("upstream", upstream) }) }

    @Test
    fun `the list names each upstream with its provider, address, health and turns in flight`() {
        val mode = serve()
        pool.checked("gpu-2", healthy = false, said = "HTTP 503")
        val held = checkNotNull(pool.lease())
        val listed = assertInstanceOf(ServerAnswer.Done::class.java, mode.upstreamApi.list()).body
        val rows = listed.getValue("upstreams").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                row("box", "gpu-1", "http://10.0.0.21:8080/v1", healthy = true, inflight = 1),
                row("box", "gpu-2", "http://10.0.0.22:8080/v1", healthy = false, inflight = 0),
            ),
            rows,
        )
        held.release()
    }

    private fun row(provider: String, name: String, url: String, healthy: Boolean, inflight: Int): JsonObject =
        buildJsonObject {
            put("provider", provider)
            put("name", name)
            put("url", url)
            put("healthy", healthy)
            put("inflight", inflight)
        }

    @Test
    fun `a person set to an upstream has their turns handed to the head naming it, and no one else's are`() {
        val mode = serve()
        val pia = rig.keyFor("pia@team.example")
        val ann = rig.keyFor("ann@team.example")
        val answer = assertInstanceOf(ServerAnswer.Done::class.java, set(mode, "pia@team.example", "gpu-2")).body
        assertEquals(JsonPrimitive("gpu-2"), answer["upstream"])

        assertEquals(200, turn(pia).statusCode())
        assertEquals(200, turn(ann).statusCode())
        // A caller's own header never chooses: the operator's setting does, and its absence chooses nothing.
        assertEquals(200, turn(ann, sent = "x-splice-upstream" to "gpu-2").statusCode())
        assertEquals(200, turn(pia, sent = "x-splice-upstream" to "gpu-1").statusCode())
        assertEquals(listOf("gpu-2", "", "", "gpu-2"), named)

        val cleared = assertInstanceOf(ServerAnswer.Done::class.java, set(mode, "pia@team.example", "")).body
        assertEquals(JsonNull, cleared["upstream"])
        assertEquals(200, turn(pia).statusCode())
        assertEquals("", named.last(), "a preference taken back is handed to the head no more")
    }

    @Test
    fun `an upstream no provider lists is refused by name, with the ones there are`() {
        val mode = serve()
        val refused = assertInstanceOf(ServerAnswer.Refused::class.java, set(mode, "pia@team.example", "gpu-9"))
        assertEquals(400, refused.status.value)
        assertTrue(listOf("gpu-9", "gpu-1", "gpu-2").all(refused.why::contains), refused.why)
        val notText = runBlocking { mode.personApi.set("pia@team.example", buildJsonObject { put("upstream", 2) }) }
        assertInstanceOf(ServerAnswer.Refused::class.java, notText)
        val caps = runBlocking { mode.personApi.set("pia@team.example", buildJsonObject { put("inflight", 3) }) }
        val kept = assertInstanceOf(ServerAnswer.Done::class.java, caps).body
        assertEquals(JsonNull, kept["upstream"], "a refused upstream was stored")
    }

    @Test
    fun `with no upstream up a turn is refused at once naming them, counts toward no cap, and is served once one is`() {
        val mode = serve()
        val key = rig.keyFor("ada@team.example")
        runBlocking { mode.personApi.set("ada@team.example", buildJsonObject { put("rpm", 1) }) }
        pool.checked("gpu-1", healthy = false, said = "HTTP 503")
        pool.checked("gpu-2", healthy = false, said = "no answer")

        val refused = turn(key)
        assertEquals(503, refused.statusCode())
        val error = Json.parseToJsonElement(refused.body()).jsonObject.getValue("error").jsonObject
        val message = error.getValue("message").jsonPrimitive.content
        assertTrue(listOf("no upstream of box is up", "gpu-1", "gpu-2").all(message::contains), message)
        assertTrue(named.isEmpty(), "a turn no upstream could take reached the head")

        pool.checked("gpu-2", healthy = true, said = "HTTP 200")
        assertEquals(200, turn(key).statusCode(), "the refused turn spent the person's one turn a minute")
    }
}

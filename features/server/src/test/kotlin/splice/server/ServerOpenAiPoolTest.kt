// Server mode's OpenAI route over a provider that is several servers (docs/specs/server-mode.md, F4): an OpenAI-style
// client's completion is placed the way a turn is, on the server its person prefers or the least busy one, passes
// over a server that takes no connection, and is told which upstreams are down when none is left.
package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.pool.UpstreamPool
import splice.core.testing.TestPorts
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ServerOpenAiPoolTest {
    @TempDir lateinit var state: Path

    private val gpu1 = ScriptedBox("gpu-1")
    private val gpu2 = ScriptedBox("gpu-2")
    private var opened: OpenAiRig? = null
    private lateinit var pool: UpstreamPool

    /** Server mode over `claude-team`, whose provider is two servers at [first] and [second]. */
    private fun serve(first: String = gpu1.base, second: String = gpu2.base): OpenAiRig {
        val servers = UpstreamPool(
            "box",
            listOf(UpstreamConfig("gpu-1", first), UpstreamConfig("gpu-2", second)),
            LogSink { },
        )
        pool = servers
        val upstreams = object : HeadUpstreams {
            override fun all(): List<UpstreamPool> = listOf(servers)

            override fun of(head: String): UpstreamPool? = servers.takeIf { head == "team" }

            override fun chat(head: String): ChatServer? = BoxServer(first).takeIf { head == "team" }
        }
        return OpenAiRig(state, upstreams).also { opened = it }
    }

    /** An address nothing listens on, which refuses a connection at once. */
    private fun nobody(): String = "http://127.0.0.1:${TestPorts.reserve()}/v1"

    @AfterEach
    fun stop() {
        opened?.close()
        gpu1.stop()
        gpu2.stop()
    }

    /** Which server answered [completion], by the words each scripted one answers with. */
    private fun answeredBy(completion: HttpResponse<String>): String {
        assertEquals(200, completion.statusCode(), completion.body())
        val choice = Json.parseToJsonElement(completion.body()).jsonObject.getValue("choices").jsonArray.single()
        val said = choice.jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
        return said.removePrefix("from ")
    }

    @Test
    fun `a completion goes to the server its person prefers, and to the least busy one otherwise`() {
        val rig = serve()
        val ola = rig.keyFor("ola@example.com")
        assertEquals("gpu-1", answeredBy(rig.chat(ola, CHAT)), "the first listed, among servers equally idle")
        rig.prefer("ola@example.com", "gpu-2")
        assertEquals("gpu-2", answeredBy(rig.chat(ola, CHAT)))

        // While gpu-1 holds a completion, one that prefers no server goes to gpu-2.
        val gate = CountDownLatch(1).also { gpu1.gate = it }
        val held = rig.chatLater(rig.keyFor("ana@example.com"), STREAMED_CHAT)
        rig.await("ana's completion is in flight on gpu-1") { pool.readings().first().inflight == 1 }
        assertEquals("gpu-2", answeredBy(rig.chat(rig.keyFor("rui@example.com"), CHAT)))
        gate.countDown()
        assertEquals(200, held.get(10, TimeUnit.SECONDS).statusCode())
        rig.await("an ended completion is in flight nowhere") { pool.readings().all { it.inflight == 0 } }
    }

    @Test
    fun `a server that takes no connection is passed over at once and reads down from then`() {
        val rig = serve(first = nobody())
        val key = rig.keyFor("ola@example.com")
        assertEquals("gpu-2", answeredBy(rig.chat(key, CHAT)))
        assertEquals(CHAT, gpu2.asked.single().body)
        assertEquals(listOf(false, true), pool.readings().map { it.healthy })
        // The server counts the completion out once its answer is written, which can be just after the client reads it.
        rig.await("an ended completion is in flight nowhere") { pool.readings().all { it.inflight == 0 } }
        // The models list is asked where a completion would be.
        assertEquals(BOX_MODELS, rig.models(key).body())
    }

    @Test
    fun `with no server up a completion is refused 503 in OpenAI's shape, naming the upstreams`() {
        val rig = serve(first = nobody(), second = nobody())
        val key = rig.keyFor("ola@example.com")
        // The first finds both gone itself; the ones after it are refused before they are sent anywhere.
        for (refused in listOf(rig.chat(key, CHAT), rig.chat(key, STREAMED_CHAT), rig.models(key))) {
            assertEquals(503, refused.statusCode())
            val body = Json.parseToJsonElement(refused.body()).jsonObject
            assertEquals(setOf("error"), body.keys, refused.body())
            val error = body.getValue("error").jsonObject
            assertEquals("server_error", error.getValue("type").jsonPrimitive.content)
            val said = error.getValue("message").jsonPrimitive.content
            assertTrue("gpu-1" in said && "gpu-2" in said && "box" in said, said)
        }
    }
}

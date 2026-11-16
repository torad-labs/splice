// Server mode's keepalive on the OpenAI route (docs/specs/server-mode.md, F4) as an OpenAI-style client meets it, over
// real HTTP on loopback: in front of a provider's server that says nothing for a while, headers included, the way a
// model server does while it prefills a long prompt. No test here sits through that silence. The server holds a
// completion until the test lets it go on, the relay's clock is the test's (SteppedClock), and the client's answer is
// read as it comes (Hearing). Each test writes what the server does, says when time passes, and reads what the client
// has heard by then.
package splice.server

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.pool.UpstreamPool
import splice.core.testing.TestPorts
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class ServerOpenAiKeepaliveTest {
    @TempDir lateinit var state: Path

    private val clock = SteppedClock()
    private val box = ScriptedBox("box")
    private val workers = Executors.newCachedThreadPool()
    private var opened: OpenAiRig? = null

    /** The server holds every completion, saying nothing, until the test lets it go on. */
    private val goOn = CountDownLatch(1).also { box.prefill = it }

    /** A provider that is the one server [box]. */
    private val alone = object : HeadUpstreams {
        override fun chat(head: String): ChatServer? = BoxServer(box.base).takeIf { head == "team" }
    }

    /** Server mode over `claude-team` with `[server] keepalive_seconds` as given, its provider what [upstreams] says,
     *  and the key of one person. */
    private fun serve(keepaliveSeconds: Int, upstreams: HeadUpstreams = alone): Pair<OpenAiRig, String> {
        val rig = OpenAiRig(state, upstreams, keepaliveSeconds = keepaliveSeconds, waiter = clock).also { opened = it }
        return rig to rig.keyFor("ola@example.com")
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
        workers.shutdownNow()
    }

    /** A streamed completion whose stream was opened while the server said nothing: the relay's first second has
     *  passed, the client has its headers, a 200 and an event stream, and the first thing on it is a keepalive. */
    private fun openedEarly(rig: OpenAiRig, key: String): Hearing {
        val sent = rig.chatHeard(key, STREAMED_CHAT)
        clock.pass()
        val heard = hearing(sent, workers)
        assertEquals(200, heard.status, "the stream was open before the server answered")
        assertEquals("text/event-stream", heard.type)
        assertTrue(keepalive(heard.block("a keepalive as its stream opens")))
        return heard
    }

    /** The one event that ends [heard]'s stream, which must be an error in OpenAI's shape with nothing after it, no
     *  `[DONE]` included: its data. */
    private fun endingError(heard: Hearing): String {
        val event = heard.block("the error that ends the stream").orEmpty()
        assertTrue(event.startsWith("data: ") && event.trimEnd().lines().size == 1, "an event of one data line: $event")
        assertNull(heard.block("the end of the stream"), "the error ends the stream")
        return event.removePrefix("data: ").trimEnd()
    }

    @Test
    fun `behind a silent server a streamed completion is opened in a second, kept alive, then answered whole`() {
        val (rig, key) = serve(keepaliveSeconds = 3)
        val heard = openedEarly(rig, key)
        repeat(3) {
            clock.pass()
            assertTrue(keepalive(heard.block("a keepalive after one more interval of silence")))
        }
        goOn.countDown()
        val answer = "$WORD_EVENT\n\n$DONE_EVENT\n\n"
        assertEquals(answer, heard.rest("the server's answer"), "the answer whole, [DONE] last, and nothing after it")
        val openedAfter = clock.asked.first()
        assertTrue(openedAfter <= 1_000, "the stream was opened after $openedAfter ms of the server's silence")
        val intervals = clock.asked.drop(1)
        assertTrue(intervals.all { it <= 3_000 }, "with keepalive_seconds = 3 a keepalive waited longer: $intervals")
        assertEquals(STREAMED_CHAT, box.asked.single().body, "the server was asked once, for what the client sent")
    }

    @Test
    fun `a server that refuses once the stream is open ends it with its own error, as one event in OpenAI's shape`() {
        val (rig, key) = serve(keepaliveSeconds = 3)
        box.refuses = 503
        val heard = openedEarly(rig, key)
        goOn.countDown()
        assertEquals("""{"error":{"message":"box says 503"}}""", endingError(heard), "the server's own words")
    }

    @Test
    fun `a late refusal that is no OpenAI error is said by the server, with the status that came`() {
        val (rig, key) = serve(keepaliveSeconds = 3)
        // As a gateway in front of the model server answers when that server is gone.
        box.refuses = 502
        box.refusal = """{"detail":"Bad Gateway"}"""
        val heard = openedEarly(rig, key)
        goOn.countDown()
        val error = openAiError(endingError(heard))
        assertEquals("server_error", error.getValue("type").jsonPrimitive.content)
        val said = error.getValue("message").jsonPrimitive.content
        assertTrue("the model server behind claude-team answered HTTP 502" in said, said)
    }

    @Test
    fun `a server that goes away once the stream is open ends it with one error event saying it is not answering`() {
        val (rig, key) = serve(keepaliveSeconds = 3)
        box.hangsUp = true
        val heard = openedEarly(rig, key)
        goOn.countDown()
        val error = openAiError(endingError(heard))
        assertEquals("server_error", error.getValue("type").jsonPrimitive.content)
        val said = error.getValue("message").jsonPrimitive.content
        assertTrue("the model server behind claude-team is not answering" in said, said)
    }

    @Test
    fun `an answer the server breaks off after a prefill ends, after what it sent, with one error event`() {
        val (rig, key) = serve(keepaliveSeconds = 3)
        box.cutAfter = 1
        val heard = openedEarly(rig, key)
        goOn.countDown()
        assertEquals("$WORD_EVENT\n\n", heard.block("what the server sent before it broke off"))
        val said = openAiError(endingError(heard)).getValue("message").jsonPrimitive.content
        assertTrue("the model server behind claude-team broke off its answer" in said, said)
    }

    @Test
    fun `a completion that asks for no stream answers one JSON body, however long its server is silent`() {
        val (rig, key) = serve(keepaliveSeconds = 3)
        clock.flies = true
        val sent = rig.chatHeard(key, CHAT)
        rig.await("the completion reached the server") { box.asked.isNotEmpty() }
        goOn.countDown()
        val heard = hearing(sent, workers)
        assertEquals(200, heard.status)
        assertEquals("application/json", heard.type)
        val whole = """{"choices":[{"message":{"content":"from box"}}],"usage":{"prompt_tokens":7}}"""
        assertEquals(whole, heard.rest("the server's answer"), "one body, the server's own, with nothing before it")
    }

    @Test
    fun `with no keepalive_seconds a streamed completion waits on its server and carries its events alone`() {
        val (rig, key) = serve(keepaliveSeconds = 0)
        clock.flies = true
        val sent = rig.chatHeard(key, STREAMED_CHAT)
        rig.await("the completion reached the server") { box.asked.isNotEmpty() }
        // Nothing signals that a stream was not opened, so one is watched for through a window: its length is how
        // long the test looks, and a slower machine only looks at less.
        assertThrows<TimeoutException>("a stream was opened before its server answered, with no keepalive set") {
            sent.get(NO_STREAM_WINDOW_MS, TimeUnit.MILLISECONDS)
        }
        goOn.countDown()
        assertEquals("$WORD_EVENT\n\n$DONE_EVENT\n\n", hearing(sent, workers).rest("the server's events"))
    }

    @Test
    fun `a kept-alive completion passes over a server that takes no connection, and hears the next one's answer`() {
        // gpu-1 is an address nothing listens on, which refuses a connection at once; gpu-2 is the box.
        val gone = "http://127.0.0.1:${TestPorts.reserve()}/v1"
        val servers = listOf(UpstreamConfig("gpu-1", gone), UpstreamConfig("gpu-2", box.base))
        val pool = UpstreamPool("box", servers, LogSink { })
        val upstreams = object : HeadUpstreams {
            override fun all(): List<UpstreamPool> = listOf(pool)

            override fun of(head: String): UpstreamPool? = pool.takeIf { head == "team" }

            override fun chat(head: String): ChatServer? = BoxServer(gone).takeIf { head == "team" }
        }
        val (rig, key) = serve(keepaliveSeconds = 3, upstreams)
        val heard = openedEarly(rig, key)
        goOn.countDown()
        assertEquals("$WORD_EVENT\n\n$DONE_EVENT\n\n", heard.rest("gpu-2's answer"))
        assertEquals(listOf(false, true), pool.readings().map { it.healthy }, "gpu-1 reads down from then")
        rig.await("an ended completion is in flight nowhere") { pool.readings().all { it.inflight == 0 } }
    }
}

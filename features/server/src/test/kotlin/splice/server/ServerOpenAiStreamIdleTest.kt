// Server mode's stream idle deadline (#791, `[server] stream_idle_seconds`) on the OpenAI route, as an OpenAI-style
// client meets it over real HTTP on loopback: in front of a model server that sends the chunk naming the role at once
// and then prefills behind it, the way llama-server and vLLM do. That chunk carries no word of the answer, so the
// prefill after it is no silence the deadline times. No test sits through the prefill: the relay's clock is the
// test's (SteppedClock), and the client's answer is read as it comes (Hearing).
package splice.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

internal class ServerOpenAiStreamIdleTest {
    @TempDir lateinit var state: Path

    private val clock = SteppedClock()
    private val box = ScriptedBox("box")
    private val workers = Executors.newCachedThreadPool()
    private var opened: OpenAiRig? = null

    /** A provider that is the one server [box]. */
    private val alone = object : HeadUpstreams {
        override fun chat(head: String): ChatServer? = BoxServer(box.base).takeIf { head == "team" }
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
        workers.shutdownNow()
    }

    @Test
    fun `a first chunk that only names the role is no word, so the prefill behind it is never timed`() {
        val rig = OpenAiRig(state, alone, waits = RigWaits(streamIdleSeconds = 30, waiter = clock)).also { opened = it }
        box.events = listOf(ROLE_EVENT, WORD_EVENT, USAGE_EVENT, DONE_EVENT)
        val prefill = CountDownLatch(1).also { box.afterFirst = it }
        // Every wait the relay asks for ends at once, as if the prefill had lasted any length of time.
        clock.flies = true

        val heard = hearing(rig.chatHeard(rig.keyFor("ola@example.com"), STREAMED_CHAT), workers)
        assertEquals(200, heard.status)
        assertEquals("$ROLE_EVENT\n\n", heard.block("the chunk that names the role"))
        prefill.countDown()
        assertEquals(
            "$WORD_EVENT\n\n",
            heard.block("the answer's first word, after the prefill"),
            "the prefill after the role-only chunk was timed as a silence in the answer",
        )
    }
}

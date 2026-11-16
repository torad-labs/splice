// Server mode (docs/specs/server-mode.md, F4): a vendor that is several servers. A server that takes no connection got
// no byte of the request, so the same request goes to the vendor's next server at once, on none of the waits a path
// that did not answer is otherwise given; with no server left the turn ends at once, naming the servers and the
// refusal the last one gave; and a request a server may already hold is never sent to another. MockEngine, no network.
package splice.upstream.transport

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.Usage
import splice.upstream.Reroute
import splice.upstream.RoundBody
import splice.upstream.VendorRoute
import splice.upstream.failure.FailedReply
import splice.upstream.failure.Replies
import java.net.ConnectException
import java.net.SocketException
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

class UpstreamClientRerouteTest {

    /** A vendor whose turn starts on the first of [servers] and moves down the list as each takes no connection. */
    private class Servers(private val servers: List<String>) : VendorRoute {
        private var at = 0

        override fun target(): String = "http://${servers[at]}/v1/chat/completions"

        override fun places(): List<String> = servers

        override fun unreachable(): Reroute {
            if (at == servers.lastIndex) return Reroute.NOWHERE
            at++
            return Reroute.MOVED
        }

        override fun sending(): Map<String, String> = emptyMap()

        override fun body(round: RoundBody): RoundBody? = null

        override fun written() = Unit

        override fun headersArrived() = Unit

        override fun answered(reply: FailedReply) = Unit

        override fun finished(usage: Usage?) = Unit

        override fun turnEnded() = Unit
    }

    private val asked = mutableListOf<String>()
    private val capture = Capture()
    private val notes = mutableListOf<String>()

    /** A client whose servers named in [down] take no connection; the others answer. */
    private fun client(down: Set<String>) = clientOver(
        MockEngine { request ->
            asked += request.url.host
            if (request.url.host in down) throw ConnectException("Connection refused")
            respond("ok", HttpStatusCode.OK, headersOf())
        },
        capture,
    )

    private fun ctx(route: VendorRoute?) = PostContext(
        url = "http://only.test/v1/chat/completions",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        replies = Replies(OpenAiReplies),
        recovery = PostRecovery(clientFrameEmitted = { false }, route = route),
        observers = PostObservers(onRetry = { notes += it }),
    )

    @Test
    fun `a server that takes no connection is left at once for the vendor's next`() = runTest {
        val three = ctx(Servers(listOf("a.test", "b.test", "c.test")))
        val out = client(down = setOf("a.test", "b.test")).posted(three, "{}") { "answered" }
        assertEquals("answered", out)
        assertEquals(listOf("a.test", "b.test", "c.test"), asked)
        assertEquals(emptyList<Long>(), capture.waits, "a move to another server waits for nothing")
        assertTrue(notes.all { "another of the provider's upstreams" in it } && notes.size == 2, notes.toString())
    }

    @Test
    fun `with no server left the turn ends at once, naming the servers and the refusal it met`() = runTest {
        val two = ctx(Servers(listOf("a.test", "b.test")))
        val none = assertEnds<NoPlaceLeft> {
            client(down = setOf("a.test", "b.test")).posted(two, "{}") { }
        }
        assertEquals(listOf("a.test", "b.test"), none.places)
        assertInstanceOf(ConnectException::class.java, none.cause)
        assertEquals(listOf("a.test", "b.test"), asked)
        assertEquals(emptyList<Long>(), capture.waits, "nothing is waited out once every server refused")
    }

    @Test
    fun `a request a server may already hold stays with that server`() = runTest {
        // a.test resets the connection once, after it may have read the request, and then answers.
        val once = clientOver(
            MockEngine { request ->
                asked += request.url.host
                if (asked.size == 1) throw SocketException("Connection reset")
                respond("ok", HttpStatusCode.OK, headersOf())
            },
            capture,
        )
        assertEquals("answered", once.posted(ctx(Servers(listOf("a.test", "b.test"))), "{}") { "answered" })
        assertEquals(listOf("a.test", "a.test"), asked)
        assertEquals(listOf(1_000L), capture.waits, "it is waited out as a path that did not answer")
    }

    @Test
    fun `a vendor with one place is asked where the provider is and waited out as before`() = runTest {
        val out = clientOver(
            MockEngine { request ->
                asked += request.url.host
                if (asked.size == 1) throw ConnectException("Connection refused")
                respond("ok", HttpStatusCode.OK, headersOf())
            },
            capture,
        ).posted(ctx(route = null), "{}") { "answered" }
        assertEquals("answered", out)
        assertEquals(listOf("only.test", "only.test"), asked)
        assertEquals(listOf(1_000L), capture.waits)
    }
}

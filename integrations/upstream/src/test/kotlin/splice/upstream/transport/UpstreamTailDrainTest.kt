// A reply left unread keeps its connection only if its last bytes are taken off the wire before the call ends. ktor
// 3.6.1 cancels the call of a reply whose body was not read to its end (HttpStatement.cleanup), which closes the
// socket, where 3.6.0 let OkHttp drain it for up to 100 ms. The chat and Responses translators stop at the upstream's
// last word and leave the body's last bytes behind, and the retries that follow a failed reply need the connection
// the failed one rode on, so UpstreamResponse.drainTail reads what is left, bounded in bytes and in time.
//
// The network arrangement is deterministic, not timed: the upstream sends a stream's last event at once and holds
// back its end (the chunked terminator) until the test says so, so at the moment a handler stops the tail is
// certainly not yet in the transport. A handler that drains it releases the tail itself; one that does not releases
// it after the post, too late to matter.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RetryNotice
import splice.upstream.failure.Replies
import splice.upstream.sse.SseReader
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

class UpstreamTailDrainTest {

    // The unit half: the drain's contract on a channel, with the bounds as arguments and virtual time.

    private suspend fun responseOver(channel: ByteReadChannel): UpstreamResponse {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        return UpstreamResponse(client.get("http://upstream.invalid/"), BodyChannelSource { channel })
    }

    @Test
    fun `a tail that ends within the bounds is read and the body is at its end`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("data: [DONE]\n\n")
        channel.close()

        val ended = responseOver(channel).drainTail()

        assertTrue(ended, "the tail was read to the body's end")
        assertTrue(channel.isClosedForRead, "nothing is left on the body")
    }

    @Test
    fun `a tail longer than the byte bound is not read to the end`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("x".repeat(4096))
        channel.close()

        val ended = responseOver(channel).drainTail(maxBytes = 1000)

        assertFalse(ended, "more than the bound is a reply still being sent, not a tail")
        assertFalse(channel.isClosedForRead, "the rest was left on the body")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a body that does not end is given up on at the time bound`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("data: [DONE]\n\n")

        val ended = responseOver(channel).drainTail(withinMs = 100)

        assertFalse(ended, "an upstream holding its stream open past its last word is not waited for")
        assertEquals(100L, testScheduler.currentTime, "and the wait was the bound, no more")
    }

    /** A body whose connection reset under the read: the wait for more throws what the transport closed it with. */
    @OptIn(InternalAPI::class)
    private class ResetChannel : ByteReadChannel {
        override val closedCause: Throwable = IOException("connection reset")
        override val isClosedForRead: Boolean = false
        override val readBuffer: Source = Buffer()
        override suspend fun awaitContent(min: Int): Boolean = throw closedCause
        override fun cancel(cause: Throwable?) { /* test double: nothing to cancel */ }
    }

    @Test
    fun `a torn body ends the drain without a throw`() = runTest {
        assertFalse(responseOver(ResetChannel()).drainTail(), "the connection is gone either way")
    }

    // The network half: the production transport against an upstream that holds back its stream's end.

    /** An upstream that refuses each post's first [refusalsPerPost] requests with a small 503 and answers the next with
     *  a stream: its last event at once, its end (the chunked terminator) held until [releaseTail]. One thread per
     *  connection, so a second connection is accepted at once. */
    private class HeldTailUpstream(private val refusalsPerPost: Int = 1) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val connections = CopyOnWriteArrayList<Socket>()
        private val tail = Semaphore(0)

        /** Connections accepted. */
        val accepted = AtomicInteger()

        /** Requests read whole. */
        val requests = AtomicInteger()

        init {
            val _ = thread(isDaemon = true, name = "tail-drain-upstream") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections += socket
                    val _ = accepted.incrementAndGet()
                    val _ = thread(isDaemon = true) { val _ = runCatching { socket.use(::serveEach) } }
                }
            }
        }

        fun releaseTail() = tail.release()

        fun context(retries: MutableList<String> = mutableListOf()) = PostContext(
            url = "http://127.0.0.1:${server.localPort}/v1/messages",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            observers = PostObservers(onRetry = RetryNotice { retries += it }),
        )

        private fun serveEach(socket: Socket) {
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()
            while (true) {
                val length = contentLength(input) ?: return
                val _ = input.readNBytes(length)
                if (requests.incrementAndGet() % (refusalsPerPost + 1) != 0) {
                    output.write(REFUSAL)
                    output.flush()
                } else {
                    output.write(STREAM_HEAD + chunk(LAST_EVENT))
                    output.flush()
                    tail.acquire()
                    output.write(STREAM_END)
                    output.flush()
                }
            }
        }

        private fun chunk(data: String): ByteArray = "${data.length.toString(16)}\r\n$data\r\n".toByteArray()

        private fun contentLength(input: InputStream): Int? {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next == -1) return null
                head.append(next.toChar())
            }
            val header = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            return header?.substringAfter(':')?.trim()?.toInt() ?: 0
        }

        override fun close() {
            server.close()
            connections.forEach { val _ = runCatching { it.close() } }
        }

        private companion object {
            const val REFUSAL_BODY = """{"error":{"message":"overloaded"}}"""
            const val LAST_EVENT = "data: {\"type\":\"response.completed\"}\n\n"
            val REFUSAL = (
                "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${REFUSAL_BODY.length}\r\n\r\n$REFUSAL_BODY"
                ).toByteArray()
            val STREAM_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                .toByteArray()
            val STREAM_END = "0\r\n\r\n".toByteArray()
        }
    }

    private fun client() = UpstreamClient(
        totalTimeoutMs = 5_000,
        client = UpstreamTransport().defaultClient(5_000),
        pacing = RetryPacing(waiter = RecordingWaiter()),
    )

    /** The handler a chat or Responses round runs: reads to the stream's last event and stops there. */
    private suspend fun UpstreamResponse.stopAtLastEvent() {
        val _ = SseReader().sseJsonEvents(bodyChannel()).first()
    }

    @Test
    fun `failed then retried posts that stop at the last word and drain the rest share one connection`() {
        HeldTailUpstream().use { upstream ->
            val client = client()
            val retries = CopyOnWriteArrayList<String>()
            val posts = 3

            val drained = List(posts) {
                runBlocking {
                    client.posted(upstream.context(retries), "{}") { response ->
                        response.stopAtLastEvent()
                        upstream.releaseTail() // the tail was not on the wire when the handler stopped
                        response.drainTail(withinMs = DRAIN_BACKSTOP_MS)
                    }
                }
            }

            assertEquals(List(posts) { true }, drained, "every tail was read to its end")
            assertEquals(posts, retries.size, "every post was refused once and sent again")
            assertEquals(2 * posts, upstream.requests.get())
            assertEquals(1, upstream.accepted.get(), "the retries and the posts after them rode one connection")
        }
    }

    @Test
    fun `refused replies with small bodies are read to their end, so the retries reuse their connection`() {
        HeldTailUpstream(refusalsPerPost = 2).use { upstream ->
            val client = client()
            val retries = CopyOnWriteArrayList<String>()
            val posts = 3

            repeat(posts) {
                runBlocking {
                    client.posted(upstream.context(retries), "{}") { response ->
                        // The reply is read to its end here, whatever the drain does, so what this counts is the
                        // two refused replies each post met first.
                        upstream.releaseTail()
                        response.bodyTextLimited(READ_LIMIT_BYTES)
                    }
                }
            }

            assertEquals(2 * posts, retries.size, "two refusals each, a 503 with a small body")
            assertEquals(3 * posts, upstream.requests.get())
            assertEquals(1, upstream.accepted.get(), "a refused reply costs no connection once its body is read")
        }
    }

    // The drain's worth, by its absence: the same posts, stopping at the last word and leaving the tail, close their
    // connection every time. It keeps the first test from passing for the wrong reason.
    @Test
    fun `without the drain the same posts dial a connection each`() {
        HeldTailUpstream().use { upstream ->
            val client = client()
            val posts = 3

            repeat(posts) {
                runBlocking { client.posted(upstream.context(), "{}") { response -> response.stopAtLastEvent() } }
                upstream.releaseTail() // after the post: the tail arrives at a connection already closed
            }

            assertEquals(posts, upstream.accepted.get(), "an undrained tail costs the connection")
        }
    }
}

private const val READ_LIMIT_BYTES = 1024

// why: a backstop for a tail the test has already released, far past any scheduler delay and far short of a hang.
private const val DRAIN_BACKSTOP_MS = 10_000L

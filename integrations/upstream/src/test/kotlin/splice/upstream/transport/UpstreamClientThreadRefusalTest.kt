// NEW: V4-307 — a thread start the host refuses on the upstream path is named by what it stopped, placed
// before or after the send, and survived.
//
// The fault, measured 2026-09-26 in a build scope held at its task ceiling: OkHttp ran each connect as a
// task on its process-wide TaskRunner, whose threads start on demand, and a refused start ("unable to create
// native thread") reached UpstreamClient as IOException("canceled due to java.lang.OutOfMemoryError: …") with
// the error as its cause (RealCall.AsyncCall). Nothing classified an OutOfMemoryError, so the attempt fell to
// the possible-duplicate default though the upstream had accepted nothing; and TaskRunner.startAnotherThread,
// which counts a start before making it, never started another thread, so every later connect in the process
// hung to the turn cap. Okio's timeout watchdog is started the same way, at the first timed read after a
// quiet spell, and so can be refused at a response read, after the upstream took the whole request.
//
// UpstreamClientThreadRefusalTest pins the verdict and the name on the failure OkHttp hands over, through a
// MockEngine, and the refusal at a response read through the real client. The three classes after it take a
// refusal that stops something for the whole JVM: OkHttp's task runner made to refuse the threads it starts,
// and okio's timeouts left off. Each is a fair test only in a JVM where that thread has never started, and
// leaves it stopped for good, so each runs in a JVM of its own (threadRefusalTest, build.gradle.kts), and
// `test` never runs them.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.internal.concurrent.TaskRunner
import okio.AsyncTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.util.LogSink
import splice.upstream.RetryNotice
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** The JDK's own text for a thread start the host refused (HotSpot, Linux, JDK 21). */
private const val NATIVE_THREAD_REFUSED =
    "unable to create native thread: possibly out of memory or process/resource limits reached"

/** What OkHttp's AsyncCall hands its callback when the call's thread caught [error] (RealCall.kt:574, 5.3.2). */
private fun okHttpCanceled(error: Throwable): IOException =
    IOException("canceled due to $error").apply { val _ = initCause(error) }

/** A refused start whose stack names the thread it was for, [owner], nearest the refusal. */
private fun refusedFor(owner: String): OutOfMemoryError = OutOfMemoryError(NATIVE_THREAD_REFUSED).apply {
    stackTrace = arrayOf(StackTraceElement(owner, "start", null, -1)) + stackTrace
}

private const val REFUSED = "the host refused splice a new thread (a process or thread limit was reached)"

/** The name of a refusal while OkHttp's task runner starts a thread, which is when a connection joins the pool. */
private const val REFUSED_REASON = REFUSED + ": idle upstream connections are no longer evicted until splice restarts"

/** The name of a refusal at a timed read or write, where okio starts its timeout watchdog. */
private const val OKIO_REFUSED_REASON =
    REFUSED + ": okio's timeouts stay off until splice restarts; splice's own caps still end a stalled turn"

class UpstreamClientThreadRefusalTest {

    private val url = "https://api.example.test/v1"

    /** What OkHttp hands over for a thread refused after the request was written whole: the refusal alone. */
    private val refused = okHttpCanceled(OutOfMemoryError(NATIVE_THREAD_REFUSED))

    /** And for one refused before that, as RequestSendState marks it. */
    private val refusedBeforeSend = okHttpCanceled(RefusedBeforeSend(OutOfMemoryError(NATIVE_THREAD_REFUSED)))

    private fun clientOver(engine: MockEngine) = UpstreamClient(
        totalTimeoutMs = 5_000,
        maxRetries = 3,
        client = HttpClient(engine),
        waiter = RecordingWaiter(),
    )

    private fun ctx(retries: MutableList<String>) =
        PostContext(url = url, auth = fakeAuth, extraHeaders = { emptyMap() }, onRetry = RetryNotice { retries += it })

    @Test
    fun `a thread refused before the request was written whole is a connect-phase failure - V4-307`() {
        assertEquals(TransportFailurePhase.CONNECT, TransportFailures().classifyTransport(refusedBeforeSend))
    }

    @Test
    fun `a thread refused after the request was written whole is a possible duplicate - V4-307`() {
        val failures = TransportFailures()

        val phase = failures.rethrowUnlessRetryableTransport(refused, deadlineHit = false, lastAttempt = false)

        assertEquals(TransportFailurePhase.POST_SEND, phase)
        assertEquals(null, failures.classifyTransport(refused), "no phase of its own: the default's")
    }

    @Test
    fun `a refused thread start is named with what it stopped, read from the thread it was for - V4-307`() {
        val taskRunner = okHttpCanceled(refusedFor("okhttp3.internal.concurrent.TaskRunner\$RealBackend"))
        val okio = okHttpCanceled(RefusedBeforeSend(refusedFor("okio.AsyncTimeout\$Companion")))

        assertEquals(REFUSED_REASON, TransportFailureReason.of(taskRunner, url))
        assertEquals(OKIO_REFUSED_REASON, TransportFailureReason.of(okio, url))
        assertEquals(REFUSED, TransportFailureReason.of(refused, url), "a thread neither owns is named alone")
    }

    @Test
    fun `a post whose connect was refused a thread retries it by name, never as a possible duplicate - V4-307`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) throw refusedBeforeSend
            respond("{}", HttpStatusCode.OK, headersOf())
        }
        val retries = mutableListOf<String>()

        val answer = clientOver(engine).posted(ctx(retries), "{}") { "ok" }

        assertEquals("ok", answer)
        assertEquals(listOf("transport IOException attempt 1/3: $REFUSED"), retries)
    }

    @Test
    fun `a post refused on every attempt ends naming the refusal - V4-307`() = runTest {
        val engine = MockEngine { throw refused }

        val ending = runCatching { clientOver(engine).posted(ctx(mutableListOf()), "{}") { "ok" } }.exceptionOrNull()

        assertTrue(ending is IOException, "the refusal itself ends the post: $ending")
        assertEquals(REFUSED, TransportFailureReason.of(checkNotNull(ending), url))
    }

    // The refusal okio's watchdog start makes at a response read, after the upstream took the request whole
    // (AsyncTimeout.insertIntoQueue starts the watchdog with no handler, okio 3.17.0). The retry sends the
    // request again, so it is a possible duplicate, whatever the refusal was.
    @Test
    fun `a thread refused at the response read, after the upstream took the request, retries as a possible duplicate - V4-307`() {
        LoopbackUpstream().use { upstream ->
            val retries = CopyOnWriteArrayList<String>()
            val client = UpstreamClient(
                totalTimeoutMs = REFUSAL_TOTAL_MS,
                maxRetries = 2,
                client = UpstreamTransport().client(
                    REFUSAL_TOTAL_MS,
                    log = LogSink {},
                    noDelayGuard = AtomicBoolean(true),
                    requestWriteTimeoutMs = REFUSAL_TOTAL_MS,
                    sockets = UpstreamSockets(factory = FirstReadRefused()),
                ),
                waiter = RecordingWaiter(),
            )

            val answer = runBlocking { client.posted(upstream.context(retries), "{}") { "ok" } }

            assertEquals("ok", answer)
            assertEquals(listOf("transport-possible-duplicate IOException attempt 1/2: $OKIO_REFUSED_REASON"), retries)
            assertEquals(2, upstream.requests.get(), "the upstream took the refused attempt's request whole")
        }
    }
}

// V4-307: a post whose connection takes a real refusal. Its own JVM (see the file header).
class RefusedThreadStartPostTest {

    @Test
    fun `a post whose connection takes a refused thread start retries it by name and completes - V4-307`() {
        LoopbackUpstream().use { upstream ->
            val client = refusalClient()
            val retries = CopyOnWriteArrayList<String>()
            val restore = refuseTaskRunnerThreads()
            val answer = try {
                runCatching { runBlocking { client.posted(upstream.context(retries), "{}") { "ok" } } }
            } finally {
                restore()
            }

            assertEquals(listOf("transport IOException attempt 1/2: $REFUSED_REASON"), retries)
            assertEquals("ok", answer.getOrThrow())
            assertEquals(1, upstream.accepted.get(), "the retry reused the connection the refused attempt made")
        }
    }
}

// V4-307: the posts after a real refusal. Its own JVM (see the file header).
class AfterRefusedThreadStartTest {

    @Test
    fun `posts after a refused thread start still connect, over one pooled connection - V4-307`() {
        LoopbackUpstream().use { upstream ->
            val client = refusalClient()
            val restore = refuseTaskRunnerThreads()
            try {
                val _ = runCatching { runBlocking { client.posted(upstream.context(), "{}") { "ok" } } }
            } finally {
                restore()
            }

            val after = List(3) {
                runCatching { runBlocking { client.posted(upstream.context(), "{}") { "ok" } } }
                    .getOrElse { failure -> failure.toString() }
            }

            assertEquals(List(3) { "ok" }, after, "a refused start must not end the process's connects")
            // The refusal also ends idle eviction for the process; what bounds the pool then is reuse, which
            // OkHttp tries before any new connect (RealRoutePlanner.plan), so these posts share one connection.
            assertEquals(1, upstream.accepted.get(), "sequential posts after the refusal share one connection")
        }
    }
}

// V4-307: a post while okio's timeouts are off, as a refused watchdog start leaves them. Its own JVM (see the
// file header). What must end it is what splice bounds the turn with: the socket's own read timeout and ktor's
// request timeout, neither of which runs on okio's watchdog.
class OkioTimeoutsRefusedTest {

    @Test
    @Timeout(CAPS_BACKSTOP_S) // a cap that rode on okio would hang here, and must fail the suite, not wedge it
    fun `with okio's timeouts off, the turn's caps still end a post the upstream never answers - V4-307`() {
        LoopbackUpstream(answering = false).use { upstream ->
            leaveOkioTimeoutsOff()
            val client = UpstreamClient(
                totalTimeoutMs = CAP_MS,
                maxRetries = 1,
                client = UpstreamTransport().defaultClient(CAP_MS),
                waiter = RecordingWaiter(),
            )

            val ending = runCatching { runBlocking { client.posted(upstream.context(), "{}") { "ok" } } }

            assertNotNull(ending.exceptionOrNull(), "the unanswered post ended: $ending")
            val watchdogs = Thread.getAllStackTraces().keys.filter { it.name == "Okio Watchdog" }
            assertEquals(emptyList<Thread>(), watchdogs, "okio's timeouts stayed off for the whole post")
        }
    }
}

private fun refusalClient() = UpstreamClient(
    totalTimeoutMs = REFUSAL_TOTAL_MS,
    maxRetries = 2,
    client = UpstreamTransport().defaultClient(REFUSAL_TOTAL_MS),
    waiter = RecordingWaiter(),
)

/** Long enough for a loopback post, short enough that a post hung on a dead connect fails the test quickly. */
private const val REFUSAL_TOTAL_MS = 5_000L

/** The turn cap of the post nobody answers: what it waits before the caps end it. */
private const val CAP_MS = 1_000L

// why: how long a post with okio's timeouts off may take before the suite calls it hung: the cap is a second,
// so thirty is room for a loaded host, and far short of the hang it catches.
private const val CAPS_BACKSTOP_S = 30L

/**
 * Makes OkHttp's process-wide task runner refuse every thread it starts until the returned function
 * restores it, the way the build scope at its task ceiling refused one. Fair only while that task runner
 * has never started a thread in this JVM, which it checks by name.
 */
private fun refuseTaskRunnerThreads(): () -> Unit {
    val executor = (TaskRunner.INSTANCE.backend as TaskRunner.RealBackend).executor
    check(executor.largestPoolSize == 0) {
        "OkHttp's task runner has already started a thread in this JVM, so it cannot be made to refuse its " +
            "first; this class needs a JVM of its own (threadRefusalTest)"
    }
    val factory = executor.threadFactory
    executor.threadFactory = ThreadFactory { throw OutOfMemoryError(NATIVE_THREAD_REFUSED) }
    return { executor.threadFactory = factory }
}

/**
 * Leaves okio's timeouts off for this JVM, as a refused watchdog start does: the sentinel set and no watchdog
 * thread, so no later timed read or write starts one (AsyncTimeout.insertIntoQueue, okio 3.17.0, sets the
 * sentinel, then starts the thread). Fair only while no watchdog runs in this JVM, which it checks.
 */
private fun leaveOkioTimeoutsOff() {
    val sentinel = AsyncTimeout::class.java.getDeclaredField("idleSentinel").apply { isAccessible = true }
    check(sentinel.get(null) == null) {
        "okio's watchdog already runs in this JVM, so its timeouts cannot be left off; this class needs a JVM " +
            "of its own (threadRefusalTest)"
    }
    sentinel.set(null, AsyncTimeout())
}

/** A loopback upstream that reads every request whole over keep-alive and, when [answering], answers it 200,
 *  counting the connections it accepts. The posts are sequential, so it serves each connection on its accept
 *  thread and starts none per connection. */
private class LoopbackUpstream(private val answering: Boolean = true) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val accepted = AtomicInteger()

    /** Requests it read whole. */
    val requests = AtomicInteger()

    init {
        val _ = thread(isDaemon = true, name = "v4307-loopback-upstream") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val _ = accepted.incrementAndGet()
                val _ = runCatching { socket.use(::serveEach) }
            }
        }
    }

    fun context(retries: MutableList<String> = mutableListOf()) = PostContext(
        url = "http://127.0.0.1:${server.localPort}/v1/messages",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        onRetry = RetryNotice { retries += it },
    )

    private fun serveEach(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        while (true) {
            val length = contentLength(input) ?: return
            val _ = input.readNBytes(length)
            val _ = requests.incrementAndGet()
            if (answering) {
                output.write(ANSWER)
                output.flush()
            }
        }
    }

    /** Reads one request head and answers its Content-Length (0 when it has none), or null once the client
     *  has closed the connection. */
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
    }

    private companion object {
        val ANSWER = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".toByteArray()
    }
}

/** The first read of all its sockets is refused a thread, the way okio refuses one at a response read when its
 *  watchdog cannot start, with the frame that start is made from nearest the refusal (AsyncTimeout.enter ->
 *  insertIntoQueue, okio 3.17.0); every other read goes to the socket. Unconnected sockets only, which is what
 *  OkHttp asks for. */
private class FirstReadRefused : SocketFactory() {
    private val refused = AtomicBoolean()

    override fun createSocket(): Socket = object : Socket() {
        override fun getInputStream(): InputStream = RefusingFirstRead(super.getInputStream(), refused)
    }

    override fun createSocket(host: String, port: Int): Socket = unconnectedOnly()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        unconnectedOnly()

    override fun createSocket(host: InetAddress, port: Int): Socket = unconnectedOnly()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        unconnectedOnly()

    private fun unconnectedOnly(): Nothing = throw UnsupportedOperationException("OkHttp asks for unconnected sockets")

    private class RefusingFirstRead(input: InputStream, private val refused: AtomicBoolean) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (refused.compareAndSet(false, true)) throw refusedFor("okio.AsyncTimeout\$Companion")
            return super.read(b, off, len)
        }
    }
}

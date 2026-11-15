// Server mode (docs/specs/server-mode.md, F4): a head whose provider is several servers, asked real turns. Which
// server answered is read off the reply, since each says its own name: turns in flight together are spread, a turn
// that names its person's upstream goes there, and a server that takes no connection is left at once.
package splice.provider.openai

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.pool.UpstreamPool
import splice.core.testing.TestPorts
import splice.core.topology.UpstreamConfig
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.chat.ChatQuirks
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** A chat server that answers every turn with its own name. While [hold] is closed it keeps each turn open, so a
 *  test can have several in flight at once; [holding] counts the ones it keeps. */
private class NamedServer(val name: String) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val workers = Executors.newCachedThreadPool()
    val url: String get() = "http://127.0.0.1:${server.address.port}/v1"
    val holding = Semaphore(0)

    @Volatile
    var hold: CountDownLatch? = null

    init {
        server.executor = workers
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            hold?.let {
                holding.release()
                it.await()
            }
            val frames = listOf(
                """{"choices":[{"delta":{"content":"from-$name"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":3,"completion_tokens":1}}""",
                "[DONE]",
            )
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out -> frames.forEach { out.write("data: $it\n\n".toByteArray()) } }
        }
        server.start()
    }

    fun stop() {
        hold?.countDown()
        server.stop(0)
        workers.shutdownNow()
    }
}

class OpenAiChatPoolTest {
    @TempDir lateinit var tmp: Path

    private val a = NamedServer("a")
    private val b = NamedServer("b")
    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private var head: HeadServer? = null

    /** An address nothing listens on, which stays that way for the test. */
    private fun nobody(): String = "http://127.0.0.1:${TestPorts.reserve()}/v1"

    private fun pool(vararg servers: Pair<String, String>): UpstreamPool =
        UpstreamPool("box", servers.map { (name, url) -> UpstreamConfig(name, url) }, LogSink { })

    /** A head whose provider's turns go where [pool] says, and the port it listens on. */
    private fun serving(pool: UpstreamPool): Int = runBlocking {
        val provider = OpenAiChatProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "box", label = "box"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-box--",
                    models = listOf(ModelEntry("team-model", "team-model", contextWindow = 128_000)),
                    defaultContextWindow = 128_000,
                ),
                pinnedModel = "team-model",
                auth = ApiKeyAuthProvider("BOX_KEY", envReader = { "box-key-123456" }),
                locations = ProviderLocations(baseUrl = pool.upstreams.first().base),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = ChatQuirks(providerTag = "box"),
            pool = pool,
        )
        val started = HeadServer(provider = provider, listenPort = 0, deps = headDeps(tmp))
        started.start()
        head = started
        awaitListening(started.port)
        started.port
    }

    /** One streamed turn on the head at [port], naming [preferred] as the server listener would; its whole answer. */
    private suspend fun turn(port: Int, preferred: String? = null): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            preferred?.let { header(PREFERRED_UPSTREAM_HEADER, it) }
            setBody(TURN)
        }.bodyAsText()

    @AfterEach
    fun stop() = runBlocking {
        a.stop()
        b.stop()
        head?.stop()
        client.close()
    }

    @Test
    fun `turns in flight together are spread over the servers and counted on each until they end`() = runBlocking {
        val pool = pool("a" to a.url, "b" to b.url)
        val port = serving(pool)
        val open = CountDownLatch(1)
        a.hold = open
        b.hold = open
        // On threads of their own: this one waits, blocked, for the servers to hold them.
        val turns = List(4) { async(Dispatchers.IO) { turn(port) } }
        assertTrue(a.holding.tryAcquire(2, 20, TimeUnit.SECONDS), "two of four turns reach a")
        assertTrue(b.holding.tryAcquire(2, 20, TimeUnit.SECONDS), "two of four turns reach b")
        assertEquals(listOf(2, 2), pool.readings().map { it.inflight })
        open.countDown()
        val answers = turns.awaitAll()
        assertEquals(2, answers.count { "from-a" in it })
        assertEquals(2, answers.count { "from-b" in it })
        assertTrue(answers.all { "event: message_stop" in it })
        assertEquals(listOf(0, 0), pool.readings().map { it.inflight }, "a turn that ended is in flight nowhere")
    }

    @Test
    fun `a turn naming its person's upstream goes there while it is healthy, and to another once it is not`() =
        runBlocking {
            val pool = pool("a" to a.url, "b" to b.url)
            val port = serving(pool)
            assertTrue(List(3) { turn(port, preferred = "b") }.all { "from-b" in it })
            pool.checked("b", healthy = false, said = "HTTP 503")
            assertTrue("from-a" in turn(port, preferred = "b"))
            pool.checked("b", healthy = true, said = "HTTP 200")
            assertTrue("from-b" in turn(port, preferred = "b"))
        }

    @Test
    fun `a server that takes no connection is left at once, and reads down for the turns after`() = runBlocking {
        val pool = pool("gone" to nobody(), "b" to b.url)
        val port = serving(pool)
        assertTrue("from-b" in turn(port), "the first turn starts on the first listed server, which is gone")
        assertEquals(listOf(false, true), pool.readings().map { it.healthy })
        assertTrue("from-b" in turn(port))
    }

    @Test
    fun `with no server taking a connection the turn ends in an error and both read down`() = runBlocking {
        val pool = pool("gone" to nobody(), "gone-too" to nobody())
        val port = serving(pool)
        val answer = turn(port)
        assertTrue("event: error" in answer, answer)
        assertFalse("from-" in answer)
        assertFalse(pool.up)
        assertEquals(listOf(0, 0), pool.readings().map { it.inflight })
    }
}

private const val TURN =
    """{"model":"claude-box--team-model","stream":true,"max_tokens":50,"messages":[{"role":"user","content":"hi"}]}"""

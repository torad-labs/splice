// Server mode's readiness probe (docs/specs/server-mode.md, `[server.probe] kind = "completion"`): a pooled server
// takes turns only once it really completes a request. Each test stands up the pool's servers as local HTTP servers
// that answer a completion as the test says, and drives the clock between probes itself; what it reads is the
// upstream list, whether the pool takes a turn at all, where a turn is sent, what the servers were asked, and the log.
package splice.app.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.provider.local.JdkHealthHttp
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.pool.UpstreamPool
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.ServerProbe
import splice.core.topology.UpstreamConfig
import splice.dialect.chat.ChatReadinessProbe
import splice.upstream.Ticker
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpstreamCompletionProbeTest {
    /** One request a pool's server got: which server, its method and path, the headers it carried, and its body. */
    private data class Asked(val server: String, val call: String, val headers: Map<String, String>, val body: String)

    /** What a server answers now: a status and a body, or nothing until the test lets [held] go, then what it says. */
    private sealed class Answer {
        data class Reply(val status: Int, val body: String) : Answer()

        data object Held : Answer()
    }

    private val asked = CopyOnWriteArrayList<Asked>()
    private val answers = ConcurrentHashMap<String, Answer>()
    private val held = CountDownLatch(1)
    private val logged = CopyOnWriteArrayList<String>()
    private val threads: ExecutorService = Executors.newCachedThreadPool()
    private val servers = listOf("gpu-1", "gpu-2").associateWith(::server)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The interval every server's loop waits out before its next probe: it ends when the test opens the next round.
     *  [probed] hears each server's reading land. */
    @Volatile
    private var round = CompletableDeferred<Unit>()
    private val probed = Channel<Unit>(Channel.UNLIMITED)

    private fun server(name: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = threads
        createContext("/") { exchange -> exchange.use { answer(name, it) } }
        start()
    }

    private fun answer(name: String, exchange: HttpExchange) {
        val body = exchange.requestBody.use { String(it.readBytes()) }
        val headers = exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.first() }
        asked += Asked(name, "${exchange.requestMethod} ${exchange.requestURI.path}", headers, body)
        if (answers[name] == Answer.Held) held.await(HELD_LIMIT_S, TimeUnit.SECONDS)
        val now = answers[name] as? Answer.Reply ?: return
        val bytes = now.body.toByteArray()
        exchange.sendResponseHeaders(now.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    @AfterEach
    fun tearDown() {
        held.countDown()
        scope.cancel()
        servers.values.forEach { it.stop(0) }
        threads.shutdownNow()
    }

    private fun base(server: HttpServer): String = "http://127.0.0.1:${server.address.port}/v1"

    private val box = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        auth = AuthConfig(kind = "api-key", env = "BOX_KEY"),
        extraHeaders = mapOf("x-box-team" to "blue"),
        upstreams = servers.map { (name, server) -> UpstreamConfig(name, base(server)) },
        healthSeconds = 2,
    )

    /** The login a turn of the box sends: its key, as it stands when the probe is made. */
    @Volatile
    private var key = "box-key-1"
    private val login = object : AuthProvider {
        override suspend fun credentials(): Credentials = Credentials.ApiKey(key)

        override suspend fun describe(): AuthDescription = AuthDescription(true, "api-key")
    }

    /** The box's pool with [probe] as `[server.probe]`, over the real JDK client, with the clock the test's. Its first
     *  probes are under way when it returns. */
    private fun pool(probe: ServerProbe): UpstreamPool {
        val pools = UpstreamPools(
            scope = scope,
            log = { logged += it },
            http = JdkHealthHttp(),
            dispatcher = Dispatchers.IO,
            ticker = Ticker { _ ->
                val next = round
                probed.send(Unit)
                next.await()
                true
            },
        ).also { it.probe = probe }
        val ready = PoolReadiness(ChatReadinessProbe("max_tokens"), login, box.staticHeaders)
        return checkNotNull(pools.of("box", box, ready) { box.staticHeaders })
    }

    /** Waits until every server's probe of this round has been read. */
    private fun landed() = runBlocking { withTimeout(ROUND_LIMIT_MS) { repeat(servers.size) { probed.receive() } } }

    /** Runs every server's next probe, one interval on, and waits until each has been read. */
    private fun nextRound() {
        val open = round
        round = CompletableDeferred()
        open.complete(Unit)
        landed()
    }

    private fun healthy(pool: UpstreamPool): List<Boolean> = pool.readings().map { it.healthy }

    private fun completion(timeoutSeconds: Int = 30) =
        ServerProbe(kind = "completion", model = MODEL, timeoutSeconds = timeoutSeconds)

    @Test
    fun `with at_start a server takes no turn before its first completion, and one that completes takes them all`() {
        answers["gpu-1"] = Answer.Held
        answers["gpu-2"] = Answer.Held
        val pool = pool(completion())
        assertFalse(pool.up, "no server has completed a probe yet, so the pool takes no turn")
        assertEquals(listOf(false, false), healthy(pool))

        answers["gpu-1"] = Answer.Reply(200, COMPLETED)
        answers["gpu-2"] = Answer.Reply(503, """{"error":"loading model"}""")
        held.countDown()
        landed()
        nextRound()
        assertTrue(pool.up)
        assertEquals(listOf(true, false), healthy(pool))
        assertEquals(List(4) { "gpu-1" }, List(4) { pool.lease().name }, "every turn goes to the server that completed")
        assertTrue(logged.any { "gpu-1 passed its health check; it takes turns\n" in it }, logged.toString())
    }

    @Test
    fun `the probe is one non-streamed token of the probe's model, posted with the headers and key a turn sends`() {
        answers["gpu-1"] = Answer.Reply(200, COMPLETED)
        answers["gpu-2"] = Answer.Reply(200, COMPLETED)
        val pool = pool(completion())
        landed()
        assertEquals(listOf(true, true), healthy(pool))
        val first = asked.first { it.server == "gpu-1" }
        assertEquals("POST /v1/chat/completions", first.call)
        val body = Json.parseToJsonElement(first.body).jsonObject
        assertEquals(JsonPrimitive(MODEL), body["model"])
        assertEquals(JsonPrimitive(1), body["max_tokens"])
        assertEquals(JsonPrimitive(false), body["stream"])
        assertEquals("Bearer box-key-1", first.headers["authorization"])
        assertEquals("blue", first.headers["x-box-team"])
        assertFalse(asked.any { it.call.endsWith("/health") }, "a completion probe never asks /health")

        key = "box-key-2"
        nextRound()
        assertEquals("Bearer box-key-2", asked.last { it.server == "gpu-2" }.headers["authorization"])
    }

    @Test
    fun `a refusal or a body that is no completion keeps a server out, and the log says which without its body`() {
        answers["gpu-1"] = Answer.Reply(401, """{"error":"bad key"}""")
        answers["gpu-2"] = Answer.Reply(200, "<html>proxy login</html>")
        val pool = pool(completion())
        landed()
        assertEquals(listOf(false, false), healthy(pool))
        assertFalse(pool.up)

        answers["gpu-1"] = Answer.Reply(200, """{"object":"error","message":"model not loaded"}""")
        nextRound()
        assertEquals(listOf(false, false), healthy(pool), "a 2xx with no choices is no completion")

        val lines = logged.joinToString("")
        assertTrue("gpu-1 failed its health check (HTTP 401)" in lines, lines)
        assertTrue("gpu-2 failed its health check (HTTP 200, not a JSON object)" in lines, lines)
        assertFalse("proxy login" in lines || "bad key" in lines, "the log never quotes a server's body")
    }

    @Test
    fun `a completion that does not come back within timeout_seconds keeps a server out`() {
        answers["gpu-1"] = Answer.Reply(200, COMPLETED)
        answers["gpu-2"] = Answer.Held
        val pool = pool(completion(timeoutSeconds = 1))
        landed()
        assertEquals(listOf(true, false), healthy(pool))
        assertTrue(logged.any { "gpu-2 failed its health check (no answer within 1 s)" in it }, logged.toString())
    }

    @Test
    fun `a server that stops completing leaves at the next probe, and comes back at the probe after it completes`() {
        answers["gpu-1"] = Answer.Reply(200, COMPLETED)
        answers["gpu-2"] = Answer.Reply(200, REASONED_AWAY)
        val pool = pool(completion())
        landed()
        assertEquals(listOf(true, true), healthy(pool), "a completion whose one token went into reasoning still counts")

        answers["gpu-2"] = Answer.Reply(500, "")
        nextRound()
        assertEquals(listOf(true, false), healthy(pool))
        assertEquals("gpu-1", pool.lease().name)

        answers["gpu-2"] = Answer.Reply(200, COMPLETED)
        nextRound()
        assertEquals(listOf(true, true), healthy(pool))
        assertTrue(logged.any { "gpu-2 passed its health check; it takes turns again" in it }, logged.toString())
    }

    @Test
    fun `with at_start false each server takes turns from the start, and leaves only when a probe fails`() {
        answers["gpu-1"] = Answer.Held
        answers["gpu-2"] = Answer.Held
        val pool = pool(completion().copy(atStart = false))
        assertEquals(listOf(true, true), healthy(pool), "up before the first probe comes back")

        answers["gpu-1"] = Answer.Reply(200, COMPLETED)
        answers["gpu-2"] = Answer.Reply(503, "")
        held.countDown()
        landed()
        nextRound()
        assertEquals(listOf(true, false), healthy(pool))
    }

    @Test
    fun `the health probe asks health as always, every server up from the start whatever at_start says`() {
        answers["gpu-1"] = Answer.Reply(200, "")
        answers["gpu-2"] = Answer.Held
        val pool = pool(ServerProbe(atStart = true))
        assertEquals(listOf(true, true), healthy(pool), "a health-checked server serves before its first check")

        answers["gpu-2"] = Answer.Reply(503, "")
        held.countDown()
        landed()
        nextRound()
        assertEquals(listOf(true, false), healthy(pool))
        assertEquals(setOf("GET /health"), asked.map { it.call }.toSet(), "only /health is ever asked")
        assertEquals(setOf("blue"), asked.map { it.headers["x-box-team"] }.toSet())
    }
}

private const val MODEL = "team-model"

// A completion's envelope, as an OpenAI-compatible server answers one token.
private const val COMPLETED =
    """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"p"},""" +
        """"finish_reason":"length"}],"usage":{"prompt_tokens":9,"completion_tokens":1}}"""

// One token spent inside reasoning: the envelope is whole and the visible text is empty.
private const val REASONED_AWAY =
    """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"Hmm"},""" +
        """"finish_reason":"length"}]}"""

// How long a held server keeps a request before the test lets it go: past every deadline a test waits on.
private const val HELD_LIMIT_S = 60L

// How long the test waits for one round of probes to be read: a held server costs a probe its timeout, one second.
private const val ROUND_LIMIT_MS = 20_000L

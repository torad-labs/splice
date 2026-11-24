// Server mode's shared load caps and its drain at stop (#791), as a team member and a load balancer meet them: over
// real HTTP on loopback, in front of one head that holds the turns that ask it to until the test lets them go.
package splice.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.pool.UpstreamPool
import splice.core.topology.ServerConfig
import splice.core.topology.ServerForwardAuth
import splice.core.topology.ServerLimits
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

internal class ServerLoadTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val workers = Executors.newCachedThreadPool()

    /** A turn whose body says [HOLD] is held at the head until this opens; [held] counts the ones being held. */
    private val release = CountDownLatch(1)
    private val held = Semaphore(0)

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = workers
        createContext("/v1/messages") { exchange ->
            val asked = exchange.requestBody.use { it.readBytes() }.decodeToString()
            if (HOLD in asked) {
                held.release()
                release.await()
            }
            val answer = answerTo(asked).toByteArray()
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

    /** The team's door, which keeps what the listener said of each turn it was handed. */
    private val door = DoorStandIn()

    /** The head `team` runs on [pool]'s two servers, and the team's door is [door]. */
    private val rig = ServerRig(
        upstreams = object : HeadUpstreams {
            override fun all(): List<UpstreamPool> = listOf(pool)

            override fun of(head: String): UpstreamPool? = pool.takeIf { head == "team" }
        },
        gateway = door,
    )

    private fun serve(server: ServerConfig): ServerMode =
        rig.open(state, listOf(StandInHead(head.address.port)), server)

    @AfterEach
    fun stop() {
        release.countDown()
        rig.close()
        head.stop(0)
        workers.shutdownNow()
    }

    private fun request(key: String, body: String): HttpRequest =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}$TURN_PATH"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun turn(key: String): HttpResponse<String> =
        http.send(request(key, TURN), HttpResponse.BodyHandlers.ofString())

    private fun turnHeld(key: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(request(key, HELD_TURN), HttpResponse.BodyHandlers.ofString())

    /** [path] asked with [key]: a POST of [body], or a GET with none. */
    private fun ask(key: String, path: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}$path"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
        if (body == null) request.GET() else request.POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun health(): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/health")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun awaitHeld(turns: Int) {
        assertTrue(held.tryAcquire(turns, 10, TimeUnit.SECONDS), "the head never held $turns turns")
    }

    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within 10 s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
        }
    }

    /** The Anthropic error Claude Code prints: its type and its message. */
    private fun error(response: HttpResponse<String>): Pair<String, String> {
        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    @Test
    fun `a turn past the server's in-flight cap is refused for everyone with a 429 and Retry-After, until one ends`() {
        serve(rig.config.copy(limits = ServerLimits(inflightTotal = 2, busyRetryAfter = 7)))
        val ada = rig.keyFor("ada@team.example")
        val bo = rig.keyFor("bo@team.example")
        val inFlight = listOf(turnHeld(ada), turnHeld(bo))
        awaitHeld(turns = 2)

        val third = turn(rig.keyFor("cy@team.example"))
        assertEquals(429, third.statusCode(), third.body())
        assertEquals("rate_limit_error", error(third).first)
        assertEquals("7", third.headers().firstValue("retry-after").orElse(""), "Retry-After is not busy_retry_after")
        assertTrue("inflight_total = 2" in error(third).second, third.body())

        release.countDown()
        inFlight.forEach { assertEquals(200, it.get(10, TimeUnit.SECONDS).statusCode()) }
        assertEquals(200, turn(ada).statusCode(), "the server's cap did not free when its turns ended")
    }

    @Test
    fun `a full server answers with the operator's status and message, and a refused turn holds no person's slot`() {
        val limits = ServerLimits(inflightPerPerson = 1, inflightTotal = 1, busyStatus = 503, busyMessage = "GPUs busy")
        serve(rig.config.copy(limits = limits))
        val ada = rig.keyFor("ada@team.example")
        val bo = rig.keyFor("bo@team.example")
        val inFlight = turnHeld(ada)
        awaitHeld(turns = 1)

        val refused = turn(bo)
        assertEquals(503, refused.statusCode(), refused.body())
        assertEquals("5", refused.headers().firstValue("retry-after").orElse(""), "no default Retry-After")
        assertTrue(error(refused).second.endsWith("GPUs busy"), refused.body())

        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode())
        // Bo's refused turn gave back the slot it took under their own cap of one, or this would be a 429.
        assertEquals(200, turn(bo).statusCode(), "a turn the server refused kept its person's slot")
    }

    @Test
    fun `a turn the server's cap refuses spends none of its person's turns a minute`() {
        serve(rig.config.copy(limits = ServerLimits(rpmPerPerson = 1, inflightTotal = 1)))
        val ada = rig.keyFor("ada@team.example")
        val bo = rig.keyFor("bo@team.example")
        val inFlight = turnHeld(ada)
        awaitHeld(turns = 1)

        val refused = turn(bo)
        assertEquals(429, refused.statusCode(), refused.body())
        assertTrue("inflight_total = 1" in error(refused).second, refused.body())

        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode())
        // The server refused Bo's first turn before it started, so their one turn a minute is still theirs to start.
        awaitUntil("Bo's one turn a minute is served") { turn(bo).statusCode() == 200 }
    }

    @Test
    fun `turns admitted before any leased never add up past inflight_per_upstream, a lease already taken counted`() {
        serve(rig.config.copy(limits = ServerLimits(inflightPerUpstream = 1)))
        // A turn of this machine's own holds gpu-1, so one place is left, on gpu-2.
        val own = checkNotNull(pool.lease())
        val inFlight = turnHeld(rig.keyFor("ada@team.example"))
        awaitHeld(turns = 1)

        // Ada's turn has not leased its place on gpu-2 (its head holds it first), and the place is hers all the same.
        val refused = turn(rig.keyFor("bo@team.example"))
        assertEquals(429, refused.statusCode(), "a second turn was admitted onto the one place left: ${refused.body()}")
        assertTrue("inflight_per_upstream = 1" in error(refused).second, refused.body())

        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode())
        own.release()
    }

    @Test
    fun `a per-upstream cap too large to multiply by the healthy servers in an Int still admits a turn`() {
        serve(rig.config.copy(limits = ServerLimits(inflightPerUpstream = Int.MAX_VALUE)))
        val served = turn(rig.keyFor("ada@team.example"))
        assertEquals(200, served.statusCode(), "the cap times two healthy servers wrapped below zero: ${served.body()}")
    }

    @Test
    fun `a client that hangs up frees its place under the server's cap while the head is still busy with it`() {
        serve(rig.config.copy(limits = ServerLimits(inflightTotal = 1)))
        val ada = rig.keyFor("ada@team.example")
        Socket("127.0.0.1", rig.port).use { client ->
            val wire = "POST $TURN_PATH HTTP/1.1\r\nHost: 127.0.0.1:${rig.port}\r\nx-api-key: $ada\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${HELD_TURN.length}\r\n\r\n$HELD_TURN"
            client.getOutputStream().apply {
                write(wire.toByteArray())
                flush()
            }
            awaitHeld(turns = 1)
            assertEquals(429, turn(rig.keyFor("bo@team.example")).statusCode(), "the server's one place is not full")
        }
        awaitUntil("the place of the client that hung up is free") { turn(ada).statusCode() == 200 }
    }

    @Test
    fun `a turn on a provider whose healthy upstreams are each full is refused naming them`() {
        serve(rig.config.copy(limits = ServerLimits(inflightPerUpstream = 1)))
        pool.checked("gpu-2", healthy = false, said = "HTTP 503")
        val ada = rig.keyFor("ada@team.example")
        val inFlight = turnHeld(ada)
        awaitHeld(turns = 1)

        val second = turn(rig.keyFor("bo@team.example"))
        assertEquals(429, second.statusCode(), second.body())
        val said = error(second).second
        assertTrue(listOf("box", "gpu-1", "inflight_per_upstream = 1").all(said::contains), said)
        assertFalse("gpu-2" in said, "a server that is down was named as full: $said")

        // A second healthy server is a second place.
        pool.checked("gpu-2", healthy = true, said = "HTTP 200")
        assertEquals(200, turn(rig.keyFor("bo@team.example")).statusCode(), "the healthy gpu-2 took no turn")
        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode())
    }

    @Test
    fun `a stop drains, health says draining, new turns are a 503 with Retry-After, and the turn in flight finishes`() {
        serve(rig.config.copy(drainSeconds = 20))
        val ada = rig.keyFor("ada@team.example")
        assertEquals(200, health().statusCode())
        assertEquals("""{"status":"ok"}""", health().body())
        val inFlight = turnHeld(ada)
        awaitHeld(turns = 1)

        val stopping = CompletableFuture.runAsync({ rig.close() }, workers)
        awaitUntil("health says the server is draining") { health().statusCode() == 503 }
        assertEquals("""{"status":"draining"}""", health().body())
        val refused = turn(ada)
        assertEquals(503, refused.statusCode(), refused.body())
        val wait = refused.headers().firstValue("retry-after")
        assertTrue(wait.isPresent, "a turn refused while draining has no Retry-After")
        assertTrue("stopping" in error(refused).second, refused.body())
        assertFalse(stopping.isDone, "the stop did not wait for the turn in flight")

        release.countDown()
        val finished = inFlight.get(10, TimeUnit.SECONDS)
        assertEquals(200, finished.statusCode(), "the turn in flight was cut by the stop")
        assertTrue("from the box" in finished.body(), finished.body())
        stopping.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `while it drains every keyed request is a 503 with Retry-After, whatever its route`() {
        val dashboards = ServerForwardAuth(allow = listOf("ada@team.example"))
        serve(rig.config.copy(drainSeconds = 20, forwardAuth = dashboards))
        val ada = rig.keyFor("ada@team.example")
        assertEquals(200, ask(ada, "/server/forward-auth").statusCode(), "forward_auth does not admit Ada")
        val inFlight = turnHeld(ada)
        awaitHeld(turns = 1)

        val stopping = CompletableFuture.runAsync({ rig.close() }, workers)
        awaitUntil("health says the server is draining") { health().statusCode() == 503 }
        val asked = mapOf(
            "a token count" to ask(ada, "/claude-team/v1/messages/count_tokens", TURN),
            "a models list" to ask(ada, "/claude-team/v1/models"),
            "a turn on the team's door" to ask(ada, "/door/v1/messages", TURN),
            "the server's profile" to ask(ada, "/server/profile"),
            "a dashboard's forward_auth" to ask(ada, "/server/forward-auth"),
        )
        for ((what, refused) in asked) {
            assertEquals(503, refused.statusCode(), "$what: ${refused.body()}")
            assertEquals("5", refused.headers().firstValue("retry-after").orElse(""), "$what has no Retry-After")
            assertTrue("stopping" in error(refused).second, "$what: ${refused.body()}")
        }
        assertTrue(door.said.isEmpty(), "a turn reached the door while the server drained")

        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode(), "the turn in flight was cut by the drain")
        stopping.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `a drain waits for a long answer to reach its client whole, after the turn has ended at the head`() {
        serve(rig.config.copy(drainSeconds = 20))
        val ada = rig.keyFor("ada@team.example")
        Socket().use { client ->
            // A client slower than its answer: it takes in little, and reads nothing until the stop is under way.
            client.receiveBufferSize = SLOW_CLIENT_BUFFER
            client.connect(InetSocketAddress("127.0.0.1", rig.port))
            val wire = "POST $TURN_PATH HTTP/1.1\r\nHost: 127.0.0.1:${rig.port}\r\nx-api-key: $ada\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${LONG_TURN.length}\r\n\r\n$LONG_TURN"
            client.getOutputStream().apply {
                write(wire.toByteArray())
                flush()
            }
            awaitHeld(turns = 1)
            val stopping = CompletableFuture.runAsync({ rig.close() }, workers)
            awaitUntil("health says the server is draining") { health().statusCode() == 503 }

            release.countDown()
            assertThrows(TimeoutException::class.java, { stopping.get(2, TimeUnit.SECONDS) }) {
                "the stop closed the listener while it was still writing the answer"
            }
            // Read to the end: the stop closes the connection once the whole answer is written.
            val answered = client.getInputStream().readBytes().decodeToString()
            assertTrue(answered.startsWith("HTTP/1.1 200"), answered.take(200))
            val body = answered.substringAfter("\r\n\r\n")
            val whole = answerTo(LONG_TURN)
            assertEquals(whole.length, body.length, "the answer was cut short")
            assertTrue(body == whole, "the answer is not the head's")
            stopping.get(10, TimeUnit.SECONDS)
        }
    }

    /** What the head answers a turn that asked [asked]. */
    private fun answerTo(asked: String): String {
        val text = if (LONG in asked) LONG_CHAR.toString().repeat(LONG_ANSWER_CHARS) else "from the box"
        return """{"type":"message","content":[{"type":"text","text":"$text"}]}"""
    }
}

private const val TURN_PATH = "/claude-team/v1/messages"
private const val HOLD = "hold this turn"
private const val LONG = "and answer it at length"
private const val LONG_TURN =
    """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"$HOLD $LONG"}]}"""

// Far more than the sockets between the listener and a client hold while the client reads nothing, so the listener is
// still writing this answer when its turn has ended.
private const val LONG_ANSWER_CHARS = 16 * 1024 * 1024
private const val LONG_CHAR = 'x'

// The little a slow client takes in before it reads.
private const val SLOW_CLIENT_BUFFER = 64 * 1024
private const val TURN = """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
private const val HELD_TURN =
    """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"$HOLD"}]}"""

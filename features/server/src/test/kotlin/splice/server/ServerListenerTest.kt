// Server mode's listener as a stranger, a team member and an admin meet it (docs/specs/server-mode.md, F1 and F3):
// over real HTTP on loopback, in front of one head that answers every turn and holds the ones that ask it to.
package splice.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerConfig
import splice.core.topology.ServerLimits
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

internal class ServerListenerTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val logged = CopyOnWriteArrayList<String>()
    private val clock = SetClock()
    private val rig = ServerRig(LogSink { logged += it }, clock)
    private val port = rig.port
    private val base = rig.config

    /** What the head was sent as its credential, one entry per request that reached it. */
    private val heard = CopyOnWriteArrayList<String>()

    /** A turn whose body says [HOLD] is held at the head until this opens; [held] counts the ones being held. */
    private val release = CountDownLatch(1)
    private val held = Semaphore(0)
    private val workers = Executors.newCachedThreadPool()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = workers
        createContext("/") { exchange ->
            heard += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val asked = exchange.requestBody.use { it.readBytes() }.decodeToString()
            // As a head's routing does, this reads a path with its escapes decoded and serves a turn at one path alone.
            val turn = exchange.requestURI.path == "/v1/messages"
            if (turn && HOLD in asked) {
                held.release()
                release.await()
            }
            val served = """{"type":"message","content":[{"type":"text","text":"from the box"}]}"""
            val answer = (if (turn) served else """{"error":"no such route on this head"}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (turn) 200 else 404, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        start()
    }

    /** A clock the test sets, so a key's expiry and a person's minute pass when the test says. */
    private class SetClock : Clock() {
        @Volatile
        private var now = Instant.parse("2026-11-15T12:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun pass(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    /** Opens server mode as the daemon does, over this machine's two heads: `claude-team` and `claude-own`. */
    private fun serve(server: ServerConfig = base): ServerMode {
        val heads = listOf(
            StandInHead(head.address.port),
            StandInHead(head.address.port, key = "own", command = "claude-own"),
        )
        return rig.open(state, heads, server)
    }

    @AfterEach
    fun stop() {
        release.countDown()
        rig.close()
        head.stop(0)
        workers.shutdownNow()
    }

    private fun prefixOf(key: String): String = key.split('_').take(2).joinToString("_")

    private fun request(method: String, path: String, key: String?, body: String? = null): HttpRequest.Builder {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .also { builder -> key?.let { builder.header("x-api-key", it) } }
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun get(path: String, key: String? = null): HttpResponse<String> = send(request("GET", path, key))

    private fun turn(key: String?, body: String = TURN): HttpResponse<String> =
        send(request("POST", TURN_PATH, key, body))

    private fun turnHeld(key: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(request("POST", TURN_PATH, key, HELD_TURN).build(), HttpResponse.BodyHandlers.ofString())

    /** One POST of a turn as its bytes on the wire, [path] exactly as it is written here: an HTTP client would decode
     *  or drop part of what some paths spell before sending them. */
    private fun onTheWire(path: String, key: String, body: String): ByteArray {
        val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nx-api-key: $key\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n"
        return (head + body).toByteArray()
    }

    /** The status a turn POSTed to [path], spelled exactly so, is answered with. */
    private fun statusOf(path: String, key: String): Int = Socket("127.0.0.1", port).use { client ->
        client.getOutputStream().apply {
            write(onTheWire(path, key, TURN))
            flush()
        }
        val status = checkNotNull(client.getInputStream().bufferedReader().readLine()) { "no answer to POST $path" }
        status.split(' ')[1].toInt()
    }

    /** The Anthropic error body Claude Code prints: its type and its message. */
    private fun error(response: HttpResponse<String>): Pair<String, String> {
        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    private fun awaitHeld(turns: Int) {
        assertTrue(held.tryAcquire(turns, 10, TimeUnit.SECONDS), "the head never held $turns turns")
    }

    /** Polls [done] with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within 10 s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
        }
    }

    @Test
    fun `health is open, and every other route asks for a key before anything reaches the head`() {
        serve()
        assertEquals(200, get("/health").statusCode())
        for (path in listOf("/", "/claude-team/launch", "/server/api/keys")) {
            assertEquals(401, get(path).statusCode(), path)
        }
        val refused = turn(key = null)
        assertEquals(401, refused.statusCode())
        assertEquals("authentication_error", error(refused).first)
        assertTrue(heard.isEmpty(), "a request with no key reached the head: $heard")
    }

    @Test
    fun `a key the server never issued is refused in words that do not repeat it`() {
        serve()
        val stranger = "spk_abcd1234_" + "Q".repeat(40)
        val refused = turn(stranger)
        assertEquals(401, refused.statusCode())
        assertEquals("authentication_error", error(refused).first)
        assertFalse(refused.body().contains("QQQQ"), refused.body())
        assertFalse(logged.any { "QQQQ" in it }, "the log repeats the key: $logged")
        assertTrue(heard.isEmpty(), "a request with a stranger's key reached the head")
    }

    @Test
    fun `a person's key opens a turn as x-api-key and as a bearer, and the head is never sent it`() {
        serve()
        val key = rig.keyFor("ada@team.example")
        val asApiKey = turn(key)
        val asBearer = send(request("POST", TURN_PATH, key = null, TURN).header("Authorization", "Bearer $key"))
        for (answer in listOf(asApiKey, asBearer)) {
            assertEquals(200, answer.statusCode(), answer.body())
            assertTrue(answer.body().contains("from the box"), answer.body())
        }
        assertEquals(2, heard.size)
        assertFalse(heard.any { prefixOf(key) in it }, "the head was sent the person's key")
    }

    @Test
    fun `a revoked key stops at the next request and an expired one when its time comes, each said as what it is`() {
        val mode = serve()
        val revocable = rig.keyFor("ada@team.example")
        val shortLived = rig.keyFor("bo@team.example", expires = "6s")
        assertEquals(200, turn(revocable).statusCode())
        runBlocking { mode.keyApi.revoke(prefixOf(revocable)) }
        val revoked = turn(revocable)
        assertEquals(401, revoked.statusCode())
        assertTrue(error(revoked).second.contains("revoked"), revoked.body())

        assertEquals(200, turn(shortLived).statusCode(), "a key works until it expires")
        clock.pass(seconds = 6)
        val expired = turn(shortLived)
        assertEquals(401, expired.statusCode())
        assertEquals("authentication_error", error(expired).first)
        assertTrue(error(expired).second.contains("expired"), expired.body())
    }

    @Test
    fun `only the commands the server lists are offered and served`() {
        serve()
        val key = rig.keyFor("ada@team.example")
        val offered = get("/", key)
        assertEquals(200, offered.statusCode())
        assertTrue(offered.body().contains("claude-team"), offered.body())
        assertFalse(offered.body().contains("claude-own"), "a command the server does not list is offered")
        val unlisted = send(request("POST", "/claude-own/v1/messages", key, TURN))
        assertEquals(404, unlisted.statusCode())
        assertTrue(error(unlisted).second.contains("claude-team"), "the refusal names what is served")
        assertTrue(heard.isEmpty(), "a turn for a command the server does not list reached its head")
    }

    @Test
    fun `the server's own API answers an admin's key and refuses a member's`() {
        serve(base.copy(admins = listOf("ops@team.example")))
        val admin = rig.keyFor("Ops@Team.example")
        val member = rig.keyFor("ada@team.example")
        val refused = get("/server/api/keys", member)
        assertEquals(403, refused.statusCode())
        assertEquals("permission_error", error(refused).first)

        val listed = get("/server/api/keys", admin)
        assertEquals(200, listed.statusCode(), listed.body())
        val rows = Json.parseToJsonElement(listed.body()).jsonObject.getValue("keys").jsonArray.map { it.jsonObject }
        val people = rows.map { it.getValue("person").jsonPrimitive.content }.toSet()
        assertEquals(setOf("ops@team.example", "ada@team.example"), people)
        assertFalse(listed.body().contains(admin.substringAfterLast('_')), "a list shows a key's secret")
        assertFalse(rows.any { "hash" in it }, "a list shows a key's hash")

        val asked = """{"person":"new@team.example","name":"laptop"}"""
        val minted = send(request("POST", "/server/api/keys", admin, asked))
        assertEquals(201, minted.statusCode(), minted.body())
        val fresh = Json.parseToJsonElement(minted.body()).jsonObject.getValue("key").jsonPrimitive.content
        assertEquals(200, turn(fresh).statusCode(), "a key an admin minted opens a turn")
        assertEquals(200, send(request("DELETE", "/server/api/keys/${prefixOf(fresh)}", admin)).statusCode())
        assertEquals(401, turn(fresh).statusCode(), "a key an admin revoked still opens a turn")
    }

    @Test
    fun `a turn past the in-flight cap is refused at once, naming the cap, while another person is served`() {
        serve(base.copy(limits = ServerLimits(inflightPerPerson = 2)))
        val ada = rig.keyFor("ada@team.example")
        val bo = rig.keyFor("bo@team.example")
        val inFlight = listOf(turnHeld(ada), turnHeld(ada))
        awaitHeld(turns = 2)

        val third = turn(ada)
        assertEquals(429, third.statusCode(), third.body())
        assertEquals("rate_limit_error", error(third).first)
        assertTrue(third.headers().firstValue("retry-after").orElse("0").toLong() >= 1, "no Retry-After")
        assertTrue(error(third).second.contains("inflight_per_person = 2"), third.body())
        assertEquals(200, turn(bo).statusCode(), "one person at their cap held up another")

        release.countDown()
        inFlight.forEach { assertEquals(200, it.get(10, TimeUnit.SECONDS).statusCode()) }
        assertEquals(200, turn(ada).statusCode(), "the cap did not free when its turns ended")
    }

    @Test
    fun `a client that hangs up mid-turn frees its slot while the head is still busy with it`() {
        serve(base.copy(limits = ServerLimits(inflightPerPerson = 1)))
        val ada = rig.keyFor("ada@team.example")
        Socket("127.0.0.1", port).use { client ->
            client.getOutputStream().apply {
                write(onTheWire(TURN_PATH, ada, HELD_TURN))
                flush()
            }
            awaitHeld(turns = 1)
            assertEquals(429, turn(ada).statusCode(), "the turn in flight does not fill the cap of one")
        }
        awaitUntil("the slot of the client that hung up is free") { turn(ada).statusCode() == 200 }
    }

    @Test
    fun `a turn whose path is spelled another way is held to its person's cap, or is not served as a turn`() {
        serve(base.copy(limits = ServerLimits(inflightPerPerson = 1)))
        val ada = rig.keyFor("ada@team.example")
        val inFlight = turnHeld(ada)
        awaitHeld(turns = 1)

        // A head decodes a path's escapes and skips its empty segments, so each of these is the turn it serves.
        val sameTurn = listOf(
            "/claude-team/v1/%6dessages",
            "/%63laude-team/v1/%6D%65ssages",
            "/claude-team//v1/messages/",
        )
        for (spelled in sameTurn) {
            assertEquals(429, statusOf(spelled, ada), "POST $spelled got past the cap of one turn in flight")
        }
        // No head reads these as its turn. One that reached a head as the turn would be answered 200, past the cap.
        val noTurn = listOf(
            "/claude-team/v1/messages#x",
            "/claude-team/v1/./messages",
            "/claude-team/v1/x/../messages",
            "/claude-team/v1/%zzessages",
        )
        for (spelled in noTurn) {
            assertEquals(404, statusOf(spelled, ada), "POST $spelled")
        }

        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode())
        assertEquals(200, statusOf("/claude-team/v1/%6dessages", ada), "a turn spelled with an escape is not served")
    }

    @Test
    fun `a person's own cap holds over the server's`() {
        val mode = serve(base.copy(limits = ServerLimits(inflightPerPerson = 2)))
        val set = runBlocking { mode.personApi.set("Carl@team.example", buildJsonObject { put("inflight", 1) }) }
        assertInstanceOf(ServerAnswer.Done::class.java, set)
        val carl = rig.keyFor("carl@team.example")
        val inFlight = turnHeld(carl)
        awaitHeld(turns = 1)
        val second = turn(carl)
        assertEquals(429, second.statusCode(), second.body())
        assertTrue(error(second).second.contains("inflight_per_person = 1"), second.body())
        release.countDown()
        assertEquals(200, inFlight.get(10, TimeUnit.SECONDS).statusCode())
    }

    @Test
    fun `a person past their turns a minute waits out the minute, and nobody else waits`() {
        val mode = serve()
        runBlocking { mode.personApi.set("rex@team.example", buildJsonObject { put("rpm", 3) }) }
        val rex = rig.keyFor("rex@team.example")
        repeat(3) { assertEquals(200, turn(rex).statusCode(), "turn ${it + 1} of 3 a minute") }
        clock.pass(seconds = 20)

        val fourth = turn(rex)
        assertEquals(429, fourth.statusCode(), fourth.body())
        assertEquals("rate_limit_error", error(fourth).first)
        val wait = fourth.headers().firstValue("retry-after").orElse("")
        assertEquals("40", wait, "the wait is not what is left of the minute")
        assertTrue(error(fourth).second.contains("rpm_per_person = 3"), fourth.body())
        assertEquals(200, turn(rig.keyFor("ada@team.example")).statusCode(), "one person's minute held up another")

        clock.pass(seconds = 40)
        assertEquals(200, turn(rex).statusCode(), "the minute passed and the person is still refused")
    }

    @Test
    fun `a refused caller is named in the log by the address its trusted proxy reports`() {
        serve(base.copy(trustedProxies = listOf("127.0.0.1/32")))
        val refused = send(request("POST", TURN_PATH, key = null, TURN).header("X-Forwarded-For", "203.0.113.9"))
        assertEquals(401, refused.statusCode())
        assertTrue(logged.any { "203.0.113.9" in it }, "the log does not name the client behind the proxy: $logged")
    }

    @Test
    fun `an address a caller claims for itself is not believed without a trusted proxy`() {
        serve()
        val refused = send(request("POST", TURN_PATH, key = null, TURN).header("X-Forwarded-For", "203.0.113.9"))
        assertEquals(401, refused.statusCode())
        assertFalse(logged.any { "203.0.113.9" in it }, "the log names an address anyone can claim: $logged")
        assertTrue(logged.any { "127.0.0.1" in it }, "the log does not name the connection's own address: $logged")
    }

    @Test
    fun `a restart keeps every key, and when each was last used`() {
        val before = serve()
        val key = rig.keyFor("ada@team.example")
        assertEquals(200, turn(key).statusCode())
        runBlocking { before.close() }

        val after = serve()
        assertEquals(200, turn(key).statusCode(), "a key issued before the restart no longer opens a turn")
        val listed = assertInstanceOf(ServerAnswer.Done::class.java, after.keyApi.list(person = null)).body
        val row: JsonObject = listed.getValue("keys").jsonArray.single().jsonObject
        assertEquals("2026-11-15T12:00:00Z", row.getValue("last_used_at").jsonPrimitive.content)
    }
}

private const val TURN_PATH = "/claude-team/v1/messages"
private const val HOLD = "hold this turn"
private const val TURN = """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
private const val HELD_TURN =
    """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"$HOLD"}]}"""

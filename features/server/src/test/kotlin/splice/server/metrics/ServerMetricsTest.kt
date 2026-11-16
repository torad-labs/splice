// What an admin's scrape of `GET /server/metrics` shows (docs/specs/server-mode.md, F6): everyone with a live key at
// zero before they send anything and no one without one, each request counted under its person, team, command and
// status class with its tokens, time to first word, speed and stall as the head said them, and what a person holds in
// flight against their cap while a turn runs.
package splice.server.metrics

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.server.ServedTokens
import splice.core.server.ServedTurn
import splice.core.server.TurnPace
import splice.core.server.TurnSpeed
import splice.core.topology.ServerConfig
import splice.core.topology.ServerLimits
import splice.core.topology.ServerTeams
import splice.core.turn.SERVED_TURN_HEADER
import splice.server.ServerMode
import splice.server.ServerRig
import splice.server.SetClock
import splice.server.StandInHead
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

internal class ServerMetricsTest {
    @TempDir lateinit var state: Path

    private val clock = SetClock()
    private val rig = ServerRig(clock = clock)
    private val http = HttpClient.newHttpClient()
    private val workers = Executors.newCachedThreadPool()

    /** The head answers a turn once the test lets it; it starts let. */
    @Volatile
    private var goOn = CountDownLatch(0)

    // The head plays its part on the board: each turn it answers was a clean one, as a llama-server timed it.
    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = workers
        createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            goOn.await(WITHIN_MS, TimeUnit.MILLISECONDS)
            rig.board.ended(exchange.requestHeaders.getFirst(SERVED_TURN_HEADER).orEmpty(), HEAD_SAID)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        start()
    }

    private val config: ServerConfig = rig.config.copy(
        admins = listOf("ops@team.example"),
        limits = ServerLimits(inflightPerPerson = 2),
        teams = ServerTeams(byDomain = mapOf("team.example" to "core")),
    )

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
        workers.shutdownNow()
    }

    private fun serve(server: ServerConfig = config): ServerMode =
        rig.open(state, listOf(StandInHead(head.address.port)), server)

    private fun get(key: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/server/metrics"))
        key?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun scrape(admin: String): String {
        val answer = get(admin)
        assertEquals(200, answer.statusCode(), answer.body())
        return answer.body()
    }

    private fun send(key: String): CompletableFuture<HttpResponse<String>> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString("""{"model":"m","messages":[]}"""))
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The value of the one sample [line] begins, as Prometheus text writes it; null when the scrape has no such
     *  line. */
    private fun sample(text: String, line: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("$line ") }?.substringAfterLast(' ')

    private fun awaitSample(admin: String, line: String, value: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WITHIN_MS)
        while (sample(scrape(admin), line) != value) {
            check(System.nanoTime() < deadline) { "$line never read $value:\n${scrape(admin)}" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(STEP_MS))
        }
    }

    @Test
    fun `only an admin's key scrapes the metrics`() {
        serve()
        val admin = rig.keyFor("ops@team.example")
        val someone = rig.keyFor("sam@team.example")

        assertEquals(401, get(null).statusCode())
        val refused = get(someone)
        assertEquals(403, refused.statusCode())
        assertTrue(refused.body().contains("sam@team.example is not one of them"), refused.body())
        val answer = get(admin)
        assertEquals(200, answer.statusCode())
        assertEquals("text/plain; version=0.0.4; charset=utf-8", answer.headers().firstValue("content-type").orElse(""))
    }

    @Test
    fun `everyone with a live key reads zero before sending anything, and no one without one is listed`() {
        val mode = serve()
        val admin = rig.keyFor("ops@team.example")
        rig.keyFor("dora@team.example")
        rig.keyFor("ex@elsewhere.example")
        runBlocking { mode.keyApi.revokePerson("ex@elsewhere.example") }

        val text = scrape(admin)
        val dora = """person="dora@team.example",team="core""""
        for (status in listOf("2xx", "4xx", "5xx")) {
            val line = """splice_server_requests_total{$dora,command="claude-team",status_class="$status"}"""
            assertEquals("0", sample(text, line))
        }
        for (kind in listOf("prompt", "cached", "completion")) {
            assertEquals("0", sample(text, """splice_server_tokens_total{$dora,kind="$kind"}"""))
        }
        assertEquals("0", sample(text, """splice_server_ttft_seconds_count{$dora}"""))
        assertEquals("0", sample(text, """splice_server_stream_stall_seconds_bucket{$dora,le="+Inf"}"""))
        assertEquals("0", sample(text, """splice_server_inflight{person="dora@team.example"}"""))
        assertEquals("2", sample(text, """splice_server_inflight_limit{person="dora@team.example"}"""))
        assertFalse(text.contains("ex@elsewhere.example"), text)
    }

    @Test
    fun `a person whose only key expires is refused and leaves the scrape at the same moment`() {
        serve()
        val admin = rig.keyFor("ops@team.example")
        val bo = rig.keyFor("bo@team.example", expires = "6s")
        val listed = """splice_server_inflight{person="bo@team.example"}"""
        assertEquals("0", sample(scrape(admin), listed))

        clock.pass(seconds = 6)
        assertEquals(401, send(bo).get(WITHIN_MS, TimeUnit.MILLISECONDS).statusCode())
        assertFalse(scrape(admin).contains("bo@team.example"), "a person no key admits has no series")
    }

    @Test
    fun `a request is counted under its person and team with what its head said of it`() {
        serve()
        val admin = rig.keyFor("ops@team.example")
        val ann = rig.keyFor("ann@team.example")
        assertEquals(200, send(ann).get(WITHIN_MS, TimeUnit.MILLISECONDS).statusCode())

        val who = """person="ann@team.example",team="core""""
        awaitSample(admin, """splice_server_requests_total{$who,command="claude-team",status_class="2xx"}""", "1")
        val text = scrape(admin)
        assertEquals("1200", sample(text, """splice_server_tokens_total{$who,kind="prompt"}"""))
        assertEquals("200", sample(text, """splice_server_tokens_total{$who,kind="cached"}"""))
        assertEquals("40", sample(text, """splice_server_tokens_total{$who,kind="completion"}"""))
        assertEquals("0.3", sample(text, """splice_server_ttft_seconds_sum{$who}"""))
        // 0.3 s falls in the 0.5 s bucket and every one above it, and in none below.
        assertEquals("0", sample(text, """splice_server_ttft_seconds_bucket{$who,le="0.25"}"""))
        assertEquals("1", sample(text, """splice_server_ttft_seconds_bucket{$who,le="0.5"}"""))
        assertEquals("2000", sample(text, """splice_server_prefill_tokens_per_second_sum{$who}"""))
        assertEquals("50", sample(text, """splice_server_decode_tokens_per_second_sum{$who}"""))
        assertEquals("3", sample(text, """splice_server_stream_stall_seconds_sum{$who}"""))
        assertEquals("1", sample(text, """splice_server_duration_seconds_count{$who}"""))
    }

    @Test
    fun `a turn in flight reads against its person's cap until it ends, and no cap reads +Inf`() {
        serve(config.copy(limits = ServerLimits()))
        val admin = rig.keyFor("ops@team.example")
        val dan = rig.keyFor("dan@team.example")
        goOn = CountDownLatch(1)
        val turn = send(dan)

        awaitSample(admin, """splice_server_inflight{person="dan@team.example"}""", "1")
        assertEquals("+Inf", sample(scrape(admin), """splice_server_inflight_limit{person="dan@team.example"}"""))
        goOn.countDown()
        assertEquals(200, turn.get(WITHIN_MS, TimeUnit.MILLISECONDS).statusCode())
        awaitSample(admin, """splice_server_inflight{person="dan@team.example"}""", "0")
    }
}

/** What the head says of every turn it answers. */
private val HEAD_SAID = ServedTurn(
    model = "team-model",
    upstream = null,
    outcome = "ok",
    tokens = ServedTokens(prompt = 1200, cached = 200, completion = 40),
    costUsd = null,
    pace = TurnPace(ttftMs = 300, stallMs = 3_000),
    speed = TurnSpeed(prefillTps = 2000.0, decodeTps = 50.0),
)

private const val WITHIN_MS = 10_000L
private const val STEP_MS = 50L

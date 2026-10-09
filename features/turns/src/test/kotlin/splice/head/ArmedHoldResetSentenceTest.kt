// NEW: the refusal a client prints while a burst hold is armed says the provider's window reset the way a person
// reads it. HeadAdmission wrote it as an ISO instant ("resets at 2026-09-29T21:30:00Z"), the last client-facing sentence
// that still did after V4-419, V4-425 and V4-428. Driven through a real head over a local upstream that answers every
// request 429 with a body naming a reset an hour and a half out: the turn that meets the 429 arms the hold, and the turns
// refused at admission behind it are read back. The journal's `provider_reset=` is the log spelling and stays ISO. Each
// refused turn runs in its own machine zone, so a sentence that still said UTC, or the ISO instant, cannot pass.
package splice.head

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.LocalTimeText
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val TOO_MANY_REQUESTS = 429
private const val TOKYO = "Asia/Tokyo"
private const val CHICAGO = "America/Chicago"
private const val MINUTE_S = 60L
private const val RESET_AHEAD_MIN = 90L
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
private val JOURNAL_RESET = Regex("""provider_reset=(\S+) gateway_hold=""")

/** A reset 90 minutes out, on the half minute: the sentence reads (wall now + the remaining hold) / 1000, which lands
 *  within a second of it, and a mid-minute instant reads as the same minute either way. */
private fun resetHalfMinuteAhead(): Long =
    (System.currentTimeMillis() / 1_000 / MINUTE_S + RESET_AHEAD_MIN) * MINUTE_S + MINUTE_S / 2

// A burst 429 that names a window reset and no plan: usage_limit_reached is what would make it a plan hold.
private fun burstBody(reset: Long) =
    """{"error":{"type":"rate_limit_exceeded","message":"slow down"},"resets_at":$reset}"""

private class PlainAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** A real head over a local upstream that answers every request 429 with [upstreamBody]. maxRetries = 1, so the turn
 *  that meets the 429 gives up at once and arms the hold instead of waiting out V4-61's 15 s schedule. */
private class BurstHead(tmp: Path, upstreamBody: String) {
    val journal = CopyOnWriteArrayList<String>()
    private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.readAllBytes()
            val bytes = upstreamBody.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(TOO_MANY_REQUESTS, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = PlainAuth(),
                baseUrl = "http://127.0.0.1:${upstream.address.port}",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
            gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 }),
            log = journal::add,
        ),
    )

    suspend fun start() = head.start()

    suspend fun close() {
        head.stop()
        client.close()
        upstream.stop(0)
    }

    suspend fun turn(): HttpResponse = client.post("http://127.0.0.1:${head.port}/v1/messages") {
        header("Content-Type", "application/json")
        setBody(
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }
}

/** Runs [block] with the machine's zone set to [zone], and puts the old one back. */
private fun <T> inZone(zone: String, block: () -> T): T {
    val saved = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    try {
        return block()
    } finally {
        TimeZone.setDefault(saved)
    }
}

class ArmedHoldResetSentenceTest {

    @Test
    fun `the armed hold says the window reset in the machine's hour and zone, and the journal keeps the ISO instant`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val reset = resetHalfMinuteAhead()
        val head = BurstHead(tmp, burstBody(reset))
        head.start()
        val refused = try {
            head.turn().bodyAsText()
            listOf(TOKYO, CHICAGO).associateWith { zone ->
                inZone(zone) {
                    runBlocking {
                        val response = head.turn()
                        assertEquals(TOO_MANY_REQUESTS, response.status.value, "refused at admission, hold armed")
                        response.bodyAsText()
                    }
                }
            }
        } finally {
            head.close()
        }

        refused.forEach { (zone, body) ->
            val instant = LocalTimeText(ZoneId.of(zone)).at(reset)
            assertTrue("The upstream reports its quota window resets at $instant;" in body, "$zone: $body")
            assertFalse(ISO_INSTANT.containsMatchIn(body), "the reset is not an ISO instant: $body")
        }
        assertTrue("JST" in refused.getValue(TOKYO) && "CDT" in refused.getValue(CHICAGO), refused.toString())
        val journalled = head.journal.mapNotNull { JOURNAL_RESET.find(it)?.groupValues?.get(1) }
        assertEquals(2, journalled.size, head.journal.toString())
        journalled.forEach { iso ->
            assertTrue(
                iso == Instant.ofEpochSecond(reset).toString() || iso == Instant.ofEpochSecond(reset - 1).toString(),
                "provider_reset stays the ISO instant of $reset, got $iso",
            )
        }
    }

    @Test
    fun `a burst that names no window reset says no time in the hold sentence`(@TempDir tmp: Path) = runBlocking {
        val head = BurstHead(tmp, """{"error":{"type":"rate_limit_exceeded","message":"slow down"}}""")
        head.start()
        val body = try {
            head.turn().bodyAsText()
            inZone(TOKYO) { runBlocking { head.turn().bodyAsText() } }
        } finally {
            head.close()
        }

        assertTrue("holding new turns for" in body, body)
        assertFalse("quota window resets at" in body, body)
        assertFalse('—' in body, "no em dash in text the client shows: $body")
    }
}

// NEW: a turn that a spent PLAN window ended is recorded as one, and says when it comes back. Marlin's walk
// (f7f1e9308, daemon.log:39520): ChatGPT answered usage_limit_reached with a reset six days out, the retry loop armed the
// plan hold, and the turn was then recorded error:upstream-failed with the table's "wait a moment and retry"; the turns
// held behind it read "wait for the limit to clear" with no time, though splice held the instant. V4-444: the
// instant is COUNTED on both rows too (earliest_reset_epoch_seconds), since the console's list reads the perf
// file and not the trace's sentence; a burst that names no reset counts none. Driven through a real
// head over a local upstream, so it is the routes that record: the turn that meets the 429 (TurnKnownEnd) and the turn
// refused at admission while the hold is live (HeadAdmission), read back from the trace the Turns page reads. A burst
// 429 that names no reset must keep both of its old endings and their sentences. The machine's zone is set to Tokyo for
// the run, so a sentence that still said Chicago's hour or "CT" cannot pass.
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.util.AsyncFileIo
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.turn.OutcomeSentences
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

private const val TOO_MANY_REQUESTS = 429
private const val SIX_DAYS_S = 6L * 24 * 3_600

// The weekly window's own length, as a provider that read it passes it: seconds (V4-444).
private const val SEVEN_DAYS_S = 7L * 24 * 3_600
private const val TRACE_DAY_EPOCH_MS = 1_789_725_600_000L
private const val TRACE_FILE = "codex-2026-09-18.jsonl"
private const val TOKYO = "Asia/Tokyo"

// ChatGPT's live weekly-limit body (Sep 28, 5:11 PM CT), reset made relative.
private fun usageLimitBody(reset: Long) =
    """{"error":{"type":"usage_limit_reached","plan_type":"pro","resets_at":$reset,""" +
        """"resets_in_seconds":$SIX_DAYS_S,"limit_window_minutes":10080}}"""

private const val BURST_BODY = """{"error":{"type":"rate_limit_exceeded","message":"slow down"}}"""

/** A body-reading provider auth, as codex is: a usage_limit_reached body with a reset ahead names a 7-day window. */
private class SpentPlanAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
    override fun planLimitFromBody(body: String, nowEpochSeconds: Long): PlanLimit? =
        Regex(""""resets_at":(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
            ?.takeIf { body.contains("usage_limit_reached") && it > nowEpochSeconds }
            // V4-444: a provider that read the window's LENGTH passes it in seconds, as codex does.
            ?.let { PlanLimit("seven_day", it, windowSeconds = SEVEN_DAYS_S) }
}

/** A real head over a local upstream that answers every request 429 with [upstreamBody]. maxRetries = 1, so the
 *  turn that meets the 429 gives up at once (and arms the horizon) instead of waiting out V4-61's 15 s schedule. */
private class LimitedHead(tmp: Path, upstreamBody: String) {
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
    val traceDir: Path = tmp.resolve("trace")
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val trace = splice.head.syntheticTraceStore(
        splice.core.storage.ActivityDays(traceDir, "codex", 7, WallClock { TRACE_DAY_EPOCH_MS }, ownerOnly = true),
        "codex",
        maxBodyChars = 4096,
        now = WallClock { TRACE_DAY_EPOCH_MS },
    )
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = SpentPlanAuth(),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
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
            log = {},
        ).let { it.copy(stores = headStores(tmp, trace = trace)) },
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

    /** The turn records the trace holds, oldest first. */
    fun endings(): List<JsonObject> {
        assertTrue(AsyncFileIo.drain())
        return Files.readAllLines(traceDir.resolve(TRACE_FILE))
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { "outcome" in it }
    }
}

private fun JsonObject.outcome() = getValue("outcome").jsonPrimitive.content

private fun JsonObject.sentence() = get("failure_sentence")?.jsonPrimitive?.content

/** The instant this row counts the spent window coming back, null when it counts none. A surface reading the perf
 *  file alone (the console's Requests list) has only this. */
private fun JsonObject.resetCounted(): Long? = get("perf")?.jsonObject
    ?.get("counters")?.jsonObject
    ?.get(PerfKeys.EARLIEST_RESET_EPOCH_SECONDS)?.jsonPrimitive?.content?.toLong()

/** How long this row says the spent window is, null when it says nothing. The console names the window from it
 *  ("Week") instead of showing a bare "At limit" (V4-444). */
private fun JsonObject.windowCounted(): Long? = get("perf")?.jsonObject
    ?.get("counters")?.jsonObject
    ?.get(PerfKeys.LIMIT_WINDOW_SECONDS)?.jsonPrimitive?.content?.toLong()

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

class PlanLimitTurnEndingTest {

    @Test
    fun `the turn that meets a spent plan window and the turn held behind it end plan-limit and name the reset`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val reset = System.currentTimeMillis() / 1_000 + SIX_DAYS_S
        val head = LimitedHead(tmp, usageLimitBody(reset))
        head.start()
        val endings = try {
            inZone(TOKYO) {
                runBlocking {
                    head.turn().bodyAsText()
                    val refused = head.turn()
                    assertEquals(TOO_MANY_REQUESTS, refused.status.value, "the second turn is refused at admission")
                    refused.bodyAsText()
                }
            }
            head.endings()
        } finally {
            head.close()
        }

        assertEquals(2, endings.size, endings.toString())
        assertEquals(listOf("error:plan-limit", "error:plan-limit"), endings.map { it.outcome() })
        val said = endings.map { requireNotNull(it.sentence()) { "a plan-limit ending left without words" } }
        val instant = LocalTimeText(ZoneId.of(TOKYO)).at(reset)
        said.forEach { sentence ->
            assertTrue(instant in sentence, "the reset $instant is named in the machine's zone: $sentence")
            assertTrue("JST" in sentence, sentence)
            assertTrue("7-day" in sentence, "the window is named: $sentence")
            assertFalse("CT" in sentence || "wait a moment" in sentence, "the generic words came back: $sentence")
        }
        assertEquals(said[0], said[1], "the held turn names the same instant, in the same words")
        endings.forEach { ending ->
            assertEquals(reset, ending.resetCounted(), "the row counts when the window comes back: $ending")
            // V4-444: and how long the window is, on BOTH endings — the turn the upstream refused and the one
            // admission turned away behind the hold. The console names the window from this alone.
            assertEquals(
                SEVEN_DAYS_S,
                ending.windowCounted(),
                "the row counts the window's length, so a surface reading it says 'Week' and not 'At limit': $ending",
            )
        }
    }

    @Test
    fun `a burst 429 that names no reset keeps upstream-failed and rate-limited with their sentences`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val head = LimitedHead(tmp, BURST_BODY)
        head.start()
        val endings = try {
            head.turn().bodyAsText()
            assertEquals(TOO_MANY_REQUESTS, head.turn().also { it.bodyAsText() }.status.value)
            head.endings()
        } finally {
            head.close()
        }

        assertEquals(listOf("error:upstream-failed", "error:rate-limited"), endings.map { it.outcome() })
        assertEquals(OutcomeSentences.of("error:upstream-failed"), endings[0].sentence())
        assertEquals(OutcomeSentences.of("error:rate-limited"), endings[1].sentence())
        endings.forEach { ending ->
            assertNull(ending.resetCounted(), "a burst with no reset counts none, so no surface can invent one")
            assertNull(ending.windowCounted(), "and no window either: a burst spends none")
        }
    }
}

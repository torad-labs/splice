// OPERATOR LAW, 2026-09-16, Marcos verbatim: "I need an E2E test that will break if we ever
// remove the retries again. We should ALWAYS have some sort of retry armed. ALWAYS. There will
// never be a case, if an error occur, that we wont have a way to retry."
//
// THE INVARIANT. Every turn that ends in an error must leave the client able to recover. For every
// error class the gateway can produce, the CLIENT-VISIBLE outcome must be exactly one of:
//   (a) the turn SUCCEEDED because splice retried internally — the client sees 200, and the retry
//       is therefore armed by construction;
//   (b) an HTTP status Claude Code retries on (429, 408, 5xx except 501);
//   (c) an SSE error frame whose TYPE Claude Code retries on (overloaded_error, api_error);
//   (d) a member of the EXPLICIT exclusion list below, each with its reason written down.
// Anything in none of the four FAILS BY NAME.
//
// (d) RECONCILED WITH V4-62 — TWO LAWS THAT TURNED OUT TO GOVERN DIFFERENT LAYERS.
// The operator's law ("we would retry on any error, no matter what, with different levels of retry
// and escalation + backoff") is about what SPLICE does to the upstream, and V4-62 made it so: the
// status gate is gone from RetryPolicy and a 400 now takes the same 200ms curve as a 503. This list
// is about what the CLIENT can usefully do with the ENDING. Different questions.
//
// SO THE FOUR STAY, AND EVERY REASON BELOW NOW READS AS "SPLICE ALREADY TRIED". The old wording
// called them "genuinely non-retryable", which V4-62 falsified; the exclusion was never a decision
// not to retry, it is a statement about EXHAUSTED retries. A 400 that splice backed off on and is
// STILL a 400 is the request's own body — handing it to the client to retry sends the identical
// body one more time. A 401 splice already refreshed and retried means the credential is really
// rejected; the client's retry carries the same one.
//
// V4-62 makes this list STRONGER, not weaker: the more turns succeed inside splice, the more of
// them land in (a) above and never reach this list at all. A type may be added here only when a
// retry has been RUN and is provably a no-op — which is also why Failure.deterministic is the sole
// carve-out in the retry path itself, for verdicts splice computed with no upstream involved.
//
// THIS TEST MUST NOT BE SATISFIABLE BY EDITING A COUNT. Every test that failed us before asserted
// POLICY — how many upstream calls splice makes — and that is a number we control, so each change
// was ratified by editing the assertion (the V4-10 notes record it edited 1->3 and 2->6, then
// back). This asserts what the CLIENT RECEIVES, which is not ours to redefine. IF A FUTURE CHANGE
// REDDENS THIS TEST, THE CORRECT RESPONSE IS TO RESTORE THE RETRY, NEVER TO RELAX THE ASSERTION.
//
// THE DENOMINATOR COMES FROM SOURCE (CLAUDE.md 24): the type list is ErrorType.entries, read by
// reflection, NOT a hand-written roster of seven. A hand-written list cannot fail for an eighth
// type somebody adds next month; a new ErrorType fails this build BY NAME until it is given a
// disposition, which is the whole point.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.TestResponsesProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** Excluded CLIENT-side after splice's own retries are EXHAUSTED — never "we did not retry". V4-62
 *  retries every upstream failure; each reason below is about the ending the client is handed once
 *  that has already happened. Anything not here must reach the client in a recoverable shape. */
private val EXCLUDED: Map<ErrorType, String> = mapOf(
    ErrorType.INVALID_REQUEST to
        "splice already backed off and re-sent it; still a 400 means the body itself, and the " +
        "client's retry sends that same body again",
    ErrorType.AUTHENTICATION to
        "splice already refreshed the credential once (G1) and was rejected again; a client retry " +
        "carries the same rejected credential, so the fix is re-auth, not repetition",
    ErrorType.PERMISSION to "an entitlement verdict that survived the retry budget; it is not transient",
    ErrorType.NOT_FOUND to "the model or route does not exist; the retry budget was spent proving it",
)

/** The FIFTH exclusion, and the only one that is not an ErrorType. It is a SHAPE, not a category:
 *  once a 200 and content are on the wire the status line is gone for good, so splice cannot
 *  retroactively answer 429 — and a stream held for an 88-minute reset is not a wait, it is a hang
 *  the client will reap long before it ends.
 *
 *  WRITTEN AS NARROWLY AS THE REASON ALLOWS, deliberately: it names MID-STREAM **and** a reset
 *  longer than the bounded wait, and nothing else. A future seat cannot lean on it for a
 *  pre-stream case (those all go through admission, where a status is still ours to write) or for
 *  a short reset (that is V4-57's fix, and it is recoverable). Widening this sentence is the defect
 *  this campaign exists to catch; the two conditions are the whole of it. */
private const val MID_STREAM_EXCLUSION =
    "a 429 discovered after the 200 and its content are already on the wire, whose reset is longer " +
        "than the bounded wait a held stream can survive"

/** The scenario that drives each type, or null when nothing in the mock can produce it yet. A null
 *  is NOT a pass: the test fails by name and says a driver is owed. */
private val DRIVER: Map<ErrorType, String?> = mapOf(
    // RATE_LIMIT is driven TWICE by design: the first turn meets the limit mid-stream, which arms
    // the cooldown, and the SECOND is the one that is refused at admission with a real 429. That
    // second turn is the operator's actual complaint and the recoverable shape V4-50 built.
    ErrorType.RATE_LIMIT to "quota429+requota429",
    ErrorType.OVERLOADED to "overload_503",
    ErrorType.AUTHENTICATION to "authfail",
    ErrorType.INVALID_REQUEST to "overflow_sse",
    ErrorType.API_ERROR to "malformed_sse",
    ErrorType.PERMISSION to null,
    ErrorType.NOT_FOUND to null,
)

private class RetryFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-retry", "acct-retry")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryAlwaysArmedTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val port = ServerSocket(0).use { it.localPort }

    private lateinit var head: HeadServer

    // maxRetries = 1 (V4-61): a 429 with budget left now waits the 15s floor in REAL time before
    // retrying, which outlived this test client's request timeout. The sweep needs the ARM that
    // follows exhaustion (provider-spi pins the schedule), so the budget is a single attempt.
    private val upstreamClient = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1)
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("retry-always")
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = RetryFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = upstreamClient,
                inferenceToken = "test-inference-token",
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = {},
            ),
        )
        head.start()
        Thread.sleep(700)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    /** One error turn against [scenario]; returns the HTTP status and the body the client sees.
     *  A scenario tagged "+re<name>" is driven twice, so the cooldown a first turn arms is what the
     *  second turn is answered from — that second reply is the one the operator was complaining
     *  about, and the first one is deliberately left as the committed-200 case. */
    private suspend fun drive(scenario: String): Pair<Int, String> {
        val (first, again) = scenario.split("+").let { it.first() to it.getOrNull(1) }
        upstreamClient.clearRateLimitCooldown()
        var seen = request(first)
        if (again != null) seen = request(again)
        return seen
    }

    private suspend fun request(scenario: String): Pair<Int, String> {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }
        return response.status.value to response.bodyAsText()
    }

    /** The four dispositions, as a decision about what the CLIENT got — never about what we sent. */
    private fun classify(status: Int, body: String): String = when {
        status == 200 && !body.contains("event: error") -> "SUCCEEDED (retry armed internally)"
        status in RETRYABLE_STATUSES -> "retryable HTTP status $status"
        else -> classifyBody(body)
    }

    /** The body half of [classify], once the status has ruled out the pre-stream outcomes. Written
     *  as one expression so the dispositions stay a single readable list, first match wins, and
     *  UNRECOVERABLE stays the honest default for a shape nothing in this table recognises. */
    private fun classifyBody(body: String): String =
        RETRYABLE_SSE_TYPES.firstOrNull { body.contains("\"type\":\"$it\"") }
            ?.let { "retryable SSE error type $it" }
            ?: EXCLUDED.keys.firstOrNull { body.contains("\"type\":\"${it.wireName}\"") }
                ?.let { "excluded by name: ${it.name}" }
            ?: "UNRECOVERABLE"

    @Test
    fun `every ErrorType is either recoverable by the client or excluded by name`() = runBlocking {
        val unrecoverable = mutableListOf<String>()
        val undriven = mutableListOf<String>()
        val observed = mutableListOf<String>()
        for (type in ErrorType.entries) {
            val scenario = DRIVER[type]
            if (scenario == null) {
                // (d) is a disposition, but ONLY for a type carrying a written reason. A type that
                // is neither driven by a scenario NOR on the exclusion list is an absence, and an
                // absence fails by name — that is what stops an eighth ErrorType slipping through.
                if (type !in EXCLUDED) {
                    undriven += "${type.name} (${type.wireName}) is neither driven by a scenario " +
                        "nor given a written exclusion"
                }
                continue
            }
            val (status, body) = drive(scenario)
            val verdict = classify(status, body)
            observed += "$scenario -> ${type.name}: status=$status verdict=$verdict"
            if (verdict == "UNRECOVERABLE") {
                unrecoverable += "${type.name} via $scenario: status=$status body=${body.take(160)}"
            }
        }
        assertTrue(observed.isNotEmpty(), "the sweep must actually drive scenarios")
        val gaps = unrecoverable + undriven
        if (gaps.isNotEmpty()) {
            fail<Unit>(
                "THE ALWAYS-ARMED LAW IS VIOLATED. Restore the retry rather than relaxing this " +
                    "assertion:\n" + gaps.joinToString("\n") +
                    "\n--- everything observed this run ---\n" + observed.joinToString("\n"),
            )
        }
    }

    @Test
    fun `a transient failure the upstream recovers from still reaches the client as a success`() = runBlocking {
        // (a) of the invariant, and the operator's law in its plainest form: splice must retry a
        // transient failure on its own. overload_once 503s exactly once then behaves; if the
        // internal retry is ever removed this turn ends in an error instead of a 200.
        val (status, body) = drive("overload_once")
        assertEquals(200, status, "a once-failing upstream must still be answered from a retry, got: ${body.take(200)}")
    }

    @Test
    fun `the exclusion list is exactly the four types and every entry says why`() {
        // The list is a written claim, so it is pinned: a fifth entry, or a blank reason, is a
        // disposition smuggled in without an argument.
        assertEquals(
            setOf(
                ErrorType.INVALID_REQUEST,
                ErrorType.AUTHENTICATION,
                ErrorType.PERMISSION,
                ErrorType.NOT_FOUND,
            ),
            EXCLUDED.keys,
            "only these four are non-retryable; anything else must reach the client recoverably",
        )
        EXCLUDED.forEach { (type, reason) ->
            assertTrue(reason.length > 20, "${type.name} needs a real reason, got: $reason")
        }
    }

    @Test
    fun `the fifth exclusion names a mid-stream shape and a long reset, and widens no further`() {
        // Narrowness is the property being pinned, not the prose. It must mention the committed
        // stream (the reason the status is gone) AND the long reset (the reason a wait cannot save
        // it). Drop either and it describes a recoverable case, which is how an exclusion list
        // stops being an argument and starts being a place to hide things.
        assertTrue(
            MID_STREAM_EXCLUSION.contains("already on the wire"),
            "exclusion 5 must name the committed stream: $MID_STREAM_EXCLUSION",
        )
        assertTrue(
            MID_STREAM_EXCLUSION.contains("longer than the bounded wait"),
            "exclusion 5 must be bounded to the LONG case; a short reset is recoverable and fixed",
        )
        assertTrue(
            !MID_STREAM_EXCLUSION.contains("any") && !MID_STREAM_EXCLUSION.contains("all"),
            "an exclusion phrased as a category is one a future seat can lean on: $MID_STREAM_EXCLUSION",
        )
    }

    @Test
    fun `the denominator is the enum itself, so a new ErrorType cannot be added silently`() {
        // Guards the guard: if the reflection ever silently returned an empty or short list, the
        // sweep above would pass for having nothing to check. This pins that the denominator is
        // the real set and that it is at least as large as the four we exclude plus one retryable.
        assertTrue(
            ErrorType.entries.size >= 5,
            "the denominator collapsed: ${ErrorType.entries.map { it.name }}",
        )
        assertTrue(
            ErrorType.entries.all { DRIVER.containsKey(it) },
            "every type needs a disposition row: missing ${ErrorType.entries.filterNot { DRIVER.containsKey(it) }}",
        )
    }
}

/** Statuses Claude Code retries on: 429, 408, and 5xx except 501. */
private val RETRYABLE_STATUSES = setOf(408, 429, 500, 502, 503, 504, 529)

/** SSE error types Claude Code retries on. */
private val RETRYABLE_SSE_TYPES = listOf("overloaded_error", "api_error")

// NEW: the last uncovered arms of TurnDriver.emitFailure, plus the inbound body-read timeout —
// the honest-error fallbacks nothing ever reached. The other four emitFailure arms (UpstreamFailed,
// StreamTornBeforeClient/IOException, SseFrameTooLargeException, and the classified zero-event path)
// are pinned by HeadServerIntegrationTest and HeadServerReviewTest; these are the two that were not,
// and a fallback that has never run is exactly the one that escapes as a truncated HTTP 200 the day
// it is finally needed.
//
// A SIBLING class rather than more cases on HeadServerIntegrationTest: that class is at detekt's
// LargeClass ceiling (400 lines), and every case here needs its own ISOLATED head anyway — an auth
// that holds no credential, a provider that throws, a head whose inbound read timeout is 300ms.
// Same build-a-head-per-test idiom as HeadServerReviewTest.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.DEFAULT_REQUEST_READ_TIMEOUT_MS
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The slow-loris case's own bound. MEASURED, not guessed (2026-08-28): that post returns after
 *  15.1s, not the 300ms readTimeoutMs it asserts on — the server answers 408 quickly, but CIO does
 *  not surface the response while the outbound writeTo coroutine is still suspended, so what
 *  actually unblocks the call is the CIO endpoint's own ~15s requestTimeout default giving up on
 *  the write. 30s is 2x that, wide enough never to race the behaviour under test and still finite. */
private const val TIMEOUT_TEST_BOUND_MS = 30_000L

/** Holds a credential and can refresh it — the ordinary case, here only so the throwing-provider
 *  test fails for its own reason and not for a missing credential. */
private class BranchFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-branch", "acct-branch")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** No credential and nothing to refresh — the shape that raises [splice.spi.UpstreamAuthMissing]
 *  inside the transport, before any upstream request is attempted. */
private class CredentiallessAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = null
    override suspend fun refresh(): Credentials? = null
    override suspend fun describe(): AuthDescription = AuthDescription(false, "fake")
}

/** A provider whose per-turn header build throws — the "internal gateway bug" class the last arm of
 *  TurnDriver.emitFailure exists for (its comment names a bad base_url parse and an IllegalState out
 *  of Ktor internals). IllegalStateException specifically, via [error]: TurnFailures.catchingTurnFailure
 *  captures IllegalArgument/IllegalState and nothing broader, so a *plain* RuntimeException would
 *  escape the turn boundary entirely rather than reaching this branch. */
private class ThrowingProvider(delegate: Provider) : Provider by delegate {
    override fun extraHeaders(creds: Credentials): Map<String, String> = error("synthetic gateway bug")
}

/** The shape the real bugs have: a SUBCLASS of the base the turn boundary converts. Ktor's
 *  io.ktor.http.URLParserException extends IllegalStateException and kotlinx.serialization's
 *  SerializationException extends IllegalArgumentException — both named in emitFailure's comment —
 *  so the `unexpected` turn line has to survive one level of subclassing to stay useful. */
private class SyntheticUrlParseException(message: String) : IllegalStateException(message)

private class SubclassThrowingProvider(delegate: Provider) : Provider by delegate {
    override fun extraHeaders(creds: Credentials): Map<String, String> =
        throw SyntheticUrlParseException("synthetic bad base_url")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerFailureBranchTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val logs = mutableListOf<String>()
    private lateinit var tmp: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("head-branch")
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun buildHead(
        headPort: Int,
        auth: RefreshableAuthProvider,
        wrap: (Provider) -> Provider = { it },
        readTimeoutMs: Long = DEFAULT_REQUEST_READ_TIMEOUT_MS,
    ): HeadServer {
        val provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = auth,
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        return HeadServer(
            provider = wrap(provider),
            listenPort = headPort,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 2),
                inferenceToken = "test-inference-token",
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = { logs.add(it) }),
                compactStats = CompactStats(tmp.resolve("compact-$headPort.jsonl")),
                usageStore = UsageStore(
                    tmp.resolve("usage-$headPort.json"),
                    tmp.resolve("ratelimit-$headPort.json"),
                ),
                perfStats = PerfStats(tmp.resolve("perf-$headPort.jsonl")),
                log = { logs.add(it) },
                requestReadTimeoutMs = readTimeoutMs,
            ),
        )
    }

    private suspend fun turn(headPort: Int, scenario: String): String =
        client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    @Test
    fun `a head with no credential emits an authentication error carrying the login hint`() = runBlocking {
        val headPort = freshPort()
        val head = buildHead(headPort, CredentiallessAuth())
        head.start()
        awaitListening(headPort)
        val before = logs.size
        try {
            val upstreamBefore = mock.upstreamBodies.size
            val sse = turn(headPort, "basic")
            assertTrue(sse.contains("event: error"), "expected an error event in: $sse")
            assertTrue(sse.contains("authentication_error"), sse)
            assertTrue(sse.contains("codex: no upstream credentials — run: claudex login"), sse)
            assertFalse(sse.contains("event: message_stop"), "never a clean stop after failure: $sse")
            // The credential is resolved BEFORE the request is built, so nothing reached upstream.
            assertEquals(upstreamBefore, mock.upstreamBodies.size)
            val scoped = logs.drop(before)
            assertTrue(
                scoped.any { it.contains("perf outcome=error:auth-missing") },
                "expected the error:auth-missing perf row in: $scoped",
            )
        } finally {
            head.stop()
        }
    }

    @Test
    fun `an internal gateway bug emits one honest api_error, not a truncated 200`() = runBlocking {
        val headPort = freshPort()
        val head = buildHead(headPort, BranchFakeAuth(), wrap = { ThrowingProvider(it) })
        head.start()
        awaitListening(headPort)
        val before = logs.size
        try {
            val sse = turn(headPort, "basic")
            assertTrue(sse.contains("event: error"), "expected an error event in: $sse")
            assertTrue(sse.contains("api_error"), sse)
            assertTrue(sse.contains("claudex: internal gateway error — retry"), sse)
            assertFalse(sse.contains("event: message_stop"), "never a clean stop after failure: $sse")
            val scoped = logs.drop(before)
            assertTrue(
                scoped.any { it.contains("perf outcome=error:unexpected") },
                "expected the error:unexpected perf row in: $scoped",
            )
        } finally {
            head.stop()
        }
    }

    // HD-18 review: the operator-facing `unexpected` line must name the class that identifies the
    // BUG SOURCE, which is the runtime class, not the base class the boundary happens to convert.
    // A `when` over IllegalArgumentException/IllegalStateException is total but it is a COARSENING:
    // it turns "URLParserException Fail to parse url" into "IllegalStateException Fail to parse
    // url" and erases the only token that says where to look. Red against that `when`, green once
    // the throwable renders itself.
    @Test
    fun `the unexpected turn line names the throwing class, not the base the boundary converts`() = runBlocking {
        val headPort = freshPort()
        val head = buildHead(headPort, BranchFakeAuth(), wrap = { SubclassThrowingProvider(it) })
        head.start()
        awaitListening(headPort)
        val before = logs.size
        try {
            turn(headPort, "basic")
            val scoped = logs.drop(before)
            val errLines = scoped.filter { it.contains("turn ERROR unexpected") }.joinToString("\n")
            assertTrue(
                errLines.contains("SyntheticUrlParseException"),
                "the subclass that identifies the bug source must survive, was: $errLines",
            )
            assertTrue(
                errLines.contains("synthetic bad base_url"),
                "the message must survive alongside it, was: $errLines",
            )
        } finally {
            head.stop()
        }
    }

    // The inbound read timeout: a client that announces a body and then stops sending must be
    // answered and released, not left holding a materialization permit. runBlocking, not runTest —
    // the 408 is decided by a real `withTimeout` on the server's own dispatcher, and virtual time
    // would skip straight past it.
    @Test
    fun `a request body that never finishes is answered with 408, not held open`() = runBlocking {
        val headPort = freshPort()
        val head = buildHead(headPort, BranchFakeAuth(), readTimeoutMs = 300)
        head.start()
        awaitListening(headPort)
        val stalled = CompletableDeferred<Unit>()
        try {
            // The bound is on the TEST, independent of the head's own 300ms readTimeoutMs, and it is
            // not belt-and-braces: the only thing that ever completes `stalled` is the finally below,
            // which runs AFTER client.post returns, so the call has to come back on its own. It does
            // — but via the CIO endpoint's ~15s requestTimeout abandoning the write, NOT because the
            // 408 is surfaced while writeTo is suspended (see TIMEOUT_TEST_BOUND_MS: measured 15.1s).
            // That is an engine DEFAULT, so a Ktor bump that raises or removes it leaves post never
            // returning and this test HANGING, wedging a CI runner instead of going red. A passing
            // test whose failure mode is a hang is the same gate-that-cannot-fail-honestly this repo
            // refuses elsewhere; the bound makes it a readable failure (review 2026-08-28, PR 99).
            val response = withTimeout(TIMEOUT_TEST_BOUND_MS) {
                client.post("http://127.0.0.1:$headPort/v1/messages") {
                    header("Content-Type", "application/json")
                    setBody(
                        object : OutgoingContent.WriteChannelContent() {
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                channel.writeStringUtf8("""{"model":"claude-codex--gpt-5.6-sol",""")
                                channel.flush()
                                stalled.await() // the slow-loris shape: a body that never completes
                            }
                        },
                    )
                }
            }
            assertEquals(408, response.status.value)
            val body = response.bodyAsText()
            assertTrue(body.contains("request body read timed out"), body)
            assertTrue(body.contains("invalid_request_error"), body)
        } finally {
            stalled.complete(Unit)
            head.stop()
        }
    }
}

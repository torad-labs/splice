// NEW: the CLIENT CONTRACT of the admission refusal (V4-72). Claude Code's persistent retry mode
// (CLAUDE_CODE_RETRY_WATCHDOG, which LaunchService now plants for every head) is what lets a session
// survive a hold longer than its default ten retries — and that mode REFUSES to wait when a 429's
// message carries one of its stop phrases, or when the response sets
// anthropic-ratelimit-unified-overage-disabled-reason (read by Zio() in the 2.1.257 binary). splice
// authors BOTH of those byte sets, so a phrase or a header we did not intend would turn our own hold
// into a session-ending error: the client would stop retrying at exactly the moment we most need it
// to keep coming back. This pins the contract where splice writes it, and each absence assertion is
// preceded by a POSITIVE one, because "the phrase is absent" over an empty map or an error response
// proves nothing at all.
package head

import campaign.v4105.headDeps
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
import mock.MockChatGptUpstream
import mock.RATE_LIMITED_STATUS
import mock.TestResponsesProvider
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
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.gateway.head.HeadServer
import splice.gateway.usage.QuotaTracker
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The head's own credential stub: the refusal path never reaches auth, it only needs a provider. */
private class ContractFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-contract", "acct-contract")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** A real HeadServer over a mock upstream, with the shared cooldown the refusal path reads. */
private class RefusalRig(tmp: Path) {
    val mock = MockChatGptUpstream()
    val upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1)
    val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    val port = ServerSocket(0).use { it.localPort } // ephemeral: a fixed port BindExceptions on TIME_WAIT
    val head = HeadServer(
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
                auth = ContractFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        ),
        listenPort = port,
        deps = headDeps(
            tmp = tmp,
            upstream = upstream,
            gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 }),
            log = {},
        ),
    )

    suspend fun start() {
        head.start() // binds before returning (Ktor Netty bind(...).sync()); no warm-up (V4-139)
    }

    suspend fun close() {
        head.stop()
        client.close()
        mock.stop()
    }

    private suspend fun turn(scenario: String?): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            val system = scenario?.let { ""","system":"You are a test. SCENARIO:$it"""" } ?: ""
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64$system,
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }

    /** Arm the head-wide horizon through the REAL path: a quota429 turn reaches upstream and arms. */
    suspend fun armHorizon() {
        turn("quota429").bodyAsText()
    }

    /** The NEXT turn never reaches upstream: it is refused at admission, where the status and the
     *  message are ours to write. */
    suspend fun refusedTurn(): HttpResponse = turn(null)
}

class RateLimitRefusalClientContractTest {

    /** The client's stop phrases, from the 2.1.257 binary. Held as data so the assertion is a SWEEP
     *  rather than one string: if our wording ever grows one of these, the same test catches it
     *  without anyone remembering to add a case. */
    private val clientStopPhrases = listOf(
        "service_spend_limit_reached",
        "exceeded_limit",
        "credits_required",
        "usage credits are required",
        "extra usage is required",
        "out_of_credits",
    )

    @Test
    fun `the rejected header set never sets the overage-disabled reason`(@TempDir tmp: Path) {
        val tracker = QuotaTracker(tmp.resolve("codex-quota.json"), WallClock { 0L }, LogSink { })

        // POSITIVE FIRST: the refusal must actually be stated, or the absence below is vacuous.
        val blind = tracker.clientHeadersRejected(1_788_030_000L)
        assertEquals("rejected", blind["anthropic-ratelimit-unified-status"], "the refusal is stated")
        assertEquals("1788030000", blind["anthropic-ratelimit-unified-reset"], "with the deadline")

        assertTrue(
            blind.keys.none { it.contains("overage-disabled-reason") },
            "splice must never tell the client its overage is disabled — persistent retry stops on it: $blind",
        )
    }

    @Test
    fun `an admission refusal carries none of the phrases that stop the client retrying`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = RefusalRig(tmp)
        rig.start()
        try {
            rig.armHorizon()
            assertTrue(rig.upstream.rateLimitedForMs > 0L, "precondition: the horizon must be armed")

            val refused = rig.refusedTurn()
            assertEquals(
                RATE_LIMITED_STATUS,
                refused.status.value,
                "an armed head refuses the next turn with a real 429, not a 200 carrying an error frame",
            )

            val body = refused.bodyAsText()
            // POSITIVE FIRST: this must be the refusal, or the sweep below scans an error page.
            assertTrue(
                body.contains("rate_limit_error"),
                "precondition: the Anthropic error shape, got: ${body.take(200)}",
            )
            clientStopPhrases.forEach { phrase ->
                assertFalse(
                    body.contains(phrase),
                    "the refusal must not carry '$phrase' — persistent retry stops on it: $body",
                )
            }
            refused.headers.forEach { name, _ ->
                assertFalse(name.contains("overage-disabled-reason"), "the refusal must never set $name")
            }
        } finally {
            rig.close()
        }
    }
}

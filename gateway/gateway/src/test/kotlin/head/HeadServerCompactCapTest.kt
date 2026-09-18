// The compact-turn watchdog budget, driven through the REAL production path — HeadServer over HTTP,
// TurnDriveFactory wiring the budget, the idlepre mock acknowledging and then going silent — because
// a direct test of WatchdogBudget.forCompact proves the arithmetic while nothing proves the factory
// ever calls it (the shape of gap DR-7's acceptance wall exists to close).
//
// One head PER ARM, same 1s first-output / 20s streamIdle tiers, different whole-turn wall.
//
// V4-125 INVERTED THE NORMAL ARM, and the inversion is why it now matches its compact sibling. It
// used to be reaped by the 1s first-output cap, and its wall sat far away (30s) purely so the reap
// could not be beaten to the tick — sharing the compact arm's 4s wall let a slow CI runner reach the
// wall first (coverage run 33549293551 failed this arm at the first-output assertion; locally it took
// 3.7s). The tier is a PROBE now: it reaps nothing, so there is no race left to dodge, and a normal
// turn silent after the handshake is HELD until the whole-turn wall ends it, exactly as a compact
// turn is.
//
// A COMPACT turn has no first-output tier at all, so the only thing that can end it is its 4s
// whole-turn wall — which surfaces as the cancellation seal's generic watchdog wording. Live
// provenance (2026-09-01 14:07): the first compaction on the corrected tier died at "no first output
// within the 300s first-output cap". When the tier was merely RAISED to the wall, gate run 33575037270
// failed this arm on a loaded runner: two pollers on one deadline, and the idle poller won the
// tick and named the wrong cap (the coin flip WatchdogBudget.forCompact's KDoc describes).
package head

import campaign.v4105.headDeps
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import mock.MockChatGptUpstream
import mock.TestResponsesProvider
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.gateway.head.HeadServer
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private class CapFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-cap", "acct-cap")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCompactCapTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun head(port: Int, watchdog: WatchdogBudget): HeadServer {
        val tmp = Files.createTempDirectory("head-compact-cap")
        return HeadServer(
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
                    auth = CapFakeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = watchdog,
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
                upstream = UpstreamClient(firstByteTimeoutMs = 20_000, totalTimeoutMs = 30_000, maxRetries = 1),
                log = {},
            ),
        )
    }

    // Drives one turn through a head built for this arm's budget; the mock stalls 6s per attempt.
    private suspend fun turnOn(watchdog: WatchdogBudget, system: String): String {
        val port = freshPort()
        val server = head(port, watchdog)
        server.start()
        awaitListening(port)
        try {
            return turn(port, system)
        } finally {
            server.stop()
        }
    }

    private suspend fun turn(port: Int, system: String): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"$system",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    // INVERTED BY V4-125, not deleted: the scenario is unchanged and the assertion is now the
    // opposite one, which is the stronger claim — the ruling is about an ABSENCE.
    //
    // The test used to assert that a silent-after-handshake turn is REAPED at the 1s first-output
    // tier, with overloaded_error and the tier's name on the wire. That is the doctrine V4-125
    // retires: an idle tier is a PROBE now (IDLE IS A PROBE), so silence past it asks the round's
    // path pulse whether the peer is alive, a live path is HELD, and nothing about it reaches the
    // client. The turn is then walled by the whole-turn cap alone — exactly the shape the COMPACT
    // arm below already had, which is why the two now read alike.
    //
    // NOTE the assertion is NOT "no overloaded_error": the whole-turn cap legitimately reports one,
    // as the compact arm's own first line records. The absence that matters is the TIER's, so the
    // discriminator is the tier's name and the wall's, which is what this arm pins.
    @Test
    fun `a normal turn silent after the handshake is held past the first-output cap and dies only on the total cap`() =
        runTest {
            // The wall is the compact arm's 4s now, and the 30s this arm used to carry was there to
            // dodge a race that no longer exists: it was set far away so the 1s first-output reap
            // could not be beaten to the tick by the wall. The tier holds instead of reaping, so
            // nothing races it, and the wall must simply land before the test client gives up.
            val sse = turnOn(WatchdogBudget(1.seconds, 20.seconds, 4.seconds), "You are a test. SCENARIO:idlepre")
            assertFalse(
                sse.contains("first-output cap"),
                "the 1s first-output tier is a probe now: a live path is HELD, never reaped by it: $sse",
            )
            assertTrue(sse.contains("stalled (watchdog)"), "only the whole-turn wall may end it: $sse")
        }

    // The system prompt carries Claude Code's verbatim summarizer marker, so the gateway classifies
    // the turn as a compaction (Compact.kt) and TurnDriveFactory hands it forCompact().
    @Test
    fun `a compact turn silent after the handshake survives the first-output cap and dies only on the total cap`() =
        runTest {
            val sse = turnOn(
                WatchdogBudget(1.seconds, 20.seconds, 4.seconds),
                "SCENARIO:idlepre You are tasked with summarizing conversations for another agent.",
            )
            assertTrue(sse.contains("overloaded_error"), "a stall is an honest, retryable failure: $sse")
            assertFalse(
                sse.contains("first-output cap"),
                "a compaction's pre-output silence must not be judged on the 1s first-output tier: $sse",
            )
            // The whole-turn cap cancels the TURN, so it surfaces through the cancellation seal's
            // generic watchdog wording (as HeadServerFoldTest's NF-03 arm pins), not a tier-named
            // message — which is exactly the discriminator: the 1s tier would have said its name.
            assertTrue(sse.contains("stalled (watchdog)"), "only the whole-turn wall may end it: $sse")
        }
}

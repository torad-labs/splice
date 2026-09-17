// NEW: the head-side seam of the quota rollup (V4-75 step 2). Its own class rather than two more
// cases on HeadServerIntegrationTest: that file is already at detekt's LargeClass ceiling, and the
// question here is a different one — not "does a turn stream correctly" but "does a finished turn
// reach the burn page's input, carrying the same numbers the perf row carries".
//
// ONE RIG PER TEST, never a shared PER_CLASS head. A quota429 turn ARMS the head-wide cooldown
// (RetryRules.giveUp), after which the next turn is refused at admission and writes a perf row with
// no economics record — so a shared store would make the 429 case silently corrupt the turn case's
// perf-vs-rollup equality, in whichever order JUnit happened to run them.
package head

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
import org.junit.jupiter.api.Assertions.assertEquals
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
import splice.core.util.AsyncFileIo
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.UsageStore
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class EconomicsFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private val IN_TOKENS_FIELD = Regex("\"in_tokens\":(\\d+)")
private val OUT_TOKENS_FIELD = Regex("\"out_tokens\":(\\d+)")

/** A real HeadServer over the mock upstream, with the economics store the head writes. */
private class EconomicsRig(tmp: Path) {
    val mock = MockChatGptUpstream()
    val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    val port = freshPort()
    val perfFile: Path = tmp.resolve("perf.jsonl")
    val economics = EconomicsStore(tmp.resolve("economics.json"))
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
                auth = EconomicsFakeAuth(),
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
        deps = HeadDeps(
            upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1),
            inferenceToken = "test-inference-token",
            gate = InflightGate({ 0 }),
            shadow = ShadowClassifier(log = {}),
            compactStats = CompactStats(tmp.resolve("compact.jsonl")),
            usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
            perfStats = PerfStats(tmp.resolve("perf.jsonl")),
            economicsStore = economics,
            log = {},
        ),
    )

    suspend fun start() {
        head.start()
        awaitListening(port)
    }

    suspend fun close() {
        head.stop()
        client.close()
        mock.stop()
    }

    suspend fun turn(scenario: String): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    /** PerfStats.record appends on the bounded file lane; the economics fold is in-memory and
     *  immediate, so reading the rows without a drain would race the JSONL write. */
    fun perfRows(): List<String> {
        AsyncFileIo.drain()
        return Files.readString(perfFile).trim().lines().filter { it.isNotBlank() }
    }
}

class HeadEconomicsSeamTest {

    /** THE SEAM. A finished turn must land in the hourly quota rollup carrying the SAME numbers the
     *  perf JSONL row carries, because TurnTelemetry builds both from ONE snapshot. This is the
     *  wiring EconomicsStoreTest cannot prove: recordPerf is the store's only caller in production,
     *  and a rollup that silently records nothing is exactly the failure mode the burn page exists
     *  to prevent — a gauge reading zero while the plan drains. */
    @Test
    fun `a real turn lands in the economics rollup carrying the perf row's own token counts`(
        @TempDir tmp: Path,
    ) = runTest {
        val rig = EconomicsRig(tmp)
        rig.start()
        try {
            rig.turn("basic")

            val bucket = rig.economics.read().single()
            assertEquals(1, bucket.turns, "one completed turn is one recorded turn")
            assertTrue(bucket.inTokens > 0, "the metered input must reach the rollup, got $bucket")

            val rows = rig.perfRows()
            val inFromPerf = rows.sumOf { IN_TOKENS_FIELD.find(it)?.groupValues?.get(1)?.toLong() ?: 0L }
            val outFromPerf = rows.sumOf { OUT_TOKENS_FIELD.find(it)?.groupValues?.get(1)?.toLong() ?: 0L }
            assertEquals(inFromPerf, bucket.inTokens, "in_tokens: one snapshot, two sinks")
            assertEquals(outFromPerf, bucket.outTokens, "out_tokens: one snapshot, two sinks")
        } finally {
            rig.close()
        }
    }

    /** A 429 is the quota instrument's most load-bearing event: the exact moment the plan said no.
     *  It must be COUNTABLE in the rollup, not merely greppable in the log — which is what the
     *  rateLimited flag on recordPerf, set from the upstream failure's own classification, buys. */
    @Test
    fun `an upstream 429 is counted as a rate-limited turn in the rollup`(@TempDir tmp: Path) = runTest {
        val rig = EconomicsRig(tmp)
        rig.start()
        try {
            rig.turn("quota429")

            val bucket = rig.economics.read().single()
            assertEquals(1, bucket.turns, "a refused turn is still a turn the rollup saw")
            assertEquals(1, bucket.rateLimited, "the 429 must be counted, not just logged: $bucket")
        } finally {
            rig.close()
        }
    }
}

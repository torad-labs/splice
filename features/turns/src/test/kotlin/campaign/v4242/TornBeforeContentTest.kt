// NEW: V4-242 (2026-09-26) — which websocket tears WsRoundDrive re-serves over SSE, one condition per arm.
//
// A tear is re-served only when the client has seen nothing of the round and nothing of ours caused it:
// not the watchdog (WsRoundDriverTest's DR-7 reap arm pins that one), not a departed client, not the
// turn's own cancellation. Every other tear stays with the translator, which folds it into its honest
// terminal as before. Driven through WsRoundDrive itself, with the real responses translator beneath it.
package campaign.v4242

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.JsonScalars
import splice.head.RecordingSink2
import splice.head.TestResponsesProvider
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.transport.WsRoundDrive
import splice.head.transport.WsRoundInputs
import splice.head.transport.WsRoundResult
import splice.head.turn.TurnDrive
import splice.head.turn.ZeroEventClassifier
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.upstream.ClientFrameEmitted
import splice.upstream.ProviderTuning
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.sse.WireSink
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private class NoAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-v4242")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixed")
}

/** A terminal that records nothing and never throws, so the round runs to its translator's verdict. */
private class RecordingTerminal : TurnTerminal, WireSink by RecordingSink2() {
    override val hasEnded: Boolean = false

    override suspend fun ensureStarted() = Unit
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) = Unit
    override fun abandon() = Unit
}

/** Only [isFailureTerminal] is read by WsRoundDrive; the round's events are handed to it directly. */
private class TerminalsOnly : WsRoundRunner {
    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? = null

    override fun isFailureTerminal(event: JsonObject): Boolean =
        JsonScalars.strOrEmpty(event["type"]) in setOf("response.failed", "error")

    override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit

    override fun roundBypassed(meta: TurnMeta) = Unit
}

class TornBeforeContentTest {
    private val tmp = Files.createTempDirectory("v4242-torn")

    private val provider = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = NoAuth(),
            baseUrl = "http://127.0.0.1:9",
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    private val drive = WsRoundDrive(provider, ZeroEventClassifier { _, outcome, _, _ -> outcome })

    /** The round a peer tore after one event: the transport's shape, a bare tear over the close. */
    private fun tornRound(
        failure: Throwable = IOException("websocket stream ended mid-round", IOException(CLOSE)),
    ): Flow<JsonObject> = flow {
        emit(Json.parseToJsonElement("""{"type":"response.created","response":{"id":"r1"}}""") as JsonObject)
        throw failure
    }

    private suspend fun inputs(
        scope: CoroutineScope,
        clientGone: Boolean = false,
        frameEmitted: Boolean = false,
        turnJob: Job = Job(),
    ): WsRoundInputs {
        val turn = TurnDrive(
            requestBody = buildJsonObject { },
            meta = TurnMeta(
                compact = false,
                showReasoning = ReasoningDisplay.TEXT,
                stream = true,
                originalModel = "claude-codex--gpt-5.6-sol",
                upstreamModel = "gpt-5.6-sol",
                clientMaxTokens = 100,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            emitter = RecordingTerminal(),
            watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
            slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
            pipeline = TurnPipeline(CompactStats(tmp.resolve("compact.jsonl")), log = {}, clampOutput = OutputClamp { it }),
            t0 = 0,
            trace = null,
            perf = TurnPerf(),
            turnHeaders = emptyMap(),
            signals = RunnerSignals(),
            channel = ClientChannel(
                ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
                Mutex(),
                AtomicBoolean(clientGone),
            ),
            toolSearch = null,
        )
        return WsRoundInputs(
            drive = turn,
            bodyJson = "{}",
            sink = RecordingSink2(),
            scope = scope,
            turnJob = turnJob,
            frameEmittedThisRound = ClientFrameEmitted { frameEmitted },
            eventsBase = 0,
        )
    }

    private suspend fun driven(inputs: WsRoundInputs, round: Flow<JsonObject> = tornRound()): WsRoundResult =
        try {
            drive.drive(inputs, TerminalsOnly(), round)
        } finally {
            inputs.drive.slot.release()
        }

    @Test
    fun `a tear before any client frame is re-served over SSE, named by the close beneath it`() = runTest {
        assertEquals(WsRoundResult.NeedsSse(CLOSE), driven(inputs(this)))
    }

    @Test
    fun `a tear after a client frame stays with the translator`() = runTest {
        assertStaysWithTheTranslator(driven(inputs(this, frameEmitted = true)))
    }

    @Test
    fun `a tear on a client that already left stays with the translator`() = runTest {
        assertStaysWithTheTranslator(driven(inputs(this, clientGone = true)))
    }

    @Test
    fun `a tear of a turn that was cancelled stays with the translator`() = runTest {
        val cancelled = Job().also { it.cancel() }
        assertStaysWithTheTranslator(driven(inputs(this, turnJob = cancelled)))
    }

    @Test
    fun `a failure that is not a tear is never re-served, and leaves as itself`() = runTest {
        val bug = IllegalStateException("a bug of ours, not the upstream's")
        val thrown = assertThrows<IllegalStateException> { driven(inputs(this), tornRound(bug)) }
        assertSame(bug, thrown, "the translator's pre-content escape is unchanged")
    }

    private fun assertStaysWithTheTranslator(result: WsRoundResult) {
        val outcome = (result as? WsRoundResult.Streamed)?.outcome
        assertTrue(
            outcome is TurnOutcome.Failure || outcome is TurnOutcome.ClientAbandoned,
            "the translator folds the tear into its own terminal, never a re-serve: $result",
        )
    }
}

private const val CLOSE =
    "socket closed by the peer (status=1011, no reason given) after 1 event (response.created)"

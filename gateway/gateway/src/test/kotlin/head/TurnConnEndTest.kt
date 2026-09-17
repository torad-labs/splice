// NEW (V4-79): the oversized-streaming-event ending, pinned on the WIRE TYPE it hands the client.
//
// SseFrameTooLargeException is the one connection-class failure that can land on either side of
// content: an upstream event can blow the frame cap before anything has been read, or after the
// client has already rendered half an answer. TurnConnEnd therefore does not emit a constant — it
// runs the shared pre-content rule, fed by the drive that tryEmit already receives as its first
// parameter (drive.perfCounter(CONTENT_FRAMES_OUT)). These two cells pin both sides of that fork.
//
// The assertions are on the ErrorType the EMITTER received, not on a splice-side count: the only
// fact that matters is the type that reaches Claude Code, because 2.1.257 retries an in-band
// overloaded_error and ends the session on an in-band api_error.
//
// MUTATION PROOF (recorded in the ledger): replace the PreContentWireType.of(...) argument at
// TurnConnEnd.kt with the bare ErrorType.API_ERROR and the pre-content cell goes red BY NAME while
// the after-content cell stays green — so this is a pin on the rule, not on a constant.
//
// The rig mirrors TurnEndingAccountingTest's (same package, same provider/telemetry/drive idiom);
// it is rebuilt here rather than shared because these cells need a RECORDING terminal where that
// file needs a dead-client one, and its Rig is private to it.
package head

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import mock.TestResponsesProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.head.HeadHealthCounters
import splice.gateway.head.TurnConnEnd
import splice.gateway.head.TurnDrive
import splice.gateway.head.TurnFailures
import splice.gateway.head.TurnTelemetry
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.usage.OutputClamp
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.TurnTerminal
import splice.spi.InflightGate
import splice.spi.LiveLimit
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.SseFrameTooLargeException
import splice.spi.TurnWatchdog
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Holds a credential so provider construction is honest; the surface under test never dials out. */
private class ConnEndFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "fake")
}

/** Records exactly what reached the wire: the error TYPE and the words beside it. */
private class ConnEndRecordingTerminal : TurnTerminal {
    var errorType: ErrorType? = null
    var errorMessage: String = ""
    override var hasEnded: Boolean = false
        private set

    override suspend fun emitError(type: ErrorType, message: String) {
        errorType = type
        errorMessage = message
        hasEnded = true
    }

    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override fun abandon() = Unit
    override suspend fun openText() = WireBlockIndex(0)
    override suspend fun openThinking() = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnConnEndTest {

    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("turn-conn-end")
    }

    private fun provider(): Provider = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = ConnEndFakeAuth(),
            baseUrl = "http://127.0.0.1:1",
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    /** One TurnConnEnd plus the drive it is handed; [tag] isolates each case's perf file. */
    private inner class Rig(private val tag: String) {
        val log = LogSink { }
        val health = HeadHealthCounters()
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
        val emitter = ConnEndRecordingTerminal()
        val connEnd: TurnConnEnd
        init {
            val p = provider()
            connEnd = TurnConnEnd(p, log, telemetry, TurnFailures(p), health)
        }

        suspend fun drive(): TurnDrive = TurnDrive(
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
            emitter = emitter,
            watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
            slot = InflightGate(LiveLimit { 1 }).acquire(),
            pipeline = TurnPipeline(
                CompactStats(perfFile.resolveSibling("compact-$tag.jsonl")),
                log = log,
                clampOutput = OutputClamp { it },
            ),
            t0 = 0,
            upstreamModel = "gpt-5.6-sol",
            perf = TurnPerf(),
            turnHeaders = emptyMap(),
            signals = RunnerSignals(),
            channel = ClientChannel(
                ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
                Mutex(),
                AtomicBoolean(false),
            ),
            toolSearch = null,
        )
    }

    /** Drives the oversized-event arm with [contentFrames] frames already counted for the turn. */
    private fun emitOversized(tag: String, contentFrames: Long): ConnEndRecordingTerminal {
        val rig = Rig(tag)
        return runBlocking {
            val drive = rig.drive()
            try {
                // The SAME counter production reads: ClientChannel adds to it per content frame.
                drive.perf.add(PerfKeys.CONTENT_FRAMES_OUT, contentFrames)
                val owned = rig.connEnd.tryEmit(drive, SseFrameTooLargeException("data", 1))
                assertEquals(true, owned, "TurnConnEnd owns the oversized-event class")
            } finally {
                drive.slot.release()
                AsyncFileIo.drain() // perf rows append asynchronously; drain before the dir is swept
            }
            rig.emitter
        }
    }

    @Test
    fun `an oversized event before any content reaches the client as overloaded_error - V4-79`() = runBlocking {
        val emitter = emitOversized("pre-content", contentFrames = 0)

        assertEquals(
            ErrorType.OVERLOADED,
            emitter.errorType,
            "nothing was read yet, so the wire type must be the one Claude Code retries in band",
        )
        // The relabel is a wire fact only — the words the operator diagnoses from are untouched.
        assertEquals("upstream sent an oversized streaming event — retry", emitter.errorMessage)
    }

    @Test
    fun `an oversized event after content keeps api_error - V4-79`() = runBlocking {
        val emitter = emitOversized("post-content", contentFrames = 1)

        assertEquals(
            ErrorType.API_ERROR,
            emitter.errorType,
            "the client is finalizing what it already holds; a relabel would buy a pointless retry",
        )
        assertEquals("upstream sent an oversized streaming event — retry", emitter.errorMessage)
    }
}

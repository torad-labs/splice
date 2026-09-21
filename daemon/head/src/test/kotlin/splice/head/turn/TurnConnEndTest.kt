// NEW (V4-79): the oversized-streaming-event ending, pinned on what it hands the emitter.
//
// SseFrameTooLargeException is the one connection-class failure that can land on either side of
// content: an upstream event can blow the frame cap before anything has been read, or after the
// client has already rendered half an answer. V4-79 read that fork here; V4-81 removed the hand
// copy and moved it to SseEmitter.emitError, so what this file now pins is the arm's own two
// facts — the failure is TRANSIENT (a decision, argued in the cell) and the surface no longer
// branches on content at all.
//
// The assertions are on what the EMITTER received, not on a splice-side count: the only fact that
// matters is the type that reaches Claude Code, because 2.1.257 retries an in-band
// overloaded_error and ends the session on an in-band api_error.
//
// MUTATION PROOF (recorded in the ledger): flip `permanent = false` to `true` at TurnConnEnd.kt and
// the transient cell goes red BY NAME; re-introduce a content fork here and the content-blind cell
// goes red — so each pins one thing this file still owns.
//
// The rig mirrors TurnEndingAccountingTest's (same package, same provider/telemetry/drive idiom);
// it is rebuilt here rather than shared because these cells need a RECORDING terminal where that
// file needs a dead-client one, and its Rig is private to it.
package splice.head.turn

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
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
import splice.head.HeadHealthCounters
import splice.head.TestResponsesProvider
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.failure.SseFrameTooLargeException
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
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
    var errorPermanent: Boolean = false
    var errorMessage: String = ""
    override var hasEnded: Boolean = false
        private set

    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {
        errorType = type
        errorPermanent = permanent
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
            slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
            pipeline = TurnPipeline(
                CompactStats(perfFile.resolveSibling("compact-$tag.jsonl")),
                log = log,
                clampOutput = OutputClamp { it },
            ),
            t0 = 0,
            trace = null,
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
    private fun emitOversized(tag: String, contentFrames: Long): ConnEndRecordingTerminal =
        emitFor(tag, SseFrameTooLargeException("data", 1), contentFrames)

    /** Drives [failure] through the surface with [contentFrames] frames already counted for the turn. */
    private fun emitFor(tag: String, failure: Throwable, contentFrames: Long = 0): ConnEndRecordingTerminal {
        val rig = Rig(tag)
        return runBlocking {
            val drive = rig.drive()
            try {
                // The SAME counter production reads: ClientChannel adds to it per content frame.
                drive.perf.add(PerfKeys.CONTENT_FRAMES_OUT, contentFrames)
                val owned = rig.connEnd.tryEmit(drive, failure)
                assertEquals(true, owned, "TurnConnEnd owns this failure class")
            } finally {
                drive.slot.release()
                AsyncFileIo.drain() // perf rows append asynchronously; drain before the dir is swept
            }
            rig.emitter
        }
    }

    @Test
    fun `an oversized event reaches the emitter as a TRANSIENT api_error - V4-81`() = runBlocking {
        val emitter = emitOversized("pre-content", contentFrames = 0)

        assertEquals(
            ErrorType.API_ERROR,
            emitter.errorType,
            "this surface reports the failure; the wire type is decided at the emitter",
        )
        // THE ONE DECISION THIS ARM STILL OWNS, and the row asked for it explicitly: an oversized
        // frame is TRANSIENT. The frame size is a property of the RESPONSE the upstream host chose
        // to send, not of the request we sent, so a re-send buys a genuinely different response and
        // can come back small. Contrast TurnEnding's unparseable base_url, which is a property of
        // our own config and reproduces exactly — that one is permanent. Without this flag the
        // emitter would have to guess, and guessing "permanent" here would end sessions that a
        // retry fixes.
        assertEquals(false, emitter.errorPermanent, "an oversized upstream frame is a transient condition")
        assertEquals("upstream sent an oversized streaming event — retry", emitter.errorMessage)
    }

    @Test
    fun `the surface is content-blind now that the fork lives at the seam - V4-81`() = runBlocking {
        // V4-79 read CONTENT_FRAMES_OUT here and forked on it. V4-81 removed that hand copy, and the
        // way to pin a REMOVAL is to show the two sides are indistinguishable at this surface: if
        // someone re-adds a fork here, one of these two goes red. What still differs pre- vs post-
        // content is the WIRE TYPE, and that is pinned where it is decided (SseEmitterTest).
        val pre = emitOversized("pre-content", contentFrames = 0)
        val post = emitOversized("post-content", contentFrames = 1)

        assertEquals(pre.errorType, post.errorType, "this surface no longer forks on content")
        assertEquals(pre.errorPermanent, post.errorPermanent, "nor on permanence")
        assertEquals("upstream sent an oversized streaming event — retry", post.errorMessage)
    }

    // V4-164, the operator's banner verbatim: "bonsai: upstream connection failed (no detail) —
    // retry". The JDK client's refused connect is a ConnectException with a NULL message, and this
    // surface printed Throwable.message. Mutant: connectionResetMessage back to error.message —
    // the banner reads "no detail" again and this cell goes red by name.
    @Test
    fun `a refused connect names the endpoint and the reason, never no detail - V4-164`() = runBlocking {
        // The JDK client's refusal (V4-167, measured): a ConnectException over a ClosedChannelException.
        val refused = java.net.ConnectException().apply { initCause(java.nio.channels.ClosedChannelException()) }
        val emitter = emitFor("refused", refused)

        assertEquals(ErrorType.OVERLOADED, emitter.errorType, "a refused connect stays the class the client retries")
        assertEquals(
            "codex: upstream connection failed (connection refused by 127.0.0.1:1 — nothing is listening there; " +
                "the server is down or still starting) — retry",
            emitter.errorMessage,
        )
    }
}

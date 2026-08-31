// DR-128: all failure surfaces emitted the error frame BEFORE recording the perf row + health
// count, and a dead-client write makes emitError rethrow IOException after sealing — so the
// accounting never ran: the turn VANISHED from perf JSONL and the G20 counters. Exactly the hole
// CancellationSeal plugs for the cancellation path, open on every failure surface (2026-07-19
// storm shape: dead clients + failing upstream — health MUST still see the upstream failures).
// These walls drive TurnEnding.emitFailure with an emitter whose emitError throws: the IOException
// still propagates (status quo at the driver), but the instruments must have recorded first.
package head

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
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
import splice.gateway.head.TurnEnding
import splice.gateway.head.TurnFailures
import splice.gateway.head.TurnKnownEnd
import splice.gateway.head.TurnTelemetry
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.usage.OutputClamp
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.TurnTerminal
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.LiveLimit
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.SseFrameTooLargeException
import splice.spi.TurnWatchdog
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamFailed
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Holds a credential — the surfaces under test never contact the upstream; this only keeps
 *  provider construction honest. */
private class BranchlessFakeAuth : splice.core.auth.RefreshableAuthProvider {
    override suspend fun credentials() = splice.core.auth.Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = splice.core.auth.AuthDescription(true, "fake")
}

/** The dead-client shape: every wire write already sealed, and the error frame write rethrows —
 *  exactly what SseEmitter does after ClientChannel flips clientGone on a failed write. */
private class DeadClientTerminal : TurnTerminal {
    override val hasEnded: Boolean = true
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String): Unit =
        throw IOException("client hung up mid error frame")
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
class TurnEndingAccountingTest {

    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("turn-ending-acct")
    }

    private fun provider(): Provider = CodexProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = BranchlessFakeAuth(),
            baseUrl = "http://127.0.0.1:1",
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    /** One ending surface with observable instruments; [tag] isolates each test's perf file. */
    private inner class Rig(tag: String) {
        val logs = mutableListOf<String>()
        val log = LogSink { logs.add(it) }
        val health = HeadHealthCounters()
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
        val ending: TurnEnding
        init {
            val p = provider()
            val failures = TurnFailures(p)
            ending = TurnEnding(
                log,
                telemetry,
                health,
                TurnConnEnd(p, log, telemetry, failures, health),
                TurnKnownEnd(p, log, telemetry, failures, health),
            )
        }

        suspend fun drive(): TurnDrive = TurnDrive(
            bodyJson = "{}",
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
            emitter = DeadClientTerminal(),
            watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
            slot = InflightGate(LiveLimit { 1 }).acquire(),
            pipeline = TurnPipeline(
                CompactStats(perfFile.resolveSibling("compact-dr128.jsonl")),
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
                AtomicBoolean(true),
            ),
            toolSearch = null,
        )

        fun assertRecorded(tag: String) {
            AsyncFileIo.drain() // perf rows append asynchronously
            assertTrue(
                Files.readString(perfFile).contains(tag),
                "the perf row must survive a dead-client emit; file=${Files.readString(perfFile)}",
            )
        }
    }

    private fun emitExpectingDeadClient(rig: Rig, e: Throwable) = runBlocking {
        val drive = rig.drive()
        try {
            assertThrows<IOException>("the dead-client write still propagates (status quo at the driver)") {
                runBlocking { rig.ending.emitFailure(drive, e) }
            }
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `upstream-failed records perf + provider health despite a dead client - DR-128`() {
        val rig = Rig("dr128-upstream")
        emitExpectingDeadClient(rig, UpstreamFailed("""{"error":{"type":"api_error"}}""", 500))
        rig.assertRecorded("error:upstream-failed")
        assertEquals(1L, rig.health.snapshot().providerError, "G20 must still see the upstream failure")
    }

    @Test
    fun `conn-reset records perf + local health despite a dead client - DR-128`() {
        val rig = Rig("dr128-connreset")
        emitExpectingDeadClient(rig, IOException("upstream socket tore"))
        rig.assertRecorded("error:conn-reset")
        assertEquals(1L, rig.health.snapshot().localOrigin)
    }

    @Test
    fun `auth-missing records perf + local health despite a dead client - DR-128`() {
        val rig = Rig("dr128-auth")
        emitExpectingDeadClient(rig, UpstreamAuthMissing())
        rig.assertRecorded("error:auth-missing")
        assertEquals(1L, rig.health.snapshot().localOrigin)
    }

    @Test
    fun `oversized-frame records perf + provider health despite a dead client - DR-128`() {
        val rig = Rig("dr128-frame")
        emitExpectingDeadClient(rig, SseFrameTooLargeException("data", 1))
        rig.assertRecorded("error:upstream-frame-too-large")
        assertEquals(1L, rig.health.snapshot().providerError)
    }

    @Test
    fun `unexpected runtime failure records perf + local health despite a dead client - DR-128`() {
        val rig = Rig("dr128-unexpected")
        emitExpectingDeadClient(rig, IllegalStateException("synthetic gateway bug"))
        rig.assertRecorded("error:unexpected")
        assertEquals(1L, rig.health.snapshot().localOrigin)
    }
}

// NEW (DR-87/DR-88): a Success outcome can still end in an ERROR terminal — the collect-path
// malformed-tool/capacity rewrite happens inside CollectingTerminal.emitTerminal and returned
// normally, and the promote-time empty_compact/empty_model paths emitError while the outcome
// stays Success. Pre-fix the turn line said success, head health recorded nothing, and (for the
// collect rewrite) the perf row said "ok" — the client saw a 502 while every instrument read
// green. These arms drive TurnFinish directly: outcome in, then assert what the instruments saw.
// The watchdog arms fire real pollers first, then prove TurnFinish carries each sentinel into the line.
package head

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.perf.TurnPerf
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.gateway.compact.CompactStats
import splice.gateway.head.HeadHealthCounters
import splice.gateway.head.TurnDrive
import splice.gateway.head.TurnFinish
import splice.gateway.head.TurnTelemetry
import splice.gateway.head.TurnUsageStamp
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.usage.OutputClamp
import splice.gateway.usage.UsageStore
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.CollectingTerminal
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.TurnTerminal
import splice.gateway.wire.UsagePayloadBuilder
import splice.spi.ClientFrameEmitted
import splice.spi.InflightGate
import splice.spi.LiveLimit
import splice.spi.Ticker
import splice.spi.TurnWatchdog
import splice.spi.WatchdogFired
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnFinishTest {

    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("turn-finish")
    }

    private class VirtualTicks {
        var now = 0L
            private set
        val clock = ElapsedClock { now }
        val ticker = Ticker { intervalMs ->
            now += intervalMs
            true
        }
    }

    /** One TurnFinish with observable instruments; [tag] isolates each test's files. */
    private class Rig(tmp: Path, tag: String) {
        val logs = mutableListOf<String>()
        val log = LogSink { logs.add(it) }
        val health = HeadHealthCounters()
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
        val finish = TurnFinish(
            ElapsedClock { 5L },
            log,
            TurnUsageStamp(UsageStore(tmp.resolve("u-$tag.json"), tmp.resolve("rl-$tag.json")), log, telemetry),
            health,
            telemetry,
        )

        suspend fun drive(
            emitter: TurnTerminal,
            watchdog: TurnWatchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
        ): TurnDrive = TurnDrive(
            requestBody = buildJsonObject { },
            meta = TurnMeta(
                compact = false,
                showReasoning = ReasoningDisplay.TEXT,
                stream = false,
                originalModel = "claude-codex--gpt-5.6-sol",
                upstreamModel = "gpt-5.6-sol",
                clientMaxTokens = 100,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            emitter = emitter,
            watchdog = watchdog,
            slot = InflightGate(LiveLimit { 1 }).acquire(),
            pipeline = TurnPipeline(
                CompactStats(perfFile.resolveSibling("compact-dr8x.jsonl")),
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

    private suspend fun finishAndReadTurnLine(tag: String, watchdog: TurnWatchdog): String {
        val rig = Rig(tmp, tag)
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter, watchdog)
        try {
            rig.finish.finishTurn(drive, TurnOutcome.Failure(ErrorType.OVERLOADED, "upstream stalled"))
        } finally {
            drive.slot.release()
        }
        return rig.logs.first()
    }

    @Test
    fun `a fired mid-output idle watchdog reaches the rendered turn line`() = runBlocking {
        val ticks = VirtualTicks()
        val watchdog = TurnWatchdog(
            WatchdogBudget(10.seconds, 100.milliseconds, 30.seconds),
            clock = ticks.clock,
            ticker = ticks.ticker,
        )
        val slot = InflightGate(LiveLimit { 1 }, clock = ticks.clock).acquire()
        val target = launch { delay(10.seconds) }
        val poller = watchdog.launchIn(this, slot, target, ClientFrameEmitted { true })
        try {
            target.join()
        } finally {
            poller.cancel()
            slot.release()
        }
        assertTrue(watchdog.fired is WatchdogFired.Idle, "setup must fire the mid-output idle tier")

        val line = finishAndReadTurnLine("watchdog-idle", watchdog)

        assertTrue("watchdog=idle(tier=mid-output limit=100ms idle=250ms)" in line, line)
    }

    @Test
    fun `a fired total cap watchdog reaches the rendered turn line`() = runBlocking {
        val ticks = VirtualTicks()
        val watchdog = TurnWatchdog(
            WatchdogBudget(10.seconds, 10.seconds, 400.milliseconds),
            clock = ticks.clock,
            ticker = ticks.ticker,
        )
        val target = launch { delay(10.seconds) }
        val poller = watchdog.launchTotalCap(this, target)
        try {
            target.join()
        } finally {
            poller.cancel()
        }
        assertTrue(watchdog.fired is WatchdogFired.TotalCap, "setup must fire the whole-turn cap")

        val line = finishAndReadTurnLine("watchdog-cap", watchdog)

        assertTrue("watchdog=total-cap(elapsed=500ms)" in line, line)
    }

    @Test
    fun `a collect-path malformed-tool rewrite reaches health, log and perf - DR-87`() = runBlocking {
        val rig = Rig(tmp, "dr87")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val idx = emitter.openTool("toolu_1", "edit")
        emitter.inputJsonDelta(idx, "{not json")
        val drive = rig.drive(emitter)
        try {
            rig.finish.finishTurn(drive, TurnOutcome.Success(hasToolUse = true, incomplete = false, usage = Usage()))
        } finally {
            drive.slot.release()
        }
        // Control first: the client-facing rewrite really happened (HEAD-003's honest failure).
        assertEquals(502, emitter.httpStatus(), "the terminal must have rewritten to the error envelope")
        assertEquals(1L, rig.health.snapshot().localOrigin, "the downgrade must reach head health")
        assertTrue(rig.logs.any { it.contains("finish-degraded") }, "the downgrade must reach the log")
        AsyncFileIo.drain() // perf rows are appended asynchronously
        assertTrue(
            Files.readString(rig.perfFile).contains("malformed_tool_input"),
            "the perf row must carry the honest tag, not ok",
        )
    }

    @Test
    fun `an empty-model downgrade reaches health and the log - DR-88`() = runBlocking {
        val rig = Rig(tmp, "dr88")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            // No text, no thinking, no tools: StreamPromote emits the CX-09 empty_model error
            // while the outcome stays Success — perf was already honest, health/log were blind.
            rig.finish.finishTurn(drive, TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage()))
        } finally {
            drive.slot.release()
        }
        assertEquals(502, emitter.httpStatus(), "the client must have received the empty-model error")
        assertEquals(1L, rig.health.snapshot().localOrigin, "the downgrade must reach head health")
        assertTrue(rig.logs.any { it.contains("finish-degraded") }, "the downgrade must reach the log")
        AsyncFileIo.drain() // perf rows are appended asynchronously
        assertTrue(Files.readString(rig.perfFile).contains("empty_model"), "perf keeps the honest tag")
    }
}

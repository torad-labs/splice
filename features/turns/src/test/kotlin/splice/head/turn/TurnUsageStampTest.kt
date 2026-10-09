// NEW (V4-85): the stamp's own pins for the cache-WRITE counter.
//
// WHY A NEW FILE AND NOT AN ARM IN TurnFinishTest: the three existing usage arms there drive
// TurnFinish and assert the in/out/cached counters as a side effect of finishing a turn. The
// question here is narrower and belongs to the stamp alone — does `cache_write_tokens` reach the
// perf row, from `Usage.cacheWriteTokens`, on every path that stamps at all — and it has to be
// answerable without a terminal, an outcome shape or a watchdog in the way.
//
// THE DEFECT THESE PIN: PassthroughUsage folded cache_creation_input_tokens into inputTokens and
// dropped it, TurnUsageStamp wrote only IN/OUT/CACHED, and SessionCost therefore could not tell a
// cache WRITE from a cache MISS and billed it at the input rate — a declared cache_write rate was
// dead arithmetic on every head. The counter is the missing link in that chain, so it is pinned
// here at the seam where it is written rather than only where it is priced.
package splice.head.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.WsAttemptTiming
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.Usage
import splice.core.turn.UsageOrigin
import splice.core.turn.WatchdogBudget
import splice.core.turn.noRequestUsage
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughStreamTranslator
import splice.dialect.anthropic.PassthroughTurnContext
import splice.head.admission.LocalRefusal
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.ObservedRoundPost
import splice.head.round.RunnerSignals
import splice.head.usage.EconomicsStore
import splice.head.usage.OutputClamp
import splice.head.usage.UsageStore
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.BuiltTurn
import splice.upstream.RoundBody
import splice.upstream.RoundInterceptor
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** One stamp with an observable perf row; [tag] isolates each test's files. */
private class UsageStampRig(tmp: Path, private val tag: String, economics: EconomicsStore? = null) {
    val log = LogSink { }
    val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
    val telemetry = TurnTelemetry("anthropic", PerfStats(perfFile), log, ElapsedClock { 5L }, economics)
    val usageStore = UsageStore(tmp.resolve("u-$tag.json"), tmp.resolve("rl-$tag.json"))
    val stamp = TurnUsageStamp(usageStore, log, telemetry)

    suspend fun drive(interceptor: RoundInterceptor? = null): TurnDrive = TurnDrive(
        inputs = TurnInputs(
            built = BuiltTurn(
                requestBody = buildJsonObject { },
                meta = TurnMeta(
                    compact = false,
                    reasoning = TurnReasoning(
                        showReasoning = ReasoningDisplay.TEXT,
                        effort = "high",
                        summary = "detailed",
                        budgetTokens = null,
                    ),
                    route = TurnRoute(
                        stream = false,
                        originalModel = "claude-anthropic--sonnet-4-6",
                        upstreamModel = "sonnet-4-6",
                        clientMaxTokens = 100,
                    ),
                ),
                extraHeaders = emptyMap(),
                toolSearch = null,
                roundInterceptor = interceptor,
            ),
            slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
            t0 = 0,
            perf = TurnPerf(),
            trace = null,
            markHandedOff = {},
        ),
        emitter = CollectingTerminal("sonnet-4-6", UsagePayloadBuilder { buildJsonObject { } }),
        watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
        pipeline = TurnPipeline(
            CompactStats(perfFile.resolveSibling("compact-$tag.jsonl")),
            log = log,
            clampOutput = OutputClamp { it },
        ),
        signals = RunnerSignals(),
        channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(false),
        ),
    )
}

class NoRequestAccountingTest(@param:TempDir private val tmp: Path) {
    private val rates = ModelRates(2.0, 0.2, 10.0)
    private val price = TurnPrice(
        ModelCatalog(
            discoveryPrefix = "synthetic--",
            models = listOf(ModelEntry("sonnet-4-6", contextWindow = 100_000, rates = rates)),
            defaultContextWindow = 100_000,
        ),
    )

    private fun success(usage: Usage) =
        TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = usage)

    private fun counters(file: Path): Map<String, Long> {
        val row = Json.parseToJsonElement(Files.readString(file)).jsonObject
        return row.filterValues { it.jsonPrimitive.content.toLongOrNull() != null }
            .mapValues { it.value.jsonPrimitive.long }
    }

    @Test
    fun `success and salvage keep no-request ownership through the row and hourly adapter`() = runBlocking {
        for (salvage in listOf(false, true)) {
            val economics = EconomicsStore(tmp.resolve("hour-$salvage.json"), price)
            val rig = UsageStampRig(tmp, "no-request-$salvage", economics)
            val drive = rig.drive()
            try {
                if (salvage) {
                    rig.stamp.stampSalvaged(drive, noRequestUsage)
                } else {
                    rig.stamp.stampSuccess(drive, success(noRequestUsage))
                }
                val stamped = drive.perf.snapshot().counters
                assertTrue(TurnBill.fullyReported(stamped), "the stamp must retain no-request ownership")
                assertNull(stamped[PerfKeys.IN_TOKENS])
                assertNull(stamped[PerfKeys.OUT_TOKENS])
                rig.telemetry.recordPerf(drive, "error:local-refusal")
                assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
                val row = counters(rig.perfFile)
                assertEquals(0.0, TurnBill.usd(row, rates))
                assertNull(row[PerfKeys.CACHED_TOKENS])
                assertNull(row[PerfKeys.CACHE_WRITE_TOKENS])
                val hour = economics.read().single()
                assertEquals(0L, hour.counts.unreportedUsageTurns)
                assertEquals(0L, hour.unpricedTurns)
                assertEquals(0.0, hour.costUsd)
            } finally {
                drive.slot.release()
                rig.usageStore.flushNow()
                economics.flushNow()
            }
        }
    }

    @Test
    fun `the direct local refusal producer writes a complete zero bill without token observations`() =
        runBlocking {
            val rig = UsageStampRig(tmp, "direct-local-refusal")
            val drive = rig.drive()
            try {
                rig.telemetry.recordLocalRefusal(
                    drive.meta,
                    drive.perf,
                    0L,
                    LocalRefusal("error:local-refusal", "synthetic refusal", null),
                )
                assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
                val row = counters(rig.perfFile)
                assertTrue(TurnBill.fullyReported(row))
                assertEquals(0.0, TurnBill.usd(row, rates))
                assertNull(row[PerfKeys.IN_TOKENS])
                assertNull(row[PerfKeys.OUT_TOKENS])
            } finally {
                drive.slot.release()
                rig.usageStore.flushNow()
            }
        }

    @Test
    fun `an intercepted pre-send refusal retains ownership despite a prepared request body`() = runBlocking {
        val economics = EconomicsStore(tmp.resolve("intercepted-refusal-hour.json"), price)
        val rig = UsageStampRig(tmp, "intercepted-refusal", economics)
        val drive = rig.drive(RoundInterceptor { _, _, _ -> error("no transport is called") })
        try {
            drive.perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, 128L)
            drive.perf.setCount(PerfKeys.TRANSPORT_ATTEMPT_STARTS, 0L)
            drive.recordRawRound(success(noRequestUsage))
            rig.stamp.stampSalvaged(drive, noRequestUsage)
            rig.telemetry.recordPerf(drive, "error:local-refusal")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = counters(rig.perfFile)
            assertEquals(1L, row[PerfKeys.NO_REQUEST])
            assertEquals(0L, row[PerfKeys.ATTEMPTS])
            assertEquals(0L, row[PerfKeys.TRANSPORT_ATTEMPT_STARTS])
            assertEquals(0.0, TurnBill.usd(row, rates))
            assertNull(row[PerfKeys.IN_TOKENS])
            val hour = economics.read().single()
            assertEquals(0L, hour.counts.unreportedUsageTurns)
            assertEquals(0L, hour.unpricedTurns)
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
            economics.flushNow()
        }
    }

    @Test
    fun `a held no-request ending writes ownership through the retained row stamp`() = runBlocking {
        val rig = UsageStampRig(tmp, "held-no-request")
        val drive = rig.drive()
        val held = drive.sourceRow.hold()
        try {
            rig.telemetry.recordPerf(drive, "error:local-refusal")
            held.release(noRequestUsage)
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = counters(rig.perfFile)
            assertTrue(TurnBill.fullyReported(row))
            assertEquals(0.0, TurnBill.usd(row, rates))
            assertNull(row[PerfKeys.IN_TOKENS])
        } finally {
            held.release(null)
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a late posted missing report replaces earlier no-request ownership`() = runBlocking {
        val rig = UsageStampRig(tmp, "held-posted-unknown")
        val drive = rig.drive()
        val held = drive.sourceRow.hold()
        try {
            rig.stamp.stampSuccess(drive, success(noRequestUsage))
            rig.telemetry.recordPerf(drive, "error:synthetic")
            drive.recordRawRound(TurnOutcome.ClientAbandoned())
            held.release(null)
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = counters(rig.perfFile)
            assertFalse(TurnBill.fullyReported(row))
            assertNull(TurnBill.usd(row, rates))
        } finally {
            held.release(null)
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a websocket sent then abandoned before its first event remains unreported with zero attempts`() =
        runBlocking {
            val economics = EconomicsStore(tmp.resolve("ws-abort-hour.json"), price)
            val rig = UsageStampRig(tmp, "ws-abort-no-event", economics)
            val drive = rig.drive()
            try {
                WsAttemptTiming(drive.perf).sendAccepted()
                drive.recordRawRound(TurnOutcome.ClientAbandoned())
                rig.stamp.stampKnownOnCancellation(drive)
                rig.telemetry.recordPerf(drive, "client_gone")
                assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
                val row = counters(rig.perfFile)
                assertEquals(0L, row[PerfKeys.ATTEMPTS])
                assertEquals(1L, row[PerfKeys.TRANSPORT_ATTEMPT_STARTS])
                assertFalse(TurnBill.fullyReported(row))
                assertNull(TurnBill.usd(row, rates))
                val hour = economics.read().single()
                assertEquals(1L, hour.counts.unreportedUsageTurns)
                assertEquals(1L, hour.unpricedTurns)
            } finally {
                drive.slot.release()
                rig.usageStore.flushNow()
                economics.flushNow()
            }
        }

    @Test
    fun `posted salvage clears any earlier no-request stamp`() = runBlocking {
        val rig = UsageStampRig(tmp, "posted-salvage")
        val drive = rig.drive()
        try {
            TurnBill.counters(noRequestUsage).forEach { (key, value) -> drive.perf.setCount(key, value) }
            rig.stamp.stampSalvaged(drive, Usage(reported = emptySet()))
            val row = drive.perf.snapshot().counters
            assertFalse(TurnBill.fullyReported(row))
            assertNull(TurnBill.usd(row, rates))
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassthroughFailureUsageTest {
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    private fun event(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun providerFailure(drive: TurnDrive, vararg events: JsonObject): TurnOutcome.Failure =
        PassthroughStreamTranslator(
            PassthroughTurnContext({ false }, { null }, 180_000, 900_000),
            PassthroughQuirks(providerTag = "synthetic"),
        ).driveTurn(events.toList().asFlow(), drive.emitter) as TurnOutcome.Failure

    @Test
    fun `usage reported before a passthrough error survives into the retained row`() = runBlocking {
        val rig = UsageStampRig(tmp, "reported-provider-failure")
        val drive = rig.drive()
        try {
            val failure = providerFailure(
                drive,
                event(
                    """{"type":"message_start","message":{"usage":{"input_tokens":100,
                        "cache_read_input_tokens":20,"cache_creation_input_tokens":5}}}""",
                ),
                event("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
                event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"synthetic"}}"""),
                event("""{"type":"content_block_stop","index":0}"""),
                event("""{"type":"message_delta","delta":{},"usage":{"output_tokens":7}}"""),
                event("""{"type":"error","error":{"type":"api_error","message":"synthetic failure"}}"""),
            )
            rig.stamp.stampSalvaged(drive, failure.salvagedUsage)
            rig.telemetry.recordPerf(drive, "failure:api_error")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertEquals(125L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
            assertEquals(7L, row.getValue(PerfKeys.OUT_TOKENS).jsonPrimitive.long)
            assertEquals(20L, row.getValue(PerfKeys.CACHED_TOKENS).jsonPrimitive.long)
            assertEquals(5L, row.getValue(PerfKeys.CACHE_WRITE_TOKENS).jsonPrimitive.long)
            assertEquals(7L, rig.usageStore.readState().outputTokens5h)
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a passthrough failure with no usage leaves retained token fields absent`() = runBlocking {
        val rig = UsageStampRig(tmp, "unreported-provider-failure")
        val drive = rig.drive()
        try {
            val failure = providerFailure(
                drive,
                event("""{"type":"message_start","message":{}}"""),
                event("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
                event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"synthetic"}}"""),
                event("""{"type":"content_block_stop","index":0}"""),
                event("""{"type":"error","error":{"type":"api_error","message":"synthetic failure"}}"""),
            )
            rig.stamp.stampSalvaged(drive, failure.salvagedUsage)
            rig.telemetry.recordPerf(drive, "failure:api_error")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertNull(row[PerfKeys.IN_TOKENS], "no input count was reported")
            assertNull(row[PerfKeys.OUT_TOKENS], "streamed content is not a reported token count")
            assertNull(row[PerfKeys.CACHED_TOKENS])
            assertNull(row[PerfKeys.CACHE_WRITE_TOKENS])
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a reported zero on a failed stream is retained as zero rather than unknown`() = runBlocking {
        val rig = UsageStampRig(tmp, "reported-zero-provider-failure")
        val drive = rig.drive()
        try {
            val failure = providerFailure(
                drive,
                event(
                    """{"type":"message_start","message":{"usage":{"input_tokens":0,
                        "cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}""",
                ),
                event("""{"type":"message_delta","delta":{},"usage":{"output_tokens":0}}"""),
                event("""{"type":"error","error":{"type":"api_error","message":"synthetic failure"}}"""),
            )
            rig.stamp.stampSalvaged(drive, failure.salvagedUsage)
            rig.telemetry.recordPerf(drive, "failure:api_error")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertEquals(0L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
            assertEquals(0L, row.getValue(PerfKeys.OUT_TOKENS).jsonPrimitive.long)
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `an input-only report before failure cannot invent an output token count`() = runBlocking {
        val rig = UsageStampRig(tmp, "input-only-provider-failure")
        val drive = rig.drive()
        try {
            val failure = providerFailure(
                drive,
                event(
                    """{"type":"message_start","message":{"usage":{"input_tokens":100,
                        "cache_read_input_tokens":20,"cache_creation_input_tokens":5}}}""",
                ),
                event("""{"type":"error","error":{"type":"api_error","message":"synthetic failure"}}"""),
            )
            rig.stamp.stampSalvaged(drive, failure.salvagedUsage)
            rig.telemetry.recordPerf(drive, "failure:api_error")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertEquals(125L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
            assertNull(row[PerfKeys.OUT_TOKENS], "the message_delta never reported output")
            val billing = TurnBill.counters(failure.salvagedUsage)
            assertEquals(0.0002165, TurnBill.lowerBoundUsd(billing, ModelRates(2.0, 0.2, 10.0, 2.5))!!, 1e-12)
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnUsageStampTest {
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    private fun success(usage: Usage) =
        TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = usage)

    @Test
    fun `a successful zero usage step cannot erase the raw round its turn posted`() = runBlocking {
        val rig = UsageStampRig(tmp, "owned-claim-refused")
        val drive = rig.drive()
        try {
            val raw = rig.stamp.stampIndependent(success(Usage(inputTokens = 100, outputTokens = 7, cachedTokens = 40)))
            drive.recordRawRound(raw)
            rig.stamp.stampSuccess(drive, success(Usage(origin = UsageOrigin(localStep = true))))
            val counters = drive.perf.snapshot().counters
            assertEquals(100L, counters[PerfKeys.IN_TOKENS], "the durable source claim cannot own the posted bill")
            assertEquals(7L, counters[PerfKeys.OUT_TOKENS])
            assertEquals(40L, counters[PerfKeys.CACHED_TOKENS])
            assertEquals(7L, rig.usageStore.readState().outputTokens5h, "independent output is counted once")
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a later successful turn cannot bill a source round another turn posted`() = runBlocking {
        val rig = UsageStampRig(tmp, "owned-continuation")
        val drive = rig.drive()
        try {
            val source = rig.stamp.stampIndependent(success(Usage(inputTokens = 100, outputTokens = 7)))
                as TurnOutcome.Success
            val own = Usage(inputTokens = 11, outputTokens = 3)
            drive.recordRawRound(success(own))
            val generated = splice.head.round.RoundUsage().plusRound(source.usage).plusRound(own).toUsage()
            val cut = generated.origin.copy(history = generated.origin.history.copy(cutRounds = 1))
            rig.stamp.stampSuccess(drive, success(generated.copy(origin = cut)))
            val counters = drive.perf.snapshot().counters
            assertEquals(11L, counters[PerfKeys.IN_TOKENS])
            assertEquals(3L, counters[PerfKeys.OUT_TOKENS], "the earlier source is billed only on its posting row")
            assertTrue(PerfKeys.ABSORBED_ROUNDS !in counters, "a carried source is not this turn's hidden round")
            assertEquals(1L, counters[PerfKeys.CUT_SOURCE_ROUNDS], "the turn's cut remains observable")
            assertEquals(10L, rig.usageStore.readState().outputTokens5h)
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a held posting row keeps its completed raw usage when a source claim releases with none`() = runBlocking {
        val rig = UsageStampRig(tmp, "owned-held-claim-refused")
        val drive = rig.drive()
        val held = drive.sourceRow.hold()
        try {
            rig.stamp.stampSuccess(drive, success(Usage(origin = UsageOrigin(localStep = true))))
            rig.telemetry.recordPerf(drive, "ok")
            val raw = rig.stamp.stampIndependent(success(Usage(inputTokens = 100, outputTokens = 7, cachedTokens = 40)))
            drive.recordRawRound(raw)
            held.release(null)
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertEquals(100L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
            assertEquals(7L, row.getValue(PerfKeys.OUT_TOKENS).jsonPrimitive.long)
            assertEquals(40L, row.getValue(PerfKeys.CACHED_TOKENS).jsonPrimitive.long)
            assertEquals(7L, rig.usageStore.readState().outputTokens5h, "the independent source's output stays once")
        } finally {
            held.release(null)
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a local boot failure and raw overlay retain one completed source request`() = runBlocking {
        val rig = UsageStampRig(tmp, "synthetic-boot-overlay")
        val drive = rig.drive()
        val source = Usage(inputTokens = 1_400, outputTokens = 12, cachedTokens = 1_100)
        val local = TurnOutcome.Failure(
            "synthetic boot failure",
            cause = FailureCause.INTERNAL,
            phase = FailurePhase.MID_OUTPUT,
            salvagedUsage = source.followedBy(noRequestUsage),
        )
        try {
            drive.recordRawRound(success(source))
            rig.stamp.stampSalvaged(drive, local.salvagedUsage)
            rig.telemetry.recordPerf(drive, "failed")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertEquals(1_400L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
            assertEquals(12L, row.getValue(PerfKeys.OUT_TOKENS).jsonPrimitive.long)
            assertNull(row[PerfKeys.ABSORBED_ROUNDS], "the raw overlay cannot leave a second copy in absorbed input")
            assertNull(row[PerfKeys.CUT_SOURCE_ROUNDS])
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a posted source with no terminal usage is unreported rather than a zero bill`() = runBlocking {
        val rig = UsageStampRig(tmp, "owned-unreported-source")
        val drive = rig.drive(RoundInterceptor { _, _, _ -> error("no source is executed by this fixture") })
        try {
            drive.perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, 123)
            drive.perf.beginUpstreamAttempt()
            rig.stamp.stampSuccess(drive, success(Usage(origin = UsageOrigin(localStep = true))))
            rig.telemetry.recordPerf(drive, "ok")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertFalse(row[PerfKeys.NO_REQUEST]?.jsonPrimitive?.long == 1L, "this source was posted")
            assertNull(row[PerfKeys.IN_TOKENS], "the cut source never reported its input")
            assertNull(row[PerfKeys.OUT_TOKENS], "absence is not a measured zero")
            assertNull(row[PerfKeys.CACHED_TOKENS])
            assertNull(row[PerfKeys.CACHE_WRITE_TOKENS])
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a successful content step whose raw post unwound before reporting has no invented zero bill`() = runBlocking {
        val endings = listOf(IOException("synthetic source threw"), CancellationException("synthetic source cancelled"))
        for ((index, ending) in endings.withIndex()) {
            val rig = UsageStampRig(tmp, "owned-unwound-source-$index")
            val drive = rig.drive(RoundInterceptor { _, _, _ -> error("the fixture dispatches only its raw post") })
            var frames = 0
            val observed = ObservedRoundPost(
                dispatch = { body, sink ->
                    drive.perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, body.byteSize())
                    drive.perf.beginUpstreamAttempt()
                    val block = sink.openText()
                    repeat(58) {
                        sink.textDelta(block, "synthetic content")
                        frames++
                    }
                    sink.closeBlock(block)
                    throw ending
                },
                ordinary = { error("the fixture uses redirected dispatch") },
                observation = drive::recordRawRound,
                perf = drive.perf,
                postingRow = drive.sourceRow,
            )
            try {
                val thrown = assertThrows(ending::class.java) {
                    runBlocking { observed.postInto(RoundBody.Text("{}"), drive.emitter) }
                }
                assertSame(ending, thrown)
                assertEquals(58, frames, "content left before the raw call unwound")
                assertNull(drive.rawRoundUsage(), "the observation runs only after raw dispatch reports")
                rig.stamp.stampSuccess(drive, success(Usage(origin = UsageOrigin(localStep = true))))
                rig.telemetry.recordPerf(drive, "ok")
                assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
                val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
                assertEquals("ok", row.getValue("outcome").jsonPrimitive.content)
                assertFalse(row[PerfKeys.NO_REQUEST]?.jsonPrimitive?.long == 1L, "unwound content was posted")
                assertNull(row[PerfKeys.IN_TOKENS], "unwound content without usage is unreported")
                assertNull(row[PerfKeys.OUT_TOKENS])
                assertNull(row[PerfKeys.CACHED_TOKENS])
                assertNull(row[PerfKeys.LOCAL_STEP], "this successful step posted before its raw source unwound")
                assertEquals(0L, rig.usageStore.readState().outputTokens5h, "unknown output is never invented")
            } finally {
                drive.slot.release()
                rig.usageStore.flushNow()
            }
        }
    }

    @Test
    fun `a local step with no post keeps its explicit zero bill`() = runBlocking {
        val rig = UsageStampRig(tmp, "owned-no-source")
        val drive = rig.drive(RoundInterceptor { _, _, _ -> error("a local step must not post") })
        try {
            rig.stamp.stampSuccess(drive, success(Usage(origin = UsageOrigin(localStep = true))))
            rig.telemetry.recordPerf(drive, "ok")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            assertEquals(0L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
            assertEquals(0L, row.getValue(PerfKeys.OUT_TOKENS).jsonPrimitive.long)
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }

    @Test
    fun `a posting row keeps reported raw salvage when its source ends after the successful step`() = runBlocking {
        val failure = TurnOutcome.Failure(
            "synthetic source ended",
            cause = splice.core.turn.FailureCause.UPSTREAM_TRUNCATED,
            phase = splice.core.turn.FailurePhase.MID_OUTPUT,
            salvagedUsage = Usage(inputTokens = 50, outputTokens = 7),
        )
        val split = failure.copy(
            partial = TurnOutcome.PartialRound(usage = Usage(inputTokens = 40, outputTokens = 3)),
            salvagedUsage = Usage(inputTokens = 10, outputTokens = 4),
        )
        val endings = listOf(failure, split, TurnOutcome.ClientAbandoned(Usage(inputTokens = 50, outputTokens = 7)))
        for ((index, ending) in endings.withIndex()) {
            val rig = UsageStampRig(tmp, "owned-raw-salvage-$index")
            val drive = rig.drive()
            val held = drive.sourceRow.hold()
            try {
                rig.stamp.stampSuccess(drive, success(Usage(origin = UsageOrigin(localStep = true))))
                rig.telemetry.recordPerf(drive, "ok")
                drive.recordRawRound(rig.stamp.stampIndependent(ending))
                held.release(null)
                assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
                val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
                assertEquals(50L, row.getValue(PerfKeys.IN_TOKENS).jsonPrimitive.long)
                assertEquals(7L, row.getValue(PerfKeys.OUT_TOKENS).jsonPrimitive.long)
                assertEquals(7L, rig.usageStore.readState().outputTokens5h)
            } finally {
                held.release(null)
                drive.slot.release()
                rig.usageStore.flushNow()
            }
        }
    }

    @Test
    fun `raw completion after a local step bills once and a later continuation bills only new output`() =
        runBlocking {
            val rig = UsageStampRig(tmp, "independent")
            val first = rig.drive()
            val resumed = rig.drive()
            try {
                rig.stamp.stampSuccess(first, success(Usage(origin = UsageOrigin(localStep = true))))
                val raw = rig.stamp.stampIndependent(success(Usage(inputTokens = 100, outputTokens = 7)))
                    as TurnOutcome.Success
                assertEquals(7L, rig.usageStore.readState().outputTokens5h)
                assertEquals(7L, raw.usage.origin.recordedOutputTokens)
                val total = splice.head.round.RoundUsage()
                    .plusRound(raw.usage)
                    .plusRound(Usage(inputTokens = 150, outputTokens = 5))
                    .toUsage()
                rig.stamp.stampSuccess(resumed, success(total))
                rig.stamp.stampKnownOnCancellation(resumed)
                assertEquals(12L, rig.usageStore.readState().outputTokens5h)
                assertEquals(12L, resumed.perf.snapshot().counters[PerfKeys.OUT_TOKENS])
            } finally {
                first.slot.release()
                resumed.slot.release()
                rig.usageStore.flushNow()
            }
        }

    @Test
    fun `a torn independent round records partial billing without billing its salvage twice`() =
        runBlocking {
            val rig = UsageStampRig(tmp, "independent-torn")
            val resumed = rig.drive()
            try {
                val failure = TurnOutcome.Failure(
                    "source torn",
                    cause = splice.core.turn.FailureCause.UPSTREAM_TRUNCATED,
                    phase = splice.core.turn.FailurePhase.MID_OUTPUT,
                    partial = TurnOutcome.PartialRound(usage = Usage(outputTokens = 7)),
                )
                val raw = rig.stamp.stampIndependent(failure) as TurnOutcome.Failure
                val usage = checkNotNull(raw.partial).usage
                rig.stamp.stampSalvaged(resumed, usage + Usage(outputTokens = 5))
                assertEquals(12L, rig.usageStore.readState().outputTokens5h)
            } finally {
                resumed.slot.release()
                rig.usageStore.flushNow()
            }
        }

    @Test
    fun `a success stamp writes cache_write_tokens from the usage's cacheWriteTokens`() = runBlocking {
        val rig = UsageStampRig(tmp, "success")
        val drive = rig.drive()
        try {
            // The shape PassthroughUsage.toUsage() produces for a turn that read a 40k cached prefix
            // and wrote a 12k block: inputTokens is INCLUSIVE of both cache buckets (8000 fresh +
            // 40000 read + 12000 written), and the two cache counts are disjoint read-offs of it.
            rig.stamp.stampSuccess(
                drive,
                success(
                    Usage(
                        inputTokens = 60_000,
                        outputTokens = 500,
                        cachedTokens = 40_000,
                        cacheWriteTokens = 12_000,
                    ),
                ),
            )

            val counters = drive.perf.snapshot().counters
            assertEquals(60_000L, counters[PerfKeys.IN_TOKENS], "the inclusive input total is unchanged")
            assertEquals(40_000L, counters[PerfKeys.CACHED_TOKENS], "the read bucket is unchanged")
            assertEquals(500L, counters[PerfKeys.OUT_TOKENS], "the output bucket is unchanged")
            assertEquals(
                12_000L,
                counters[PerfKeys.CACHE_WRITE_TOKENS],
                "the cache-write bucket must reach the perf row, or SessionCost prices it as a miss",
            )
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `a dialect that reports no cache-creation bucket stamps a literal zero, not an absent key`() =
        runBlocking {
            val rig = UsageStampRig(tmp, "chat-shaped")
            val drive = rig.drive()
            try {
                // The normalized chat input group explicitly reports a measured zero cache-write bucket.
                // A historical row missing that observation stays unpriced; it is never a measured zero.
                rig.stamp.stampSuccess(drive, success(Usage(inputTokens = 1_000, outputTokens = 7, cachedTokens = 200)))

                val counters = drive.perf.snapshot().counters
                assertTrue(
                    PerfKeys.CACHE_WRITE_TOKENS in counters,
                    "the counter is written unconditionally, like cached_tokens: $counters",
                )
                assertEquals(0L, counters[PerfKeys.CACHE_WRITE_TOKENS])
            } finally {
                drive.slot.release()
            }
        }

    @Test
    fun `a salvaged stamp carries the cache-write bucket too, so billed tokens are not lost`() = runBlocking {
        val rig = UsageStampRig(tmp, "salvaged")
        val drive = rig.drive()
        try {
            // Salvaged usage from absorbed rounds of an ultimately-failed turn is REAL billed spend —
            // that is why stampSalvaged exists — and a cache write inside it is the most expensive
            // bucket on the row. It goes through the same setKnownCounters, and this pins that.
            rig.stamp.stampSalvaged(drive, Usage(inputTokens = 30_000, outputTokens = 0, cacheWriteTokens = 30_000))

            assertEquals(30_000L, drive.perf.snapshot().counters[PerfKeys.CACHE_WRITE_TOKENS])
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `a cancellation with no completed raw round leaves the cache-write counter absent`() = runBlocking {
        val rig = UsageStampRig(tmp, "cancelled-empty")
        val drive = rig.drive()
        try {
            // Nothing recorded, nothing stamped — the new counter must not appear as a confident 0 on a
            // turn that never reported usage at all, for the same reason the other three do not.
            rig.stamp.stampKnownOnCancellation(drive)

            val counters = drive.perf.snapshot().counters
            assertTrue(PerfKeys.CACHE_WRITE_TOKENS !in counters, counters.toString())
            assertTrue(PerfKeys.CACHED_TOKENS !in counters, counters.toString())
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `a cancelled turn's completed raw rounds keep the LATEST cache-write count, never their sum`() =
        runBlocking {
            val rig = UsageStampRig(tmp, "cancelled-prefix")
            val drive = rig.drive()
            try {
                // Each code-mode continuation re-sends the whole conversation, so round N's
                // cache_creation already CONTAINS round N-1's — exactly the cumulative law RoundUsage
                // applies to input and cached. Summing them would double-bill the most expensive bucket
                // on the row; 12000 + 18000 = 30000 is the number a naive accumulator would stamp.
                drive.recordRawRound(
                    success(
                        Usage(
                            inputTokens = 40_000,
                            outputTokens = 3,
                            cachedTokens = 20_000,
                            cacheWriteTokens = 12_000,
                        ),
                    ),
                )
                drive.recordRawRound(
                    success(
                        Usage(
                            inputTokens = 70_000,
                            outputTokens = 5,
                            cachedTokens = 40_000,
                            cacheWriteTokens = 18_000,
                        ),
                    ),
                )

                rig.stamp.stampKnownOnCancellation(drive)

                val counters = drive.perf.snapshot().counters
                assertEquals(70_000L, counters[PerfKeys.IN_TOKENS], "input is the latest completed raw round")
                assertEquals(40_000L, counters[PerfKeys.CACHED_TOKENS], "so is the read bucket")
                assertEquals(
                    18_000L,
                    counters[PerfKeys.CACHE_WRITE_TOKENS],
                    "and so is the write bucket — a sum here would bill the earlier rounds again",
                )
                assertEquals(8L, counters[PerfKeys.OUT_TOKENS], "output is the one bucket that accrues")
            } finally {
                drive.slot.release()
            }
        }
}

class CodeModeZeroBillingTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `an intercepted raw round's reported zero bill survives the posted-row filter`() = runBlocking {
        val rig = UsageStampRig(tmp, "synthetic-zero-source")
        val drive = rig.drive(RoundInterceptor { _, _, _ -> error("synthetic only") })
        try {
            drive.perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, 123)
            drive.recordRawRound(TurnOutcome.Success(false, false, Usage()))
            rig.stamp.stampSuccess(drive, TurnOutcome.Success(false, false, Usage(
                origin = UsageOrigin(localStep = true),
            )))
            rig.telemetry.recordPerf(drive, "ok")
            assertTrue(AsyncFileIo.awaitFile(rig.perfFile))
            val row = Json.parseToJsonElement(Files.readString(rig.perfFile)).jsonObject
            val keys = listOf(
                PerfKeys.IN_TOKENS,
                PerfKeys.OUT_TOKENS,
                PerfKeys.CACHED_TOKENS,
                PerfKeys.CACHE_WRITE_TOKENS,
            )
            for (key in keys) {
                assertEquals(0L, row[key]?.jsonPrimitive?.long, "observed zero remains reported: $key")
            }
        } finally {
            drive.slot.release()
            rig.usageStore.flushNow()
        }
    }
}

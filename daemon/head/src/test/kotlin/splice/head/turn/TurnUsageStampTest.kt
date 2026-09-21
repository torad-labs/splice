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

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.usage.OutputClamp
import splice.head.usage.UsageStore
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnUsageStampTest {

    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("turn-usage-stamp")
    }

    /** One stamp with an observable perf row; [tag] isolates each test's files. */
    private class Rig(tmp: Path, private val tag: String) {
        val log = LogSink { }
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val telemetry = TurnTelemetry("anthropic", PerfStats(perfFile), log, ElapsedClock { 5L })
        val usageStore = UsageStore(tmp.resolve("u-$tag.json"), tmp.resolve("rl-$tag.json"))
        val stamp = TurnUsageStamp(usageStore, log, telemetry)

        suspend fun drive(): TurnDrive = TurnDrive(
            requestBody = buildJsonObject { },
            meta = TurnMeta(
                compact = false,
                showReasoning = ReasoningDisplay.TEXT,
                stream = false,
                originalModel = "claude-anthropic--sonnet-4-6",
                upstreamModel = "sonnet-4-6",
                clientMaxTokens = 100,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            emitter = CollectingTerminal("sonnet-4-6", UsagePayloadBuilder { buildJsonObject { } }),
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

    private fun success(usage: Usage) =
        TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = usage)

    @Test
    fun `a success stamp writes cache_write_tokens from the usage's cacheWriteTokens`() = runBlocking {
        val rig = Rig(tmp, "success")
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
            val rig = Rig(tmp, "chat-shaped")
            val drive = rig.drive()
            try {
                // ChatUsage.toUsage() builds Usage(inputTokens, outputTokens, cachedTokens) positionally,
                // so cacheWriteTokens defaults to 0 on every OpenAI-chat head. setCount writes it anyway,
                // which is the distinction worth keeping: a 0 in the row means "this head wrote no
                // cache", while an ABSENT key means "this row predates the counter" — and SessionCost
                // reads the second as 0 too, which is what keeps historical rows priced exactly as before.
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
        val rig = Rig(tmp, "salvaged")
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
        val rig = Rig(tmp, "cancelled-empty")
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
            val rig = Rig(tmp, "cancelled-prefix")
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

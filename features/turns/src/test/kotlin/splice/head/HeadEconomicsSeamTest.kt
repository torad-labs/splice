// NEW: the head-side seam of the quota rollup (V4-75 step 2). Its own class rather than two more
// cases on HeadServerIntegrationTest: that file is already at detekt's LargeClass ceiling, and the
// question here is a different one — not "does a turn stream correctly" but "does a finished turn
// reach the burn page's input, carrying the same numbers the perf row carries".
//
// ONE RIG PER TEST, never a shared PER_CLASS head. A quota429 turn ARMS the head-wide cooldown
// (RetryRules.giveUp), after which the next turn is refused at admission and writes a perf row with
// no economics record — so a shared store would make the 429 case silently corrupt the turn case's
// perf-vs-rollup equality, in whichever order JUnit happened to run them.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.turn.TurnDrive
import splice.head.turn.TurnTelemetry
import splice.head.usage.EconomicsStore
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private class EconomicsFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private val IN_TOKENS_FIELD = Regex("\"in_tokens\":(\\d+)")
private val OUT_TOKENS_FIELD = Regex("\"out_tokens\":(\\d+)")
private val CACHE_WRITE_FIELD = Regex("\"cache_write_tokens\":(\\d+)")

/** A real HeadServer over the mock upstream, with the economics store the head writes. */
private class EconomicsRig(tmp: Path) {
    val mock = MockChatGptUpstream()
    val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    val port: Int get() = head.port
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
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
            log = {},
        ).copy(stores = headStores(tmp, economics = economics)),
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

/** The economics seam ALONE: one TurnTelemetry, one EconomicsStore, one drive whose perf row
 *  already carries the counters — no upstream, no dialect, no HeadServer.
 *
 *  WHY THIS RIG AND NOT ANOTHER [EconomicsRig] ARM. The full-head rig speaks the responses dialect,
 *  and no OpenAI-responses wire reports a cache-creation bucket at all (ResponsesHarvest reads
 *  input/output plus input_tokens_details.cached_tokens and nothing else), so a cache-write
 *  assertion over it could only ever compare 0 to 0 — green under every mutation, including
 *  deleting the field from TurnEconomics. A NONZERO counter is the only thing that can fail, and
 *  the shortest honest way to one is to set it on the snapshot the way TurnUsageStamp does. */
private class TelemetryRig(tmp: Path, private val tag: String) {
    val log = LogSink { }
    val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
    val economics = EconomicsStore(tmp.resolve("economics-$tag.json"))
    val telemetry = TurnTelemetry("anthropic", PerfStats(perfFile), log, ElapsedClock { 5L }, economics)

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

    fun perfRow(): String {
        AsyncFileIo.drain()
        return Files.readString(perfFile).trim().lines().first { it.isNotBlank() }
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

    /** V4-86 THE SEAM THIS ROW ADDS. TurnUsageStamp writes cache_write_tokens on every turn
     *  (V4-85), and recordEconomics is the one place that decides whether the rollup ever sees it.
     *  It did not: TurnEconomics had no such field, so the counter died here and the burn page
     *  counted a cache write as ordinary input with nothing to distinguish it. ONE snapshot, TWO
     *  sinks, so the number in the store must equal the number in the JSONL row exactly. */
    @Test
    fun `the cache-write counter reaches the hourly rollup, equal to the perf row's own`(
        @TempDir tmp: Path,
    ) = runTest {
        val rig = TelemetryRig(tmp, "cache-write")
        val drive = rig.drive()
        try {
            // The shape PassthroughUsage.toUsage() produces for a turn that read a 40k prefix and
            // wrote a 12k block: in_tokens is INCLUSIVE of both cache buckets, which are disjoint
            // read-offs of it. Set exactly as TurnUsageStamp.setKnownCounters sets them.
            drive.perf.setCount(PerfKeys.IN_TOKENS, 60_000)
            drive.perf.setCount(PerfKeys.CACHED_TOKENS, 40_000)
            drive.perf.setCount(PerfKeys.CACHE_WRITE_TOKENS, 12_000)
            drive.perf.setCount(PerfKeys.OUT_TOKENS, 500)

            rig.telemetry.recordPerf(drive, "ok")

            val bucket = rig.economics.read().single()
            assertEquals(
                12_000,
                bucket.cacheWriteTokens,
                "the cache-write bucket must reach the rollup, or the burn page cannot see it: $bucket",
            )
            assertEquals(60_000, bucket.inTokens, "input stays the METERED total, both cache buckets in it")
            assertEquals(40_000, bucket.cachedTokens, "the read bucket is unchanged by the write bucket")

            val fromPerf = CACHE_WRITE_FIELD.find(rig.perfRow())?.groupValues?.get(1)?.toLong()
            assertEquals(12_000L, fromPerf, "the JSONL row carries it too: one snapshot, two sinks")
            assertEquals(fromPerf, bucket.cacheWriteTokens, "the two sinks may never disagree")
        } finally {
            drive.slot.release()
        }
    }

    /** A turn that never reported usage leaves the counter ABSENT from the snapshot (pinned by
     *  TurnUsageStampTest), and the rollup must fold that as 0 rather than throwing or skipping the
     *  turn — the same reading the persisted old-shape row gets. A rollup that dropped such turns
     *  would under-count the very thing it exists to count. */
    @Test
    fun `a turn whose snapshot has no cache-write counter folds as zero, and still counts`(
        @TempDir tmp: Path,
    ) = runTest {
        val rig = TelemetryRig(tmp, "absent")
        val drive = rig.drive()
        try {
            drive.perf.setCount(PerfKeys.IN_TOKENS, 1_000)

            rig.telemetry.recordPerf(drive, "ok")

            val bucket = rig.economics.read().single()
            assertEquals(1, bucket.turns, "the turn is still recorded")
            assertEquals(0, bucket.cacheWriteTokens, "an absent counter is 0, never a dropped turn")
            assertEquals(1_000, bucket.inTokens)
        } finally {
            drive.slot.release()
        }
    }
}

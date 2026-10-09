// NEW (DR-87/DR-88): a Success outcome can still end in an ERROR terminal — the collect-path
// malformed-tool/capacity rewrite happens inside CollectingTerminal.emitTerminal and returned
// normally, and the promote-time empty_compact/empty_model paths emitError while the outcome
// stays Success. Pre-fix the turn line said success, head health recorded nothing, and (for the
// collect rewrite) the perf row said "ok" — the client saw a 502 while every instrument read
// green. These arms drive TurnFinish directly: outcome in, then assert what the instruments saw.
// The watchdog arms fire real pollers first, then prove TurnFinish carries each sentinel into the line.
package splice.head.turn

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.HeadHealthCounters
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.PostRound
import splice.head.round.ReanchorRunner
import splice.head.round.RunnerSignals
import splice.head.transport.UpstreamEventTiming
import splice.head.turn.delivery.CollectedReply
import splice.head.usage.OutputClamp
import splice.head.usage.UsageStore
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.ClientFrameEmitted
import splice.upstream.ReanchorPolicy
import splice.upstream.RetryBackoff
import splice.upstream.Ticker
import splice.upstream.Waiter
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.retry.WatchdogFired
import java.io.IOException
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One TurnFinish with observable instruments; [tag] isolates each test's files. */
private class Rig(tmp: Path, tag: String, clock: ElapsedClock = ElapsedClock { 5L }) {
    val logs = mutableListOf<String>()
    val log = LogSink { logs.add(it) }
    val health = HeadHealthCounters()
    val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
    val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, clock)
    val usageStore = UsageStore(tmp.resolve("u-$tag.json"), tmp.resolve("rl-$tag.json"))
    val usageStamp = TurnUsageStamp(usageStore, log, telemetry)
    val finish = TurnFinish(
        ElapsedClock { 5L },
        log,
        usageStamp,
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
        slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
        pipeline = TurnPipeline(
            CompactStats(perfFile.resolveSibling("compact-dr8x.jsonl")),
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

/** The actual persisted row must include paced delivery, including cancellation's cleanup. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnPerfRowTest {
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `persisted perf row includes the completed paced tail`(cancelBeforeRecording: Boolean) = runTest {
        val clock = ElapsedClock { testScheduler.currentTime }
        val rig = Rig(tmp, "paced-row-$cancelBeforeRecording", clock)
        val emitter = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter).copy(perf = TurnPerf(clock = clock))
        val pacing = drive.channel.launchPacer(
            this,
            kotlinx.coroutines.Job(),
            Ticker { interval ->
                delay(interval)
                true
            },
            clock,
            splice.head.wire.LostClient("synthetic", {}),
        )
        try {
            drive.channel.writeMutex.withLock {
                repeat(190) {
                    drive.channel.timedClientWrite(
                        "event: content_block_delta\ndata: {\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"synthetic\"}}\n\n",
                        drive.perf,
                        clock,
                    )
                }
                drive.channel.timedClientWrite("event: message_stop\ndata: {}\n\n", drive.perf, clock)
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                if (cancelBeforeRecording) currentCoroutineContext().cancel()
                rig.telemetry.recordPerf(drive, "ok")
            }.join()
            drive.channel.finishPacing(pacing, clock)
            AsyncFileIo.drain()
            val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).single()).jsonObject
            val held = row[PerfKeys.OUT_HOLD_MAX_MS]?.jsonPrimitive?.long ?: 0L
            assertTrue(held > 0, "the persisted row must include the held tail, not just the first write")
            assertEquals(drive.perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS], held)
            assertEquals(191L, row.getValue(PerfKeys.FRAMES_OUT).jsonPrimitive.long)
            assertEquals(
                drive.perf.snapshot().counters[PerfKeys.OUT_GAP_MAX_MS],
                row.getValue(PerfKeys.OUT_GAP_MAX_MS).jsonPrimitive.long,
            )
        } finally {
            pacing.cancel()
            drive.slot.release()
        }
    }

    @Test
    fun `collected publication waits for a flushed reply and preserves its ending exactly once`() = runTest {
        var now = 10L
        val clock = ElapsedClock { now }
        val rig = Rig(tmp, "collected-row", clock)
        val terminal = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(terminal).copy(perf = TurnPerf(clock = clock))
        try {
            drive.collectPerf.defer()
            rig.telemetry.recordPerf(drive, "overloaded", true, "synthetic-cause", 3)
            assertTrue(!Files.exists(rig.perfFile), "collect must not save an early immutable snapshot")
            now = 60L
            val sink = ByteChannel()
            CollectedReply("synthetic reply", HttpStatusCode.OK, drive.perf).writeTo(sink)
            drive.collectPerf.publish(drive)
            drive.collectPerf.publish(drive)
            rig.telemetry.recordPerf(drive, "wrong duplicate")
            assertTrue(AsyncFileIo.drain())
            val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).single()).jsonObject
            assertEquals(50L, row.getValue(PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS).jsonPrimitive.long)
            assertEquals("overloaded", row.getValue("outcome").jsonPrimitive.content)
            assertEquals("synthetic-cause", row.getValue("cause").jsonPrimitive.content)
            assertEquals(3L, row.getValue("layers").jsonPrimitive.long)
        } finally {
            drive.slot.release()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed or cancelled collect flush keeps one ending without fabricating a client byte`(
        cancelled: Boolean,
    ) = runTest {
        val rig = Rig(tmp, "collect-flush-$cancelled")
        val terminal = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(terminal)
        val sink = object : ByteWriteChannel by ByteChannel() {
            override suspend fun flush(): Unit = if (cancelled) {
                throw CancellationException("synthetic cancelled flush")
            } else {
                throw IOException("synthetic failed flush")
            }
        }
        try {
            drive.collectPerf.defer()
            rig.telemetry.recordPerf(drive, "upstream-error", cause = "synthetic-cause", layers = 2)
            val failure = try {
                CollectedReply("synthetic reply", HttpStatusCode.BadGateway, drive.perf).writeTo(sink)
                null
            } catch (cancelled: CancellationException) {
                cancelled
            } catch (failed: IOException) {
                failed
            } finally {
                withContext(NonCancellable) { drive.collectPerf.publish(drive) }
            }
            assertTrue(failure != null, "the injected downstream flush must actually fail")
            drive.collectPerf.publish(drive)
            assertTrue(AsyncFileIo.drain())
            val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).single()).jsonObject
            assertTrue(PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS !in row, "an unsuccessful flush has no client byte")
            assertEquals("upstream-error", row.getValue("outcome").jsonPrimitive.content)
            assertEquals("synthetic-cause", row.getValue("cause").jsonPrimitive.content)
            assertEquals(2L, row.getValue("layers").jsonPrimitive.long)
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `a later collect flush failure keeps the first successful client byte`() = runTest {
        var now = 10L
        val perf = TurnPerf { now }
        val channel = ByteChannel(autoFlush = true)
        var flushes = 0
        val sink = object : ByteWriteChannel by channel {
            override suspend fun flush() {
                flushes++
                if (flushes > 1) throw IOException("synthetic late flush failure")
                now = 60L
                channel.flush()
            }
        }
        val failure = try {
            CollectedReply("synthetic reply", HttpStatusCode.OK, perf).writeTo(sink)
            null
        } catch (failed: IOException) {
            failed
        }
        assertTrue(failure != null && flushes == 2, "the second flush must fail after the first succeeds")
        assertEquals(50L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS])
    }

    private fun tornThenResumedPost(drive: TurnDrive, clock: ElapsedClock, waiter: Waiter): PostRound {
        val event = Json.parseToJsonElement(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"synthetic"}}""",
        ).jsonObject
        var posted = 0
        return PostRound {
            val first = posted++ == 0
            val startedAtMs = drive.perf.elapsedMs()
            val events = flow {
                if (!first) waiter.wait(2_000)
                emit(event)
                if (first) {
                    waiter.wait(40_000)
                    throw SocketException("synthetic reset")
                }
            }
            try {
                UpstreamEventTiming(drive.perf, startedAtMs).observe(events).collect {
                    drive.channel.writeMutex.withLock {
                        drive.channel.timedClientWrite(
                            "event: content_block_delta\ndata: $event\n\n",
                            drive.perf,
                            clock,
                        )
                    }
                }
                TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage())
            } catch (_: SocketException) {
                TurnOutcome.Failure(
                    "synthetic reset",
                    cause = FailureCause.UPSTREAM_STALLED,
                    partial = TurnOutcome.PartialRound(bodyText = "synthetic", emittedText = true),
                    phase = FailurePhase.MID_OUTPUT,
                )
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `persisted row separates a torn read tail reanchor backoff and the next POST wait`() = runTest {
        val clock = ElapsedClock { testScheduler.currentTime }
        val rig = Rig(tmp, "torn-tail-reanchor", clock)
        val emitter = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter).copy(perf = TurnPerf(clock = clock))
        val runner = ReanchorRunner(
            key = "synthetic",
            log = {},
            postRound = tornThenResumedPost(drive, clock, Waiter { ms -> delay(ms) }),
            finish = { rig.telemetry.recordPerf(drive, "ok") },
            signals = RunnerSignals(),
            backoff = RetryBackoff { _, _ -> delay(3_000) },
        )
        try {
            runner.run(buildJsonObject {}, ReanchorPolicy { buildJsonObject {} }, drive.perf)
            assertTrue(AsyncFileIo.drain())
            val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).single()).jsonObject
            assertAll(
                { assertEquals(40_000L, row[PerfKeys.UP_GAP_MAX_MS]?.jsonPrimitive?.long) },
                { assertEquals("torn", row[PerfKeys.UP_GAP_END]?.jsonPrimitive?.content) },
                { assertEquals(3_000L, row[PerfKeys.BACKOFF_MS]?.jsonPrimitive?.long) },
                { assertEquals(40_000L, row[PerfKeys.UP_CONTENT_GAP_MAX_MS]?.jsonPrimitive?.long) },
                { assertEquals(45_000L, row[PerfKeys.OUT_GAP_MAX_MS]?.jsonPrimitive?.long) },
            )
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `a cancelled paced socket write still publishes its row and preserves cancellation`() = runBlocking {
        val rig = Rig(tmp, "paced-write-cancel")
        val emitter = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
        val cancellation = CancellationException("synthetic cancelled socket")
        var writes = 0
        val channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { if (++writes > 1) throw cancellation }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(false),
        )
        val drive = rig.drive(emitter).copy(channel = channel)
        val pacing = channel.launchPacer(
            this,
            kotlinx.coroutines.Job(),
            Ticker { false },
            ElapsedClock { 5L },
            splice.head.wire.LostClient("synthetic", {}),
        )
        try {
            channel.writeMutex.withLock {
                repeat(2) {
                    channel.timedClientWrite(
                        "event: content_block_delta\ndata: {\"delta\":{\"type\":\"text_delta\",\"text\":\"synthetic\"}}\n\n",
                        drive.perf,
                        ElapsedClock { 5L },
                    )
                }
            }
            pacing.cancel()
            var thrown: CancellationException? = null
            try {
                rig.telemetry.recordPerf(drive, "cancelled")
            } catch (failure: CancellationException) {
                thrown = failure
            }
            assertTrue(
                generateSequence<Throwable>(thrown) { it.cause }.any { it === cancellation },
                "coroutine stack recovery may wrap the cancellation, but must retain its original cause",
            )
            assertTrue(AsyncFileIo.drain())
            val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).single()).jsonObject
            assertEquals(1L, row.getValue(PerfKeys.FRAMES_OUT).jsonPrimitive.long)
        } finally {
            pacing.cancel()
            drive.slot.release()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnFinishTest {

    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
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

    private suspend fun finishAndReadTurnLine(tag: String, watchdog: TurnWatchdog): String {
        val rig = Rig(tmp, tag)
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter, watchdog)
        try {
            rig.finish.finishTurn(
                drive,
                TurnOutcome.Failure(
                    "upstream stalled",
                    cause = FailureCause.UPSTREAM_STALLED,
                    phase = FailurePhase.MID_OUTPUT,
                ),
            )
        } finally {
            drive.slot.release()
        }
        return rig.logs.first()
    }

    /** V4-117's perf-row pin: the CAUSE the taxonomy named and the ATTEMPT COUNT the retry loop
     *  stamped both reach the JSONL row, and the row still carries the outcome tag the operator
     *  already greps — the two fields are an addition to the row, never a replacement for it. */
    @Test
    fun `a failure puts its cause and the loop attempt count on the perf row - V4-117`() = runBlocking {
        val rig = Rig(tmp, "perf-cause")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            rig.finish.finishTurn(
                drive,
                TurnOutcome.Failure(
                    "upstream stalled",
                    cause = FailureCause.UPSTREAM_STALLED,
                    phase = FailurePhase.MID_OUTPUT,
                    layers = 3,
                ),
            )
        } finally {
            drive.slot.release()
        }
        AsyncFileIo.drain()
        val row = Files.readAllLines(rig.perfFile).last()
        assertTrue("\"cause\":\"UPSTREAM_STALLED\"" in row, "the cause must ride the row: $row")
        assertTrue("\"layers\":3" in row, "the loop's attempt count must ride the row: $row")
        assertTrue("\"outcome\":" in row, "the greppable outcome tag must survive: $row")
    }

    @Test
    fun `only a code-mode response without an upstream attempt marks a local step`() = runBlocking {
        for (attempts in listOf(0L, 1L)) {
            val rig = Rig(tmp, "local-step-$attempts")
            val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
            val drive = rig.drive(emitter)
            drive.perf.setCount(PerfKeys.ATTEMPTS, attempts)
            try {
                rig.finish.finishTurn(
                    drive,
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(localStep = true),
                        bodyText = "done",
                        messageClosed = true,
                    ),
                )
                assertEquals(attempts == 0L, drive.perf.snapshot().counters[PerfKeys.LOCAL_STEP] == 1L)
            } finally {
                drive.slot.release()
            }
        }
    }

    @Test
    fun `a divergent result fallback marks its upstream perf row`() = runBlocking {
        val rig = Rig(tmp, "code-mode-divergence")
        val drive = rig.drive(CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } }))
        drive.perf.setCount(PerfKeys.ATTEMPTS, 1)
        try {
            rig.finish.finishTurn(
                drive,
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(codeModeDiverged = true),
                    bodyText = "served upstream",
                    messageClosed = true,
                ),
            )
            assertEquals(1L, drive.perf.snapshot().counters[PerfKeys.CODE_MODE_DIVERGENCE])
        } finally {
            drive.slot.release()
        }
        assertTrue(AsyncFileIo.drain())
        val row = Files.readAllLines(rig.perfFile).last()
        assertTrue("\"code_mode_divergence\":1" in row, row)
    }

    @Test
    fun `a dead client still records a divergent branch on its connection-reset row`() = runBlocking {
        val rig = Rig(tmp, "code-mode-divergence-reset")
        val collecting = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val failing = object : TurnTerminal by collecting {
            override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
                throw IOException("client closed")
            }
        }
        val drive = rig.drive(failing)
        var torn = false
        try {
            rig.finish.finishTurn(
                drive,
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(codeModeDiverged = true),
                    bodyText = "served upstream",
                    messageClosed = true,
                ),
            )
        } catch (_: IOException) {
            torn = true
        } finally {
            drive.slot.release()
        }
        assertTrue(torn, "the terminal actually failed before the ordinary finish path")
        rig.telemetry.recordPerf(drive, splice.core.turn.CONN_RESET_OUTCOME)
        assertEquals(1L, drive.perf.snapshot().counters[PerfKeys.CODE_MODE_DIVERGENCE])
        assertTrue(AsyncFileIo.drain())
        assertTrue("\"code_mode_divergence\":1" in Files.readAllLines(rig.perfFile).last())
    }

    /** Guards the existing short turn-line renderer for an operational failure reason. */
    @Test
    fun `worker failure reason survives the short daemon turn line`() = runBlocking {
        val rig = Rig(tmp, "worker-cause")
        val drive = rig.drive(CollectingTerminal("gpt-6.1-sol", UsagePayloadBuilder { buildJsonObject { } }))
        try {
            rig.finish.finishTurn(
                drive,
                TurnOutcome.Failure(
                    "code-mode runtime failed: IllegalStateException: worker pool exhausted; " +
                        "accepted results=3; source was not rerun",
                    cause = FailureCause.CODE_MODE_PROTOCOL,
                    phase = FailurePhase.MID_OUTPUT,
                ),
            )
        } finally {
            drive.slot.release()
        }
        assertTrue("IllegalStateException: worker pool exhausted" in rig.logs.first(), rig.logs.first())
    }

    @Test
    fun `a fired mid-output idle watchdog reaches the rendered turn line`() = runBlocking {
        val ticks = VirtualTicks()
        val watchdog = TurnWatchdog(
            WatchdogBudget(10.seconds, 100.milliseconds, 30.seconds),
            clock = ticks.clock,
            ticker = ticks.ticker,
        )
        val slot = InflightGate(LiveLimit { 1 }, clock = ticks.clock).admittedSlot()
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

        assertTrue("watchdog=progress-timeout(idle=500ms elapsed=500ms)" in line, line)
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
        // V4-42: the empty_model ending is OVERLOADED now (retried with backoff), so the collect
        // path's status is 529, not the api_error 502 it carried when this pin was written.
        assertEquals(529, emitter.httpStatus(), "the client must have received the empty-model error")
        assertEquals(1L, rig.health.snapshot().localOrigin, "the downgrade must reach head health")
        assertTrue(rig.logs.any { it.contains("finish-degraded") }, "the downgrade must reach the log")
        AsyncFileIo.drain() // perf rows are appended asynchronously
        assertTrue(Files.readString(rig.perfFile).contains("empty_model"), "perf keeps the honest tag")
    }

    @Test
    fun `closed empty messages stay clean while empty model and compact errors stay degraded`() = runBlocking {
        for (tag in listOf(OutcomeTag.EMPTY_MODEL, OutcomeTag.EMPTY_COMPACT, OutcomeTag.EMPTY_MESSAGE)) {
            val rig = Rig(tmp, "ending-${tag.wire}")
            val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
            val original = rig.drive(emitter)
            val drive = original.copy(meta = original.meta.copy(compact = tag == OutcomeTag.EMPTY_COMPACT))
            val degraded = tag != OutcomeTag.EMPTY_MESSAGE
            try {
                rig.finish.finishTurn(
                    drive,
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(),
                        messageClosed = tag == OutcomeTag.EMPTY_MESSAGE,
                    ),
                )
                assertEquals(if (degraded) 529 else 200, emitter.httpStatus(), tag.wire)
                assertEquals(if (degraded) 1L else 0L, rig.health.snapshot().localOrigin, tag.wire)
                assertEquals(degraded, rig.logs.any { it.contains("finish-degraded") }, tag.wire)
                assertTrue(AsyncFileIo.drain())
                assertTrue(Files.readString(rig.perfFile).contains("\"outcome\":\"${tag.wire}\""), tag.wire)
            } finally {
                drive.slot.release()
            }
        }
    }

    @Test
    fun `client abandonment does not enter success degradation accounting`() = runBlocking {
        val rig = Rig(tmp, "ending-abandoned")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            rig.finish.finishTurn(drive, TurnOutcome.ClientAbandoned())
            assertEquals(0L, rig.health.snapshot().localOrigin)
            assertTrue(rig.logs.none { it.contains("finish-degraded") })
            assertTrue(AsyncFileIo.drain())
            assertTrue(Files.readString(rig.perfFile).contains("\"outcome\":\"${OutcomeTag.CLIENT_ABORT.wire}\""))
        } finally {
            drive.slot.release()
        }
    }

    /** Blocker #5: completed raw posts are a cancellation prefix. The normal final aggregate already
     *  includes that prefix, so finish must replace it rather than adding it again. */
    @Test
    fun `a normal aggregate replaces the raw cancellation prefix`() = runBlocking {
        val rig = Rig(tmp, "usage-aggregate")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            drive.recordRawRound(
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 10, reasoningTokens = 1),
                ),
            )
            drive.recordRawRound(
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 200, outputTokens = 5, cachedTokens = 20, reasoningTokens = 2),
                ),
            )

            rig.finish.finishTurn(
                drive,
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 200, outputTokens = 8, cachedTokens = 20, reasoningTokens = 3),
                ),
            )

            val counters = drive.perf.snapshot().counters
            assertEquals(200L, counters["in_tokens"], "the final aggregate owns cumulative input")
            assertEquals(20L, counters["cached_tokens"], "the final aggregate owns the latest cache count")
            assertEquals(8L, counters["out_tokens"], "completed raw outputs must not be added twice")
            assertEquals(8, rig.usageStore.readState().outputTokens5h)
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `cancellation stamps the completed raw prefix once with cumulative accounting`() = runBlocking {
        val rig = Rig(tmp, "usage-cancelled-prefix")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            drive.recordRawRound(
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 10, reasoningTokens = 1),
                ),
            )
            drive.recordRawRound(
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 200, outputTokens = 5, cachedTokens = 20, reasoningTokens = 2),
                ),
            )

            rig.usageStamp.stampKnownOnCancellation(drive)
            rig.usageStamp.stampKnownOnCancellation(drive)

            val counters = drive.perf.snapshot().counters
            assertEquals(200L, counters["in_tokens"], "input is the latest completed raw round")
            assertEquals(20L, counters["cached_tokens"], "cache is the latest completed raw round")
            assertEquals(8L, counters["out_tokens"], "output accrues across completed raw rounds")
            assertEquals(8, rig.usageStore.readState().outputTokens5h, "the shared stamp must be once-only")
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `a cancelled success stamp still commits its claimed usage`() = runBlocking {
        val rig = Rig(tmp, "usage-success-cancelled")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            val stampJob = launch {
                cancel(CancellationException("cancel between terminal and usage write"))
                rig.usageStamp.stampSuccess(
                    drive,
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(inputTokens = 100, outputTokens = 7, cachedTokens = 4),
                    ),
                )
            }
            stampJob.join()

            assertEquals(7, rig.usageStore.readState().outputTokens5h)
            assertEquals(7L, drive.perf.snapshot().counters["out_tokens"])
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `cancellation without code-mode raw rounds leaves ordinary usage counters absent`() = runBlocking {
        val rig = Rig(tmp, "usage-cancelled-disabled")
        val emitter = CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject { } })
        val drive = rig.drive(emitter)
        try {
            rig.usageStamp.stampKnownOnCancellation(drive)

            val counters = drive.perf.snapshot().counters
            assertTrue("in_tokens" !in counters)
            assertTrue("out_tokens" !in counters)
            assertTrue("cached_tokens" !in counters)
            assertEquals(0, rig.usageStore.readState().outputTokens5h)
        } finally {
            drive.slot.release()
        }
    }
}

// NEW: detached-drive finalization survives a synthetic recording OOM without retaining a dead replay.
package splice.head.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.head.HeadHealthCounters
import splice.head.TestResponsesProvider
import splice.head.compaction.CompactionRecordings
import splice.head.compaction.CompactionReplay
import splice.head.headDeps
import splice.head.turn.stream.PendingSse
import splice.head.wire.ClientChannel
import splice.head.wire.FrameRecording
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.SseEmitterFactory
import splice.upstream.BuiltTurn
import splice.upstream.LifecycleScope
import splice.upstream.ProviderTuning
import splice.upstream.RoundInterceptor
import splice.upstream.TurnEnd
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.seconds

class TurnStreamerCleanupTest {
    @Test
    @Timeout(20)
    fun `a recording OOM releases the slot and removes the dead replay`(@TempDir tmp: Path) = runBlocking {
        rig(tmp).use { rig ->
            val recording = FrameRecording()
            val failure = OutOfMemoryError("synthetic recording completion failure")
            failCompletion(recording, failure)
            rig.run(recording)
            assertSame(failure, withTimeout(5_000) { rig.failure.await() })
            assertTrue(rig.drive.emitter.endedCleanly, "the replay would otherwise have been kept")
            assertTrue(recording.isComplete, "a failed completion settles the recording as torn")
            assertFalse(recording.isWhole)
            assertTrue(rig.handedOff.get())
            assertEquals(0, rig.gate.snapshot().inflight, "recording failure must not leak the handed-off slot")
            assertNull(rig.replay.lookup(rig.key), "finish must drop the failed recording with keep=false")
            assertNull(rig.replay.lookup(rig.key), "an identical retry must not follow the dead drive")
        }
    }

    @Test
    @Timeout(20)
    fun `recording failure preserves the first error and suppresses slot release failure`(@TempDir tmp: Path) =
        runBlocking {
            rig(tmp).use { rig ->
                val recording = FrameRecording()
                val first = OutOfMemoryError("synthetic recording completion failure")
                val later = IllegalStateException("synthetic slot release failure")
                failCompletion(recording, first)
                rig.failRelease(later)
                rig.run(recording)
                val actual = withTimeout(5_000) { rig.failure.await() }
                assertSame(first, actual)
                assertEquals(listOf(later), actual.suppressed.toList())
                assertEquals(0, rig.gate.snapshot().inflight)
                assertNull(rig.replay.lookup(rig.key))
            }
        }

    @Test
    @Timeout(20)
    fun `drive failure remains primary through recording and slot cleanup failures`(@TempDir tmp: Path) =
        runBlocking {
            val first = OutOfMemoryError("synthetic drive failure")
            rig(tmp, first).use { rig ->
                val recording = FrameRecording()
                val completing = OutOfMemoryError("synthetic recording completion failure")
                val releasing = IllegalStateException("synthetic slot release failure")
                failCompletion(recording, completing)
                rig.failRelease(releasing)
                rig.run(recording)
                val actual = withTimeout(5_000) { rig.failure.await() }
                assertSame(first, generateSequence(actual) { it.cause }.last())
                assertEquals(listOf(completing, releasing), actual.suppressed.toList())
                assertEquals(0, rig.gate.snapshot().inflight)
                assertNull(rig.replay.lookup(rig.key))
            }
        }

    @Test
    @Timeout(20)
    fun `a replay persistence failure still returns the permit and preserves its throwable`(@TempDir tmp: Path) =
        runBlocking {
            val first = OutOfMemoryError("synthetic replay persistence failure")
            val later = IllegalStateException("synthetic slot release failure")
            val recordings = object : CompactionRecordings {
                override fun save(key: String, frames: List<String>): Unit = throw first
                override fun load(key: String): List<String>? = null
                override fun remove(key: String) = Unit
            }
            rig(tmp, recordings = recordings).use { rig ->
                val recording = FrameRecording()
                rig.failRelease(later)
                rig.run(recording)
                val actual = withTimeout(5_000) { rig.failure.await() }
                assertSame(first, actual)
                assertEquals(listOf(later), actual.suppressed.toList())
                assertTrue(recording.isComplete)
                assertTrue(recording.isWhole, "the replay persistence boundary was reached")
                assertEquals(0, rig.gate.snapshot().inflight)
            }
        }

    @Test
    @Timeout(20)
    fun `a launch failure after handoff returns ownership and tears the recording`(@TempDir tmp: Path) =
        runBlocking {
            rig(tmp).use { rig ->
                val recording = FrameRecording()
                val first = OutOfMemoryError("synthetic launch failure")
                rig.failLaunch(first)
                val actual = try {
                    rig.run(recording)
                    null
                } catch (failure: OutOfMemoryError) {
                    failure
                }
                assertSame(first, actual)
                assertTrue(rig.handedOff.get())
                assertEquals(0, rig.gate.snapshot().inflight, "launch must return handed-off ownership")
                assertNull(rig.replay.lookup(rig.key))
                assertTrue(recording.isComplete)
                assertFalse(recording.isWhole)
            }
        }

    @Test
    @Timeout(20)
    fun `an attached follower drains frames and ends torn when completion fails`(@TempDir tmp: Path) =
        runBlocking {
            rig(tmp).use { rig ->
                val recording = FrameRecording()
                val first = OutOfMemoryError("synthetic follower completion failure")
                failCompletion(recording, first)
                val frames = ArrayList<String>()
                val follower = async(start = CoroutineStart.UNDISPATCHED) { recording.follow { frames.add(it) } }
                try {
                    rig.run(recording)
                    assertSame(first, withTimeout(5_000) { rig.failure.await() })
                    assertFalse(withTimeout(1_000) { follower.await() }, "an attached retry ends torn")
                    assertEquals(recording.frames(), frames, "every recorded frame still reaches the follower")
                } finally {
                    follower.cancel()
                }
            }
        }

    @Test
    @Timeout(20)
    fun `head stop remains cancellation and logs its fatal cleanup and finish`(@TempDir tmp: Path) =
        runBlocking {
            rig(tmp, awaitStop = true).use { rig ->
                val recording = FrameRecording()
                val fatal = OutOfMemoryError("synthetic cancelled cleanup failure")
                failCompletion(recording, fatal)
                val running = async { rig.run(recording) }
                rig.stop()
                running.await()
                val ending = withTimeout(5_000) { rig.ending.await() }
                assertTrue(ending is CancellationException, "cleanup must not replace head-stop cancellation")
                // Job cancellation reports its initiating cause, not the recovered body exception.
                // The fatal-cleanup diagnostic below observes the body's actual suppressed error.
                assertFalse(rig.failure.isCompleted, "cancellation never invokes the crash handler")
                assertTrue(rig.logs.any { it.contains("detached compaction ended without a terminal frame") })
                assertTrue(
                    rig.logs.any { it.contains("fatal cleanup") },
                    "suppressed fatal cleanup must be observable",
                )
                assertFalse(
                    rig.logs.any { it.contains("synthetic cancelled cleanup failure") },
                    "no failure text is logged",
                )
                assertEquals(0, rig.gate.snapshot().inflight)
                assertNull(rig.replay.lookup(rig.key))
            }
        }

    @Test
    @Timeout(20)
    fun `one reused OOM remains primary through every cleanup stage`(@TempDir tmp: Path) =
        runBlocking {
            val shared = SharedOom()
            rig(tmp, shared).use { rig ->
                val recording = FrameRecording()
                failCompletion(recording, shared)
                rig.failRelease(shared)
                rig.run(recording)
                val actual = withTimeout(5_000) { rig.failure.await() }
                assertSame(shared, generateSequence(actual) { it.cause }.last())
                assertTrue(actual.suppressed.isEmpty(), "a shared throwable must never suppress itself")
                assertEquals(0, rig.gate.snapshot().inflight)
                assertNull(rig.replay.lookup(rig.key))
            }
        }

    @Test
    @Timeout(20)
    fun `a dispatcher that queues then throws cannot drive after launch ownership was returned`(@TempDir tmp: Path) =
        runBlocking {
            rig(tmp).use { rig ->
                val recording = FrameRecording()
                val first = SharedOom()
                val queued = rig.queueThenFail(first)
                val actual = runCatching { rig.run(recording) }.exceptionOrNull()
                assertSame(first, generateSequence(actual) { it.cause }.last())
                assertEquals(0, rig.gate.snapshot().inflight)
                assertEquals(1, rig.releases.get())
                queued.run()
                assertEquals(0, recording.size, "a losing queued body must not emit any frame: ${rig.logs}")
                assertEquals(0, rig.drives.get(), "a queued body that lost ownership must never drive upstream")
                val settled = rig.gate.snapshot()
                assertEquals(1L, settled.acquired)
                assertEquals(1L, settled.released, "the real gate returns exactly its one acquired permit")
                assertEquals(1, rig.releases.get(), "the actual counted slot returns exactly once")
                assertNull(rig.replay.lookup(rig.key))
                assertTrue(recording.isComplete)
                assertFalse(recording.isWhole)
            }
        }

    /** Extra instance state prevents coroutine recovery from cloning the JVM-style shared error. */
    private class SharedOom : OutOfMemoryError("synthetic reused OOM") {
        val identity = Any()
    }

    private suspend fun rig(
        tmp: Path,
        driveFailure: Throwable? = null,
        recordings: CompactionRecordings? = null,
        awaitStop: Boolean = false,
    ): Rig {
        val gate = InflightGate({ 1 })
        val slot = (gate.acquire() as InflightGate.Admission.Acquired).slot
        return Rig(tmp, gate, slot, driveFailure, recordings, awaitStop)
    }

    /** Replace only the recording's completion CAS, without consuming or exhausting real heap. */
    private fun failCompletion(recording: FrameRecording, failure: Throwable) {
        val field = FrameRecording::class.java.getDeclaredField("progress").apply { isAccessible = true }
        field.set(recording, failingCompletion(field.get(recording) as MutableStateFlow<*>, failure))
    }

    /** The progress flow's own state type, kept generic: the test reads it reflectively and never names it. */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private fun <S> failingCompletion(progress: MutableStateFlow<S>, failure: Throwable): MutableStateFlow<S> {
        val initial = checkNotNull(progress.value)
        val state = MutableStateFlow(progress.value)
        val completing = initial.javaClass.getDeclaredField("complete").apply { isAccessible = true }
        return object : MutableStateFlow<S> by state {
            override fun compareAndSet(expect: S, update: S): Boolean {
                if (completing.getBoolean(update)) throw failure
                return state.compareAndSet(expect, update)
            }
        }
    }

    private class Rig(
        tmp: Path,
        val gate: InflightGate,
        slot: InflightGate.Slot,
        driveFailure: Throwable?,
        recordings: CompactionRecordings?,
        awaitStop: Boolean,
    ) : AutoCloseable {
        val failure = CompletableDeferred<Throwable>()
        val ending = CompletableDeferred<Throwable?>()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val entered = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        val handedOff = AtomicBoolean(false)
        val drives = AtomicInteger()
        val releases = AtomicInteger()

        init {
            slot.onRelease(TurnEnd { releases.incrementAndGet() })
        }
        val key = "synthetic-cleanup-retry"
        val replay = CompactionReplay(recordings, clock = ElapsedClock { 0L })
        private val scope = LifecycleScope(ProcessDispatchers().background())
        private val upstream = UpstreamClient(totalTimeoutMs = 1_000, maxRetries = 0)
        private val clock = ElapsedClock { 0L }
        private val provider = TestResponsesProvider(
            ProviderTuning(
                key = "cleanup-test",
                label = "cleanup test",
                catalog = ModelCatalog(
                    discoveryPrefix = "cleanup-test--",
                    models = listOf(ModelEntry("synthetic-model", "Synthetic", contextWindow = 100)),
                    defaultContextWindow = 100,
                ),
                pinnedModel = "synthetic-model",
                auth = object : RefreshableAuthProvider {
                    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token")
                    override suspend fun refresh(): Credentials = credentials()
                    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
                },
                baseUrl = "http://127.0.0.1:9",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 20.seconds),
            ),
            showReasoning = ReasoningDisplay.OFF,
            replayReasoning = false,
            configEffort = "high",
            configSummary = null,
        )
        private val deps = headDeps(tmp = tmp, upstream = upstream, gate = gate, log = { logs.add(it) })
        private val factory = TurnDriveFactory(provider, deps, HeadHealthCounters())
        private val perf = TurnPerf(clock)
        private val inputs = TurnInputs(
            built = BuiltTurn(
                Json.parseToJsonElement("""{"model":"synthetic-model","input":[]}""").jsonObject,
                TurnMeta(
                    compact = true,
                    showReasoning = ReasoningDisplay.OFF,
                    stream = true,
                    originalModel = "synthetic-model",
                    upstreamModel = "synthetic-model",
                    clientMaxTokens = 100,
                    effort = "high",
                    summary = null,
                    budgetTokens = null,
                    sessionId = "synthetic-session",
                ),
                roundInterceptor = RoundInterceptor { _, sink, _ ->
                    drives.incrementAndGet()
                    driveFailure?.let { throw it }
                    if (awaitStop) {
                        entered.complete(Unit)
                        stopped.await()
                    }
                    val index = sink.openText()
                    sink.textDelta(index, "synthetic complete answer")
                    sink.closeBlock(index)
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        emittedText = true,
                        usage = Usage(),
                        bodyText = "synthetic complete answer",
                        messageClosed = true,
                    )
                },
            ),
            slot = slot,
            t0 = 0L,
            perf = perf,
            markHandedOff = HandoffMark { handedOff.set(true) },
            trace = null,
        )
        private val channel = ClientChannel(
            ImmediateSseWriter({}, {}),
            Mutex(),
            AtomicBoolean(false),
            detached = AtomicBoolean(true),
        )
        private var activeRecording: FrameRecording? = null
        val drive = factory.assembleDrive(
            inputs,
            SseEmitterFactory().create(
                write = { checkNotNull(activeRecording).append(it) },
                model = "synthetic-model",
                usagePayload = { JsonObject(emptyMap()) },
            ),
            channel,
        )
        private val streamer = TurnStreamer(
            provider,
            deps,
            factory,
            TurnDriver(provider, deps, replay).sealedDrive,
            replay,
            scope,
        ).also { streamer ->
            // Observe the actual detached job's rethrown error instead of replacing its drive.
            TurnStreamer::class.java.getDeclaredField("detachedContext").apply { isAccessible = true }
                .set(streamer, CoroutineExceptionHandler { _, error -> failure.complete(error) })
        }

        fun failLaunch(failure: Throwable) {
            TurnStreamer::class.java.getDeclaredField("detachedScope").apply { isAccessible = true }
                .set(
                    streamer,
                    object : CoroutineScope {
                        override val coroutineContext: kotlin.coroutines.CoroutineContext get() = throw failure
                    },
                )
        }

        fun queueThenFail(failure: Throwable): Runnable {
            var queued: Runnable? = null
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    if (queued == null) {
                        queued = block
                        throw failure
                    }
                    // Only the first worker-start signal fails; later work can run on an existing worker.
                    block.run()
                }
            }
            TurnStreamer::class.java.getDeclaredField("detachedScope").apply { isAccessible = true }
                .set(
                    streamer,
                    object : CoroutineScope {
                        override val coroutineContext = scope.coroutineContext + dispatcher
                    },
                )
            return Runnable { checkNotNull(queued).run() }
        }

        suspend fun stop() {
            entered.await()
            val job = checkNotNull(scope.coroutineContext[Job]).children.single()
            job.invokeOnCompletion { ending.complete(it) }
            streamer.stopDetached()
        }

        fun failRelease(failure: Throwable) {
            inputs.slot.onRelease(TurnEnd { throw failure })
        }

        suspend fun run(recording: FrameRecording) {
            activeRecording = recording
            val pending = PendingSse(perf, clock, null, recording)
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = TurnStreamer::class.java.getDeclaredMethod(
                    "driveDetachable",
                    TurnDrive::class.java,
                    TurnInputs::class.java,
                    String::class.java,
                    FrameRecording::class.java,
                    PendingSse::class.java,
                    Continuation::class.java,
                ).apply { isAccessible = true }
                val result = try {
                    method.invoke(streamer, drive, inputs, key, recording, pending, continuation)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
        }

        /** A finished drive writes its perf and compact rows under the temp dir through AsyncFileIo's
         *  one process-wide lane, so behind a backlog they land after teardown, while JUnit deletes the
         *  directory: run 37189271569 failed on DirectoryNotEmptyException for the root. The scope is
         *  joined first, so nothing submits after the drain, and the drain settles what was submitted. */
        override fun close() {
            try {
                runBlocking { checkNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
            } finally {
                inputs.slot.release()
            }
            check(AsyncFileIo.drain()) { "queued writes must settle before the temp directory is deleted" }
        }
    }
}

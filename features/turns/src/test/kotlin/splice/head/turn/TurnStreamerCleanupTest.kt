// NEW: detached-drive finalization survives a synthetic recording OOM without retaining a dead replay.
package splice.head.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancel
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
            assertFalse(recording.isComplete, "completion actually failed before settling the recording")
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

    private suspend fun rig(
        tmp: Path,
        driveFailure: Throwable? = null,
        recordings: CompactionRecordings? = null,
    ): Rig {
        val gate = InflightGate({ 1 })
        val slot = (gate.acquire() as InflightGate.Admission.Acquired).slot
        return Rig(tmp, gate, slot, driveFailure, recordings)
    }

    /** Replace only the recording's completion CAS, without consuming or exhausting real heap. */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private fun failCompletion(recording: FrameRecording, failure: Throwable) {
        val field = FrameRecording::class.java.getDeclaredField("progress").apply { isAccessible = true }
        val initial = checkNotNull((field.get(recording) as MutableStateFlow<*>).value)
        val state = MutableStateFlow(initial)
        val completing = initial.javaClass.getDeclaredField("complete").apply { isAccessible = true }
        field.set(
            recording,
            object : MutableStateFlow<Any> by state {
                override fun compareAndSet(expect: Any, update: Any): Boolean {
                    if (completing.getBoolean(update)) throw failure
                    return state.compareAndSet(expect, update)
                }
            },
        )
    }

    private class Rig(
        tmp: Path,
        val gate: InflightGate,
        slot: InflightGate.Slot,
        driveFailure: Throwable?,
        recordings: CompactionRecordings?,
    ) : AutoCloseable {
        val failure = CompletableDeferred<Throwable>()
        val handedOff = AtomicBoolean(false)
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
        private val deps = headDeps(tmp = tmp, upstream = upstream, gate = gate, log = {})
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
                    driveFailure?.let { throw it }
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

        override fun close() {
            scope.cancel()
            inputs.slot.release()
        }
    }
}

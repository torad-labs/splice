package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.upstream.sse.CustomToolSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.hours

class CodeModeLockOrderTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a nested sweep releases the outer key before ending a blocked source reader`() {
        val state = LiveState()
        val readerError = AtomicReference<Throwable?>()
        val sweepError = AtomicReference<Throwable?>()
        val reader = daemon(readerError) {
            runBlocking {
                state.round.switching.customToolSource(CustomToolSource.Delta(state.record.outerCallId, ";next"))
            }
        }
        val sweep = daemon(sweepError) {
            state.registry.changes.edit(state.record) {
                reader.start()
                waiting(reader)
                state.dead.set(true)
                state.registry.recordsFor(state.record.key)
                assertEquals(CodeModePhase.LOST, state.record.phase)
            }
        }
        sweep.start()
        sweep.join(WAIT_MILLIS)
        assertFalse(sweep.isAlive, "the sweep must release its outer registry locks before stopping the reader")
        reader.join(WAIT_MILLIS)
        assertFalse(reader.isAlive, "the reader must leave its key wait after the record is parked")
        assertNull(sweepError.get())
        assertTrue(readerError.get() is IllegalStateException, "the parked record must reject the reader's delta")
        assertNull(state.record.sourceEnd)
    }

    @Test
    fun `retry ownership waits for pending ids under the key without sweeping a dead session`() {
        val state = LiveState()
        state.dead.set(true)
        val failure = AtomicReference<Throwable?>()
        val owns = AtomicBoolean(false)
        val lookup = daemon(failure) {
            owns.set(state.round.owns(turn("client-pending")))
        }
        state.registry.changes.edit(state.record) {
            lookup.start()
            waiting(lookup)
            state.record.pending += CodeModePending(
                "runtime-pending", "client-pending", "Read", JsonObject(emptyMap()), true,
            )
        }
        lookup.join(WAIT_MILLIS)
        assertFalse(lookup.isAlive, "ownership must finish after the pending change releases its key")
        assertNull(failure.get())
        assertTrue(owns.get(), "ownership must see the pending id published before its key was released")
        assertEquals(
            CodeModePhase.ACTIVE,
            state.record.phase,
            "a retry ownership read must never reap the dead session",
        )
        assertTrue(state.record.sourceEnd != null, "the read must not end a source lease")
    }

    @Test
    fun `a late reader failure cannot recreate an expired record`() {
        val state = LiveState()
        state.registry.changes.edit(state.record) { it.updatedAt -= 25.hours.inWholeMilliseconds }
        state.registry.recordsFor(state.record.key)
        assertTrue(stateFiles.records().isEmpty(), "expiry must remove the retained record")
        state.registry.lose(state.record, "late source reader failure")
        assertTrue(
            stateFiles.records().isEmpty(),
            "a reader reaching its key after expiry must not recreate its record",
        )
    }

    @Test
    fun `admission releases its own key before ending a source blocked in observe`() {
        val state = LiveState()
        val readerError = AtomicReference<Throwable?>()
        val admissionError = AtomicReference<Throwable?>()
        val reader = daemon(readerError) {
            runBlocking {
                state.round.switching.customToolSource(CustomToolSource.Delta(state.record.outerCallId, ";next"))
            }
        }
        state.onDeath = Runnable {
            reader.start()
            waiting(reader)
        }
        state.dead.set(true)
        val admission = daemon(admissionError) {
            assertTrue(state.registry.add(CodeModeRecords.of(state.record.key, 1)))
        }
        admission.start()
        admission.join(WAIT_MILLIS)
        assertFalse(admission.isAlive, "admission must release its own key before stopping the old reader")
        reader.join(WAIT_MILLIS)
        assertFalse(reader.isAlive)
        assertNull(admissionError.get())
        assertTrue(readerError.get() is IllegalStateException)
    }

    @Test
    fun `deferred endings stay on their thread through nested and failing lock scopes`() {
        val state = LiveState()
        val first = CodeModeRegistryAccess(ReentrantLock(), CodeModeKeyLocks())
        val second = CodeModeRegistryAccess(ReentrantLock(), CodeModeKeyLocks())
        val rounds = ConcurrentHashMap<String, CodeModeLiveRound>()
        rounds["first"] = state.round
        rounds["second"] = state.round
        val deferred = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val expected = IllegalStateException("synthetic scope failure")
        val holder = daemon(failure) {
            first.withKey("first") {
                first.tryKey("first") {
                    CodeModeSourceEnds.defer(CodeModeSourceLease("first", state.round, rounds))
                }
                assertTrue(rounds.containsKey("first"), "a nested key scope must not end an outer scope's lease")
                deferred.countDown()
                check(release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS))
                throw expected
            }
        }
        holder.start()
        try {
            assertTrue(deferred.await(WAIT_MILLIS, TimeUnit.MILLISECONDS))
            second.tryKey("second") {
                CodeModeSourceEnds.defer(CodeModeSourceLease("second", state.round, rounds))
                assertTrue(rounds.containsKey("second"), "tryKey must defer its lease until the monitor releases")
            }
            assertFalse(rounds.containsKey("second"))
            assertTrue(rounds.containsKey("first"), "a different thread must not drain the first thread's lease")
        } finally {
            release.countDown()
            holder.join(WAIT_MILLIS)
        }
        assertFalse(holder.isAlive)
        assertTrue(failure.get() === expected, "the original failing scope must survive lease cleanup")
        assertFalse(rounds.containsKey("first"), "a failing outer scope must still end its own deferred lease")
    }

    private inner class LiveState {
        val dead = AtomicBoolean(false)
        var onDeath = Runnable {}
        private val config = CodeModeBridgeConfig(
            { error("no runtime is needed") },
            stateLocation(),
            sessionAlive = CodeModeSessionAlive {
                if (dead.get()) {
                    onDeath.run()
                    false
                } else {
                    true
                }
            },
        )
        val registry = CodexCodeModeRegistry(config, Json, 1.hours)
        val record = CodeModeRecords.of(CodeModeTurnIdentity().turnKey(turn()), 0, config.clock.millis()).also {
            it.sessionId = turn().sessionId
        }
        val round = CodeModeLiveRound(
            config,
            registry,
            CodexCodeModeWire(Json, {}),
            CodeModeStreamAdmission { record },
            RecordingSink(),
        )

        init {
            assertTrue(registry.add(record))
            record.phase = CodeModePhase.ACTIVE
            runBlocking {
                round.switching.customToolSource(CustomToolSource.Started(outer(record.outerCallId, source = "")))
            }
            val rounds = ConcurrentHashMap<String, CodeModeLiveRound>()
            rounds[record.id] = round
            record.sourceEnd = CodeModeSourceLease(record.id, round, rounds)
        }
    }

    private fun daemon(failure: AtomicReference<Throwable?>, action: Runnable): Thread = Thread(action).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
    }

    private fun waiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS)
        while (thread.isAlive && thread.state != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) break
            Thread.onSpinWait()
        }
        assertEquals(Thread.State.WAITING, thread.state, "the source operation must wait for the held record key")
    }
}

// why: each lock interleaving fails by name instead of leaving a blocked test process unbounded.
private const val WAIT_MILLIS = 5_000L

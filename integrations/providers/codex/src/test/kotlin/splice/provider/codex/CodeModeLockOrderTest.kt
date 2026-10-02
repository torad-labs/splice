package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.sse.CustomToolSource
import java.io.IOException
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
            readSource(state)
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
    fun `abandoning an expired owner cannot journal its interruption completion back into the store`() {
        val state = LiveState()
        state.registry.lose(state.record, "the previous head ended")
        val abandoned = state.registry.recordsFor(state.record.key).single()
        state.registry.changes.edit(abandoned) { it.updatedAt -= 25.hours.inWholeMilliseconds }
        state.registry.recordsFor(abandoned.key)
        assertTrue(stateFiles.records().isEmpty(), "the owner's key was expired before abandon completed")
        state.registry.lose(abandoned, "additional client content arrived")
        state.machine.interrupt(abandoned)
        assertTrue(stateFiles.records().isEmpty(), "abandon completion must not resurrect an expired owner")
    }

    @Test
    fun `a live changed record retries a failed purge without restoring the expired record beside it`() {
        val fail = AtomicBoolean()
        val state = LiveState(
            writer = CodeModeStateWrite { path, text ->
                if (fail.getAndSet(false)) throw IOException("synthetic failed purge")
                CodeModeStateJournal.write(path, text)
            },
        )
        val now = state.record.updatedAt
        state.registry.changes.edit(state.record) { it.updatedAt -= 25.hours.inWholeMilliseconds }
        fail.set(true)
        assertThrows(CodeModePersistenceException::class.java) { state.registry.recordsFor(state.record.key) }
        val next = CodeModeRecords.of(state.record.key, 1, now)
        assertTrue(state.registry.add(next))
        assertEquals(
            setOf(next.id),
            stateFiles.records().map { it.getValue("id").jsonPrimitive.content }.toSet(),
            "a cell delta must not skip the conversation purge still pending on disk",
        )
    }

    @Test
    fun `expiry releases every registry lock before ending its leased reader`() {
        val state = LiveState()
        endedAfterKey(state, "expiry") {
            state.registry.changes.edit(state.record) { it.updatedAt -= 25.hours.inWholeMilliseconds }
            state.registry.recordsFor(state.record.key)
        }
    }

    @Test
    fun `admission eviction releases every registry lock before ending its leased reader`() {
        val state = LiveState(CodeModeRetention(records = 1))
        endedAfterKey(state, "eviction") {
            state.record.phase = CodeModePhase.COMPLETED
            assertTrue(state.registry.add(CodeModeRecords.of(state.record.key, 1)))
        }
    }

    @Test
    fun `admission releases its own key before ending a source blocked in ownership`() {
        val state = LiveState()
        val readerError = AtomicReference<Throwable?>()
        val admissionError = AtomicReference<Throwable?>()
        val reader = daemon(readerError) {
            readSource(state)
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
    fun `a key holder can read the source while a retry holds lifecycle and waits for that key`() {
        val state = LiveState()
        runBlocking {
            state.round.switching.customToolSource(CustomToolSource.Delta(state.record.outerCallId, ";next"))
        }
        val cursor = state.round.source.view()
        val ownerError = AtomicReference<Throwable?>()
        val consumerError = AtomicReference<Throwable?>()
        val owner = daemon(ownerError) { state.round.owns(turn("source-consumer")) }
        val consumer = daemon(consumerError) {
            state.registry.changes.edit(state.record) {
                owner.start()
                waiting(owner)
                assertTrue(runBlocking { cursor.read() } is CodeModeSourcePart.Delta)
            }
        }
        consumer.start()
        consumer.join(WAIT_MILLIS)
        assertFalse(consumer.isAlive, "a key-held consumer must not acquire the retry's lifecycle monitor")
        owner.join(WAIT_MILLIS)
        assertFalse(owner.isAlive)
        assertNull(ownerError.get())
        assertNull(consumerError.get())
    }

    @Test
    fun `a captured prefix cannot overwrite terminal source persisted before its consumer gets the key`() {
        val state = LiveState()
        val prefix = "const value = 1;"
        val complete = "$prefix return value;"
        runBlocking {
            state.round.switching.customToolSource(CustomToolSource.Delta(state.record.outerCallId, prefix))
        }
        val cursor = state.round.source.view()
        val consumerError = AtomicReference<Throwable?>()
        val consumer = daemon(consumerError) { runBlocking { cursor.read() } }
        state.registry.changes.edit(state.record) {
            consumer.start()
            waiting(consumer)
            val call = outer(state.record.outerCallId, source = complete)
            val outcome = TurnOutcome.Success(false, false, Usage(), customCalls = listOf(call))
            val continuity = CodexCodeModeWire(Json, {}).continuity(outcome)
            state.registry.source.finish(state.record, call, continuity, Usage())
            state.round.source.complete(complete)
        }
        consumer.join(WAIT_MILLIS)
        assertFalse(consumer.isAlive)
        assertNull(consumerError.get())
        assertEquals(
            complete,
            state.record.source,
            "an older read prefix must not replace the certified complete source",
        )
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

    /** Ownership still takes lifecycle then key; the consumer read afterwards checks the revoked source fence. */
    private fun readSource(state: LiveState) = runBlocking {
        state.round.switching.customToolSource(CustomToolSource.Delta(state.record.outerCallId, ";next"))
        state.round.owns(turn("source-consumer"))
        state.round.source.view().read()
    }

    private fun endedAfterKey(state: LiveState, site: String, ending: Runnable) {
        val readerError = AtomicReference<Throwable?>()
        val endingError = AtomicReference<Throwable?>()
        val reader = daemon(readerError) {
            readSource(state)
        }
        val end = daemon(endingError) {
            state.registry.changes.edit(state.record) {
                reader.start()
                waiting(reader)
                ending.run()
            }
        }
        end.start()
        end.join(WAIT_MILLIS)
        assertFalse(end.isAlive, "$site must release the outer key and monitor before stopping the reader")
        reader.join(WAIT_MILLIS)
        assertFalse(reader.isAlive, "the revoked reader must leave its key wait")
        assertNull(endingError.get())
        assertTrue(readerError.get() is IllegalStateException, "the revoked record must reject the reader's delta")
        assertNull(state.record.sourceEnd)
    }

    private inner class LiveState(
        retention: CodeModeRetention = CodeModeRetention(),
        writer: CodeModeStateWrite? = null,
    ) {
        val dead = AtomicBoolean(false)
        var onDeath = Runnable {}
        private val config = CodeModeBridgeConfig(
            { error("no runtime is needed") },
            stateLocation(),
            retention = retention,
            sessionAlive = CodeModeSessionAlive {
                if (dead.get()) {
                    onDeath.run()
                    false
                } else {
                    true
                }
            },
        )
        val registry = CodexCodeModeRegistry(config, Json, 1.hours, writer)
        val machine = CodexCodeModeMachine(config, registry, CodexCodeModeValidation(config))
        val record = CodeModeRecords.of(CodeModeTurnIdentity().turnKey(turn()), 0, config.clock.millis()).also {
            it.sessionId = turn().sessionId
            it.source = ""
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

package splice.provider.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.turn.TurnOutcome
import splice.core.util.ElapsedClock
import splice.provider.codex.state.CodeModeCellRetention
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class CodeModeCellLeaseTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a temporary liveness gap starts its lease after the last positive sample`() {
        val fixture = LeaseFixture(dir)
        val record = fixture.park("alive", 0)
        fixture.alive["alive"] = true
        fixture.advance(29.minutes.inWholeMilliseconds)
        fixture.registry.retainedCells.sweep(null)
        fixture.alive["alive"] = null
        fixture.advance(31.minutes.inWholeMilliseconds)
        fixture.registry.retainedCells.sweep(null)
        assertEquals(CodeModePhase.ACTIVE, record.phase, "a missing poll is not thirty minutes of silence")
        fixture.advance(59.minutes.inWholeMilliseconds)
        fixture.registry.retainedCells.sweep(null)
        assertEquals(CodeModePhase.LOST, record.phase)
        fixture.registry.onHeadStop()
    }

    @Test
    fun `a forward wall clock jump does not expire a parked cell`() {
        val fixture = LeaseFixture(dir)
        val record = fixture.park("unknown", 0)
        fixture.wall.now = 1.hours.inWholeMilliseconds
        fixture.registry.retainedCells.sweep(null)
        assertEquals(CodeModePhase.ACTIVE, record.phase)
        fixture.registry.onHeadStop()
    }

    @Test
    fun `a backward wall clock jump does not extend the monotonic idle lease`() {
        val fixture = LeaseFixture(dir)
        val record = fixture.park("unknown", 0)
        fixture.wall.now = -1.hours.inWholeMilliseconds
        fixture.elapsed = 30.minutes.inWholeMilliseconds
        fixture.registry.retainedCells.sweep(null)
        assertEquals(CodeModePhase.LOST, record.phase)
        fixture.registry.onHeadStop()
    }

    @Test
    fun `capacity reclaims an alive parked cell without an idle floor`() {
        val fixture = LeaseFixture(dir)
        fixture.alive["alive"] = true
        val record = fixture.park("alive", 0)
        assertSame(record, fixture.registry.evictIdleCell(), "an alive-only pool must not wedge newcomers")
        assertEquals(CodeModePhase.LOST, record.phase)
        assertTrue("source was not rerun" in record.error.orEmpty())
        fixture.registry.onHeadStop()
    }

    @Test
    fun `capacity orders alive unknown and dead parked cells only by idle time`() {
        val fixture = LeaseFixture(dir)
        fixture.alive["alive"] = true
        val alive = fixture.park("alive", 0)
        val unknown = fixture.park("unknown", 1_000)
        val dead = fixture.park("dead", 2_000)
        fixture.alive["dead"] = false
        assertSame(alive, fixture.registry.evictIdleCell())
        assertSame(unknown, fixture.registry.evictIdleCell())
        assertSame(dead, fixture.registry.evictIdleCell())
        fixture.registry.onHeadStop()
    }

    @Test
    fun `both eligible conversations are ordered by idle time not insertion`() {
        val fixture = LeaseFixture(dir)
        val newer = fixture.park("newer", 2_000)
        val older = fixture.park("older", 1_000)
        fixture.advance(31.minutes.inWholeMilliseconds)
        assertSame(older, fixture.registry.evictIdleCell())
        assertEquals(CodeModePhase.ACTIVE, newer.phase)
        fixture.registry.onHeadStop()
    }

    @Test
    fun `a nested borrower protects the engine until its final release`() {
        val fixture = LeaseFixture(dir)
        val record = fixture.park("borrowed", 0)
        assertTrue(fixture.registry.retainedCells.acquire(record) != null)
        assertTrue(fixture.registry.retainedCells.acquire(record) != null)
        fixture.registry.retainedCells.release(record)
        fixture.alive["borrowed"] = false
        fixture.advance(31.minutes.inWholeMilliseconds)
        assertNull(fixture.registry.evictIdleCell())
        fixture.registry.retainedCells.release(record)
        assertSame(record, fixture.registry.evictIdleCell())
        fixture.registry.onHeadStop()
    }

    @Test
    fun `a new model key admits independently without displacing an executing engine`() {
        val fixture = LeaseFixture(dir)
        val conversation = CodeModeTurnIdentity().digest("first-key${0.toChar()}synthetic-conversation")
        val record = fixture.park("first-key", 0).also { it.conversationId = conversation }
        assertTrue(fixture.registry.retainedCells.acquire(record) != null)
        val next = CodeModeRecords.of("next-key", 1).also {
            it.sessionId = "first-key"
            it.conversationId = record.conversationId
            it.phase = CodeModePhase.STARTING
        }
        assertTrue(fixture.registry.add(next))
        assertEquals(CodeModePhase.ACTIVE, record.phase)
        fixture.registry.retainedCells.release(record)
        val later = CodeModeRecords.of("later-key", 2).also {
            it.sessionId = record.sessionId
            it.conversationId = record.conversationId
        }
        assertTrue(fixture.registry.add(later))
        assertEquals(CodeModePhase.LOST, record.phase)
        fixture.registry.onHeadStop()
    }

    @Test
    fun `a completed predecessor lease does not block the next model engine`() {
        val fixture = LeaseFixture(dir)
        val record = fixture.park("first-key", 0)
        assertTrue(fixture.registry.retainedCells.acquire(record) != null)
        fixture.registry.complete(record, "completed")
        val next = CodeModeRecords.of("next-key", 1).also { it.sessionId = "first-key" }
        assertTrue(fixture.registry.add(next), "completion already closed the predecessor's runtime cell")
        fixture.registry.retainedCells.release(record)
        fixture.registry.onHeadStop()
    }

    @Test
    fun `both eligible cells in one conversation evict the minimum idle time`() {
        val fixture = LeaseFixture(dir)
        // Explicit multi-cell state makes insertion order disagree with the eligible idle order.
        val older = CodeModeRecords.of("same", 0).apply {
            phase = CodeModePhase.ACTIVE
            cellIdleSince = 1_000
        }
        val newer = CodeModeRecords.of("same", 1).apply {
            phase = CodeModePhase.ACTIVE
            cellIdleSince = 2_000
        }
        val access = CodeModeRegistryAccess(java.util.concurrent.locks.ReentrantLock(), CodeModeKeyLocks())
        val cells = mutableMapOf<String, CodeModeCell>(older.id to LeaseCell(), newer.id to LeaseCell())
        val policy = CodeModeCellRetention(
            fixture.config,
            access,
            listOf(newer, older),
            cells,
            mutableMapOf(),
            CodeModeSessionEnd {},
        )
        fixture.advance(31.minutes.inWholeMilliseconds)
        assertSame(older, access.withKey("same") { policy.evict("same") })
        assertEquals(CodeModePhase.ACTIVE, newer.phase)
        fixture.registry.onHeadStop()
    }
}

class CodeModeResumeLeaseTest : CodeModeBridgeTestSupport() {
    @Test
    fun `an arriving callback protects its own over-age cell without pool pressure`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = completingRuntime()
        val manager = bridge(runtime, clock = clock)
        try {
            val sink = RecordingSink()
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, sink) {
                RoundResult.Outcome(outerOutcome())
            }
            clock.now += 31.minutes.inWholeMilliseconds
            val id = sink.tools.single().id
            val outcome = manager.interceptor(turn(id, "A"), null, false)
                .intercept(requestWithResult(id, "A"), RecordingSink()) {
                    RoundResult.Outcome(completedOutcome())
                }.turn()
            assertTrue(outcome is TurnOutcome.Success && !outcome.hasToolUse)
            assertEquals(2, runtime.cell.advances, "lookup must not kill the callback's own cell")
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `a stale active selection continues a record reclaimed before resume`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime, clock = clock)
        try {
            val sink = RecordingSink()
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, sink) {
                RoundResult.Outcome(outerOutcome())
            }
            val registry = manager.registry
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val record = registry.recordsFor(key).single()
            val id = sink.tools.single().id
            assertSame(record, registry.owner(record.key, "callback", setOf(id), emptySet()))
            deadSessions += record.sessionId.orEmpty()
            clock.now += 31.minutes.inWholeMilliseconds
            assertSame(record, registry.evictIdleCell())
            val context = CodeModeRunContext(
                turn(id, "A"),
                false,
                record.key,
                "callback",
                CodeModeRoundLink(
                    RecordingSink(),
                    upstreamPost(InterceptedRoundPost { RoundResult.Outcome(completedOutcome()) }),
                ),
            )
            val outcome = manager.resume.active(record, context, codeModeBody(requestWithResult(id, "A")))
            assertTrue(outcome is TurnOutcome.Success && !outcome.hasToolUse, outcome.toString())
            assertEquals(1, runtime.cell.advances, "reclamation never reruns the source")
            assertEquals(CodeModePhase.COMPLETED, record.phase)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `callback application holds a lease across a capacity reap race`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = completingRuntime()
        val manager = bridge(runtime, clock = clock)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val sink = RecordingSink()
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, sink) {
                RoundResult.Outcome(outerOutcome())
            }
            val registry = manager.registry
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val record = registry.recordsFor(key).single()
            val id = sink.tools.single().id
            assertSame(record, registry.owner(record.key, "callback", setOf(id), emptySet()))
            val results = object : AbstractList<CodeModeResult>() {
                override val size = 1
                override fun get(index: Int): CodeModeResult {
                    reached.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return CodeModeResult(id, "A")
                }
            }
            val post = upstreamPost(InterceptedRoundPost { RoundResult.Outcome(completedOutcome()) })
            val context = CodeModeRunContext(
                turn(id, "A").copy(toolResults = results),
                false,
                record.key,
                "callback",
                CodeModeRoundLink(RecordingSink(), post),
            )
            val request = async(Dispatchers.Default) {
                manager.resume.active(record, context, codeModeBody(requestWithResult(id, "A")))
            }
            assertTrue(reached.await(5, TimeUnit.SECONDS), "callback validation reached the latched race point")
            deadSessions += record.sessionId.orEmpty()
            clock.now += 31.minutes.inWholeMilliseconds
            try {
                assertNull(registry.evictIdleCell(), "the owner is borrowed until callback application finishes")
                assertFalse(runtime.cell.closed)
            } finally {
                release.countDown()
            }
            val outcome = request.await()
            assertTrue(outcome is TurnOutcome.Success && !outcome.hasToolUse, outcome.toString())
            assertEquals(2, runtime.cell.advances)
        } finally {
            release.countDown()
            manager.onHeadStop()
        }
    }

    private fun completingRuntime() = ScriptedRuntime(
        ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))),
    )
}

private class LeaseFixture(dir: Path) {
    val wall = LeaseClock(0)
    var elapsed = 0L
    val alive = mutableMapOf<String, Boolean?>()
    private var serial = 0
    val config = CodeModeBridgeConfig(
        { error("a lease fixture runs no runtime") },
        CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy")),
        clock = wall,
        cellLease = CodeModeCellLease(
            sessionAlive = CodeModeSessionAlive { alive[it] },
            clock = ElapsedClock { elapsed },
        ),
    )
    val registry = CodexCodeModeRegistry(config, Json, 1.hours)

    fun park(key: String, at: Long): CodeModeRecord {
        advance(at)
        val record = CodeModeRecords.of(key, serial++, wall.millis()).also { it.sessionId = key }
        assertTrue(registry.add(record))
        assertTrue(registry.attach(record, LeaseCell()))
        registry.retainedCells.release(record)
        return record
    }

    fun advance(at: Long) {
        elapsed = at
        wall.now = at
    }
}

private class LeaseCell : CodeModeCell {
    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = CodeModeStep.Completed("done")
    override fun close() = Unit
}

private class LeaseClock(var now: Long) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(now)
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
}

// NEW: default code-mode progress is resource-bounded, never cut at a fixed small record or call count.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import splice.core.turn.TurnOutcome
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CodeModeCountPolicyTest : CodeModeBridgeTestSupport() {
    private fun config() = CodeModeBridgeConfig(
        { error("a registry fixture does not open a runtime") },
        stateLocation(),
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

    private fun registry(config: CodeModeBridgeConfig = config()) =
        CodexCodeModeRegistry(config, Json { encodeDefaults = true }, 5.minutes)

    @Test
    fun `a default conversation keeps history beyond the old per conversation cap`() {
        val registry = registry()
        repeat(130) { n ->
            val record = CodeModeRecords.of("history", n, 1_000)
            assertTrue(registry.add(record))
            registry.complete(record, "done")
        }
        registry.turnStart.begin("history")
        assertEquals(130, registry.recordsFor("history").size)
    }

    @Test
    fun `the default head keeps small records beyond the old total count cap`() {
        val registry = registry()
        repeat(1_025) { n ->
            val record = CodeModeRecords.of("history", n, 1_000)
            assertTrue(registry.add(record))
            registry.complete(record, "done")
        }
        assertEquals(1_025, registry.recordsFor("history").size)
    }

    @Test
    fun `expiry evidence ages out by TTL after its record without a default count ceiling`() {
        val clock = MutableClock(1_000)
        val registry = registry(config().copy(clock = clock))
        val record = CodeModeRecords.of("history", 1, 1_000)
        assertTrue(registry.add(record))
        registry.complete(record, "done")
        clock.now += 25.hours.inWholeMilliseconds
        assertTrue(registry.recordsFor("history").isEmpty())
        assertTrue(registry.expiredHistory("history", record.progress.lastDigest, emptySet()))
        clock.now += 25.hours.inWholeMilliseconds
        assertFalse(registry.expiredHistory("history", record.progress.lastDigest, emptySet()))
        assertTrue(stateFiles.files().isEmpty(), "aged-out expiry markers leave no conversation file")
    }

    @Test
    fun `the timer purges expiry evidence after the last record with no further turn`() {
        val now = AtomicLong(1_000)
        val clock = object : Clock() {
            override fun instant(): Instant = Instant.ofEpochMilli(now.get())
            override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
        }
        val expired = CountDownLatch(1)
        val writer = CodeModeStateWrite { path, text ->
            splice.provider.codex.state.CodeModeStateJournal.write(path, text)
            if (text.contains("\"expiredAt\":")) expired.countDown()
        }
        val registry = CodexCodeModeRegistry(config().copy(clock = clock), Json, 25.milliseconds, writer)
        val record = CodeModeRecords.of("timer", 1, 1_000)
        assertTrue(registry.add(record))
        registry.complete(record, "done")
        now.addAndGet(25.hours.inWholeMilliseconds)
        assertTrue(expired.await(3, TimeUnit.SECONDS), "the record expires before its marker")
        now.addAndGet(25.hours.inWholeMilliseconds)
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3)) {
            while (stateFiles.files().isNotEmpty()) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    @Test
    fun `default callbacks continue beyond the old call count while explicit limits still reject`() {
        val config = config()
        val record = CodeModeRecords.of("history", 1, 1_000).also { it.progress.totalCalls = 64 }
        val calls = listOf(call("next", "Read"))
        assertNull(CodexCodeModeValidation(config).calls(record, setOf("Read"), calls))
        assertEquals(
            "code-mode call limit exceeded",
            CodexCodeModeValidation(config.copy(bounds = CodeModeScriptBounds(maxCalls = 64)))
                .calls(record, setOf("Read"), calls),
        )
    }

    @Test
    fun `a default parked cell advances beyond the old round count`() = runTest {
        val config = config()
        val registry = registry(config)
        val record = CodeModeRecords.of("history", 1, 1_000).also { it.progress.rounds = 32 }
        val cell = ScriptedCell(ArrayDeque(listOf(CodeModeStep.Completed("done"))))
        assertTrue(registry.add(record))
        assertTrue(registry.attach(record, cell))
        val outcome = CodexCodeModeMachine(config, registry, CodexCodeModeValidation(config))
            .advance(CodeModeAdvanceRequest(record, turn(), false, emptyList(), RecordingSink()))
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, cell.advances)
    }

    @Test
    fun `default upstream drive accepts more than thirty two immediate scripts`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque((1..40).map { ArrayDeque(listOf(CodeModeStep.Completed("done"))) }),
        )
        val manager = CodexCodeModeBridge(config().copy(runtimes = { runtime }))
        var posts = 0
        val outcome = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                posts++
                RoundResult.Outcome(if (posts <= 40) outerOutcome("outer-$posts") else completedOutcome())
            }.turn()
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(40, runtime.starts)
    }
}

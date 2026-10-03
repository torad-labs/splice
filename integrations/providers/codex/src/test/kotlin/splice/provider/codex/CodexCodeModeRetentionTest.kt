// NEW: V4-287 — a code-mode record keeps the model's reasoning summaries in plaintext, and README and
// SECURITY.md promise it goes 24 hours after its last use whether or not the head is used again. The
// registry swept only when it was built or a code-mode turn touched it, so an idle head kept a record
// for as long as the daemon stayed up; closing an idle cell and stopping the head each restarted the
// record's 24 hours.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import splice.upstream.codemode.CodeModeStep
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val START = 1_000L

/** A script source only the record holds; an expired record's history marker keeps none of it. */
private const val SOURCE = "kept-script-v4287"

class CodexCodeModeRetentionTest : CodeModeBridgeTestSupport() {

    @Test
    fun `an idle head's record leaves the disk within the sweep interval of its 24 hours`() = runTest {
        val clock = WallTime(START)
        val (bridge, state) = recording(clock, "idle", sweepInterval = 50.milliseconds)
        startScript(bridge)
        assertTrue(SOURCE in state.text(), "the script is recorded")

        clock.now = START + 24.hours.inWholeMilliseconds + 1
        awaitUntil("the record 24 hours past its last use left the disk, with no turn") {
            SOURCE !in state.text()
        }
    }

    @Test
    fun `closing a record's idle cell and stopping its head do not restart its 24 hours`() = runTest {
        val clock = WallTime(START)
        val (parked, parkedState) = recording(clock, "parked", sweepInterval = 50.milliseconds)
        startScript(parked)
        clock.now = START + 31.minutes.inWholeMilliseconds
        deadSessions += "session-a"
        awaitUntil("the timer closes the dead session's cell without a request-path reap") {
            parkedState.records().single()["error"]?.jsonPrimitive?.content?.contains("session ended") == true
        }
        clock.now = START + 24.hours.inWholeMilliseconds + 1
        touch(parked)
        assertFalse(SOURCE in parkedState.text(), "closing the idle cell restarted the record's 24 hours")

        clock.now = START
        deadSessions.clear()
        val (stopped, stoppedState) = recording(clock, "stopped")
        startScript(stopped)
        clock.now = START + 23.hours.inWholeMilliseconds
        stopped.onHeadStop()
        clock.now = START + 24.hours.inWholeMilliseconds + 1
        touch(stopped)
        assertFalse(SOURCE in stoppedState.text(), "a head stop restarted the record's 24 hours")
    }

    @Test
    fun `fifty live conversations with two MiB each fit the default head retention`() {
        val clock = WallTime(START)
        val state = CodeModeStateFiles(tempDir.resolve("fifty-live"))
        val registry = CodexCodeModeRegistry(
            CodeModeBridgeConfig(
                { error("no worker runs in a persistence budget test") },
                CodeModeStateLocation(state.dir, tempDir.resolve("legacy.json")),
                clock = clock,
                sessionAlive = CodeModeSessionAlive { true },
            ),
            Json { encodeDefaults = true },
            5.minutes,
        )
        val history = listOf(JsonPrimitive("x".repeat(2 * 1024 * 1024)))
        val threads = Executors.newFixedThreadPool(8)
        try {
            val starts = (1..50).map { n ->
                threads.submit {
                    val record = CodeModeRecords.of("live-$n", 1, START).copy(continuity = history)
                        .also { it.sessionId = "live-$n" }
                    assertTrue(registry.add(record))
                    registry.complete(record, "done")
                }
            }
            starts.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            threads.shutdownNow()
        }
        assertEquals(50, (1..50).count { registry.recordsFor("live-$it").isNotEmpty() })
    }

    @Test
    fun `a parked cell still belongs to its working session after thirty one minutes`() = runTest {
        val clock = WallTime(START)
        val state = CodeModeStateFiles(tempDir.resolve("working-session"))
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))))
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                { runtime },
                CodeModeStateLocation(state.dir, tempDir.resolve("working-session.json")),
                clock = clock,
                sessionAlive = CodeModeSessionAlive { true },
            ),
        )
        startScript(manager)
        clock.now = START + 31.minutes.inWholeMilliseconds
        touch(manager)
        assertFalse(runtime.cell.closed, "a quiet client is not evidence that its session died")
    }

    @Test
    fun `unknown sessions lose their parked cells after the idle bound without another turn`() = runTest {
        val clock = WallTime(START)
        val (manager, state) = recording(clock, "unknown-idle", sweepInterval = 50.milliseconds)
        try {
            startScript(manager)
            clock.now = START + 30.minutes.inWholeMilliseconds
            awaitUntil("unknown session's parked cell was durably lost") {
                state.records().single()["error"]?.jsonPrimitive?.content?.contains("idle") == true
            }
            val lost = state.records().single()
            assertEquals("LOST", lost.getValue("phase").jsonPrimitive.content)
            assertEquals(START, lost.getValue("updatedAt").jsonPrimitive.content.toLong())
            assertTrue(SOURCE in state.text(), "the record and its source remain no-rerun evidence")
        } finally {
            manager.onHeadStop()
        }
    }

    /** A bridge over its own state directory [name], whose one script waits on a client call that never returns. */
    private fun recording(
        clock: Clock,
        name: String,
        sweepInterval: Duration = 5.minutes,
    ): Pair<CodexCodeModeBridge, CodeModeStateFiles> {
        val state = CodeModeStateFiles(tempDir.resolve(name))
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))))
        val config = CodeModeBridgeConfig(
            { runtime },
            CodeModeStateLocation(state.dir, tempDir.resolve("$name.json")),
            clock = clock,
            cellClock = splice.core.util.ElapsedClock(clock::millis),
            log = LogSink { logLines += it },
            sessionAlive = CodeModeSessionAlive { id -> if (id in deadSessions) false else null },
        )
        return CodexCodeModeBridge(config, sweepInterval) to state
    }

    private suspend fun startScript(bridge: CodexCodeModeBridge) {
        bridge.interceptor(turn(), outer("outer-kept", SOURCE), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-kept") }
    }

    /** This conversation's turn sweeps only its own key; unrelated-key housekeeping runs on the timer. */
    private suspend fun touch(bridge: CodexCodeModeBridge) {
        bridge.interceptor(turn(sessionId = "session-a"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
    }

    /** Polls [done] with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within 10 s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    /** The wall clock the registry reads, moved by the test and read by the sweep's own thread. */
    private class WallTime(@Volatile var now: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
    }
}

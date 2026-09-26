// NEW: V4-287 — a code-mode record keeps the model's reasoning summaries in plaintext, and README and
// SECURITY.md promise it goes 24 hours after its last use whether or not the head is used again. The
// registry swept only when it was built or a code-mode turn touched it, so an idle head kept a record
// for as long as the daemon stayed up; closing an idle cell and stopping the head each restarted the
// record's 24 hours.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
        val (bridge, state) = recording(clock, "idle.json", sweepInterval = 50.milliseconds)
        startScript(bridge)
        assertTrue(SOURCE in Files.readString(state), "the script is recorded")

        clock.now = START + 24.hours.inWholeMilliseconds + 1
        awaitUntil("the record 24 hours past its last use left the disk, with no turn") {
            SOURCE !in Files.readString(state)
        }
    }

    @Test
    fun `closing a record's idle cell and stopping its head do not restart its 24 hours`() = runTest {
        val clock = WallTime(START)
        val (parked, parkedState) = recording(clock, "parked.json")
        startScript(parked)
        clock.now = START + 31.minutes.inWholeMilliseconds
        touch(parked)
        assertTrue(logLines.any { "closed after 31 min" in it }, "the idle cell was closed: $logLines")
        clock.now = START + 24.hours.inWholeMilliseconds + 1
        touch(parked)
        assertFalse(SOURCE in Files.readString(parkedState), "closing the idle cell restarted the record's 24 hours")

        clock.now = START
        val (stopped, stoppedState) = recording(clock, "stopped.json")
        startScript(stopped)
        clock.now = START + 23.hours.inWholeMilliseconds
        stopped.onHeadStop()
        clock.now = START + 24.hours.inWholeMilliseconds + 1
        touch(stopped)
        assertFalse(SOURCE in Files.readString(stoppedState), "a head stop restarted the record's 24 hours")
    }

    /** A bridge over its own state [file], whose one script waits on a client call that never returns. */
    private fun recording(
        clock: Clock,
        file: String,
        sweepInterval: Duration = 5.minutes,
    ): Pair<CodexCodeModeBridge, Path> {
        val state = tempDir.resolve(file)
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r1", "Read"))))))
        val config = CodeModeBridgeConfig({ runtime }, state, clock = clock, log = LogSink { logLines += it })
        return CodexCodeModeBridge(config, sweepInterval) to state
    }

    private suspend fun startScript(bridge: CodexCodeModeBridge) {
        bridge.interceptor(turn(), outer("outer-kept", SOURCE), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-kept") }
    }

    /** Another session's turn with no script: it runs the registry's sweep, as any code-mode turn does. */
    private suspend fun touch(bridge: CodexCodeModeBridge) {
        bridge.interceptor(turn(sessionId = "session-b"), null, disableParallel = false)
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

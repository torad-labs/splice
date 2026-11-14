package splice.app.instrumentation

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.observability.PerfTelemetry
import splice.app.sources.PerfSourceFiles
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import splice.usage.alerts.AlertStore
import splice.usage.perf.query.PerfQuery
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.cancellation.CancellationException

// why: how long the summary may wait for the JVM's shared work budget to have room; under runTest's own timeout.
private const val SUMMARY_WAIT_MS = 5_000L

// why: the pause between asks while the budget is busy; other work gives its room back within milliseconds.
private const val BUSY_POLL_MS = 50L

class PerfCancelledShutdownTest {
    @Test
    fun `cancelled caller closes telemetry owners and allows the next store owner`(@TempDir directory: Path) = runTest {
        val budget = WorkBudgetMark()
        val first = telemetry(directory)
        first.start()
        val until = System.currentTimeMillis()
        val room = PerfWorkBudget.snapshot()
        val reply = summary(first, until)
        // why: the budget is one total for the whole JVM, so a summary refused for room is no reply to own; the message
        // says what held the budget before it was asked and at its last refusal.
        val refused = reply["error"]?.let {
            "the summary was refused: $reply; the budget before it read $room, " +
                "and after it ${PerfWorkBudget.snapshot()}"
        }
        var cancellationPreserved = false
        launch {
            currentCoroutineContext().cancel()
            try {
                first.close()
            } catch (_: CancellationException) {
                cancellationPreserved = true
            }
        }.join()
        val schema = JsonScalars.str(reply, "schema_version")
        // why: other tests' owners fall off the JVM-wide total whenever they are collected (WorkBudgetMark), so the
        // whole total is no measure of this reply. What releasing it gives back is, and a collection only adds to that.
        val held = PerfWorkBudget.retainedBytes()
        PerfWorkBudget.release(reply)
        val givenBack = held - PerfWorkBudget.retainedBytes()
        assertNull(refused, refused)
        assertTrue(cancellationPreserved)
        assertEquals("1", schema)
        assertTrue(givenBack > 0, "the caller still owned its returned reply until it released it")
        budget.assertGivenBack()
        val replacement = telemetry(directory)
        try {
            replacement.start()
        } finally {
            replacement.close()
        }
        budget.assertGivenBack()
    }

    /** The summary, asked again while the shared budget is busy, as any caller does with a `memory_busy` answer. Other
     *  work in this JVM takes and gives back room while it runs: on 0.4.1's promotion run (Nov 14, 2026) the budget
     *  held 4.2 MiB just before the summary and had no room for its 4 MiB a moment later. */
    private fun summary(telemetry: PerfTelemetry, until: Long): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SUMMARY_WAIT_MS)
        var reply = telemetry.inspection.query.summary(PerfQuery(until - 60_000L, until, source = "history"))
        while (busy(reply) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(BUSY_POLL_MS))
            reply = telemetry.inspection.query.summary(PerfQuery(until - 60_000L, until, source = "history"))
        }
        return reply
    }

    private fun busy(reply: JsonObject): Boolean =
        (reply["error"] as? JsonObject)?.let { JsonScalars.str(it, "code") } == "memory_busy"

    private fun telemetry(directory: Path): PerfTelemetry {
        val paths = StatePaths(baseOverride = directory)
        return PerfTelemetry(
            ConfigService(paths, envReader = EnvReader { null }),
            setOf("one"),
            PerfSourceFiles(paths),
            AlertStore(directory.resolve("alerts.json")),
            LogSink {},
        )
    }
}

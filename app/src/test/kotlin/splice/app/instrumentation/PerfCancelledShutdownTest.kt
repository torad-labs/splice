package splice.app.instrumentation

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.coroutines.cancellation.CancellationException

class PerfCancelledShutdownTest {
    @Test
    fun `cancelled caller closes telemetry owners and allows the next store owner`(@TempDir directory: Path) = runTest {
        val budget = WorkBudgetMark()
        val first = telemetry(directory)
        first.start()
        val until = System.currentTimeMillis()
        val reply = first.inspection.query.summary(PerfQuery(until - 60_000L, until, source = "history"))
        var cancellationPreserved = false
        launch {
            currentCoroutineContext().cancel()
            try {
                first.close()
            } catch (_: CancellationException) {
                cancellationPreserved = true
            }
        }.join()
        try {
            assertTrue(cancellationPreserved)
            assertEquals("1", JsonScalars.str(reply, "schema_version"))
            assertTrue(PerfWorkBudget.retainedBytes() > budget.total, "the caller still owns its returned reply")
        } finally {
            PerfWorkBudget.release(reply)
        }
        budget.assertGivenBack()
        val replacement = telemetry(directory)
        try {
            replacement.start()
        } finally {
            replacement.close()
        }
        budget.assertGivenBack()
    }

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

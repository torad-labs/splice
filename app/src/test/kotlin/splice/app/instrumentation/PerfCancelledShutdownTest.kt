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
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import splice.usage.alerts.AlertStore
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

class PerfCancelledShutdownTest {
    @Test
    fun `cancelled caller closes telemetry owners and allows the next store owner`(@TempDir directory: Path) = runTest {
        val before = PerfWorkBudget.retainedBytes()
        val first = telemetry(directory)
        first.start()
        var cancellationPreserved = false
        launch {
            currentCoroutineContext().cancel()
            try {
                first.close()
            } catch (_: CancellationException) {
                cancellationPreserved = true
            }
        }.join()
        assertTrue(cancellationPreserved)
        assertEquals(before, PerfWorkBudget.retainedBytes())
        val replacement = telemetry(directory)
        try {
            replacement.start()
        } finally {
            replacement.close()
        }
        assertEquals(before, PerfWorkBudget.retainedBytes())
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

package splice.app.instrumentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.observability.PerfConfigHistory
import splice.app.observability.PerfConfigTracking
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Path

class PerfConfigurationAssemblyTest {
    @Test
    fun `per head gate and timing changes persist while next boot export settings do not relabel running owners`(
        @TempDir directory: Path,
    ) {
        val paths = StatePaths(baseOverride = directory)
        val config = ConfigService(paths, envReader = EnvReader { null })
        val history = PerfConfigHistory(paths.perfConfigurationFile)
        val tracking = PerfConfigTracking(config, setOf("a", "b"), "test-boot", history)
        try {
            tracking.start()
            val before = history.intervals(0, Long.MAX_VALUE).associate { it.head to it.revision }
            config.headLayer.set("a", mapOf("maxInflight" to "3"))
            tracking.drain()
            val after = history.intervals(
                0,
                Long.MAX_VALUE,
            ).filter { it.untilMs == null }.associate { it.head to it.revision }
            assertNotEquals(before["a"], after["a"])
            assertEquals(before["b"], after["b"])
            config.patch(mapOf("firstByteTimeoutMs" to 12345L))
            tracking.drain()
            val timed = history.intervals(
                0,
                Long.MAX_VALUE,
            ).filter { it.untilMs == null }.associate { it.head to it.revision }
            assertNotEquals(after["a"], timed["a"])
            config.patch(
                mapOf(
                    "telemetryPrometheusEnabled" to true,
                    "telemetryOtlpHeaders" to "{\"Authorization\":\"private\"}",
                ),
            )
            tracking.drain()
            assertEquals(
                timed,
                history.intervals(0, Long.MAX_VALUE).filter {
                    it.untilMs == null
                }.associate { it.head to it.revision },
            )
            history.persist(System.currentTimeMillis() + 1)
            PerfConfigHistory(paths.perfConfigurationFile).use { offline ->
                val intervals = offline.intervals(0, Long.MAX_VALUE)
                assertTrue(intervals.any { it.head == "a" && it.revision == before["a"] })
                assertTrue(intervals.all { it.untilMs != null && it.bootId == "test-boot" })
                assertTrue(intervals.none { it.revision?.contains("private") == true })
            }
        } finally {
            tracking.close()
        }
    }
}

package splice.diagnostics.doctor.v4371

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.daemonclient.DaemonProbe
import splice.daemonclient.TraceConfigProbe
import splice.diagnostics.doctor.CheckStatus
import splice.diagnostics.doctor.DoctorTraceChecks
import splice.topology.TopologyLoader
import java.nio.file.Path

class DoctorTraceRuntimeTest {
    @Test
    fun `running config parser refuses absent or nonboolean trace instead of guessing off`() {
        val probe = TraceConfigProbe()
        assertEquals(
            DaemonProbe.HeadTrace(true),
            probe.parse("""{"effective":{"trace":true}}"""),
        )
        assertEquals(
            DaemonProbe.HeadTrace(false),
            probe.parse("""{"effective":{"trace":false}}"""),
        )
        assertNull(probe.parse("""{"effective":{}}"""))
        assertNull(probe.parse("""{"effective":{"trace":"false"}}"""))
    }

    @Test
    fun `declared on but running off names no current writes and a pending restart`(@TempDir tmp: Path) {
        val row = rows(tmp, "true", running = false).single()
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("will write after the next restart"), row.detail)
        assertTrue(row.detail.contains("nothing is written now"), row.detail)
        assertFalse(row.detail.contains("writes its FULL"), row.detail)
        assertTrue(row.pendingRestart)
    }

    @Test
    fun `explicit off but running on still warns and labels next-start retention`(@TempDir tmp: Path) {
        val row = rows(tmp, "false", running = true).single()
        assertEquals("trace:local", row.name)
        assertTrue(row.detail.contains("still writes until the next restart"), row.detail)
        assertTrue(row.detail.contains("kept 7 day(s) on the next start"), row.detail)
        assertTrue(row.pendingRestart)
        assertEquals(emptyList<String>(), rows(tmp, null, running = true).map { it.name })
    }

    @Test
    fun `both running and declared on need no doctor warning`(@TempDir tmp: Path) {
        assertEquals(emptyList<String>(), rows(tmp, "true", running = true).map { it.name })
    }

    @Test
    fun `unanswered daemon reports only an explicit opt-out as the next start`(@TempDir tmp: Path) {
        val row = rows(tmp, "false", running = null).single()
        assertTrue(row.detail.contains("next start"), row.detail)
        assertFalse(row.pendingRestart)
        assertEquals(emptyList<String>(), rows(tmp, null, running = null).map { it.name })
    }

    @Test
    fun `invalid trace override is never echoed beyond true or false`(@TempDir tmp: Path) {
        val row = rows(tmp, "SENSITIVE_TEST_SECRET", running = false).single()
        assertEquals(CheckStatus.FAIL, row.status)
        assertTrue(row.detail.contains("neither true nor false"), row.detail)
        assertFalse(row.detail.contains("SENSITIVE_TEST_SECRET"), row.detail)
        assertFalse(row.pendingRestart)
    }

    private fun rows(tmp: Path, declared: String?, running: Boolean?) = DoctorTraceChecks(
        StatePaths(baseOverride = tmp.resolve("state")),
    ).traceChecks(
        TopologyLoader.parse(
            """
            [providers.local]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:9/v1"
            auth = { kind = "api-key", env = "SYNTHETIC_KEY" }
            [[providers.local.models]]
            id = "m"
            context_window = 200000
            [heads.local]
            provider = "local"
            port = 3102
            discovery_prefix = "claude-local--"
            pinned_model = "m"
            ${if (declared == null) "" else "[heads.local.overrides]\ntrace = \"$declared\"\ntraceRetentionDays = \"7\""}
            """.trimIndent(),
        ),
        running?.let { mapOf("local" to DaemonProbe.HeadTrace(it)) },
    )
}

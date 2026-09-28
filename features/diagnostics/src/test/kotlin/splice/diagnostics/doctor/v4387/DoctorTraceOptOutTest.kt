package splice.diagnostics.doctor.v4387

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.daemonclient.DaemonProbe
import splice.diagnostics.doctor.DoctorTraceChecks
import splice.topology.TopologyLoader
import java.nio.file.Path

class DoctorTraceOptOutTest {
    @Test
    fun `default-on heads stay quiet and explicit off heads are named`(@TempDir root: Path) {
        val doctor = DoctorTraceChecks(StatePaths(baseOverride = root.resolve("state")))
        val running = mapOf("local" to DaemonProbe.HeadTrace(true))
        assertEquals(emptyList<String>(), doctor.traceChecks(topology(null), running).map { it.name })
        val off = doctor.traceChecks(topology("false"), mapOf("local" to DaemonProbe.HeadTrace(false))).single()
        assertEquals("trace:local", off.name)
        assertTrue(off.detail.contains("does not write"), off.detail)
        assertFalse(off.pendingRestart)
    }

    @Test
    fun `removing the off key means trace starts after restart and is pending`(@TempDir root: Path) {
        val doctor = DoctorTraceChecks(StatePaths(baseOverride = root.resolve("state")))
        val pending = doctor.traceChecks(topology(null), mapOf("local" to DaemonProbe.HeadTrace(false))).single()
        assertTrue(pending.detail.contains("will write after the next restart"), pending.detail)
        assertTrue(pending.pendingRestart)
        val down = doctor.traceChecks(topology("false")).single()
        assertTrue(down.detail.contains("next start"), down.detail)
        assertFalse(down.pendingRestart)
    }

    private fun topology(trace: String?) = TopologyLoader.parse(
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
        ${trace?.let { "[heads.local.overrides]\ntrace = \"$it\"" }.orEmpty()}
        """.trimIndent(),
    )
}

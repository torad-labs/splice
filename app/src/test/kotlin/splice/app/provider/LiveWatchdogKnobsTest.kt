// NEW: firstByteTimeoutMs, streamIdleMs and stallReanchorMs are live knobs. A turn asks the head for its watchdog budget when it
// starts, so an operator who PATCHes either one is obeyed by the next turn on the running daemon, and splice says no
// restart is needed. Driven the way the head is built: a real HeadBuildInputs over one ConfigService, the budget asked
// before and after the patch from the same head.
package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.config.restartRequiredKnobKeys
import splice.topology.TopologyLoader
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class LiveWatchdogKnobsTest {
    // A head on a provider measured to continue from a prefill, so the re-anchor tier is armed.
    private val toml = """
        [daemon]
        control_port = 0

        [providers.deep]
        dialect = "anthropic-passthrough"
        base_url = "http://127.0.0.1:1"
        auth = { kind = "api-key", env = "DEEP_KEY" }
        quirks = { reanchor_prefill = true }

        [[providers.deep.models]]
        id = "deep-1"
        label = "Deep"
        context_window = 100000

        [heads.deep]
        provider = "deep"
        port = 0
        discovery_prefix = "claude-deep--"
        pinned_model = "deep-1"
    """.trimIndent()

    private fun build(config: ConfigService): ProviderBuild {
        val topology = TopologyLoader.parse(toml)
        val inputs = HeadBuildInputs(config, SignInPlanner())
        return inputs.providerContext("deep", topology.heads.getValue("deep"), topology.providers.getValue("deep"))
    }

    @Test
    fun `a patched first-byte, idle and re-anchor tier govern the next turn of the head that is already built`(
        @TempDir tmp: Path,
    ) {
        val config = ConfigService(StatePaths(baseOverride = tmp), envReader = { null })
        val head = build(config)
        assertEquals(90_000.milliseconds, head.faultPlan.liveWatchdog().firstByteTimeout)
        assertEquals(20_000.milliseconds, head.faultPlan.liveWatchdog().stallReanchor)
        assertEquals(90_000.milliseconds, head.faultPlan.liveWatchdog().streamIdle)

        val patched = config.patch(
            mapOf("firstByteTimeoutMs" to 45_000L, "streamIdleMs" to 120_000L, "stallReanchorMs" to 8_000L),
        )

        assertTrue(patched.restartRequired.isEmpty(), "both are live: ${patched.restartRequired}")
        val now = head.faultPlan.liveWatchdog()
        assertEquals(45_000.milliseconds, now.firstByteTimeout)
        assertEquals(8_000.milliseconds, now.stallReanchor)
        assertEquals(120_000.milliseconds, now.streamIdle)
        assertEquals(
            head.faultPlan.watchdog.totalCap,
            now.totalCap,
            "the no-progress limit is still restartRequired and stays as the head was built",
        )
    }

    @Test
    fun `none of the live knobs is listed as restart only`() {
        val restartOnly = restartRequiredKnobKeys
        listOf(
            Knob.FIRST_BYTE_TIMEOUT_MS,
            Knob.STREAM_IDLE_MS,
            Knob.STALL_REANCHOR_MS,
        ).forEach { assertTrue(it.key !in restartOnly, "${it.key} in $restartOnly") }
    }
}

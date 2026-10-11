// NEW: firstByteTimeoutMs and stallReanchorMs are live knobs. A turn asks the head for its watchdog budget when it
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
import splice.upstream.transport.BackoffCurve
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
    fun `a patched first-byte and re-anchor tier govern the next turn of the head that is already built`(
        @TempDir tmp: Path,
    ) {
        val config = ConfigService(StatePaths(baseOverride = tmp), envReader = { null })
        val head = build(config)
        assertEquals(90_000.milliseconds, head.faultPlan.liveWatchdog().firstByteTimeout)
        assertEquals(20_000.milliseconds, head.faultPlan.liveWatchdog().stallReanchor)

        val patched = config.patch(mapOf("firstByteTimeoutMs" to 45_000L, "stallReanchorMs" to 8_000L))

        assertTrue(patched.restartRequired.isEmpty(), "both are live: ${patched.restartRequired}")
        val now = head.faultPlan.liveWatchdog()
        assertEquals(45_000.milliseconds, now.firstByteTimeout)
        assertEquals(8_000.milliseconds, now.stallReanchor)
        assertEquals(
            head.faultPlan.watchdog.streamIdle,
            now.streamIdle,
            "the tiers that are still restartRequired stay as the head was built",
        )
    }

    @Test
    fun `a patched retry curve governs the next retry of the head that is already built`(@TempDir tmp: Path) {
        val config = ConfigService(StatePaths(baseOverride = tmp), envReader = { null })
        val head = build(config)
        assertEquals(BackoffCurve(baseMs = 200, capMs = 10_000, jitterPct = 10), head.faultPlan.liveRetryCurve())

        val patched = config.patch(
            mapOf("retryBackoffBaseMs" to 500L, "retryBackoffCapMs" to 4_000L, "retryBackoffJitterPct" to 0L),
        )

        assertTrue(patched.restartRequired.isEmpty(), "all three are live: ${patched.restartRequired}")
        assertEquals(BackoffCurve(baseMs = 500, capMs = 4_000, jitterPct = 0), head.faultPlan.liveRetryCurve())
    }

    @Test
    fun `none of the live knobs is listed as restart only`() {
        val restartOnly = restartRequiredKnobKeys
        listOf(
            Knob.FIRST_BYTE_TIMEOUT_MS,
            Knob.STALL_REANCHOR_MS,
            Knob.RETRY_BACKOFF_BASE_MS,
            Knob.RETRY_BACKOFF_CAP_MS,
            Knob.RETRY_BACKOFF_JITTER_PCT,
        ).forEach { assertTrue(it.key !in restartOnly, "${it.key} in $restartOnly") }
    }
}

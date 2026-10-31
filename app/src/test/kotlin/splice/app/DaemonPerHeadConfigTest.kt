// The real provider-context wiring keeps admission limits per head while watchdog timing comes
// from the daemon-wide defaults. A no-key capacity lookup must not collapse the two heads.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class DaemonPerHeadConfigTest {

    // Two heads on one provider share the configured timing bound and have distinct admission limits.
    private fun topologyToml(authFile: String) = """
        [daemon]
        control_port = 0

        [defaults]
        upstreamTimeoutMs = "1200000"

        [providers.codex]
        dialect = "openai-responses"
        base_url = "http://127.0.0.1:1"
        auth = { kind = "chatgpt-oauth", file = "$authFile" }

        [[providers.codex.models]]
        id = "gpt-5.6-sol"
        label = "Sol"
        context_window = 272000

        [heads.slow]
        provider = "codex"
        port = 0
        discovery_prefix = "claude-slow--"
        pinned_model = "gpt-5.6-sol"
        [heads.slow.overrides]
        maxInflight = "3"

        [heads.fast]
        provider = "codex"
        port = 0
        discovery_prefix = "claude-fast--"
        pinned_model = "gpt-5.6-sol"
        [heads.fast.overrides]
        maxInflight = "40"
    """.trimIndent()

    @Test
    fun `heads retain their own admission limits and share the configured daemon timing`(@TempDir tmp: Path) {
        val authFile = tmp.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"t","account_id":"a","refresh_token":"r"}}""")
        val topology = TopologyLoader.parse(topologyToml(authFile.toString().replace("\\", "/")))
        val d = Daemon(
            topology = topology,
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        fun build(key: String) =
            d.buildInputs.providerContext(key, topology.heads.getValue(key), topology.providers.getValue("codex"))

        val slow = build("slow")
        val fast = build("fast")

        assertEquals(1_200_000.milliseconds, slow.faultPlan.watchdog.totalCap)
        assertEquals(slow.faultPlan.watchdog.totalCap, fast.faultPlan.watchdog.totalCap)

        // A missing head key still collapses admission capacity, which is independent of the shared timing.
        assertNotEquals(
            slow.cfg.maxInflight,
            fast.cfg.maxInflight,
            "per-head maxInflight collapsed to one shared value — getConfig(key) lost somewhere",
        )

        // maxInflight is the knob the Daemon comment quantifies; it feeds the admission gate.
        assertEquals(3, slow.cfg.maxInflight)
        assertEquals(40, fast.cfg.maxInflight)
        assertEquals(slow.cfg.codexAuthPath, slow.providerCfg.auth.file)
        assertEquals(fast.cfg.codexAuthPath, fast.providerCfg.auth.file)

        val declared = d.buildInputs.providerContext(
            "slow",
            topology.heads.getValue("slow"),
            topology.providers.getValue("codex"),
            legacyKnobsGovern = false,
        )
        assertEquals(authFile.toString(), declared.providerCfg.auth.file)
    }
}

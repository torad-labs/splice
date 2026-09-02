// NEW (2026-07-26 review of #58): the per-head knob layer is covered at the SERVICE level by
// ConfigServiceTest, but nothing pinned the CALL SITES. `config.getConfig()` (no key) compiles in
// place of `config.getConfig(key)` everywhere in Daemon, keeps the whole suite green, and silently
// restores the one-ceiling-governs-all-heads failure the Daemon comment quantifies (67% turn
// failure at a shared inflight=100). This drives the real resolution path — two heads carrying
// DIFFERENT [heads.<key>.overrides] must resolve to different effective values.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import splice.app.Daemon
import splice.app.TopologyLoader
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

class DaemonPerHeadConfigTest {

    // Two heads on ONE provider, differing only in their overrides block. Slow head gets a long
    // upstream cap and a small inflight ceiling; fast head takes neither.
    private fun topologyToml(authFile: String) = """
        [daemon]
        control_port = 0

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
        upstreamTimeoutMs = "2400000"

        [heads.fast]
        provider = "codex"
        port = 0
        discovery_prefix = "claude-fast--"
        pinned_model = "gpt-5.6-sol"
        [heads.fast.overrides]
        maxInflight = "40"
        upstreamTimeoutMs = "600000"
    """.trimIndent()

    @Test
    fun `each head resolves its OWN overrides, not one shared view`() {
        val tmp = Files.createTempDirectory("daemon-perhead-test")
        val authFile = tmp.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"t","account_id":"a","refresh_token":"r"}}""")
        val topology = TopologyLoader.parse(topologyToml(authFile.toString().replace("\\", "/")))
        val d = Daemon(
            topology = topology,
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            dashboardHtml = { "" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        fun build(key: String) =
            d.buildInputs.providerContext(key, topology.heads.getValue(key), topology.providers.getValue("codex"))

        val slow = build("slow")
        val fast = build("fast")

        // The watchdog total cap comes straight from the per-head upstreamTimeoutMs. If any call
        // site drops the key, both heads read the same global view and these collapse to equal.
        assertNotEquals(
            slow.watchdog.totalCap,
            fast.watchdog.totalCap,
            "per-head upstreamTimeoutMs collapsed to one shared value — getConfig(key) lost somewhere",
        )
        assertEquals(2_400_000.milliseconds, slow.watchdog.totalCap)
        assertEquals(600_000.milliseconds, fast.watchdog.totalCap)

        // maxInflight is the knob the Daemon comment quantifies; it feeds the admission gate.
        assertEquals(3, slow.cfg.maxInflight)
        assertEquals(40, fast.cfg.maxInflight)
    }
}

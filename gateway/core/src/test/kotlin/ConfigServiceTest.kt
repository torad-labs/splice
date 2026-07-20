// PORT-OF: server/test/config.test.mjs semantics @ 4ca99f7 — layer precedence, PATCH persistence
// + restart-required flagging, invalid-value/unknown-key rejection, maxInflight aliases,
// normalization floors, showReasoning folding, mtime cache pickup. Env is faked via the
// injected reader seam (JVM cannot setenv).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.normalizeShowReasoning
import splice.core.turn.ReasoningDisplay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigServiceTest {

    @TempDir
    lateinit var tmp: Path

    private fun service(
        env: Map<String, String> = emptyMap(),
        overrides: Map<String, String> = emptyMap(),
    ): ConfigService {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        return ConfigService(paths, headOverrides = overrides, envReader = { env[it] })
    }

    @Test
    fun `defaults resolve and normalize`() {
        val cfg = service().getConfig()
        assertEquals(3099, cfg.port)
        assertEquals("https://chatgpt.com/backend-api/codex", cfg.chatgptApiBase)
        assertEquals(ReasoningDisplay.TEXT, cfg.showReasoning)
        assertEquals(false, cfg.replayReasoning)
        // Bounded by default since the 2026-07-19 storm (0 = unlimited stays an explicit opt-out).
        assertEquals(48, cfg.maxInflight)
        assertEquals(512, cfg.maxQueued)
        assertEquals(3096, cfg.controlPort)
    }

    @Test
    fun `layer precedence - overrides then file then env then runtime`() {
        val svc = service(
            env = mapOf("CLAUDEX_REASONING_EFFORT" to "xhigh"),
            overrides = mapOf("effort" to "low", "pinnedModel" to "gpt-5.4"),
        )
        // head TOML override beats default
        assertEquals("gpt-5.4", svc.getConfig().pinnedModel)
        // env beats override
        assertEquals("xhigh", svc.getConfig().effort)
        // runtime PATCH beats env
        svc.patch(mapOf("effort" to "medium"))
        assertEquals("medium", svc.getConfig().effort)
    }

    @Test
    fun `file layer applies and mtime cache picks up external edits`() {
        val svc = service()
        val stateDir = tmp.resolve("state")
        Files.createDirectories(stateDir)
        val cfgFile = stateDir.resolve("config.json")
        cfgFile.writeText("""{"pinnedModel":"gpt-5.5"}""")
        assertEquals("gpt-5.5", svc.getConfig().pinnedModel)
        Thread.sleep(1_100) // mtime resolution
        cfgFile.writeText("""{"pinnedModel":"gpt-5.4-mini"}""")
        assertEquals("gpt-5.4-mini", svc.getConfig().pinnedModel)
    }

    @Test
    fun `patch persists applies and flags restart-required`() {
        val svc = service()
        val result = svc.patch(mapOf("port" to 4000, "effort" to "high", "bogus" to 1, "maxInflight" to "unlimited"))
        assertEquals(setOf("port", "effort", "maxInflight"), result.applied.keys)
        assertEquals(mapOf("bogus" to "unknown key"), result.rejected)
        // effort is snapshotted into providers at Daemon.start — honestly restart-required
        // (audit 2026-07-18); maxInflight stays the one hot knob.
        assertEquals(listOf("port", "effort"), result.restartRequired)
        assertEquals(4000, result.effective.port)
        assertEquals(0, result.effective.maxInflight)
        val persisted = tmp.resolve("state/config.json").readText()
        assertTrue(persisted.contains("\"port\""))
        // null deletes from runtime and file
        svc.patch(mapOf("effort" to null))
        assertEquals(null, svc.getConfig().effort)
    }

    @Test
    fun `normalization floors and clamps`() {
        val svc = service(
            env = mapOf(
                "CLAUDEX_UPSTREAM_TIMEOUT_MS" to "5",
                "CLAUDEX_FIRST_BYTE_TIMEOUT_MS" to "5",
                "CLAUDEX_STREAM_IDLE_MS" to "5",
                "CLAUDEX_AUTH_CACHE_MS" to "5",
                "SPLICE_USAGE_WARN_PCT" to "150",
                "CHATGPT_API_BASE" to "https://example.com/base/",
            ),
        )
        val cfg = svc.getConfig()
        assertEquals(30_000, cfg.upstreamTimeoutMs)
        assertEquals(10_000, cfg.firstByteTimeoutMs)
        assertEquals(30_000, cfg.streamIdleMs)
        assertEquals(5_000, cfg.authCacheMs)
        assertEquals(100, cfg.usageWarnPct)
        assertEquals("https://example.com/base", cfg.chatgptApiBase)

        val negative = service(env = mapOf("CLAUDEX_MAX_QUEUED" to "-5"))
        assertEquals(0, negative.getConfig().maxQueued)
    }

    @Test
    fun `maxQueued env alias applies`() {
        val svc = service(env = mapOf("CLAUDEX_MAX_QUEUED" to "50"))
        assertEquals(50, svc.getConfig().maxQueued)
    }

    @Test
    fun `test idle floor drops to 250ms under CODEX_PROXY_TEST`() {
        val svc = service(env = mapOf("CODEX_PROXY_TEST" to "1", "CLAUDEX_STREAM_IDLE_MS" to "300"))
        assertEquals(300, svc.getConfig().streamIdleMs)
        val floored = service(env = mapOf("CODEX_PROXY_TEST" to "1", "CLAUDEX_STREAM_IDLE_MS" to "10"))
        assertEquals(250, floored.getConfig().streamIdleMs)
    }

    @Test
    fun `showReasoning folding matches the node table`() {
        assertEquals("off", normalizeShowReasoning("0"))
        assertEquals("off", normalizeShowReasoning("none"))
        assertEquals("off", normalizeShowReasoning(null))
        assertEquals("text", normalizeShowReasoning("mirror"))
        assertEquals("text", normalizeShowReasoning("FULL"))
        assertEquals("thinking", normalizeShowReasoning("anything-else"))
        assertEquals("thinking", normalizeShowReasoning("1"))
    }

    @Test
    fun `env alias order - first present name wins`() {
        val svc = service(env = mapOf("CODEX_REASONING_EFFORT" to "low", "CLAUDEX_REASONING_EFFORT" to "high"))
        assertEquals("high", svc.getConfig().effort)
    }
}

// PORT-OF: server/test/config.test.mjs semantics @ pre-public-port-baseline — layer precedence, PATCH persistence
// + restart-required flagging, invalid-value/unknown-key rejection, maxInflight aliases,
// normalization floors, showReasoning folding, sub-second cache pickup. Env is faked via the
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigServiceTest {

    @TempDir
    lateinit var tmp: Path

    private fun service(
        env: Map<String, String> = emptyMap(),
        overrides: Map<String, String> = emptyMap(),
        perHead: Map<String, Map<String, String>> = emptyMap(),
    ): ConfigService {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        return ConfigService(
            paths,
            headOverrides = overrides,
            perHeadOverrides = perHead,
            envReader = { env[it] },
        )
    }

    @Test
    fun `defaults resolve and normalize`() {
        val cfg = service().getConfig()
        assertEquals(3099, cfg.port)
        assertEquals("https://chatgpt.com/backend-api/codex", cfg.chatgptApiBase)
        assertEquals(ReasoningDisplay.TEXT, cfg.showReasoning)
        assertEquals(false, cfg.replayReasoning)
        // Bounded by default since the 2026-07-19 storm (0 = unlimited stays an explicit opt-out).
        // NF-02: 12 sits inside the measured 0.3%-failure band (<=14; 67% failure at the old 100).
        assertEquals(12, cfg.maxInflight)
        // 0 = unlimited stays the explicit operator opt-out — the new default must not eat it.
        assertEquals(0, service(env = mapOf("CLAUDEX_MAX_INFLIGHT" to "0")).getConfig().maxInflight)
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
    fun `file layer cache picks up a same-size external edit without a one-second delay`() {
        val svc = service()
        val stateDir = tmp.resolve("state")
        Files.createDirectories(stateDir)
        val cfgFile = stateDir.resolve("config.json")
        cfgFile.writeText("""{"pinnedModel":"gpt-5.5"}""")
        assertEquals("gpt-5.5", svc.getConfig().pinnedModel)
        cfgFile.writeText("""{"pinnedModel":"gpt-5.4"}""")
        assertEquals("gpt-5.4", svc.getConfig().pinnedModel)
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

        // Regression: an explicit 0/negative port must fall back to the knob DEFAULT, not clamp
        // to the floor (1 = unbindable/privileged).
        val zeroPort = service(env = mapOf("SPLICE_CONTROL_PORT" to "0")).getConfig()
        assertEquals(3096, zeroPort.controlPort)
        val negativePort = service(env = mapOf("SPLICE_CONTROL_PORT" to "-1")).getConfig()
        assertEquals(3096, negativePort.controlPort)

        val oversized = service(
            env = mapOf(
                "CLAUDEX_MAX_INFLIGHT" to "3000000000",
                "CLAUDEX_MAX_QUEUED" to "9999999999",
                "SPLICE_CONTROL_PORT" to "999999",
            ),
        ).getConfig()
        assertEquals(Int.MAX_VALUE, oversized.maxInflight)
        assertEquals(Int.MAX_VALUE, oversized.maxQueued)
        assertEquals(65_535, oversized.controlPort)

        val nonFinite = service(
            env = mapOf(
                "CLAUDEX_MAX_INFLIGHT" to "NaN",
                "CLAUDEX_MAX_QUEUED" to "Infinity",
            ),
        ).getConfig()
        assertEquals(12, nonFinite.maxInflight)
        assertEquals(512, nonFinite.maxQueued)
    }

    // The r3 invalid-env-value lesson: an unrecognized value must never silently ARM a feature.
    // TOOL_SURFACE normalizes to exactly "off" or "auto" — nothing else.
    @Test
    fun `toolSurface knob normalizes to exactly off or auto, never an unrecognized value`() {
        assertEquals("auto", service().getConfig().asMap()["toolSurface"])
        listOf("off", "OFF", " off ").forEach { raw ->
            val cfg = service(env = mapOf("CLAUDEX_TOOL_SURFACE" to raw)).getConfig()
            assertEquals("off", cfg.asMap()["toolSurface"])
            assertTrue(cfg.toolSurfaceOff)
        }
        listOf("on", "true", "garbage").forEach { raw ->
            val cfg = service(env = mapOf("CLAUDEX_TOOL_SURFACE" to raw)).getConfig()
            assertEquals("auto", cfg.asMap()["toolSurface"])
            assertEquals(false, cfg.toolSurfaceOff)
        }
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

    @Test
    fun `concurrent patches persist a complete atomic merge`() {
        val svc = service()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit {
            start.await()
            svc.patch(mapOf("effort" to "high"))
        }
        val second = pool.submit {
            start.await()
            svc.patch(mapOf("maxQueued" to 77))
        }
        start.countDown()
        first.get()
        second.get()
        pool.shutdown()

        val persisted = tmp.resolve("state/config.json").readText()
        assertTrue(persisted.contains("\"effort\":\"high\""))
        assertTrue(persisted.contains("\"maxQueued\":77"))
        assertEquals("high", svc.getConfig().effort)
        assertEquals(77, svc.getConfig().maxQueued)
    }

    @Test
    fun `statuslineGitRoots parses colon-separated absolute paths and drops relative entries`() {
        assertEquals(emptyList<String>(), service().getConfig().statuslineGitRoots)
        val cfg = service(
            env = mapOf("CLAUDEX_STATUSLINE_GIT_ROOTS" to "/workspace:/srv/repos:relative:"),
        ).getConfig()
        assertEquals(listOf("/workspace", "/srv/repos"), cfg.statuslineGitRoots)
    }

    // Per-head overrides ([heads.<key>.overrides]). Before this layer existed, every head shared
    // ONE maxInflight, so a ceiling sized for a fast upstream also governed a rate-limited one.
    @Test
    fun `layers expose the per-head override map - JW-06`() {
        val svc = service(
            overrides = mapOf("maxInflight" to "100"),
            perHead = mapOf("kimi" to mapOf("maxInflight" to "8"), "claudex" to emptyMap()),
        )
        val layers = svc.layers()
        // the tuned head appears with its coerced knobs; a head with no overrides is ABSENT
        assertEquals(mapOf("maxInflight" to 8L), layers.perHead["kimi"])
        assertEquals(setOf("kimi"), layers.perHead.keys)
    }

    @Test
    fun `per-head override applies to its own head only`() {
        val svc = service(
            overrides = mapOf("maxInflight" to "100"),
            perHead = mapOf("kimi" to mapOf("maxInflight" to "8")),
        )
        assertEquals(8, svc.getConfig("kimi").maxInflight)
        // The sibling and the global view keep the shared value — no leak in either direction.
        assertEquals(100, svc.getConfig("claudex").maxInflight)
        assertEquals(100, svc.getConfig().maxInflight)
    }

    @Test
    fun `per-head override beats global TOML but yields to env and runtime PATCH`() {
        val svc = service(
            env = mapOf("CLAUDEX_MAX_QUEUED" to "64"),
            overrides = mapOf("maxInflight" to "100", "maxQueued" to "512"),
            perHead = mapOf("kimi" to mapOf("maxInflight" to "8", "maxQueued" to "256")),
        )
        // more specific TOML wins over less specific TOML...
        assertEquals(8, svc.getConfig("kimi").maxInflight)
        // ...but env keeps its existing authority over BOTH TOML layers.
        assertEquals(64, svc.getConfig("kimi").maxQueued)
        svc.patch(mapOf("maxInflight" to 20))
        assertEquals(20, svc.getConfig("kimi").maxInflight)
    }

    @Test
    fun `head with no overrides is byte-identical to the global view`() {
        val svc = service(
            overrides = mapOf("maxInflight" to "100"),
            perHead = mapOf("kimi" to mapOf("maxInflight" to "8")),
        )
        assertEquals(svc.getConfig().asMap(), svc.getConfig("grok").asMap())
        assertEquals(svc.getConfig().asMap(), svc.getConfig("no-such-head").asMap())
    }
}

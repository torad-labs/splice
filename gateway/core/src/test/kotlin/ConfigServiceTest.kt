// PORT-OF: server/test/config.test.mjs semantics @ pre-public-port-baseline — layer precedence, PATCH persistence
// + restart-required flagging, invalid-value/unknown-key rejection, maxInflight aliases,
// normalization floors, showReasoning folding, sub-second cache pickup. Env is faked via the
// injected reader seam (JVM cannot setenv).
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigCoercion
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.turn.ReasoningDisplay
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // DR-9: the mutation-path read must be STRICT (KeyStore.entriesStrict doctrine) — an
    // unreadable config.json as the merge base for an atomic overwrite destroys every previously
    // persisted knob, silently, while the CLI reports success.
    @Test
    fun `a corrupt config file aborts persistence and keeps its bytes`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        svc.patch(mapOf("effort" to "high"))
        val corrupt = "{ \"effort\": \"high\", "
        paths.configFile.writeText(corrupt)

        svc.patch(mapOf("maxQueued" to 77))

        assertEquals(corrupt, paths.configFile.readText(), "an unreadable merge base must abort the rewrite")
        assertTrue(logged.any { it.contains("refusing to rewrite") }, "the aborted persist must log, got $logged")
    }

    @Test
    fun `an unreadable file layer is discarded with one logged cause not silence`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        paths.configFile.writeText("not json at all")

        svc.getConfig()
        svc.getConfig()

        assertEquals(
            1,
            logged.count { it.contains("unreadable") },
            "a present-but-unparseable file logs its discard once per mtime, got $logged",
        )
    }

    @Test
    fun `concurrent readers of an unreadable file log its discard exactly once per mtime`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        // DR-9 redo (2026-08-31): the latch was volatile check-then-set; a 64-way probe logged the
        // same mtime up to 11 times. Four rounds of barrier-released readers, one distinct mtime
        // each: the CAS latch must produce exactly one line per round.
        val logged = ConcurrentLinkedQueue<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        paths.configFile.writeText("not json at all")

        val readers = 64
        val rounds = 4
        val pool = Executors.newFixedThreadPool(readers)
        try {
            repeat(rounds) { round ->
                Files.setLastModifiedTime(paths.configFile, FileTime.fromMillis(1_000_000L + round * 10_000L))
                val start = CountDownLatch(1)
                val done = CountDownLatch(readers)
                repeat(readers) {
                    pool.execute {
                        start.await()
                        svc.getConfig()
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(30, TimeUnit.SECONDS), "readers wedged")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(
            rounds,
            logged.count { it.contains("unreadable") },
            "the discard latch must fire exactly once per mtime under concurrent readers, got $logged",
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
    fun `reasoning mirror stays locked off across every configuration layer`() {
        assertEquals(false, service().getConfig().mirrorReasoning)
        assertEquals(
            false,
            service(overrides = mapOf("mirrorReasoning" to "true")).getConfig().mirrorReasoning,
        )
        assertEquals(
            false,
            service(perHead = mapOf("codex" to mapOf("mirrorReasoning" to "true")))
                .getConfig("codex").mirrorReasoning,
        )
        assertEquals(
            false,
            service(env = mapOf("CLAUDEX_MIRROR_REASONING" to "true")).getConfig().mirrorReasoning,
        )

        val stateRoot = tmp.resolve("mirror-state")
        Files.createDirectories(stateRoot)
        stateRoot.resolve("config.json").writeText("""{"mirrorReasoning":true}""")
        val state = ConfigService(StatePaths(baseOverride = stateRoot), envReader = { null })
        assertEquals(false, state.getConfig().mirrorReasoning)

        val runtimeRoot = tmp.resolve("mirror-runtime")
        val runtime = ConfigService(StatePaths(baseOverride = runtimeRoot), envReader = { null })
        val patch = runtime.patch(mapOf("mirrorReasoning" to true))
        assertEquals(false, patch.applied["mirrorReasoning"])
        assertEquals(false, patch.effective.mirrorReasoning)
        assertEquals(false, runtime.layers().runtime["mirrorReasoning"])
        assertTrue(runtimeRoot.resolve("config.json").readText().contains("\"mirrorReasoning\":false"))

        val normalized = ConfigCoercion { null }.normalize(mapOf("mirrorReasoning" to true))
        assertEquals(false, normalized["mirrorReasoning"])
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

    // DR-48: a JSON null in config.json is ABSENCE, never the four-char string "null". jsonScalar
    // fell through JsonNull (a JsonPrimitive) to el.content, so a nulled STRING knob replaced its
    // default with the literal "null" — e.g. a "null" chatgptApiBase upstream URL.
    @Test
    fun `a json null knob in the state file reads as absent, not the string null`() {
        val svc = service()
        val stateDir = tmp.resolve("state")
        Files.createDirectories(stateDir)
        stateDir.resolve("config.json").writeText("""{"chatgptApiBase":null,"maxQueued":null}""")
        assertEquals(Knob.CHATGPT_API_BASE.default, svc.getConfig().chatgptApiBase)
        assertEquals(512, svc.getConfig().maxQueued, "a nulled NUMBER knob keeps its default")
    }

    // DR-150: normalize's own `default` for upstreamRetries was still the pre-G4b 2, four years of
    // knob history behind Knob.UPSTREAM_RETRIES.default of 4. It never fired in production —
    // mergedRaw seeds every knob before normalize runs — so nothing could catch the drift. This
    // arm normalizes an UNSEEDED map, which is the only shape that reaches the substitution, and
    // pins it to the DECLARED default rather than to a second copy of the literal.
    @Test
    fun `an unseeded upstreamRetries normalizes to the declared knob default - DR-150`() {
        val normalized = ConfigCoercion { null }.normalize(emptyMap())
        assertEquals(Knob.UPSTREAM_RETRIES.default, normalized["upstreamRetries"])
        assertEquals(4L, normalized["upstreamRetries"], "and that declared default is still 4")
        // the seeded path a real caller takes must agree with it, or the two sites have drifted
        // again in the other direction
        assertEquals(4, service().getConfig().upstreamRetries)
    }

    @Test
    fun `coercion never manufactures the string null from an absent value`() {
        val coercion = ConfigCoercion { null }
        assertNull(coercion.jsonScalar(JsonNull))
        assertNull(coercion.coerce(Knob.CHATGPT_API_BASE, null))
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

    // Same one-way law for the plan-usage poller: only an exact "off" stops it.
    @Test
    fun `quotaPoll knob normalizes to exactly off or auto, never an unrecognized value`() {
        assertEquals("auto", service().getConfig().asMap()["quotaPoll"])
        assertEquals(false, service().getConfig().quotaPollOff)
        listOf("off", "OFF", " off ").forEach { raw ->
            val cfg = service(env = mapOf("CLAUDEX_QUOTA_POLL" to raw)).getConfig()
            assertEquals("off", cfg.asMap()["quotaPoll"])
            assertTrue(cfg.quotaPollOff)
        }
        listOf("on", "true", "garbage").forEach { raw ->
            val cfg = service(env = mapOf("CLAUDEX_QUOTA_POLL" to raw)).getConfig()
            assertEquals("auto", cfg.asMap()["quotaPoll"])
            assertEquals(false, cfg.quotaPollOff)
        }
    }

    @Test
    fun `maxQueued env alias applies`() {
        val svc = service(env = mapOf("CLAUDEX_MAX_QUEUED" to "50"))
        assertEquals(50, svc.getConfig().maxQueued)
    }

    // Pinned against the reference client, not chosen freehand: codex-rs sets its only stream
    // timer to 300_000ms and puts it on the receive side alone. At 180_000 the idle tier ended 129
    // compactions in a single day (2026-09-01), each mid-stream on work already flowing.
    @Test
    fun `the idle stall detector defaults to the reference client's 300s, not a tighter guess`() {
        val cfg = service().getConfig()
        assertEquals(300_000L, cfg.streamIdleMs)
        assertEquals(
            cfg.firstByteTimeoutMs,
            cfg.streamIdleMs,
            "one number judges the stream before and after its first frame",
        )
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
        val coercion = ConfigCoercion { null }
        assertEquals("off", coercion.normalizeShowReasoning("0"))
        assertEquals("off", coercion.normalizeShowReasoning("none"))
        assertEquals("off", coercion.normalizeShowReasoning(null))
        assertEquals("text", coercion.normalizeShowReasoning("mirror"))
        assertEquals("text", coercion.normalizeShowReasoning("FULL"))
        assertEquals("thinking", coercion.normalizeShowReasoning("anything-else"))
        assertEquals("thinking", coercion.normalizeShowReasoning("1"))
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

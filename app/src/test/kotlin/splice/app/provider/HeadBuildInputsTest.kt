package splice.app.provider

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ExtraWindow
import splice.core.model.LiveWindows
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.nio.file.Path

class HeadBuildInputsTest {
    private val local = ProviderConfig(
        Dialect.OPENAI_CHAT,
        "http://localhost:1/v1",
        AuthConfig("api-key", env = "TEST_LOCAL_WINDOW_API_KEY"),
    )
    private val localHead = HeadConfig("local", 0, "claude-local--", "synthetic")
    private val listed = """{"data":[{"id":"synthetic"}]}"""
    private val metadata = listOf(
        mapOf(
            "GET /api/version" to """{"version":"synthetic"}""",
            "GET /v1/models" to listed,
            "GET /api/ps" to """{"models":[{"name":"synthetic","context_length":8192}]}""",
            "POST /api/show" to """{"model_info":{"synthetic.context_length":131072}}""",
        ),
        mapOf(
            "GET /api/v0/models" to
                """{"data":[{"id":"synthetic","loaded_context_length":8192,"max_context_length":131072}]}""",
        ),
        mapOf("GET /v1/models" to """{"data":[{"id":"synthetic","max_model_len":8192}]}"""),
        mapOf(
            "GET /v1/models" to """{"data":[{"id":"synthetic","meta":{"n_ctx_train":131072}}]}""",
            "GET /props" to """{"default_generation_settings":{"n_ctx":8192}}""",
        ),
    )

    private fun fake(routes: Map<String, String>, calls: MutableList<String>) = LocalHttp { method, url, _ ->
        val request = "$method ${url.substringAfter("http://localhost:1")}"
        check(!url.endsWith("/chat/completions")) { "metadata lookup must never generate a provider request" }
        calls += request
        routes[request]?.let { LocalHttpReply(200, it) }
    }

    @Test
    fun `local boot and live catalog reads use the served window for every runtime`(@TempDir tempDir: Path) {
        assertAll(
            metadata.map { routes ->
                Executable {
                    val calls = mutableListOf<String>()
                    val probe = LocalProbeInputs(fake(routes, calls))
                    val config = ConfigService(StatePaths(baseOverride = tempDir), envReader = { null })
                    val inputs = HeadBuildInputs(config, SignInPlanner(), localProbe = probe)
                    val ctx = inputs.providerContext("local", localHead, local, legacyKnobsGovern = false)
                    assertEquals(8192L, ctx.catalog.contextWindowFor("synthetic"))
                    assertEquals(8192L, ctx.catalog.clientLaunchWindow)
                    val found = ctx.localRows as LocalRowsCheck.Checked
                    assertEquals(8192L, found.rows.getValue("synthetic"))
                    assertTrue(found.refused.isEmpty())
                    val requests = calls.size
                    assertTrue(requests > 0, "boot must have asked the metadata endpoints")
                    assertEquals(8192L, LiveRosterCatalog(ctx, LiveWindows { null }).current().clientLaunchWindow)
                    assertEquals(8192L, inputs.catalogFor("local", localHead, local, false).clientLaunchWindow)
                    assertEquals(requests, calls.size, "catalog reads must not ask the runtime again")
                }
            },
        )
    }

    @Test
    fun `unloaded runtimes use settings then card windows and llama aliases use props`(@TempDir tempDir: Path) {
        val fallbacks = listOf(
            metadata[0] - "GET /api/ps" + (
                "POST /api/show" to
                    """{"parameters":"num_ctx 4096","model_info":{"synthetic.context_length":131072}}"""
                ),
            mapOf("GET /api/v0/models" to """{"data":[{"id":"synthetic","max_context_length":131072}]}"""),
            metadata[3] - "GET /props",
            metadata[3] + ("GET /v1/models" to """{"data":[{"id":"synthetic.gguf","meta":{"n_ctx_train":131072}}]}"""),
            metadata[0] - "GET /api/ps",
        )
        assertAll(
            fallbacks.mapIndexed { index, routes ->
                Executable {
                    val calls = mutableListOf<String>()
                    val probe = LocalProbeInputs(fake(routes, calls))
                    val inputs = HeadBuildInputs(
                        ConfigService(StatePaths(baseOverride = tempDir), envReader = { null }),
                        SignInPlanner(),
                        localProbe = probe,
                    )
                    val ctx = inputs.providerContext("local", localHead, local, false)
                    val expected = when (index) {
                        0 -> 4096L
                        3 -> 8192L
                        else -> 131072L
                    }
                    assertEquals(expected, ctx.catalog.clientLaunchWindow)
                    assertEquals(expected, LiveRosterCatalog(ctx, LiveWindows { null }).current().clientLaunchWindow)
                }
            },
        )
    }

    @Test
    fun `an Ollama bare id receives its latest tag metadata and keeps explicit num_ctx validation`() {
        val routes = metadata[0] - "GET /api/ps" + mapOf(
            "GET /v1/models" to """{"data":[{"id":"synthetic:latest"}]}""",
            "POST /api/show" to """{"parameters":"num_ctx 4096","model_info":{"synthetic.context_length":131072}}""",
        )
        val probe = LocalProbeInputs(fake(routes, mutableListOf()))
        val inferred = probe.check(local, null, local.catalogFor(localHead)) as LocalRowsCheck.Checked
        assertTrue(inferred.refused.isEmpty())
        assertEquals(4096L, inferred.rows.getValue("synthetic"))
        val declared = local.copy(models = listOf(ModelEntry("synthetic", contextWindow = 8192)))
        val checked = probe.check(declared, null, declared.catalogFor(localHead)) as LocalRowsCheck.Checked
        assertTrue(checked.refused.single().reason.contains("declares context_window 8192, runtime serves 4096"))
    }

    @Test
    fun `an inferred local window is not refused as a declaration`() {
        val probe = LocalProbeInputs(fake(metadata[2], mutableListOf()))
        val found = probe.check(local, null, local.catalogFor(localHead)) as LocalRowsCheck.Checked
        assertTrue(found.refused.isEmpty(), "splice's 200K fallback is not an operator declaration")
        assertEquals(8192L, found.rows.getValue("synthetic"))
    }

    @Test
    fun `a declared local window remains declared and an unknown runtime keeps the fallback`(@TempDir tempDir: Path) {
        val declarations = listOf(
            local.copy(models = listOf(ModelEntry("synthetic", contextWindow = 16384))),
            local.copy(extraWindows = listOf(ExtraWindow("synthetic", contextWindow = 16384))),
            local.copy(windowRules = listOf(WindowRule("synt", contextWindow = 16384))),
            local.copy(defaultContextWindow = 16384),
        )
        assertAll(
            metadata.flatMap { routes ->
                declarations.map { declared ->
                    Executable {
                        val probe = LocalProbeInputs(fake(routes, mutableListOf()))
                        val found = probe.check(
                            declared,
                            null,
                            declared.catalogFor(localHead),
                        ) as LocalRowsCheck.Checked
                        val reason = found.refused.single().reason
                        assertTrue(reason.contains("declares context_window 16384, runtime serves 8192"))
                        val headWindow = local.catalogFor(localHead.copy(contextWindow = 16384))
                        val headCheck = probe.check(local, null, headWindow) as LocalRowsCheck.Checked
                        assertTrue(headCheck.refused.single().reason.contains("declares context_window 16384"))
                    }
                }
            },
        )
        val unknown = LocalProbeInputs(fake(mapOf("GET /v1/models" to listed), mutableListOf()))
        val inputs = HeadBuildInputs(
            ConfigService(StatePaths(baseOverride = tempDir), envReader = { null }),
            SignInPlanner(),
            localProbe = unknown,
        )
        assertEquals(200000L, inputs.providerContext("local", localHead, local, false).catalog.clientLaunchWindow)
    }

    @Test
    fun `head key text cannot opt an unrelated provider into Grok overrides`(@TempDir tempDir: Path) {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = tempDir),
            headOverrides = mapOf(
                "grokPort" to "4999",
                "grokModel" to "grok-override",
                "xaiApiBase" to "https://grok.invalid",
            ),
            envReader = { null },
        )
        val inputs = HeadBuildInputs(config, SignInPlanner())
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://openrouter.example",
            auth = AuthConfig("api-key"),
        )
        val head = HeadConfig(
            provider = "openrouter",
            port = 4107,
            discoveryPrefix = "claude-router--",
            pinnedModel = "router-model",
        )
        val cfg = config.getConfig("not-grok")

        assertEquals(head, inputs.resolveHeadConfig(head, provider, cfg))
        assertEquals(provider, inputs.resolveProviderConfig(provider, cfg))
    }

    // DR-80 (assembly sweep): with two-plus heads of a legacy kind nothing is seeded into the
    // shared layer, so the unconditional legacy overwrite replaced each head's declared
    // port/model/base with the knob DEFAULTS (and with first-head-wins seeding, with the first
    // head's values). The resolve side now gates on sole-head-of-kind.
    @Test
    fun `a non-sole legacy head keeps its declared port, model and base - DR-80`(@TempDir tempDir: Path) {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = tempDir),
            headOverrides = emptyMap(), // what the shared layer holds when a kind has two heads
            envReader = { null },
        )
        val inputs = HeadBuildInputs(config, SignInPlanner())
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://second-codex.example",
            auth = AuthConfig("chatgpt-oauth"),
            models = listOf(splice.core.model.ModelEntry("m-b", contextWindow = 100_000)),
        )
        val head = HeadConfig("codex-b", 4202, "claude-two--", "m-b")

        val build = inputs.providerContext("two", head, provider, legacyKnobsGovern = false)

        assertEquals(head, build.head)
        assertEquals(provider, build.providerCfg)
    }

    /** V4-227: the command a 401 names ("— run: <this>") is the one that fixes it. For an api-key head
     *  that is `splice key set` on the var the daemon reads, which the head picks up on its next
     *  request; `<wrapper> login` only asked for the same key through a terminal prompt. An OAuth head
     *  keeps its browser login. RED before: the api-key head's build carried "claude-router login". */
    @Test
    fun `a 401 on an api-key head names splice key set, an OAuth head keeps its login`(@TempDir tempDir: Path) {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = tempDir),
            headOverrides = emptyMap(),
            envReader = { null },
        )
        val inputs = HeadBuildInputs(config, SignInPlanner())
        val models = listOf(splice.core.model.ModelEntry("m", contextWindow = 100_000))
        val apiKey = ProviderConfig(Dialect.OPENAI_CHAT, "https://or.example", AuthConfig("api-key"), models = models)
        val oauth = ProviderConfig(
            Dialect.OPENAI_RESPONSES,
            "https://codex.example",
            AuthConfig("chatgpt-oauth"),
            models = models,
        )
        val wrapper = ClaudeWrapperConfig("claude-router")
        val router = HeadConfig("openrouter", 4107, "claude-router--", "m", claude = wrapper)
        val codex = HeadConfig("codex", 4108, "claude-codex--", "m")

        val keyed = inputs.providerContext("router", router, apiKey, legacyKnobsGovern = false)
        val signed = inputs.providerContext("codex", codex, oauth, legacyKnobsGovern = false)

        assertEquals("splice key set ROUTER_API_KEY", keyed.loginCommand)
        assertEquals("codex login", signed.loginCommand)
    }
}

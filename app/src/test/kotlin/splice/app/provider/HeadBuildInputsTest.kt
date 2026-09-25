package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.auth.SignInPlanner
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import java.nio.file.Files

class HeadBuildInputsTest {

    @Test
    fun `head key text cannot opt an unrelated provider into Grok overrides`() {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = Files.createTempDirectory("head-build-inputs")),
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
    fun `a non-sole legacy head keeps its declared port, model and base - DR-80`() {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = Files.createTempDirectory("head-build-inputs")),
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
    fun `a 401 on an api-key head names splice key set, an OAuth head keeps its login`() {
        val config = ConfigService(
            statePaths = StatePaths(baseOverride = Files.createTempDirectory("head-build-inputs")),
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
